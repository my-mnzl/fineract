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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MnzlTransferFeeChargeForLoansTaskletTest {

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
    void happyPathSingleLoanTransfersFee() throws Exception {
        final LoanChargeData charge = nonInstallmentCharge(101L, 19543L, new BigDecimal("50.00"), LocalDate.of(2026, 3, 10));
        when(loanChargeReadPlatformService.retrieveLoanChargesForFeePayment(ChargePaymentMode.ACCOUNT_TRANSFER.getValue(),
                LoanStatus.ACTIVE.getValue())).thenReturn(List.of(charge));
        when(accountAssociationsReadPlatformService.retriveLoanLinkedAssociation(eq(19543L)))
                .thenReturn(PortfolioAccountData.lookup(51352L, "000051352"));
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));

        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            mockedDateUtils.when(() -> DateUtils.isDateInTheFuture(any(LocalDate.class))).thenReturn(false);

            assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));
        }

        verify(accountTransfersWritePlatformService, times(1)).transferFunds(any(AccountTransferDTO.class));
    }

    @Test
    void happyPathMultipleLoansTransfersAll() throws Exception {
        final LoanChargeData a = nonInstallmentCharge(201L, 1L, new BigDecimal("10.00"), LocalDate.of(2026, 3, 10));
        final LoanChargeData b = nonInstallmentCharge(202L, 2L, new BigDecimal("20.00"), LocalDate.of(2026, 3, 11));
        final LoanChargeData c = nonInstallmentCharge(203L, 3L, new BigDecimal("30.00"), LocalDate.of(2026, 3, 12));
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

            assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));
        }

        verify(accountTransfersWritePlatformService, times(3)).transferFunds(any(AccountTransferDTO.class));
        verify(transactionTemplate, times(3)).execute(any());
    }

    @Test
    void oneTransferFailsOthersSucceed() {
        final LoanChargeData a = nonInstallmentCharge(301L, 1L, new BigDecimal("10.00"), LocalDate.of(2026, 3, 10));
        final LoanChargeData b = nonInstallmentCharge(302L, 2L, new BigDecimal("20.00"), LocalDate.of(2026, 3, 11));
        final LoanChargeData c = nonInstallmentCharge(303L, 3L, new BigDecimal("30.00"), LocalDate.of(2026, 3, 12));
        when(loanChargeReadPlatformService.retrieveLoanChargesForFeePayment(ChargePaymentMode.ACCOUNT_TRANSFER.getValue(),
                LoanStatus.ACTIVE.getValue())).thenReturn(List.of(a, b, c));
        when(accountAssociationsReadPlatformService.retriveLoanLinkedAssociation(any(Long.class)))
                .thenReturn(PortfolioAccountData.lookup(51352L, "000051352"));
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        // First call succeeds, second fails, third succeeds.
        doThrow(new IllegalStateException("middle blew up")).when(accountTransfersWritePlatformService).transferFunds(argMatchesLoan(2L));

        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        try (MockedStatic<DateUtils> mockedDateUtils = Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(BUSINESS_DATE);
            mockedDateUtils.when(() -> DateUtils.isDateInTheFuture(any(LocalDate.class))).thenReturn(false);

            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext)).isInstanceOf(TruncatedJobExecutionException.class);
        }

        // All three are still attempted; the failure does not short-circuit later transfers.
        verify(accountTransfersWritePlatformService, times(3)).transferFunds(any(AccountTransferDTO.class));
        verify(transactionTemplate, times(3)).execute(any());
    }

    private MnzlTransferFeeChargeForLoansTasklet newTasklet() {
        return new MnzlTransferFeeChargeForLoansTasklet(loanChargeReadPlatformService, accountAssociationsReadPlatformService,
                accountTransfersWritePlatformService, transactionTemplate);
    }

    private LoanChargeData nonInstallmentCharge(Long id, Long loanId, BigDecimal outstanding, LocalDate dueDate) {
        // chargeTimeType id=2 is SPECIFIED_DUE_DATE — isInstalmentFee() returns false. Production
        // takes the non-installment branch which calls accountAssociations + transfer once per loan.
        org.apache.fineract.infrastructure.core.data.EnumOptionData chargeTimeType = new org.apache.fineract.infrastructure.core.data.EnumOptionData(
                2L, "chargeTimeType.specifiedDueDate", "Specified due date");
        return new LoanChargeData(id, dueDate, /* submittedOnDate */ dueDate, /* amountOutstanding */ outstanding, chargeTimeType, loanId,
                /* externalLoanId */ org.apache.fineract.infrastructure.core.domain.ExternalId.empty(),
                /* installmentChargeData */ null, /* externalId */ org.apache.fineract.infrastructure.core.domain.ExternalId.empty());
    }

    private static AccountTransferDTO argMatchesLoan(long loanId) {
        return Mockito.argThat(dto -> dto != null && dto.getToAccountId() != null && dto.getToAccountId() == loanId);
    }
}
