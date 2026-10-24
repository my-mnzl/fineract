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
package co.mnzl.fineract.custom.loan.grace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.holiday.domain.HolidayStatusType;
import org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.service.LoanArrearsAgingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the handler (upstream's SQL plus the working-day correction) against a real database. Point
 * {@code GRACE_TEST_JDBC_URL} at an empty MySQL or PostgreSQL schema (optionally {@code GRACE_TEST_JDBC_USER} /
 * {@code GRACE_TEST_JDBC_PASSWORD}); the test creates the handful of tables it needs.
 *
 * <p>
 * Sun-Thu week, grace 3, installments due Thu 12 Nov 2026: the calendar rule puts them in arrears from 15 Nov, the
 * working-day rule from Wed 18 Nov (staging loan 120944).
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "GRACE_TEST_JDBC_URL", matches = "jdbc:(mysql|postgresql):.+")
class MnzlLoanArrearsAgeingUpdateHandlerDatabaseTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 12);
    private static final long OFFICE_WITH_HOLIDAY = 2L;

    private JdbcTemplate jdbcTemplate;
    private MnzlLoanArrearsAgeingUpdateHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("GRACE_TEST_JDBC_URL");
        boolean postgres = url.startsWith("jdbc:postgresql:");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName(postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
        DatabaseTypeResolver typeResolver = new DatabaseTypeResolver(hikariConfig);
        typeResolver.afterPropertiesSet();
        DatabaseSpecificSQLGenerator sqlGenerator = new DatabaseSpecificSQLGenerator(typeResolver, null);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, System.getenv().getOrDefault("GRACE_TEST_JDBC_USER", "root"),
                System.getenv().getOrDefault("GRACE_TEST_JDBC_PASSWORD", ""));
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();

        WorkingDaysRepositoryWrapper workingDaysRepository = mock(WorkingDaysRepositoryWrapper.class);
        when(workingDaysRepository.findOne())
                .thenReturn(new WorkingDays("FREQ=WEEKLY;BYDAY=SU,MO,TU,WE,TH", RepaymentRescheduleType.SAME_DAY.getValue(), false, false));
        HolidayRepositoryWrapper holidayRepository = mock(HolidayRepositoryWrapper.class);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(1L), any(LocalDate.class))).thenReturn(List.of());
        // Office 2 is closed Mon 16 - Tue 17 Nov, so its grace runs Sun 15, Wed 18, Thu 19.
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_WITH_HOLIDAY), any(LocalDate.class))).thenReturn(
                List.of(new Holiday().setName("closed").setFromDate(LocalDate.of(2026, 11, 16)).setToDate(LocalDate.of(2026, 11, 17))
                        .setStatus(HolidayStatusType.ACTIVE.getValue()).setReschedulingType(0).setProcessed(false)));
        handler = new MnzlLoanArrearsAgeingUpdateHandler(jdbcTemplate, sqlGenerator, mock(LoanArrearsAgingService.class),
                new MnzlWorkingDayCalculator(workingDaysRepository, holidayRepository));

        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        seedLoans();
    }

    @Test
    void jobKeepsLoansOutOfArrearsUntilTheWorkingDayGraceEnds() {
        setBusinessDate(LocalDate.of(2026, 11, 17));

        handler.updateLoanArrearsAgeingDetailsForAllLoans();

        Map<Long, Map<String, Object>> arrears = arrearsByLoan();
        assertThat(arrears).doesNotContainKey(1L); // calendar says overdue since 15 Nov; working grace runs to 17 Nov
        assertThat(arrears.get(2L)).containsEntry("overdue_since_date_derived", LocalDate.of(2026, 10, 12));
        assertAmount(arrears.get(2L), "total_overdue_derived", "120"); // only the October installment, not November's
        assertAmount(arrears.get(2L), "principal_overdue_derived", "100");
        assertThat(arrears).containsKey(3L); // no grace: upstream's result stands
        assertThat(arrears).containsKey(4L); // purchased receivable: upstream's result stands
        assertThat(arrears).doesNotContainKey(5L);
        assertThat(arrears).doesNotContainKey(6L); // paid
    }

    @Test
    void jobPutsLoansInArrearsOnceTheGraceEnds() {
        setBusinessDate(LocalDate.of(2026, 11, 18));

        handler.updateLoanArrearsAgeingDetailsForAllLoans();

        Map<Long, Map<String, Object>> arrears = arrearsByLoan();
        assertThat(arrears.get(1L)).containsEntry("overdue_since_date_derived", DUE);
        assertAmount(arrears.get(1L), "total_overdue_derived", "120");
        assertAmount(arrears.get(2L), "total_overdue_derived", "239");
        // Group loan in office 2: the holiday pushes its grace to Thu 19 Nov.
        assertThat(arrears).doesNotContainKey(5L);
    }

    @Test
    void holidayOfficeLoanEntersArrearsAfterItsLongerGrace() {
        setBusinessDate(LocalDate.of(2026, 11, 20));

        handler.updateLoanArrearsAgeingDetailsForAllLoans();

        assertThat(arrearsByLoan().get(5L)).containsEntry("overdue_since_date_derived", DUE);
    }

    @Test
    void cobStepPathCorrectsTheLoanItIsGiven() {
        setBusinessDate(LocalDate.of(2026, 11, 17));

        handler.updateLoanArrearsAgeingDetails(List.of(1L));
        handler.updateLoanArrearsAgeingDetails(List.of(2L));
        handler.updateLoanArrearsAgeingDetails(List.of(3L));

        Map<Long, Map<String, Object>> arrears = arrearsByLoan();
        assertThat(arrears).doesNotContainKey(1L);
        assertAmount(arrears.get(2L), "total_overdue_derived", "120");
        assertThat(arrears).containsKey(3L);
    }

    private void seedLoans() {
        // loanId, clientId, groupId, grace, strategy
        loan(1, 1L, null, 3, "mnzl-loan-strategy");
        installment(1, DUE, "100", "20", false);
        loan(2, 1L, null, 3, "mnzl-loan-strategy");
        installment(2, LocalDate.of(2026, 10, 12), "100", "20", false);
        installment(2, DUE, "100", "19", false);
        loan(3, 1L, null, 0, "mnzl-loan-strategy");
        installment(3, DUE, "100", "20", false);
        loan(4, 1L, null, 3, Loan.FIXED_RECEIVABLE_TRANSACTION_STRATEGY);
        installment(4, DUE, "100", "0", false);
        loan(5, null, 1L, 3, "mnzl-loan-strategy");
        installment(5, DUE, "100", "20", false);
        loan(6, 1L, null, 3, "mnzl-loan-strategy");
        installment(6, DUE, "100", "20", true);
    }

    private void loan(long id, Long clientId, Long groupId, int grace, String strategy) {
        jdbcTemplate.update("insert into m_loan(id, loan_status_id, grace_on_arrears_ageing, product_id, client_id, group_id, "
                + "loan_transaction_strategy_code) values (?, 300, ?, 1, ?, ?, ?)", id, grace, clientId, groupId, strategy);
    }

    private void installment(long loanId, LocalDate dueDate, String principal, String interest, boolean completed) {
        jdbcTemplate.update("insert into m_loan_repayment_schedule(loan_id, duedate, completed_derived, principal_amount, interest_amount) "
                + "values (?, ?, ?, ?, ?)", loanId, dueDate, completed, new BigDecimal(principal), new BigDecimal(interest));
    }

    private Map<Long, Map<String, Object>> arrearsByLoan() {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("select * from m_loan_arrears_aging")) {
            Map<String, Object> normalized = new HashMap<>();
            row.forEach((column, value) -> normalized.put(column.toLowerCase(),
                    value instanceof java.sql.Date date ? date.toLocalDate() : value));
            result.put(((Number) normalized.get("loan_id")).longValue(), normalized);
        }
        return result;
    }

    private static void assertAmount(Map<String, Object> row, String column, String expected) {
        assertThat((BigDecimal) row.get(column)).isEqualByComparingTo(expected);
    }

    private void createSchema() {
        for (String table : List.of("m_loan_arrears_aging", "m_loan_repayment_schedule", "m_loan", "m_client", "m_group",
                "m_product_loan_recalculation_details")) {
            jdbcTemplate.execute("drop table if exists " + table);
        }
        jdbcTemplate.execute("create table m_client (id bigint primary key, office_id bigint not null)");
        jdbcTemplate.execute("create table m_group (id bigint primary key, office_id bigint not null)");
        jdbcTemplate.execute("create table m_product_loan_recalculation_details (product_id bigint primary key, "
                + "arrears_based_on_original_schedule boolean)");
        jdbcTemplate.execute("create table m_loan (id bigint primary key, loan_status_id int not null, grace_on_arrears_ageing int, "
                + "product_id bigint, client_id bigint, group_id bigint, loan_transaction_strategy_code varchar(100) not null)");
        StringBuilder schedule = new StringBuilder(
                "create table m_loan_repayment_schedule (loan_id bigint not null, duedate date not null, "
                        + "completed_derived boolean not null");
        for (String component : List.of("principal", "interest", "fee_charges", "penalty_charges")) {
            schedule.append(", ").append(component).append("_amount decimal(19,6)");
            for (String suffix : List.of("completed", "writtenoff", "waived")) {
                schedule.append(", ").append(component).append('_').append(suffix).append("_derived decimal(19,6)");
            }
        }
        jdbcTemplate.execute(schedule.append(")").toString());
        jdbcTemplate.execute("create table m_loan_arrears_aging (loan_id bigint primary key, principal_overdue_derived decimal(19,6), "
                + "interest_overdue_derived decimal(19,6), fee_charges_overdue_derived decimal(19,6), "
                + "penalty_charges_overdue_derived decimal(19,6), total_overdue_derived decimal(19,6), overdue_since_date_derived date)");
        jdbcTemplate.update("insert into m_client(id, office_id) values (1, 1)");
        jdbcTemplate.update("insert into m_group(id, office_id) values (1, ?)", OFFICE_WITH_HOLIDAY);
        jdbcTemplate.update(
                "insert into m_product_loan_recalculation_details(product_id, arrears_based_on_original_schedule) values (1, ?)", false);
    }

    private static void setBusinessDate(LocalDate date) {
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, date);
        dates.put(BusinessDateType.COB_DATE, date.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);
    }
}
