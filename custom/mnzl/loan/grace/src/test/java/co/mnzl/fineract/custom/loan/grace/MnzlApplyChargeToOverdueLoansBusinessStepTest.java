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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
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
class MnzlApplyChargeToOverdueLoansBusinessStepTest {

    private static final long LOAN_ID = 42L;
    private static final long OFFICE_ID = 7L;
    private static final long PENALTY_CHARGE_ID = 99L;
    private static final LocalDate DUE_DATE = LocalDate.of(2025, 1, 5);
    private static final WorkingDays WORKING_DAYS = new WorkingDays("FREQ=WEEKLY;BYDAY=SU,MO,TU,WE,TH",
            RepaymentRescheduleType.SAME_DAY.getValue(), false, false);

    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private LoanChargeWritePlatformService loanChargeWritePlatformService;
    @Mock
    private MnzlWorkingDayCalculator workingDayCalculator;
    @Mock
    private Loan loan;
    @Mock
    private LoanProduct loanProduct;
    @Mock
    private Office office;
    @Mock
    private Charge penaltyCharge;
    @Mock
    private LoanRepaymentScheduleInstallment installment;
    @Mock
    private MonetaryCurrency currency;
    @Mock
    private Money zeroMoney;

    private MnzlApplyChargeToOverdueLoansBusinessStep step;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        step = new MnzlApplyChargeToOverdueLoansBusinessStep(configurationDomainService, loanChargeWritePlatformService,
                workingDayCalculator);

        when(loan.isOpen()).thenReturn(true);
        when(loan.getId()).thenReturn(LOAN_ID);
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loan.getOffice()).thenReturn(office);
        when(office.getId()).thenReturn(OFFICE_ID);
        when(loan.getCurrency()).thenReturn(currency);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(installment.isObligationsMet()).thenReturn(false);
        when(installment.isRecalculatedInterestComponent()).thenReturn(false);
        when(installment.getDueDate()).thenReturn(DUE_DATE);
        when(installment.getInstallmentNumber()).thenReturn(1);
        when(installment.getPrincipalOutstanding(currency)).thenReturn(zeroMoney);
        when(installment.getInterestOutstanding(currency)).thenReturn(zeroMoney);
        when(zeroMoney.getAmount()).thenReturn(BigDecimal.ZERO);

        when(loanProduct.getCharges()).thenReturn(List.of(penaltyCharge));
        when(penaltyCharge.getChargeTimeType()).thenReturn(ChargeTimeType.OVERDUE_INSTALLMENT.getValue());
        when(penaltyCharge.isLoanCharge()).thenReturn(true);
        when(penaltyCharge.getId()).thenReturn(PENALTY_CHARGE_ID);
        when(penaltyCharge.getAmount()).thenReturn(BigDecimal.valueOf(50));

        when(configurationDomainService.retrievePenaltyWaitPeriod()).thenReturn(5L);
        when(configurationDomainService.isBackdatePenaltiesEnabled()).thenReturn(false);
        when(workingDayCalculator.getWorkingDays()).thenReturn(WORKING_DAYS);
        when(workingDayCalculator.getActiveHolidaysForOffice(eq(OFFICE_ID), any())).thenReturn(List.of());
    }

    @Test
    void appliesPenaltyOnTheFirstWorkingDayPastGrace() {
        LocalDate firstPenaltyDate = LocalDate.of(2025, 1, 13); // 5 working days from Sun 2025-01-05.
        setBusinessDate(firstPenaltyDate);
        when(workingDayCalculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(firstPenaltyDate);

        step.execute(loan);

        verify(loanChargeWritePlatformService).applyOverdueChargesForLoan(eq(LOAN_ID), argThat(list -> list.size() == 1));
    }

    @Test
    void doesNotApplyPenaltyWhileStillInWorkingDayGrace() {
        // Calendar grace would have expired (5 calendar days from due) but only 3 working days have passed.
        LocalDate stillInGrace = LocalDate.of(2025, 1, 10);
        setBusinessDate(stillInGrace);
        when(workingDayCalculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(LocalDate.of(2025, 1, 13));

        step.execute(loan);

        verify(loanChargeWritePlatformService, never()).applyOverdueChargesForLoan(anyLong(), any());
    }

    @Test
    void skipsLaterDaysWhenBackdatePenaltiesIsDisabled() {
        // backdate=false: penalty fires only on the first day. On day-2 past grace, do nothing.
        LocalDate dayAfterFirstPenalty = LocalDate.of(2025, 1, 14);
        setBusinessDate(dayAfterFirstPenalty);
        when(workingDayCalculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(LocalDate.of(2025, 1, 13));

        step.execute(loan);

        verify(loanChargeWritePlatformService, never()).applyOverdueChargesForLoan(anyLong(), any());
    }

    @Test
    void appliesOnLaterDaysWhenBackdatePenaltiesIsEnabled() {
        when(configurationDomainService.isBackdatePenaltiesEnabled()).thenReturn(true);
        LocalDate dayAfterFirstPenalty = LocalDate.of(2025, 1, 14);
        setBusinessDate(dayAfterFirstPenalty);
        when(workingDayCalculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(LocalDate.of(2025, 1, 13));

        step.execute(loan);

        verify(loanChargeWritePlatformService).applyOverdueChargesForLoan(eq(LOAN_ID), argThat(list -> list.size() == 1));
    }

    @Test
    void noopWhenLoanProductHasNoOverduePenaltyCharge() {
        setBusinessDate(LocalDate.of(2025, 1, 13));
        when(loanProduct.getCharges()).thenReturn(List.of());

        step.execute(loan);

        verify(loanChargeWritePlatformService, never()).applyOverdueChargesForLoan(anyLong(), any());
    }

    @Test
    void noopForClosedLoans() {
        setBusinessDate(LocalDate.of(2025, 1, 13));
        when(loan.isOpen()).thenReturn(false);

        step.execute(loan);

        verify(loanChargeWritePlatformService, never()).applyOverdueChargesForLoan(anyLong(), any());
    }

    private static void setBusinessDate(LocalDate date) {
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, date);
        dates.put(BusinessDateType.COB_DATE, date.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);
    }
}
