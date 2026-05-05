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
package co.mnzl.fineract.custom.loan.grace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.repayment.LoanRepaymentOverdueBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlCheckLoanRepaymentOverdueBusinessStepTest {

    private static final long OFFICE_ID = 7L;
    private static final LocalDate DUE_DATE = LocalDate.of(2025, 1, 5);
    private static final WorkingDays WORKING_DAYS = new WorkingDays("FREQ=WEEKLY;BYDAY=SU,MO,TU,WE,TH",
            RepaymentRescheduleType.SAME_DAY.getValue(), false, false);

    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;
    @Mock
    private MnzlWorkingDayCalculator workingDayCalculator;
    @Mock
    private Loan loan;
    @Mock
    private LoanProduct loanProduct;
    @Mock
    private LoanSummary loanSummary;
    @Mock
    private Office office;
    @Mock
    private LoanRepaymentScheduleInstallment installment;
    @Mock
    private MonetaryCurrency currency;

    private MnzlCheckLoanRepaymentOverdueBusinessStep step;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        step = new MnzlCheckLoanRepaymentOverdueBusinessStep(configurationDomainService, businessEventNotifierService,
                workingDayCalculator);

        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getSummary()).thenReturn(loanSummary);
        when(loanSummary.getTotalOutstanding()).thenReturn(BigDecimal.valueOf(100));
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loanProduct.getOverDueDaysForRepaymentEvent()).thenReturn(null);
        when(loan.getOffice()).thenReturn(office);
        when(office.getId()).thenReturn(OFFICE_ID);
        when(loan.getCurrency()).thenReturn(currency);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.isObligationsMet()).thenReturn(false);
        when(installment.getDueDate()).thenReturn(DUE_DATE);
        when(configurationDomainService.retrieveRepaymentOverdueDays()).thenReturn(5L);
        when(workingDayCalculator.getWorkingDays()).thenReturn(WORKING_DAYS);
        when(workingDayCalculator.getActiveHolidaysForOffice(eq(OFFICE_ID), any())).thenReturn(List.of());
    }

    @Test
    void firesOverdueEventOnTheWorkingDayTrigger() {
        LocalDate businessDate = LocalDate.of(2025, 1, 12); // 5 working days after Sunday 2025-01-05.
        setBusinessDate(businessDate);
        when(workingDayCalculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(businessDate);
        Money outstanding = positiveMoney();
        when(installment.getTotalOutstanding(currency)).thenReturn(outstanding);

        step.execute(loan);

        verify(businessEventNotifierService).notifyPostBusinessEvent(any(LoanRepaymentOverdueBusinessEvent.class));
    }

    @Test
    void doesNotFireBeforeWorkingDayTriggerEvenIfCalendarGraceHasElapsed() {
        LocalDate businessDate = LocalDate.of(2025, 1, 10); // 5 calendar days, but only 3 working days have passed.
        setBusinessDate(businessDate);
        when(workingDayCalculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(LocalDate.of(2025, 1, 12));

        step.execute(loan);

        verify(businessEventNotifierService, never()).notifyPostBusinessEvent(any());
    }

    @Test
    void skipsObligationsMetInstallments() {
        setBusinessDate(LocalDate.of(2025, 1, 12));
        when(installment.isObligationsMet()).thenReturn(true);

        step.execute(loan);

        verify(businessEventNotifierService, never()).notifyPostBusinessEvent(any());
        verify(workingDayCalculator, never()).addWorkingDays(any(), anyInt(), any(), any());
    }

    @Test
    void noopForFullyPaidLoans() {
        setBusinessDate(LocalDate.of(2025, 1, 12));
        when(loanSummary.getTotalOutstanding()).thenReturn(BigDecimal.ZERO);

        step.execute(loan);

        verify(businessEventNotifierService, never()).notifyPostBusinessEvent(any());
    }

    @Test
    void negativeOffsetFallsBackToCalendarArithmetic() {
        // Upstream supports "raise event N days BEFORE due"; preserve that for negative configs.
        LocalDate businessDate = LocalDate.of(2025, 1, 3); // due 2025-01-05 minus 2 calendar days
        setBusinessDate(businessDate);
        when(configurationDomainService.retrieveRepaymentOverdueDays()).thenReturn(-2L);
        Money outstanding = positiveMoney();
        when(installment.getTotalOutstanding(currency)).thenReturn(outstanding);

        step.execute(loan);

        verify(businessEventNotifierService).notifyPostBusinessEvent(any(LoanRepaymentOverdueBusinessEvent.class));
        verify(workingDayCalculator, never()).addWorkingDays(any(LocalDate.class), anyInt(), any(WorkingDays.class), any());
    }

    private static void setBusinessDate(LocalDate date) {
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, date);
        dates.put(BusinessDateType.COB_DATE, date.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);
    }

    private static Money positiveMoney() {
        Money money = mock(Money.class);
        when(money.isGreaterThanZero()).thenReturn(true);
        return money;
    }
}
