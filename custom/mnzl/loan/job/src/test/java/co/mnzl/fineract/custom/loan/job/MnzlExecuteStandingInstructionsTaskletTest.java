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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.exception.TruncatedJobExecutionException;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MnzlExecuteStandingInstructionsTaskletTest {

    private static final String UPDATE_LAST_RUN_DATE_QUERY = "UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?";

    @Mock
    private StandingInstructionReadPlatformService standingInstructionReadPlatformService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;

    @Mock
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ScheduledDateGenerator scheduledDateGenerator;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private TransactionStatus transactionStatus;

    @Test
    void executeRetriesOptimisticLockFailuresAndCompletesSuccessfully() throws Exception {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("123.45");
        final StandingInstructionData instruction = standingInstruction(9794L, amount);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(businessDate)))
                .thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new OptimisticLockException("Loan 19543 changed during COB")).doReturn(29363L).when(accountTransfersWritePlatformService)
                .transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(
                new MnzlExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator,
                        accountTransfersWritePlatformService, transactionTemplate, scheduledDateGenerator));
        doNothing().when(underTest).sleepBeforeRetry(anyInt(), eq(9794L));

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            RepeatStatus result = underTest.execute(stepContribution, chunkContext);

            assertEquals(RepeatStatus.FINISHED, result);
        }

        verify(underTest).sleepBeforeRetry(1, 9794L);
        verify(accountTransfersWritePlatformService, times(2)).transferFunds(any(AccountTransferDTO.class));
        verify(jdbcTemplate).update(UPDATE_LAST_RUN_DATE_QUERY, businessDate, 9794L);
        verify(jdbcTemplate).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(9794L), eq("success"),
                eq(amount), eq(""));
        verify(jdbcTemplate, never()).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(9794L),
                eq("failed"), eq(amount), any(String.class));
    }

    @Test
    void executeRecordsFailedHistoryInNewTransactionAndTruncatesErrorLog() {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("42990.62");
        final StandingInstructionData instruction = standingInstruction(9794L, amount);
        final String longMessage = "x".repeat(600);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(businessDate)))
                .thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new IllegalStateException(longMessage)).when(accountTransfersWritePlatformService)
                .transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = new MnzlExecuteStandingInstructionsTasklet(
                standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                transactionTemplate, scheduledDateGenerator);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        final ArgumentCaptor<String> errorLogCaptor = ArgumentCaptor.forClass(String.class);
        verify(accountTransfersWritePlatformService).transferFunds(any(AccountTransferDTO.class));
        verify(jdbcTemplate, never()).update(eq(UPDATE_LAST_RUN_DATE_QUERY), any(LocalDate.class), any(Long.class));
        verify(jdbcTemplate).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(9794L), eq("failed"),
                eq(amount), errorLogCaptor.capture());
        verify(transactionTemplate, times(2)).execute(any());
        assertThat(errorLogCaptor.getValue()).hasSize(500).startsWith("Exception while trasfering funds ");
    }

    @Test
    void executesScheduledInstructionsOnDueDate() throws Exception {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("999.00");
        final StandingInstructionData instruction = standingInstruction(7777L, amount);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(businessDate)))
                .thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        final MnzlExecuteStandingInstructionsTasklet underTest = new MnzlExecuteStandingInstructionsTasklet(
                standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                transactionTemplate, scheduledDateGenerator);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            RepeatStatus result = underTest.execute(stepContribution, chunkContext);
            assertEquals(RepeatStatus.FINISHED, result);
        }

        verify(accountTransfersWritePlatformService, times(1)).transferFunds(any(AccountTransferDTO.class));
    }

    @Test
    void historyRowWrittenOnSuccess() throws Exception {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("250.00");
        final StandingInstructionData instruction = standingInstruction(8888L, amount);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(businessDate)))
                .thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        final MnzlExecuteStandingInstructionsTasklet underTest = new MnzlExecuteStandingInstructionsTasklet(
                standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                transactionTemplate, scheduledDateGenerator);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            underTest.execute(stepContribution, chunkContext);
        }

        verify(jdbcTemplate).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(8888L), eq("success"),
                eq(amount), eq(""));
    }

    @Test
    void historyRowWrittenOnFailure() {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("75.00");
        final StandingInstructionData instruction = standingInstruction(5555L, amount);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(businessDate)))
                .thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new IllegalStateException("boom")).when(accountTransfersWritePlatformService).transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = new MnzlExecuteStandingInstructionsTasklet(
                standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                transactionTemplate, scheduledDateGenerator);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        final ArgumentCaptor<String> errorLogCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(5555L), eq("failed"),
                eq(amount), errorLogCaptor.capture());
        assertThat(errorLogCaptor.getValue()).startsWith("Exception while trasfering funds ");
    }

    @Test
    void workingDayAwareDueDateSkipsHoliday() throws Exception {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("123.45");
        final StandingInstructionData instruction = standingInstruction(4321L, amount);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        // ScheduledDateGenerator says the date does NOT fall in schedule (e.g. holiday).
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(businessDate)))
                .thenReturn(false);

        final MnzlExecuteStandingInstructionsTasklet underTest = new MnzlExecuteStandingInstructionsTasklet(
                standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                transactionTemplate, scheduledDateGenerator);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            RepeatStatus result = underTest.execute(stepContribution, chunkContext);
            assertEquals(RepeatStatus.FINISHED, result);
        }

        verify(accountTransfersWritePlatformService, never()).transferFunds(any(AccountTransferDTO.class));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void duesBasedTriggersOnInstallmentDue() throws Exception {
        final LocalDate businessDate = LocalDate.of(2026, 3, 15);
        final BigDecimal amount = new BigDecimal("0.00");
        final BigDecimal duesAmount = new BigDecimal("321.00");
        // Dues-based, dues-amount-transfer, recurrence as_per_dues.
        final StandingInstructionData instruction = standingInstructionDues(2468L, amount);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(java.util.List.of(instruction));
        when(standingInstructionReadPlatformService.retriveLoanDuesData(eq(19543L)))
                .thenReturn(new StandingInstructionDuesData(LocalDate.of(2026, 3, 14), duesAmount));
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        final MnzlExecuteStandingInstructionsTasklet underTest = new MnzlExecuteStandingInstructionsTasklet(
                standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                transactionTemplate, scheduledDateGenerator);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);
            // The dues-recurrence path also calls LocalDate.now(DateUtils.getDateTimeZoneOfTenant()).
            mockedDateUtils.when(DateUtils::getDateTimeZoneOfTenant).thenReturn(java.time.ZoneId.of("UTC"));

            RepeatStatus result = underTest.execute(stepContribution, chunkContext);
            assertEquals(RepeatStatus.FINISHED, result);
        }

        verify(accountTransfersWritePlatformService, times(1)).transferFunds(any(AccountTransferDTO.class));
        verify(jdbcTemplate).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(2468L), eq("success"),
                eq(duesAmount), eq(""));
    }

    private StandingInstructionData standingInstruction(Long id, BigDecimal amount) {
        return StandingInstructionData.instance(id, 29363L, "To loan 000019543 from savings 000051352", null, null, null, null,
                enumOption(PortfolioAccountType.SAVINGS.getValue().longValue(), "accountType.savings", "Savings"),
                PortfolioAccountData.lookup(51352L, "000051352"),
                enumOption(PortfolioAccountType.LOAN.getValue().longValue(), "accountType.loan", "Loan"),
                PortfolioAccountData.lookup(19543L, "000019543"),
                enumOption((long) AccountTransferType.LOAN_REPAYMENT.getValue(), "accountTransferType.loan.repayment", "Loan repayment"),
                enumOption(3L, "standingInstructionPriority.medium", "Medium"),
                enumOption((long) StandingInstructionType.FIXED.getValue(), "standingInstructionType.fixed", "Fixed"),
                enumOption((long) StandingInstructionStatus.ACTIVE.getValue(), "standingInstructionStatus.active", "Active"), amount,
                LocalDate.of(2026, 2, 25), null,
                enumOption((long) AccountTransferRecurrenceType.PERIODIC.getValue(), "accountTransferRecurrenceType.periodic", "Periodic"),
                enumOption((long) PeriodFrequencyType.DAYS.getValue(), "periodFrequencyType.days", "Days"), 1, null);
    }

    private StandingInstructionData standingInstructionDues(Long id, BigDecimal amount) {
        return StandingInstructionData.instance(id, 29363L, "To loan 000019543 from savings 000051352 (dues)", null, null, null, null,
                enumOption(PortfolioAccountType.SAVINGS.getValue().longValue(), "accountType.savings", "Savings"),
                PortfolioAccountData.lookup(51352L, "000051352"),
                enumOption(PortfolioAccountType.LOAN.getValue().longValue(), "accountType.loan", "Loan"),
                PortfolioAccountData.lookup(19543L, "000019543"),
                enumOption((long) AccountTransferType.LOAN_REPAYMENT.getValue(), "accountTransferType.loan.repayment", "Loan repayment"),
                enumOption(3L, "standingInstructionPriority.medium", "Medium"),
                enumOption((long) StandingInstructionType.DUES.getValue(), "standingInstructionType.dues", "Dues"),
                enumOption((long) StandingInstructionStatus.ACTIVE.getValue(), "standingInstructionStatus.active", "Active"), amount,
                LocalDate.of(2026, 2, 25), null,
                enumOption((long) AccountTransferRecurrenceType.AS_PER_DUES.getValue(), "accountTransferRecurrenceType.as.per.dues",
                        "As per dues"),
                enumOption((long) PeriodFrequencyType.DAYS.getValue(), "periodFrequencyType.days", "Days"), 1, null);
    }

    private EnumOptionData enumOption(Long id, String code, String description) {
        return new EnumOptionData(id, code, description);
    }
}
