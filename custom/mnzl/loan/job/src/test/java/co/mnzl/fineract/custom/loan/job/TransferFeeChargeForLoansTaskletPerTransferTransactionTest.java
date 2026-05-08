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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.jobs.exception.TruncatedJobExecutionException;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargeData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression coverage for commit {@code f4489d051}: savings-to-loan fee-charge job uses an independent transaction per
 * transfer.
 */
@ExtendWith(MockitoExtension.class)
class TransferFeeChargeForLoansTaskletPerTransferTransactionTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 15);

    @Mock
    private LoanChargeReadPlatformService loanChargeReadPlatformService;

    @Mock
    private AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;

    @Mock
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private TransactionStatus transactionStatus;

    @Test
    void failureInTransferNDoesNotRollbackTransfer1ToNMinus1() {
        LoanChargeData a = nonInstallmentCharge(401L, 19501L, new BigDecimal("10.00"));
        LoanChargeData b = nonInstallmentCharge(402L, 19502L, new BigDecimal("20.00"));
        LoanChargeData c = nonInstallmentCharge(403L, 19503L, new BigDecimal("30.00"));
        when(loanChargeReadPlatformService.retrieveLoanChargesForFeePayment(ChargePaymentMode.ACCOUNT_TRANSFER.getValue(),
                LoanStatus.ACTIVE.getValue())).thenReturn(List.of(a, b, c));
        when(accountAssociationsReadPlatformService.retriveLoanLinkedAssociation(any(Long.class)))
                .thenReturn(PortfolioAccountData.lookup(51352L, "000051352"));
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(19501L))).thenReturn(1L);
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(19502L)))
                .thenThrow(new IllegalStateException("middle blew up"));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(19503L))).thenReturn(1L);

        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            mockedDateUtils.when(() -> DateUtils.isDateInTheFuture(any(LocalDate.class))).thenReturn(false);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        // All three are attempted — failure on N=2 doesn't short-circuit transfer 3.
        verify(accountTransfersWritePlatformService, times(3)).transferFunds(any(AccountTransferDTO.class));
        // 3 separate execute() calls — one transaction per transfer.
        verify(transactionTemplate, times(3)).execute(any());
    }

    @Test
    void successAfterFailureContinuesProcessing() {
        LoanChargeData a = nonInstallmentCharge(501L, 29501L, new BigDecimal("10.00"));
        LoanChargeData b = nonInstallmentCharge(502L, 29502L, new BigDecimal("20.00"));
        when(loanChargeReadPlatformService.retrieveLoanChargesForFeePayment(ChargePaymentMode.ACCOUNT_TRANSFER.getValue(),
                LoanStatus.ACTIVE.getValue())).thenReturn(List.of(a, b));
        when(accountAssociationsReadPlatformService.retriveLoanLinkedAssociation(any(Long.class)))
                .thenReturn(PortfolioAccountData.lookup(51352L, "000051352"));
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(29501L)))
                .thenThrow(new IllegalStateException("first blew up"));
        when(accountTransfersWritePlatformService.transferFunds(argMatchesLoan(29502L))).thenReturn(1L);

        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            mockedDateUtils.when(() -> DateUtils.isDateInTheFuture(any(LocalDate.class))).thenReturn(false);
            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        verify(accountTransfersWritePlatformService, times(2)).transferFunds(any(AccountTransferDTO.class));
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void eachTransferUsesIndependentTransactionBoundary() throws Exception {
        LoanChargeData a = nonInstallmentCharge(601L, 39501L, new BigDecimal("10.00"));
        LoanChargeData b = nonInstallmentCharge(602L, 39502L, new BigDecimal("20.00"));
        LoanChargeData c = nonInstallmentCharge(603L, 39503L, new BigDecimal("30.00"));
        when(loanChargeReadPlatformService.retrieveLoanChargesForFeePayment(ChargePaymentMode.ACCOUNT_TRANSFER.getValue(),
                LoanStatus.ACTIVE.getValue())).thenReturn(List.of(a, b, c));
        when(accountAssociationsReadPlatformService.retriveLoanLinkedAssociation(any(Long.class)))
                .thenReturn(PortfolioAccountData.lookup(51352L, "000051352"));
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            mockedDateUtils.when(() -> DateUtils.isDateInTheFuture(any(LocalDate.class))).thenReturn(false);
            RepeatStatus result = underTest.execute(stepContribution, chunkContext);
            assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        }

        verify(transactionTemplate, times(3)).execute(any());
        verify(transactionTemplate, atLeastOnce()).setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(accountTransfersWritePlatformService, times(3)).transferFunds(any(AccountTransferDTO.class));

        // Confirm the production class wires a TransactionTemplate as a field — per-transfer boundary precondition.
        assertThat(MnzlTransferFeeChargeForLoansTasklet.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(TransactionTemplate.class));
    }

    private MnzlTransferFeeChargeForLoansTasklet newTasklet() {
        return new MnzlTransferFeeChargeForLoansTasklet(loanChargeReadPlatformService, accountAssociationsReadPlatformService,
                accountTransfersWritePlatformService, transactionTemplate);
    }

    private static AccountTransferDTO argMatchesLoan(long loanId) {
        return Mockito.argThat(dto -> dto != null && dto.getToAccountId() != null && dto.getToAccountId() == loanId);
    }

    private LoanChargeData nonInstallmentCharge(Long id, Long loanId, BigDecimal outstanding) {
        EnumOptionData chargeTimeType = new EnumOptionData(2L, "chargeTimeType.specifiedDueDate", "Specified due date");
        return new LoanChargeData(id, BUSINESS_DATE.minusDays(1), BUSINESS_DATE.minusDays(1), outstanding, chargeTimeType, loanId,
                ExternalId.empty(), null, ExternalId.empty());
    }
}
