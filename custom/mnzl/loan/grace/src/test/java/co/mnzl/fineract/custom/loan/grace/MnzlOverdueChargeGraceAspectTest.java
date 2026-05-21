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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlOverdueChargeGraceAspectTest {

    private static final long LOAN_ID = 39550L;
    private static final long OFFICE_ID = 1L;
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 6, 21);
    private static final WorkingDays WORKING_DAYS = new WorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU,MO,TU,WE,TH",
            RepaymentRescheduleType.MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false);

    @Mock
    private MnzlWorkingDayCalculator calculator;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private LoanRepositoryWrapper loanRepositoryWrapper;
    @Mock
    private Loan loan;
    @Mock
    private Office office;
    @Mock
    private LoanRepaymentScheduleInstallment installment1;
    @Mock
    private LoanRepaymentScheduleInstallment installment2;
    @Mock
    private ProceedingJoinPoint joinPoint;

    private MnzlOverdueChargeGraceAspect aspect;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        aspect = new MnzlOverdueChargeGraceAspect(calculator, configurationDomainService, loanRepositoryWrapper);

        when(configurationDomainService.retrievePenaltyWaitPeriod()).thenReturn(5L);
        when(loanRepositoryWrapper.findOneWithNotFoundDetection(LOAN_ID)).thenReturn(loan);
        when(loan.getOffice()).thenReturn(office);
        when(office.getId()).thenReturn(OFFICE_ID);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment1));
        when(installment1.getInstallmentNumber()).thenReturn(1);
        when(installment1.getDueDate()).thenReturn(DUE_DATE);
        when(calculator.getWorkingDays()).thenReturn(WORKING_DAYS);
        when(calculator.getActiveHolidaysForOffice(eq(OFFICE_ID), any())).thenReturn(List.of());
        // 5 working days from 21 June (with weekends + 24-28 holiday) -> 01 July.
        when(calculator.addWorkingDays(eq(DUE_DATE), eq(5), eq(WORKING_DAYS), any())).thenReturn(LocalDate.of(2026, 7, 1));
    }

    @Test
    void withholdsPenaltyWhileStillWithinWorkingDayGrace() throws Throwable {
        setBusinessDate(LocalDate.of(2026, 6, 28)); // before 01 July
        Object[] args = { LOAN_ID, List.of(overdue(1)) };
        when(joinPoint.getArgs()).thenReturn(args);

        Object result = aspect.enforceWorkingDayGrace(joinPoint);

        assertThat(result).isNull();
        verify(joinPoint, never()).proceed(any(Object[].class));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void appliesPenaltyOnceWorkingDayGraceHasElapsed() throws Throwable {
        setBusinessDate(LocalDate.of(2026, 7, 1)); // the 5th working day
        Object[] args = { LOAN_ID, List.of(overdue(1)) };
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed(any(Object[].class))).thenReturn(null);

        aspect.enforceWorkingDayGrace(joinPoint);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(joinPoint).proceed(captor.capture());
        @SuppressWarnings("unchecked")
        List<OverdueLoanScheduleData> passed = (List<OverdueLoanScheduleData>) captor.getValue()[1];
        assertThat(passed).hasSize(1);
    }

    @Test
    void mixedSplit_onlyInstallmentsPastGraceProceed() throws Throwable {
        // installment 1 (due 21 Jun) has cleared its 5-working-day grace by 01 Jul; installment 2 (due 21 Jul) has not.
        // Only installment 1 should be passed through to applyOverdueChargesForLoan.
        LocalDate due2 = LocalDate.of(2026, 7, 21);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment1, installment2));
        when(installment2.getInstallmentNumber()).thenReturn(2);
        when(installment2.getDueDate()).thenReturn(due2);
        when(calculator.addWorkingDays(eq(due2), eq(5), eq(WORKING_DAYS), any())).thenReturn(LocalDate.of(2026, 7, 28));
        setBusinessDate(LocalDate.of(2026, 7, 1)); // installment 1 due (firstPenaltyDate 01 Jul), installment 2 still
                                                   // in grace
        Object[] args = { LOAN_ID, List.of(overdue(1), overdue(2)) };
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed(any(Object[].class))).thenReturn(null);

        aspect.enforceWorkingDayGrace(joinPoint);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(joinPoint).proceed(captor.capture());
        @SuppressWarnings("unchecked")
        List<OverdueLoanScheduleData> passed = (List<OverdueLoanScheduleData>) captor.getValue()[1];
        assertThat(passed).hasSize(1);
        assertThat(passed.get(0).getPeriodNumber()).isEqualTo(1);
    }

    @Test
    void emptyCollectionProceedsUntouched() throws Throwable {
        Object[] args = { LOAN_ID, List.of() };
        when(joinPoint.getArgs()).thenReturn(args);

        aspect.enforceWorkingDayGrace(joinPoint);

        verify(joinPoint).proceed();
        verify(calculator, never()).addWorkingDays(any(), anyInt(), any(), any());
    }

    private static OverdueLoanScheduleData overdue(int periodNumber) {
        return new OverdueLoanScheduleData(LOAN_ID, 14L, DateUtils.DEFAULT_DATE_FORMATTER.format(DUE_DATE), BigDecimal.valueOf(50),
                DateUtils.DEFAULT_DATE_FORMAT, "en", BigDecimal.valueOf(1000), BigDecimal.valueOf(100), periodNumber);
    }

    private static void setBusinessDate(LocalDate date) {
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, date);
        dates.put(BusinessDateType.COB_DATE, date.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);
    }
}
