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

import static org.mockito.Mockito.never;
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
}
