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
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression coverage for commit {@code b345fea66}: standing-instructions job uses an independent transaction per
 * transfer, so a failure on transfer N does not roll back transfers 1..N-1, and the loop continues to transfer N+1.
 */
@ExtendWith(MockitoExtension.class)
class StandingInstructionsTaskletPerTransferTransactionTest {

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
    void failureInTransferNDoesNotRollbackTransfersBeforeIt() {
        // 3 instructions; the second blows up. Verify the first commits its history+last_run rows
        // (no rollback) and the third still attempts.
        StandingInstructionData a = standingInstruction(1001L, new BigDecimal("10.00"), 19501L);
        StandingInstructionData b = standingInstruction(1002L, new BigDecimal("20.00"), 19502L);
        StandingInstructionData c = standingInstruction(1003L, new BigDecimal("30.00"), 19503L);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue())).thenReturn(List.of(a, b, c));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class),
                eq(BUSINESS_DATE))).thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        // First and third succeed (transferFunds returns long); middle fails.
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(19501L))).thenReturn(1L);
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(19502L)))
                .thenThrow(new IllegalStateException("middle blew up"));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(19503L))).thenReturn(1L);

        MnzlExecuteStandingInstructionsTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        // Transfer 1 still committed its success history + last_run update.
        verify(jdbcTemplate, times(1)).update(eq("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?"),
                eq(BUSINESS_DATE), eq(1001L));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(1001L),
                eq("success"), any(BigDecimal.class), eq(""));
        // Transfer 2 logged a failure history row.
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(1002L),
                eq("failed"), any(BigDecimal.class), any(String.class));
        // Transfer 3 was attempted (failure on 2 didn't short-circuit the loop) and committed.
        verify(jdbcTemplate, times(1)).update(eq("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?"),
                eq(BUSINESS_DATE), eq(1003L));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(1003L),
                eq("success"), any(BigDecimal.class), eq(""));
    }

    @Test
    void successAfterFailureContinuesProcessing() {
        StandingInstructionData a = standingInstruction(2001L, new BigDecimal("10.00"), 29501L);
        StandingInstructionData b = standingInstruction(2002L, new BigDecimal("20.00"), 29502L);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue())).thenReturn(List.of(a, b));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class),
                eq(BUSINESS_DATE))).thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(29501L)))
                .thenThrow(new IllegalStateException("first blew up"));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(29502L))).thenReturn(1L);

        MnzlExecuteStandingInstructionsTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        // Both observable: failure history for 2001, success history + last_run for 2002.
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(2001L),
                eq("failed"), any(BigDecimal.class), any(String.class));
        verify(jdbcTemplate, times(1)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"), eq(2002L),
                eq("success"), any(BigDecimal.class), eq(""));
        verify(jdbcTemplate, times(1)).update(eq("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?"),
                eq(BUSINESS_DATE), eq(2002L));
    }

    @Test
    void eachTransferUsesIndependentTransactionBoundary() {
        // 3 instructions, all succeed. Production uses TransactionTemplate.execute() per transfer
        // (success path = 1 call per transfer for transferFunds + 1 call for the success history).
        // Failure path uses 2 separate transactions (transferFunds attempt, history insert).
        // Either way, propagation must be REQUIRES_NEW.
        StandingInstructionData a = standingInstruction(3001L, new BigDecimal("10.00"), 39501L);
        StandingInstructionData b = standingInstruction(3002L, new BigDecimal("20.00"), 39502L);
        StandingInstructionData c = standingInstruction(3003L, new BigDecimal("30.00"), 39503L);
        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue())).thenReturn(List.of(a, b, c));
        when(scheduledDateGenerator.isDateFallsInSchedule(any(PeriodFrequencyType.class), anyInt(), any(LocalDate.class),
                eq(BUSINESS_DATE))).thenReturn(true);
        when(sqlGenerator.escape("status")).thenReturn("status");
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        MnzlExecuteStandingInstructionsTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            try {
                underTest.execute(stepContribution, chunkContext);
            } catch (Exception e) {
                throw new AssertionError("happy-path execute should not throw", e);
            }
        }

        // 3 transfers succeed, each in its own TransactionTemplate.execute() call.
        verify(transactionTemplate, times(3)).execute(any());
        // Propagation must be REQUIRES_NEW each time — set before each call.
        verify(transactionTemplate, atLeastOnce()).setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Sanity: history rows + last-run-date rows reflect 3 successful, independent transfers.
        verify(jdbcTemplate, times(3)).update(eq("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?"),
                any(LocalDate.class), any(Long.class));
        verify(accountTransfersWritePlatformService, times(3)).transferFunds(any(AccountTransferDTO.class));

        // Confirm the production class is using TransactionTemplate (per-transfer boundary).
        assertThat(MnzlExecuteStandingInstructionsTasklet.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(TransactionTemplate.class));
    }

    private MnzlExecuteStandingInstructionsTasklet newTasklet() {
        return new MnzlExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator,
                accountTransfersWritePlatformService, transactionTemplate, scheduledDateGenerator);
    }

    private static AccountTransferDTO argMatchesLoan(long loanId) {
        return Mockito.argThat(dto -> dto != null && dto.getToAccountId() != null && dto.getToAccountId() == loanId);
    }

    private StandingInstructionData standingInstruction(Long id, BigDecimal amount, long toLoanId) {
        return StandingInstructionData.instance(id, 29363L, "instr-" + id, null, null, null, null,
                enumOption(PortfolioAccountType.SAVINGS.getValue().longValue(), "accountType.savings", "Savings"),
                PortfolioAccountData.lookup(51352L, "000051352"),
                enumOption(PortfolioAccountType.LOAN.getValue().longValue(), "accountType.loan", "Loan"),
                PortfolioAccountData.lookup(toLoanId, String.format("%09d", toLoanId)),
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
