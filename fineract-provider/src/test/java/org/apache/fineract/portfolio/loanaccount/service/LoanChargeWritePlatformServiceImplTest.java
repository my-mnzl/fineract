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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanAccrualTransactionCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.data.AccountingBridgeDataDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.SingleLoanChargeRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeApiJsonValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanChargeWritePlatformServiceImplTest {

    private static final Long LOAN_ID = 1L;
    private static final Integer SPECIFIED_DUE_DATE = 2;
    private static final LocalDate MATURITY_DATE = LocalDate.of(2024, 2, 15);
    private static final LocalDate BUSINESS_DATE_AFTER = LocalDate.of(2024, 2, 26);
    private static final LocalDate BUSINESS_DATE_ON = MATURITY_DATE;
    private static final LocalDate BUSINESS_DATE_BEFORE = LocalDate.of(2024, 2, 14);
    private static final String CURRENCY_CODE = "USD";

    @InjectMocks
    private LoanChargeWritePlatformServiceImpl loanChargeWritePlatformService;

    @Mock
    private JsonCommand jsonCommand;

    @Mock
    private LoanChargeApiJsonValidator loanChargeApiJsonValidator;

    @Mock
    private LoanAssembler loanAssembler;

    @Mock
    private Loan loan;

    @Mock
    private ChargeRepositoryWrapper chargeRepository;

    @Mock
    private Charge chargeDefinition;

    @Mock
    private LoanChargeAssembler loanChargeAssembler;

    @Mock
    private LoanCharge loanCharge;

    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    @Mock
    private LoanProductRelatedDetail loanRepaymentScheduleDetail;

    @Mock
    private LoanChargeRepository loanChargeRepository;

    @Mock
    private ConfigurationDomainService configurationDomainService;

    @Mock
    private LoanTransactionRepository loanTransactionRepository;

    @Mock
    private LoanTransaction loanTransaction;

    @Mock
    private LoanAccountDomainService loanAccountDomainService;

    @Mock
    private MonetaryCurrency monetaryCurrency;

    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;

    @Mock
    private LoanChargeValidator loanChargeValidator;

    @Mock
    private LoanAccrualTransactionBusinessEventService loanAccrualTransactionBusinessEventService;

    @Mock
    private ReprocessLoanTransactionsService reprocessLoanTransactionsService;

    @Mock
    private LoanAccountService loanAccountService;

    @Mock
    private LoanChargeService loanChargeService;

    @Mock
    private ChargeCalculationType chargeCalculationType;

    @Mock
    private SingleLoanChargeRepaymentScheduleProcessingWrapper wrapper;

    @Mock
    private LoanLifecycleStateMachine loanLifecycleStateMachine;

    @Mock
    private LoanJournalEntryPoster journalEntryPoster;

    @Mock
    private LoanScheduleService loanScheduleService;

    @Mock
    private ScheduledDateGenerator scheduledDateGenerator;

    @Mock
    private LoanProduct loanProduct;

    @BeforeEach
    void setUp() {
        when(loanAssembler.assembleFrom(LOAN_ID)).thenReturn(loan);
        when(chargeRepository.findOneWithNotFoundDetection(anyLong())).thenReturn(chargeDefinition);
        when(chargeDefinition.getChargeTimeType()).thenReturn(SPECIFIED_DUE_DATE);
        when(chargeDefinition.getCurrencyCode()).thenReturn(CURRENCY_CODE);
        when(loanChargeAssembler.createNewFromJson(loan, chargeDefinition, jsonCommand)).thenReturn(loanCharge);
        when(loan.getLoanProductRelatedDetail()).thenReturn(loanRepaymentScheduleDetail);
        when(loanRepaymentScheduleDetail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        when(loan.getLoanRepaymentScheduleDetail()).thenReturn(loanRepaymentScheduleDetail);
        when(loan.hasCurrencyCodeOf(CURRENCY_CODE)).thenReturn(true);
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loanCharge.getChargePaymentMode()).thenReturn(ChargePaymentMode.REGULAR);
        when(loanCharge.getChargeCalculation()).thenReturn(chargeCalculationType);
        when(chargeCalculationType.isPercentageBased()).thenReturn(false);
        when(loanCharge.amountOrPercentage()).thenReturn(BigDecimal.TEN);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loanChargeRepository.saveAndFlush(any(LoanCharge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(loan.getCurrency()).thenReturn(monetaryCurrency);
        when(monetaryCurrency.getCode()).thenReturn(CURRENCY_CODE);
        when(loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(any())).thenReturn(loan);

        when(loan.getLoanCharges()).thenReturn(new HashSet<>());
        when(loan.getCharges()).thenReturn(new ArrayList<>());
        when(loan.getDisbursementDate()).thenReturn(LocalDate.now(ZoneId.systemDefault()));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(new ArrayList<>());
        when(loanChargeService.calculateAmountPercentageAppliedTo(any(Loan.class), any(LoanCharge.class))).thenReturn(BigDecimal.TEN);
        when(loan.fetchNumberOfInstallmentsAfterExceptions()).thenReturn(5);
        when(loan.updateSummaryWithTotalFeeChargesDueAtDisbursement(any(BigDecimal.class))).thenReturn(null);
        when(loan.deriveSumTotalOfChargesDueAtDisbursement()).thenReturn(BigDecimal.ZERO);
        when(loanCharge.getDueLocalDate()).thenReturn(LocalDate.now(ZoneId.systemDefault()));
        when(loanCharge.getEffectiveDueDate()).thenReturn(LocalDate.now(ZoneId.systemDefault()));

        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);
        when(loan.isCashBasedAccountingEnabledOnLoanProduct()).thenReturn(false);
        when(loan.isUpfrontAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);

        doNothing().when(journalEntryWritePlatformService).createJournalEntriesForLoan(any(AccountingBridgeDataDTO.class));
        doNothing().when(loanChargeService).addLoanCharge(any(Loan.class), any(LoanCharge.class));
    }

    @ParameterizedTest
    @MethodSource("loanChargeAccrualTestCases")
    void shouldHandleAccrualBasedOnConfigurationAndDates(boolean isAccrualEnabled, LocalDate businessDate, LocalDate maturityDate, boolean isAccrualExpected) {
        when(configurationDomainService.isImmediateChargeAccrualPostMaturityEnabled()).thenReturn(isAccrualEnabled);
        when(loan.getMaturityDate()).thenReturn(maturityDate);
        when(loanChargeService.handleChargeAppliedTransaction(loan, loanCharge, null)).thenReturn(loanTransaction);
        when(loanChargeService.createChargeAppliedTransaction(loan, loanCharge, null)).thenReturn(loanTransaction);

        if (isAccrualExpected) {
            when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(true);
        }

        try (MockedStatic<DateUtils> mockedDateUtils = mockStatic(DateUtils.class);
             MockedStatic<MoneyHelper> mockedMoneyHelper = mockStatic(MoneyHelper.class)) {

            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);
            mockedDateUtils.when(() -> DateUtils.isBeforeBusinessDate(any(LocalDate.class))).thenReturn(false);
            mockedMoneyHelper.when(MoneyHelper::getMathContext).thenReturn(java.math.MathContext.DECIMAL64);
            mockedMoneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(java.math.RoundingMode.HALF_EVEN);

            loanChargeWritePlatformService.addLoanCharge(LOAN_ID, jsonCommand);
        }

        if (isAccrualExpected) {
            verify(loanTransactionRepository, times(1)).saveAndFlush(any(LoanTransaction.class));
            verify(businessEventNotifierService, times(1)).notifyPostBusinessEvent(any(LoanAccrualTransactionCreatedBusinessEvent.class));
        } else {
            verify(loanTransactionRepository, never()).saveAndFlush(any(LoanTransaction.class));
            verify(businessEventNotifierService, never()).notifyPostBusinessEvent(any(LoanAccrualTransactionCreatedBusinessEvent.class));
        }
    }

    @Test
    void applyPeriodicChargesForLoanBackfillsMissingYearlyOccurrencesFromExpectedFirstRepaymentDate() {
        final LocalDate anchorDate = LocalDate.of(2024, 2, 15);
        final LocalDate nextOccurrence = LocalDate.of(2025, 2, 15);
        final LocalDate businessDate = LocalDate.of(2025, 3, 12);
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(anchorDate);
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition));
        when(chargeDefinition.isActive()).thenReturn(true);
        when(chargeDefinition.isLoanCharge()).thenReturn(true);
        when(chargeDefinition.isLoanPeriodic()).thenReturn(true);
        when(chargeDefinition.getId()).thenReturn(100L);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.YEARS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(1);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.YEARS, 1, anchorDate)).thenReturn(nextOccurrence);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.YEARS, 1, nextOccurrence))
                .thenReturn(nextOccurrence.plusYears(1));
        LoanCharge firstPeriodicCharge = materializedCharge(anchorDate);
        LoanCharge secondPeriodicCharge = materializedCharge(nextOccurrence);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, anchorDate)).thenReturn(firstPeriodicCharge);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, nextOccurrence)).thenReturn(secondPeriodicCharge);

        try (MockedStatic<DateUtils> mockedDateUtils = mockStatic(DateUtils.class, org.mockito.Answers.CALLS_REAL_METHODS)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            loanChargeWritePlatformService.applyPeriodicChargesForLoan(LOAN_ID);
        }

        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, anchorDate);
        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, nextOccurrence);
        verify(reprocessLoanTransactionsService, times(1)).reprocessTransactions(loan);
        verify(loanLifecycleStateMachine, times(1)).determineAndTransition(loan, nextOccurrence);
        verify(loanAccountService, times(1)).saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
        verify(loanAccountDomainService, times(1)).setLoanDelinquencyTag(loan, businessDate);
    }

    @Test
    void applyPeriodicChargesForLoanUsesRepaymentScheduleFallbackAndSkipsExistingOccurrences() {
        final LocalDate anchorDate = LocalDate.of(2024, 2, 15);
        final LocalDate secondOccurrence = LocalDate.of(2024, 5, 15);
        final LocalDate thirdOccurrence = LocalDate.of(2024, 8, 15);
        final LocalDate businessDate = LocalDate.of(2024, 8, 15);
        LoanRepaymentScheduleInstallment downPayment = repaymentInstallment(1, LocalDate.of(2024, 1, 15), true);
        LoanRepaymentScheduleInstallment firstRepayment = repaymentInstallment(2, anchorDate, false);
        LoanCharge existingCharge = existingCharge(anchorDate);
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(null);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(firstRepayment, downPayment));
        when(loan.getCharges()).thenReturn(List.of(existingCharge));
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition));
        when(chargeDefinition.isActive()).thenReturn(true);
        when(chargeDefinition.isLoanCharge()).thenReturn(true);
        when(chargeDefinition.isLoanPeriodic()).thenReturn(true);
        when(chargeDefinition.getId()).thenReturn(100L);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(3);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, anchorDate)).thenReturn(secondOccurrence);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, secondOccurrence)).thenReturn(thirdOccurrence);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, thirdOccurrence))
                .thenReturn(thirdOccurrence.plusMonths(3));
        LoanCharge secondPeriodicCharge = materializedCharge(secondOccurrence);
        LoanCharge thirdPeriodicCharge = materializedCharge(thirdOccurrence);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, secondOccurrence)).thenReturn(secondPeriodicCharge);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, thirdOccurrence)).thenReturn(thirdPeriodicCharge);

        try (MockedStatic<DateUtils> mockedDateUtils = mockStatic(DateUtils.class, org.mockito.Answers.CALLS_REAL_METHODS)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(businessDate);

            loanChargeWritePlatformService.applyPeriodicChargesForLoan(LOAN_ID);
        }

        verify(loanChargeAssembler, never()).createNewFromChargeDefinition(loan, chargeDefinition, anchorDate);
        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, secondOccurrence);
        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, thirdOccurrence);
        verify(loanLifecycleStateMachine, times(1)).determineAndTransition(loan, thirdOccurrence);
    }

    @ParameterizedTest
    @MethodSource("nonOpenLoanStates")
    void applyPeriodicChargesForLoanSkipsClosedAndChargedOffLoans(final boolean chargedOff, final LoanStatus status) {
        when(loan.isChargedOff()).thenReturn(chargedOff);
        when(loan.getStatus()).thenReturn(status);

        loanChargeWritePlatformService.applyPeriodicChargesForLoan(LOAN_ID);

        verifyNoInteractions(loanChargeAssembler, reprocessLoanTransactionsService, loanLifecycleStateMachine);
    }

    private static Stream<Arguments> loanChargeAccrualTestCases() {
        return Stream.of(Arguments.of(true, BUSINESS_DATE_AFTER, MATURITY_DATE, true),
                Arguments.of(false, BUSINESS_DATE_AFTER, MATURITY_DATE, false), Arguments.of(true, BUSINESS_DATE_ON, MATURITY_DATE, false),
                Arguments.of(true, BUSINESS_DATE_BEFORE, MATURITY_DATE, false));
    }

    private static Stream<Arguments> nonOpenLoanStates() {
        return Stream.of(Arguments.of(false, LoanStatus.CLOSED_OBLIGATIONS_MET), Arguments.of(true, LoanStatus.ACTIVE));
    }

    private LoanCharge materializedCharge(final LocalDate dueDate) {
        LoanCharge createdCharge = org.mockito.Mockito.mock(LoanCharge.class);
        when(createdCharge.amount()).thenReturn(BigDecimal.TEN);
        when(createdCharge.getDueLocalDate()).thenReturn(dueDate);
        when(createdCharge.getEffectiveDueDate()).thenReturn(dueDate);
        when(createdCharge.getChargePaymentMode()).thenReturn(ChargePaymentMode.REGULAR);
        when(createdCharge.getChargeCalculation()).thenReturn(chargeCalculationType);
        return createdCharge;
    }

    private LoanCharge existingCharge(final LocalDate dueDate) {
        LoanCharge existingCharge = org.mockito.Mockito.mock(LoanCharge.class);
        when(existingCharge.getCharge()).thenReturn(chargeDefinition);
        when(existingCharge.getDueLocalDate()).thenReturn(dueDate);
        return existingCharge;
    }

    private LoanRepaymentScheduleInstallment repaymentInstallment(final Integer installmentNumber, final LocalDate dueDate,
            final boolean downPayment) {
        LoanRepaymentScheduleInstallment installment = org.mockito.Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getInstallmentNumber()).thenReturn(installmentNumber);
        when(installment.getDueDate()).thenReturn(dueDate);
        when(installment.isDownPayment()).thenReturn(downPayment);
        return installment;
    }
}
