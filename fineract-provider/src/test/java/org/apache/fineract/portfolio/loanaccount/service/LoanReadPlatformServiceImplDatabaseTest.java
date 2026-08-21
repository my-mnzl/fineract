/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LoanReadPlatformServiceImplDatabaseTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);
    private static final long SUBMITTED_LOAN_ID = 101L;
    private static final long APPROVED_LOAN_ID = 102L;
    private static final long PRODUCT_LINKED_NON_FLOATING_LOAN_ID = 103L;
    private static final long FUTURE_RATE_LOAN_ID = 104L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private LoanReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, TODAY);
        dates.put(BusinessDateType.COB_DATE, TODAY.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        seedLoans();
        service = LoanReadPlatformServiceImplTest.newService(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void recalculationQueriesHandleUndisbursedAndProductLinkedLoanShapes() {
        Collection<Long> unpaged = service.fetchLoansForInterestRecalculation();
        List<Long> paged = service.fetchLoansForInterestRecalculation(10, 0L, ".branch.%");

        assertEligibleLoans(unpaged);
        assertEligibleLoans(paged);
    }

    private static void assertEligibleLoans(Collection<Long> loanIds) {
        assertThat(loanIds).containsExactlyInAnyOrder(SUBMITTED_LOAN_ID, APPROVED_LOAN_ID, PRODUCT_LINKED_NON_FLOATING_LOAN_ID)
                .doesNotContain(FUTURE_RATE_LOAN_ID);
    }

    private void createSchema() {
        jdbcTemplate.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100) NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT NOT NULL)");
        jdbcTemplate.execute("""
                CREATE TABLE m_loan (
                    id BIGINT PRIMARY KEY,
                    product_id BIGINT NOT NULL,
                    client_id BIGINT NOT NULL,
                    loan_status_id INTEGER NOT NULL,
                    is_npa BOOLEAN NOT NULL,
                    is_charged_off BOOLEAN NOT NULL,
                    interest_recalculation_enabled BOOLEAN NOT NULL,
                    interest_recalcualated_on DATE,
                    disbursedon_date DATE,
                    is_floating_interest_rate BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_repayment_schedule (
                    loan_id BIGINT NOT NULL,
                    completed_derived BOOLEAN NOT NULL,
                    duedate DATE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_disbursement_detail (
                    loan_id BIGINT NOT NULL,
                    disbursedon_date DATE,
                    expected_disburse_date DATE,
                    is_reversed BOOLEAN
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_recalculation_details (
                    loan_id BIGINT NOT NULL,
                    disallow_interest_calc_on_past_due BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_product_loan_floating_rates (
                    loan_product_id BIGINT NOT NULL,
                    floating_rates_id BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_floating_rates (
                    id BIGINT PRIMARY KEY,
                    is_active BOOLEAN NOT NULL,
                    is_base_lending_rate BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_floating_rates_periods (
                    id BIGINT PRIMARY KEY,
                    floating_rates_id BIGINT NOT NULL,
                    from_date DATE NOT NULL,
                    created_date DATE NOT NULL,
                    is_active BOOLEAN NOT NULL,
                    is_differential_to_base_lending_rate BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE TABLE m_loan_reschedule_request (loan_id BIGINT NOT NULL)");
    }

    private void seedLoans() {
        jdbcTemplate.update("INSERT INTO m_office (id, hierarchy) VALUES (1, '.branch.')");
        jdbcTemplate.update("INSERT INTO m_client (id, office_id) VALUES (1001, 1), (1002, 1), (1003, 1), (1004, 1)");
        jdbcTemplate.update("""
                INSERT INTO m_loan (
                    id, product_id, client_id, loan_status_id, is_npa, is_charged_off,
                    interest_recalculation_enabled, interest_recalcualated_on, disbursedon_date,
                    is_floating_interest_rate
                ) VALUES
                    (?, 201, 1001, ?, FALSE, FALSE, TRUE, NULL, NULL, FALSE),
                    (?, 202, 1002, ?, FALSE, FALSE, TRUE, NULL, NULL, FALSE),
                    (?, 203, 1003, ?, FALSE, FALSE, FALSE, NULL, ?, FALSE),
                    (?, 204, 1004, ?, FALSE, FALSE, FALSE, NULL, ?, FALSE)
                """, SUBMITTED_LOAN_ID, LoanStatus.SUBMITTED_AND_PENDING_APPROVAL.getValue(), APPROVED_LOAN_ID,
                LoanStatus.APPROVED.getValue(), PRODUCT_LINKED_NON_FLOATING_LOAN_ID, LoanStatus.ACTIVE.getValue(), TODAY.minusMonths(3),
                FUTURE_RATE_LOAN_ID, LoanStatus.ACTIVE.getValue(), TODAY.minusMonths(3));
        jdbcTemplate.update("""
                INSERT INTO m_loan_repayment_schedule (loan_id, completed_derived, duedate) VALUES
                    (?, FALSE, ?), (?, FALSE, ?), (?, TRUE, ?), (?, TRUE, ?)
                """, SUBMITTED_LOAN_ID, TODAY.plusMonths(1), APPROVED_LOAN_ID, TODAY.plusMonths(1), PRODUCT_LINKED_NON_FLOATING_LOAN_ID,
                TODAY.plusMonths(1), FUTURE_RATE_LOAN_ID, TODAY.plusMonths(1));
        jdbcTemplate.update("""
                INSERT INTO m_loan_disbursement_detail (loan_id, disbursedon_date, expected_disburse_date, is_reversed) VALUES
                    (?, NULL, ?, FALSE), (?, NULL, ?, FALSE)
                """, SUBMITTED_LOAN_ID, TODAY.minusDays(1), APPROVED_LOAN_ID, TODAY.minusDays(1));
        jdbcTemplate.update("""
                INSERT INTO m_loan_recalculation_details (loan_id, disallow_interest_calc_on_past_due) VALUES
                    (?, FALSE), (?, FALSE)
                """, SUBMITTED_LOAN_ID, APPROVED_LOAN_ID);
        jdbcTemplate.update("""
                INSERT INTO m_product_loan_floating_rates (loan_product_id, floating_rates_id) VALUES
                    (203, 301), (204, 302)
                """);
        jdbcTemplate.update("""
                INSERT INTO m_floating_rates (id, is_active, is_base_lending_rate) VALUES
                    (301, TRUE, FALSE), (302, TRUE, FALSE)
                """);
        jdbcTemplate.update("""
                INSERT INTO m_floating_rates_periods (
                    id, floating_rates_id, from_date, created_date, is_active, is_differential_to_base_lending_rate
                ) VALUES
                    (401, 301, ?, ?, TRUE, FALSE),
                    (402, 302, ?, ?, TRUE, FALSE)
                """, TODAY.minusDays(1), TODAY.minusDays(1), TODAY.plusDays(1), TODAY.minusDays(1));
    }
}
