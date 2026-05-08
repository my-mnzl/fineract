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
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.exception.TruncatedJobExecutionException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression coverage for commit {@code 4c5c0a1e5}: optimistic-lock retry-with-backoff in
 * {@link MnzlExecuteStandingInstructionsTasklet}. Pins maximum retry attempts (6) and verifies that the production code
 * delegates to the protected-package {@code sleepBeforeRetry} hook with a delay that grows by attempt.
 */
@ExtendWith(MockitoExtension.class)
class MnzlExecuteStandingInstructionsTaskletOptimisticLockRetryTest {

    private static final long INSTRUCTION_ID = 9794L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 15);

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
    void attempt1SucceedsNoRetry() throws Exception {
        commonReadStubs();
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(newTasklet());
        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));
        }

        verify(underTest, never()).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));
        verify(accountTransfersWritePlatformService, times(1)).transferFunds(any(AccountTransferDTO.class));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"),
                eq(INSTRUCTION_ID), eq("success"), eq(AMOUNT), eq(""));
    }

    @Test
    void attempt1OptimisticLockAttempt2SucceedsLogsRetry() throws Exception {
        commonReadStubs();
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new OptimisticLockException("conflict")).doReturn(1L).when(accountTransfersWritePlatformService)
                .transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(newTasklet());
        doNothing().when(underTest).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));
        }

        verify(underTest, times(1)).sleepBeforeRetry(1, INSTRUCTION_ID);
        verify(accountTransfersWritePlatformService, times(2)).transferFunds(any(AccountTransferDTO.class));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"),
                eq(INSTRUCTION_ID), eq("success"), eq(AMOUNT), eq(""));
        verify(jdbcTemplate, never()).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(INSTRUCTION_ID),
                eq("failed"), eq(AMOUNT), any(String.class));
    }

    @Test
    void attempts1To5OptimisticLockAttempt6Succeeds() throws Exception {
        commonReadStubs();
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new OptimisticLockingFailureException("v1"), new OptimisticLockingFailureException("v2"),
                new OptimisticLockingFailureException("v3"), new OptimisticLockingFailureException("v4"),
                new OptimisticLockingFailureException("v5")).doReturn(1L).when(accountTransfersWritePlatformService)
                .transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(newTasklet());
        doNothing().when(underTest).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));
        }

        verify(accountTransfersWritePlatformService, times(6)).transferFunds(any(AccountTransferDTO.class));
        verify(underTest, times(5)).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"),
                eq(INSTRUCTION_ID), eq("success"), eq(AMOUNT), eq(""));
    }

    @Test
    void attempts1To6OptimisticLockFailsLogsHistoryFailure() {
        commonReadStubs();
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new OptimisticLockingFailureException("v1"), new OptimisticLockingFailureException("v2"),
                new OptimisticLockingFailureException("v3"), new OptimisticLockingFailureException("v4"),
                new OptimisticLockingFailureException("v5"), new OptimisticLockingFailureException("final"))
                .when(accountTransfersWritePlatformService).transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(newTasklet());
        doNothing().when(underTest).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        verify(accountTransfersWritePlatformService, times(6)).transferFunds(any(AccountTransferDTO.class));
        // 5 sleeps between the 6 attempts; no sleep after the last failure.
        verify(underTest, times(5)).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));
        final ArgumentCaptor<String> errorLogCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"),
                eq(INSTRUCTION_ID), eq("failed"), eq(AMOUNT), errorLogCaptor.capture());
        assertThat(errorLogCaptor.getValue()).startsWith("Exception while trasfering funds ");
    }

    @Test
    void attempt1NonLockExceptionDoesNotRetry() {
        commonReadStubs();
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new IllegalStateException("not retryable")).when(accountTransfersWritePlatformService)
                .transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(newTasklet());

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        verify(underTest, never()).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));
        verify(accountTransfersWritePlatformService, times(1)).transferFunds(any(AccountTransferDTO.class));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"),
                eq(INSTRUCTION_ID), eq("failed"), eq(AMOUNT), any(String.class));
    }

    /**
     * Verifies the sleep delay grows per attempt. Production calls {@code Thread.sleep(attempt * 1000L)} from inside
     * {@code sleepBeforeRetry(int, Long)}. Mocking {@link Thread#sleep(long)} via {@link MockedStatic} would also catch
     * the JUnit framework's own sleeps and is brittle; instead we assert the package-private hook is called with
     * monotonically increasing attempt numbers, which the production method translates 1:1 into milliseconds.
     */
    @Test
    void backoffDelayIncreasesPerAttempt() {
        commonReadStubs();
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new OptimisticLockingFailureException("v1"), new OptimisticLockingFailureException("v2"),
                new OptimisticLockingFailureException("v3"), new OptimisticLockingFailureException("v4"),
                new OptimisticLockingFailureException("v5"), new OptimisticLockingFailureException("final"))
                .when(accountTransfersWritePlatformService).transferFunds(any(AccountTransferDTO.class));

        final MnzlExecuteStandingInstructionsTasklet underTest = spy(newTasklet());
        doNothing().when(underTest).sleepBeforeRetry(anyInt(), eq(INSTRUCTION_ID));

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        InOrder inOrder = Mockito.inOrder(underTest);
        inOrder.verify(underTest).sleepBeforeRetry(1, INSTRUCTION_ID);
        inOrder.verify(underTest).sleepBeforeRetry(2, INSTRUCTION_ID);
        inOrder.verify(underTest).sleepBeforeRetry(3, INSTRUCTION_ID);
        inOrder.verify(underTest).sleepBeforeRetry(4, INSTRUCTION_ID);
        inOrder.verify(underTest).sleepBeforeRetry(5, INSTRUCTION_ID);
    }

    private MnzlExecuteStandingInstructionsTasklet newTasklet() {
        return new MnzlExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator,
                accountTransfersWritePlatformService, transactionTemplate, scheduledDateGenerator);
    }

    private void commonReadStubs() {
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(List.of(standingInstruction(INSTRUCTION_ID, AMOUNT)));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class), eq(BUSINESS_DATE)))
                .thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
    }

    private StandingInstructionData standingInstruction(Long id, BigDecimal amount) {
        return StandingInstructionData.instance(id, 29363L, "test instruction", null, null, null, null,
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

    private EnumOptionData enumOption(Long id, String code, String description) {
        return new EnumOptionData(id, code, description);
    }
}
