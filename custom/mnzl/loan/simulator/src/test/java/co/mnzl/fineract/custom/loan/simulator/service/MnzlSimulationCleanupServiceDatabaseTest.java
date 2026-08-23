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
package co.mnzl.fineract.custom.loan.simulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MnzlSimulationCleanupServiceDatabaseTest {

    private static final long LOAN_ID = 11L;
    private static final long SAVINGS_ID = 21L;
    private static final long CLIENT_ID = 31L;
    private static final long LOAN_PAYMENT_DETAIL_ID = 41L;
    private static final long SAVINGS_PAYMENT_DETAIL_ID = 42L;
    private static final long SHARED_PAYMENT_DETAIL_ID = 43L;
    private static final long JOB_EXECUTION_ID = 501L;
    private static final long JOB_INSTANCE_ID = 601L;
    private static final long LOAN_IDS_PARAMETER_ID = 701L;
    private static final long BUSINESS_DATE_PARAMETER_ID = 702L;
    private static final String COMMAND_PREFIX = "mnzlsim-database-test";

    private static final List<String> LOAN_CHILD_TABLES = List.of("m_loan_repayment_schedule_history", "m_loan_account_locks",
            "m_loan_arrears_aging", "m_loan_delinquency_action", "m_loan_delinquency_tag_history", "m_loan_installment_delinquency_tag",
            "m_loan_approved_amount_history", "m_loan_officer_assignment_history", "m_loan_rate", "m_loan_status_change_history",
            "m_loan_term_variations", "m_loan_payment_allocation_rule", "m_loan_credit_allocation_rule", "m_loan_progressive_model",
            "m_loan_recalculation_details");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void cleansPaymentAndInlineCobArtifactsOnPostgreSql() {
        assertCleanup(POSTGRES);
    }

    @Test
    void cleansPaymentAndInlineCobArtifactsOnMySql() {
        assertCleanup(MYSQL);
    }

    private void assertCleanup(JdbcDatabaseContainer<?> database) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        createSchema(jdbcTemplate);
        seedSimulationGraph(jdbcTemplate);

        new MnzlSimulationCleanupService(jdbcTemplate).cleanup(LOAN_ID, SAVINGS_ID, CLIENT_ID, COMMAND_PREFIX, List.of(JOB_EXECUTION_ID));

        assertEmpty(jdbcTemplate, "m_loan", "m_savings_account", "m_client", "m_portfolio_account_associations",
                "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_JOB_EXECUTION_PARAMS",
                "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE", "batch_custom_job_parameters", "m_journal_entry_aggregation_tracking",
                "m_journal_entry_aggregation_summary");
        assertThat(count(jdbcTemplate, "m_payment_detail")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM m_payment_detail", Long.class)).isEqualTo(SHARED_PAYMENT_DETAIL_ID);
        assertThat(jdbcTemplate.queryForList("SELECT idempotency_key FROM m_portfolio_command_source", String.class))
                .containsExactly("unrelated-command");
        assertThat(count(jdbcTemplate, "m_client_transaction")).isEqualTo(1);
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE m_client (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE m_payment_detail (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE m_loan (id BIGINT PRIMARY KEY, client_id BIGINT REFERENCES m_client(id))");
        jdbcTemplate.execute("""
                CREATE TABLE m_savings_account (
                    id BIGINT PRIMARY KEY,
                    client_id BIGINT REFERENCES m_client(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_transaction (
                    id BIGINT PRIMARY KEY,
                    loan_id BIGINT NOT NULL REFERENCES m_loan(id),
                    payment_detail_id BIGINT REFERENCES m_payment_detail(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_repayment_schedule (
                    id BIGINT PRIMARY KEY,
                    loan_id BIGINT NOT NULL REFERENCES m_loan(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_charge (
                    id BIGINT PRIMARY KEY,
                    loan_id BIGINT NOT NULL REFERENCES m_loan(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_disbursement_detail (
                    id BIGINT PRIMARY KEY,
                    loan_id BIGINT NOT NULL REFERENCES m_loan(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_savings_account_transaction (
                    id BIGINT PRIMARY KEY,
                    savings_account_id BIGINT NOT NULL REFERENCES m_savings_account(id),
                    payment_detail_id BIGINT REFERENCES m_payment_detail(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_savings_account_charge (
                    id BIGINT PRIMARY KEY,
                    savings_account_id BIGINT NOT NULL REFERENCES m_savings_account(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_client_transaction (
                    id BIGINT PRIMARY KEY,
                    payment_detail_id BIGINT REFERENCES m_payment_detail(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE acc_gl_journal_entry (
                    id BIGINT PRIMARY KEY,
                    loan_transaction_id BIGINT REFERENCES m_loan_transaction(id),
                    savings_transaction_id BIGINT REFERENCES m_savings_account_transaction(id),
                    payment_details_id BIGINT REFERENCES m_payment_detail(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_note (
                    loan_transaction_id BIGINT REFERENCES m_loan_transaction(id),
                    loan_id BIGINT REFERENCES m_loan(id),
                    savings_account_id BIGINT REFERENCES m_savings_account(id),
                    client_id BIGINT REFERENCES m_client(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_portfolio_account_associations (
                    loan_account_id BIGINT REFERENCES m_loan(id),
                    linked_loan_account_id BIGINT REFERENCES m_loan(id),
                    savings_account_id BIGINT REFERENCES m_savings_account(id),
                    linked_savings_account_id BIGINT REFERENCES m_savings_account(id)
                )
                """);
        createLoanRelationTables(jdbcTemplate);
        createSavingsRelationTables(jdbcTemplate);
        LOAN_CHILD_TABLES.forEach(table -> createReferenceTable(jdbcTemplate, table, "loan_id", "m_loan"));
        createReferenceTable(jdbcTemplate, "m_deposit_account_on_hold_transaction", "savings_account_id", "m_savings_account");
        createReferenceTable(jdbcTemplate, "m_mandatory_savings_schedule", "savings_account_id", "m_savings_account");
        createReferenceTable(jdbcTemplate, "m_savings_account_interest_rate_chart", "savings_account_id", "m_savings_account");
        createReferenceTable(jdbcTemplate, "m_savings_officer_assignment_history", "account_id", "m_savings_account");
        jdbcTemplate.execute("CREATE TABLE m_portfolio_command_source (idempotency_key VARCHAR(64) NOT NULL)");
        createBatchSchema(jdbcTemplate);
    }

    private void createLoanRelationTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_transaction_repayment_schedule_mapping (
                    loan_transaction_id BIGINT REFERENCES m_loan_transaction(id),
                    loan_repayment_schedule_id BIGINT REFERENCES m_loan_repayment_schedule(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_charge_paid_by (
                    loan_transaction_id BIGINT REFERENCES m_loan_transaction(id),
                    loan_charge_id BIGINT REFERENCES m_loan_charge(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_transaction_relation (
                    from_loan_transaction_id BIGINT REFERENCES m_loan_transaction(id),
                    to_loan_transaction_id BIGINT REFERENCES m_loan_transaction(id),
                    to_loan_charge_id BIGINT REFERENCES m_loan_charge(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_installment_charge (
                    loan_schedule_id BIGINT REFERENCES m_loan_repayment_schedule(id),
                    loan_charge_id BIGINT REFERENCES m_loan_charge(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_overdue_installment_charge (
                    loan_schedule_id BIGINT REFERENCES m_loan_repayment_schedule(id),
                    loan_charge_id BIGINT REFERENCES m_loan_charge(id)
                )
                """);
        createReferenceTable(jdbcTemplate, "m_loan_charge_tax_details", "loan_charge_id", "m_loan_charge");
        jdbcTemplate.execute("""
                CREATE TABLE m_loan_tranche_disbursement_charge (
                    loan_charge_id BIGINT REFERENCES m_loan_charge(id),
                    disbursement_detail_id BIGINT REFERENCES m_loan_disbursement_detail(id)
                )
                """);
    }

    private void createSavingsRelationTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE m_savings_account_charge_paid_by (
                    savings_account_transaction_id BIGINT REFERENCES m_savings_account_transaction(id),
                    savings_account_charge_id BIGINT REFERENCES m_savings_account_charge(id)
                )
                """);
        createReferenceTable(jdbcTemplate, "m_savings_account_transaction_tax_details", "savings_transaction_id",
                "m_savings_account_transaction");
    }

    private void createBatchSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE batch_custom_job_parameters (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE BATCH_JOB_INSTANCE (JOB_INSTANCE_ID BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("""
                CREATE TABLE BATCH_JOB_EXECUTION (
                    JOB_EXECUTION_ID BIGINT PRIMARY KEY,
                    JOB_INSTANCE_ID BIGINT NOT NULL REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
                    JOB_EXECUTION_ID BIGINT NOT NULL REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID),
                    PARAMETER_NAME VARCHAR(100) NOT NULL,
                    PARAMETER_VALUE VARCHAR(100)
                )
                """);
        createReferenceTable(jdbcTemplate, "BATCH_JOB_EXECUTION_CONTEXT", "JOB_EXECUTION_ID", "BATCH_JOB_EXECUTION", "JOB_EXECUTION_ID");
        jdbcTemplate.execute("""
                CREATE TABLE BATCH_STEP_EXECUTION (
                    STEP_EXECUTION_ID BIGINT PRIMARY KEY,
                    JOB_EXECUTION_ID BIGINT NOT NULL REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
                )
                """);
        createReferenceTable(jdbcTemplate, "BATCH_STEP_EXECUTION_CONTEXT", "STEP_EXECUTION_ID", "BATCH_STEP_EXECUTION",
                "STEP_EXECUTION_ID");
        createReferenceTable(jdbcTemplate, "m_journal_entry_aggregation_tracking", "job_execution_id", "BATCH_JOB_EXECUTION",
                "JOB_EXECUTION_ID");
        createReferenceTable(jdbcTemplate, "m_journal_entry_aggregation_summary", "job_execution_id", "BATCH_JOB_EXECUTION",
                "JOB_EXECUTION_ID");
    }

    private void seedSimulationGraph(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("INSERT INTO m_client (id) VALUES (?)", CLIENT_ID);
        jdbcTemplate.update("INSERT INTO m_loan (id, client_id) VALUES (?, ?)", LOAN_ID, CLIENT_ID);
        jdbcTemplate.update("INSERT INTO m_savings_account (id, client_id) VALUES (?, ?)", SAVINGS_ID, CLIENT_ID);
        jdbcTemplate.update("INSERT INTO m_payment_detail (id) VALUES (?), (?), (?)", LOAN_PAYMENT_DETAIL_ID, SAVINGS_PAYMENT_DETAIL_ID,
                SHARED_PAYMENT_DETAIL_ID);
        jdbcTemplate.update("INSERT INTO m_loan_transaction (id, loan_id, payment_detail_id) VALUES (?, ?, ?)", 101L, LOAN_ID,
                LOAN_PAYMENT_DETAIL_ID);
        jdbcTemplate.update("INSERT INTO m_savings_account_transaction (id, savings_account_id, payment_detail_id) VALUES (?, ?, ?)", 201L,
                SAVINGS_ID, SAVINGS_PAYMENT_DETAIL_ID);
        jdbcTemplate.update("INSERT INTO m_client_transaction (id, payment_detail_id) VALUES (?, ?)", 301L, SHARED_PAYMENT_DETAIL_ID);
        jdbcTemplate.update("INSERT INTO m_loan_repayment_schedule (id, loan_id) VALUES (?, ?)", 102L, LOAN_ID);
        jdbcTemplate.update("INSERT INTO m_loan_charge (id, loan_id) VALUES (?, ?)", 103L, LOAN_ID);
        jdbcTemplate.update("INSERT INTO m_loan_disbursement_detail (id, loan_id) VALUES (?, ?)", 104L, LOAN_ID);
        jdbcTemplate.update("INSERT INTO m_savings_account_charge (id, savings_account_id) VALUES (?, ?)", 202L, SAVINGS_ID);
        seedLoanRelations(jdbcTemplate);
        seedSavingsRelations(jdbcTemplate);
        LOAN_CHILD_TABLES.forEach(table -> insertReference(jdbcTemplate, table, "loan_id", LOAN_ID));
        insertReference(jdbcTemplate, "m_deposit_account_on_hold_transaction", "savings_account_id", SAVINGS_ID);
        insertReference(jdbcTemplate, "m_mandatory_savings_schedule", "savings_account_id", SAVINGS_ID);
        insertReference(jdbcTemplate, "m_savings_account_interest_rate_chart", "savings_account_id", SAVINGS_ID);
        insertReference(jdbcTemplate, "m_savings_officer_assignment_history", "account_id", SAVINGS_ID);
        jdbcTemplate.update("INSERT INTO m_portfolio_command_source (idempotency_key) VALUES (?), (?)", COMMAND_PREFIX + "-1",
                "unrelated-command");
        seedBatchExecution(jdbcTemplate);
    }

    private void seedLoanRelations(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                INSERT INTO m_portfolio_account_associations
                    (loan_account_id, linked_loan_account_id, savings_account_id, linked_savings_account_id)
                VALUES (?, NULL, ?, NULL)
                """, LOAN_ID, SAVINGS_ID);
        jdbcTemplate.update(
                "INSERT INTO m_loan_transaction_repayment_schedule_mapping (loan_transaction_id, loan_repayment_schedule_id) VALUES (?, ?)",
                101L, 102L);
        jdbcTemplate.update("INSERT INTO m_loan_charge_paid_by (loan_transaction_id, loan_charge_id) VALUES (?, ?)", 101L, 103L);
        jdbcTemplate.update("""
                INSERT INTO m_loan_transaction_relation
                    (from_loan_transaction_id, to_loan_transaction_id, to_loan_charge_id)
                VALUES (?, ?, ?)
                """, 101L, 101L, 103L);
        jdbcTemplate.update("INSERT INTO m_loan_installment_charge (loan_schedule_id, loan_charge_id) VALUES (?, ?)", 102L, 103L);
        jdbcTemplate.update("INSERT INTO m_loan_overdue_installment_charge (loan_schedule_id, loan_charge_id) VALUES (?, ?)", 102L, 103L);
        insertReference(jdbcTemplate, "m_loan_charge_tax_details", "loan_charge_id", 103L);
        jdbcTemplate.update("INSERT INTO m_loan_tranche_disbursement_charge (loan_charge_id, disbursement_detail_id) VALUES (?, ?)", 103L,
                104L);
        jdbcTemplate.update("INSERT INTO acc_gl_journal_entry (id, loan_transaction_id, payment_details_id) VALUES (?, ?, ?)", 401L, 101L,
                LOAN_PAYMENT_DETAIL_ID);
        jdbcTemplate.update("INSERT INTO m_note (loan_transaction_id) VALUES (?)", 101L);
        jdbcTemplate.update("INSERT INTO m_note (loan_id) VALUES (?)", LOAN_ID);
    }

    private void seedSavingsRelations(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO m_savings_account_charge_paid_by (savings_account_transaction_id, savings_account_charge_id) VALUES (?, ?)",
                201L, 202L);
        insertReference(jdbcTemplate, "m_savings_account_transaction_tax_details", "savings_transaction_id", 201L);
        jdbcTemplate.update("INSERT INTO acc_gl_journal_entry (id, savings_transaction_id, payment_details_id) VALUES (?, ?, ?)", 402L,
                201L, SAVINGS_PAYMENT_DETAIL_ID);
        jdbcTemplate.update("INSERT INTO m_note (savings_account_id) VALUES (?)", SAVINGS_ID);
        jdbcTemplate.update("INSERT INTO m_note (client_id) VALUES (?)", CLIENT_ID);
    }

    private void seedBatchExecution(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("INSERT INTO batch_custom_job_parameters (id) VALUES (?), (?)", LOAN_IDS_PARAMETER_ID,
                BUSINESS_DATE_PARAMETER_ID);
        jdbcTemplate.update("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID) VALUES (?)", JOB_INSTANCE_ID);
        jdbcTemplate.update("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, JOB_INSTANCE_ID) VALUES (?, ?)", JOB_EXECUTION_ID,
                JOB_INSTANCE_ID);
        jdbcTemplate.update("""
                INSERT INTO BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_VALUE)
                VALUES (?, ?, ?), (?, ?, ?)
                """, JOB_EXECUTION_ID, "CUSTOM_JOB_PARAMETER_ID", Long.toString(LOAN_IDS_PARAMETER_ID), JOB_EXECUTION_ID, "BusinessDate",
                Long.toString(BUSINESS_DATE_PARAMETER_ID));
        insertReference(jdbcTemplate, "BATCH_JOB_EXECUTION_CONTEXT", "JOB_EXECUTION_ID", JOB_EXECUTION_ID);
        jdbcTemplate.update("INSERT INTO BATCH_STEP_EXECUTION (STEP_EXECUTION_ID, JOB_EXECUTION_ID) VALUES (?, ?)", 801L, JOB_EXECUTION_ID);
        insertReference(jdbcTemplate, "BATCH_STEP_EXECUTION_CONTEXT", "STEP_EXECUTION_ID", 801L);
        insertReference(jdbcTemplate, "m_journal_entry_aggregation_tracking", "job_execution_id", JOB_EXECUTION_ID);
        insertReference(jdbcTemplate, "m_journal_entry_aggregation_summary", "job_execution_id", JOB_EXECUTION_ID);
    }

    private void createReferenceTable(JdbcTemplate jdbcTemplate, String table, String column, String parentTable) {
        createReferenceTable(jdbcTemplate, table, column, parentTable, "id");
    }

    private void createReferenceTable(JdbcTemplate jdbcTemplate, String table, String column, String parentTable, String parentColumn) {
        jdbcTemplate.execute(
                "CREATE TABLE " + table + " (" + column + " BIGINT NOT NULL REFERENCES " + parentTable + "(" + parentColumn + "))");
    }

    private void insertReference(JdbcTemplate jdbcTemplate, String table, String column, long value) {
        jdbcTemplate.update("INSERT INTO " + table + " (" + column + ") VALUES (?)", value);
    }

    private void assertEmpty(JdbcTemplate jdbcTemplate, String... tables) {
        for (String table : tables) {
            assertThat(count(jdbcTemplate, table)).as(table).isZero();
        }
    }

    private long count(JdbcTemplate jdbcTemplate, String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
