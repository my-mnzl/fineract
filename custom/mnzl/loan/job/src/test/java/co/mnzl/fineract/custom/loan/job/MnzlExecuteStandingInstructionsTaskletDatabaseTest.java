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
package co.mnzl.fineract.custom.loan.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MnzlExecuteStandingInstructionsTaskletDatabaseTest {

    private static final long INSTRUCTION_ID = 9794L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 15);
    private static final BigDecimal AMOUNT = new BigDecimal("123.45");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private ScheduledDateGenerator scheduledDateGenerator;
    private StepContribution stepContribution;
    private ChunkContext chunkContext;
    private MnzlExecuteStandingInstructionsTasklet tasklet;

    @BeforeEach
    void setUp() throws Exception {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE);
        dates.put(BusinessDateType.COB_DATE, BUSINESS_DATE.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();

        standingInstructionReadPlatformService = mock(StandingInstructionReadPlatformService.class);
        accountTransfersWritePlatformService = mock(AccountTransfersWritePlatformService.class);
        scheduledDateGenerator = mock(ScheduledDateGenerator.class);
        stepContribution = mock(StepContribution.class);
        chunkContext = mock(ChunkContext.class);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(standingInstruction()));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class),
                eq(BUSINESS_DATE))).thenReturn(true);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        DatabaseTypeResolver databaseTypeResolver = new DatabaseTypeResolver(hikariConfig);
        databaseTypeResolver.afterPropertiesSet();
        DatabaseSpecificSQLGenerator sqlGenerator = new DatabaseSpecificSQLGenerator(databaseTypeResolver, null);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tasklet = new MnzlExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator,
                accountTransfersWritePlatformService, transactionTemplate, scheduledDateGenerator);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void successUpdatesLastRunDateAndInsertsHistoryUsingPostgreSqlDialect() throws Exception {
        tasklet.execute(stepContribution, chunkContext);

        LocalDate lastRunDate = jdbcTemplate.queryForObject(
                "SELECT last_run_date FROM m_account_transfer_standing_instructions WHERE id = ?", LocalDate.class, INSTRUCTION_ID);
        Map<String, Object> history = singleHistoryRow();
        assertThat(lastRunDate).isEqualTo(BUSINESS_DATE);
        assertThat(history.get("status")).isEqualTo("success");
        assertThat((BigDecimal) history.get("amount")).isEqualByComparingTo(AMOUNT);
        assertThat(history.get("execution_time")).isNotNull();
        assertThat(history.get("error_log")).isEqualTo("");
    }

    @Test
    void transferFailureRollsBackItsTransactionAndCommitsTruncatedFailureHistory() {
        String longMessage = "x".repeat(600);
        doAnswer(invocation -> {
            jdbcTemplate.update("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? WHERE id = ?",
                    BUSINESS_DATE.minusDays(1), INSTRUCTION_ID);
            throw new IllegalStateException(longMessage);
        }).when(accountTransfersWritePlatformService).transferFunds(any(AccountTransferDTO.class));

        assertThatThrownBy(() -> tasklet.execute(stepContribution, chunkContext)).isInstanceOf(JobExecutionException.class);

        LocalDate lastRunDate = jdbcTemplate.queryForObject(
                "SELECT last_run_date FROM m_account_transfer_standing_instructions WHERE id = ?", LocalDate.class, INSTRUCTION_ID);
        Map<String, Object> history = singleHistoryRow();
        assertThat(lastRunDate).isNull();
        assertThat(history.get("status")).isEqualTo("failed");
        assertThat((BigDecimal) history.get("amount")).isEqualByComparingTo(AMOUNT);
        assertThat((String) history.get("error_log")).hasSize(500).startsWith("Exception while trasfering funds ");
    }

    @Test
    void successHistoryFailureRollsBackLastRunDateBeforeFailureHistoryTransaction() {
        jdbcTemplate.execute(
                "ALTER TABLE m_account_transfer_standing_instructions_history ADD CONSTRAINT failed_status_only CHECK (\"status\" = 'failed')");

        assertThatThrownBy(() -> tasklet.execute(stepContribution, chunkContext)).isInstanceOf(JobExecutionException.class);

        LocalDate lastRunDate = jdbcTemplate.queryForObject(
                "SELECT last_run_date FROM m_account_transfer_standing_instructions WHERE id = ?", LocalDate.class, INSTRUCTION_ID);
        assertThat(lastRunDate).isNull();
        assertThat(singleHistoryRow().get("status")).isEqualTo("failed");
    }

    private void createSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS m_account_transfer_standing_instructions_history");
        jdbcTemplate.execute("DROP TABLE IF EXISTS m_account_transfer_standing_instructions");
        jdbcTemplate.execute("""
                CREATE TABLE m_account_transfer_standing_instructions (
                    id BIGINT PRIMARY KEY,
                    last_run_date DATE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE m_account_transfer_standing_instructions_history (
                    id BIGSERIAL PRIMARY KEY,
                    standing_instruction_id BIGINT NOT NULL REFERENCES m_account_transfer_standing_instructions(id),
                    "status" VARCHAR(20) NOT NULL,
                    amount DECIMAL(19, 6) NOT NULL,
                    execution_time TIMESTAMP NOT NULL,
                    error_log VARCHAR(500)
                )
                """);
        jdbcTemplate.update("INSERT INTO m_account_transfer_standing_instructions (id) VALUES (?)", INSTRUCTION_ID);
    }

    private Map<String, Object> singleHistoryRow() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM m_account_transfer_standing_instructions_history", Integer.class))
                .isEqualTo(1);
        return jdbcTemplate
                .queryForMap("SELECT \"status\", amount, execution_time, error_log FROM m_account_transfer_standing_instructions_history");
    }

    private static StandingInstructionData standingInstruction() {
        return StandingInstructionData.instance(INSTRUCTION_ID, 29363L, "Test transfer", null, null, null, null,
                enumOption(PortfolioAccountType.SAVINGS.getValue().longValue(), "accountType.savings", "Savings"),
                PortfolioAccountData.lookup(51352L, "000051352"),
                enumOption(PortfolioAccountType.LOAN.getValue().longValue(), "accountType.loan", "Loan"),
                PortfolioAccountData.lookup(19543L, "000019543"),
                enumOption((long) AccountTransferType.LOAN_REPAYMENT.getValue(), "accountTransferType.loan.repayment", "Loan repayment"),
                enumOption(3L, "standingInstructionPriority.medium", "Medium"),
                enumOption((long) StandingInstructionType.FIXED.getValue(), "standingInstructionType.fixed", "Fixed"),
                enumOption((long) StandingInstructionStatus.ACTIVE.getValue(), "standingInstructionStatus.active", "Active"), AMOUNT,
                LocalDate.of(2026, 2, 25), null, enumOption((long) AccountTransferRecurrenceType.PERIODIC.getValue(),
                        "standingInstructionRecurrenceType.periodic", "Periodic"),
                enumOption((long) PeriodFrequencyType.DAYS.getValue(), "periodFrequencyType.days", "Days"), 1, null);
    }

    private static EnumOptionData enumOption(Long id, String code, String description) {
        return new EnumOptionData(id, code, description);
    }
}
