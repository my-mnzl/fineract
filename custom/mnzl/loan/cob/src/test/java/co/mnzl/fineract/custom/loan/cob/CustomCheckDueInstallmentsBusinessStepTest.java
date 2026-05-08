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
package co.mnzl.fineract.custom.loan.cob;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.cob.loan.CheckDueInstallmentsBusinessStep;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.BusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAccountCustomSnapshotBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomCheckDueInstallmentsBusinessStepTest {

    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    @Mock
    private CheckDueInstallmentsBusinessStep defaultBusinessStep;

    @Mock
    private MnzlLoanProductStrategyReadService loanProductStrategyReadService;

    @Captor
    private ArgumentCaptor<BusinessEvent<?>> businessEventArgumentCaptor;

    @InjectMocks
    private CustomCheckDueInstallmentsBusinessStep underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.parse("2024-01-16"),
                BusinessDateType.COB_DATE, LocalDate.parse("2024-01-15"))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void executeDelegatesToDefaultCoreStepWhenProductUsesCoreCobStrategy() {
        Loan loan = Mockito.mock(Loan.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L)).thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_CORE));
        when(defaultBusinessStep.execute(loan)).thenReturn(loan);

        Loan result = underTest.execute(loan);

        Assertions.assertSame(loan, result);
        verify(defaultBusinessStep).execute(loan);
        verifyNoInteractions(businessEventNotifierService);
    }

    @Test
    void executePublishesSnapshotEventFromLegacyDefaultWhenNoStrategyRowExists() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment installment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L)).thenReturn(Optional.empty());
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(installment.isNotFullyPaidOff()).thenReturn(true);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        Loan result = underTest.execute(loan);

        Assertions.assertSame(loan, result);
        verify(defaultBusinessStep, never()).execute(loan);
        verify(businessEventNotifierService).notifyPostBusinessEvent(businessEventArgumentCaptor.capture());
        BusinessEvent<?> rawEvent = businessEventArgumentCaptor.getValue();
        Assertions.assertInstanceOf(LoanAccountCustomSnapshotBusinessEvent.class, rawEvent);
        Assertions.assertSame(loan, rawEvent.get());
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    @Test
    void executePublishesSnapshotEventFromCustomCobPathWhenProductUsesCustomStrategy() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment installment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(installment.isNotFullyPaidOff()).thenReturn(true);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        Loan result = underTest.execute(loan);

        Assertions.assertSame(loan, result);
        verify(defaultBusinessStep, never()).execute(loan);
        verify(businessEventNotifierService).notifyPostBusinessEvent(businessEventArgumentCaptor.capture());
        BusinessEvent<?> rawEvent = businessEventArgumentCaptor.getValue();
        Assertions.assertInstanceOf(LoanAccountCustomSnapshotBusinessEvent.class, rawEvent);
        Assertions.assertSame(loan, rawEvent.get());
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    @Test
    void firesEventOnlyWhenInstallmentNotFullyPaidOff() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment paidInstallment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        LoanRepaymentScheduleInstallment unpaidInstallment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(paidInstallment, unpaidInstallment));
        when(paidInstallment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(paidInstallment.isNotFullyPaidOff()).thenReturn(false);
        when(unpaidInstallment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(unpaidInstallment.isNotFullyPaidOff()).thenReturn(true);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        Loan result = underTest.execute(loan);

        Assertions.assertSame(loan, result);
        verify(businessEventNotifierService).notifyPostBusinessEvent(businessEventArgumentCaptor.capture());
        Assertions.assertInstanceOf(LoanAccountCustomSnapshotBusinessEvent.class, businessEventArgumentCaptor.getValue());
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    @Test
    void noEventWhenAllDueInstallmentsAreFullyPaidOff() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment paidInstallment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(paidInstallment));
        when(paidInstallment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(paidInstallment.isNotFullyPaidOff()).thenReturn(false);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        Loan result = underTest.execute(loan);

        Assertions.assertSame(loan, result);
        verify(businessEventNotifierService, never()).notifyPostBusinessEvent(Mockito.any());
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    @Test
    void actionContextSwitchedToDefaultDuringEvent_restoredToCobAfter() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment installment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(installment.isNotFullyPaidOff()).thenReturn(true);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        AtomicReference<ActionContext> contextAtNotifyTime = new AtomicReference<>();
        doAnswer(invocation -> {
            contextAtNotifyTime.set(ThreadLocalContextUtil.getActionContext());
            return null;
        }).when(businessEventNotifierService).notifyPostBusinessEvent(Mockito.any());

        underTest.execute(loan);

        Assertions.assertEquals(ActionContext.DEFAULT, contextAtNotifyTime.get(),
                "ActionContext should be DEFAULT while the snapshot event is firing");
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext(),
                "ActionContext should be restored to COB after the step completes");
    }

    @Test
    void actionContextRestored_evenWhenListenerThrows() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment installment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(installment.isNotFullyPaidOff()).thenReturn(true);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        doThrow(new RuntimeException("listener boom")).when(businessEventNotifierService).notifyPostBusinessEvent(Mockito.any());

        Assertions.assertThrows(RuntimeException.class, () -> underTest.execute(loan));
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext(),
                "finally block must restore ActionContext to COB even when listener throws");
    }

    @Test
    void noEventWhenCobDateMatchesNoInstallment() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment installment = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.getDueDate()).thenReturn(LocalDate.parse("2024-02-01"));
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        Loan result = underTest.execute(loan);

        Assertions.assertSame(loan, result);
        verify(businessEventNotifierService, never()).notifyPostBusinessEvent(Mockito.any());
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    /**
     * Production behaviour: the custom step uses a single boolean flag and fires AT MOST ONE
     * {@link LoanAccountCustomSnapshotBusinessEvent} per loan, regardless of how many due-and-unpaid installments share
     * the COB date. This test pins that contract.
     */
    @Test
    void multipleDueInstallmentsOnSameDate_firesSingleEventPerLoan() {
        Loan loan = Mockito.mock(Loan.class);
        LoanRepaymentScheduleInstallment first = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        LoanRepaymentScheduleInstallment second = Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(loan.productId()).thenReturn(12L);
        when(loanProductStrategyReadService.findCobStrategyCode(12L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(first, second));
        when(first.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(first.isNotFullyPaidOff()).thenReturn(true);
        when(second.getDueDate()).thenReturn(LocalDate.parse("2024-01-15"));
        when(second.isNotFullyPaidOff()).thenReturn(true);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);

        underTest.execute(loan);

        verify(businessEventNotifierService, times(1)).notifyPostBusinessEvent(businessEventArgumentCaptor.capture());
        Assertions.assertInstanceOf(LoanAccountCustomSnapshotBusinessEvent.class, businessEventArgumentCaptor.getValue());
        Assertions.assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }
}
