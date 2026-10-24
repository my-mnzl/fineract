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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.holiday.domain.HolidayStatusType;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.arrears.LoanArrearsData;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Staging loan 120944: grace 3, Sun-Thu week, installment due Thu 12 Nov 2026. Upstream's event-driven refresh would
 * put it in arrears from 13 Nov; with working-day grace it is in arrears from Wed 18 Nov.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanArrearsAgingServiceTest {

    private static final long OFFICE_ID = 1L;
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 12);

    @Mock
    private WorkingDaysRepositoryWrapper workingDaysRepository;
    @Mock
    private HolidayRepositoryWrapper holidayRepository;
    @Mock
    private Loan loan;
    @Mock
    private Office office;
    @Mock
    private LoanProductRelatedDetail productRelatedDetail;
    @Mock
    private LoanRepaymentScheduleInstallment overdueInstallment;
    @Mock
    private LoanRepaymentScheduleInstallment futureInstallment;

    private MnzlLoanArrearsAgingService service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        when(workingDaysRepository.findOne())
                .thenReturn(new WorkingDays("FREQ=WEEKLY;BYDAY=SU,MO,TU,WE,TH", RepaymentRescheduleType.SAME_DAY.getValue(), false, false));
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(List.of());
        service = new MnzlLoanArrearsAgingService(mock(JdbcTemplate.class), mock(BusinessEventNotifierService.class),
                mock(DatabaseSpecificSQLGenerator.class), new MnzlWorkingDayCalculator(workingDaysRepository, holidayRepository));

        when(loan.getOffice()).thenReturn(office);
        when(office.getId()).thenReturn(OFFICE_ID);
        when(loan.getLoanProductRelatedDetail()).thenReturn(productRelatedDetail);
        when(productRelatedDetail.getGraceOnArrearsAgeing()).thenReturn(3);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(overdueInstallment, futureInstallment));
        stubInstallment(overdueInstallment, DUE_DATE, "100.00", "20.00");
        stubInstallment(futureInstallment, DUE_DATE.plusMonths(1), "100.00", "19.00");
    }

    @Test
    void notInArrearsWhileTheWorkingDayGraceRuns() {
        setBusinessDate(LocalDate.of(2026, 11, 17)); // calendar grace ended 15 Nov; working grace runs to Tue 17 Nov

        LoanArrearsData arrears = service.calculateArrearsForLoan(loan);

        assertThat(arrears.isOverdue()).isFalse();
        assertThat(arrears.getTotalOverdue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(arrears.getOverDueSince()).isNull();
    }

    @Test
    void inArrearsOnceTheWorkingDayGraceHasElapsed() {
        setBusinessDate(LocalDate.of(2026, 11, 18));

        LoanArrearsData arrears = service.calculateArrearsForLoan(loan);

        assertThat(arrears.isOverdue()).isTrue();
        assertThat(arrears.getPrincipalOverdue()).isEqualByComparingTo("100.00");
        assertThat(arrears.getInterestOverdue()).isEqualByComparingTo("20.00");
        assertThat(arrears.getTotalOverdue()).isEqualByComparingTo("120.00");
        assertThat(arrears.getOverDueSince()).isEqualTo(DUE_DATE);
    }

    @Test
    void withoutGraceItKeepsUpstreamsDayAfterDueRule() {
        when(productRelatedDetail.getGraceOnArrearsAgeing()).thenReturn(null);
        setBusinessDate(DUE_DATE.plusDays(1));

        LoanArrearsData arrears = service.calculateArrearsForLoan(loan);

        assertThat(arrears.isOverdue()).isTrue();
        assertThat(arrears.getOverDueSince()).isEqualTo(DUE_DATE);
    }

    @Test
    void purchasedReceivablesKeepUpstreamsRule() {
        when(loan.isPurchasedReceivable()).thenReturn(true);
        setBusinessDate(DUE_DATE.plusDays(1));

        LoanArrearsData arrears = service.calculateArrearsForLoan(loan);

        assertThat(arrears.isOverdue()).isTrue();
    }

    @Test
    void futureInstallmentInsideALongHolidayDoesNotFailTheRefresh() {
        // A 30-day closure after a not-yet-due installment would exhaust the calculator's search cap if it were
        // evaluated; it cannot be in arrears, so the event-driven refresh must skip it.
        when(productRelatedDetail.getGraceOnArrearsAgeing()).thenReturn(1); // search cap 1 * 7 + 14 = 21 days
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class)))
                .thenReturn(List.of(new Holiday().setName("closure").setFromDate(DUE_DATE.plusMonths(1).plusDays(1))
                        .setToDate(DUE_DATE.plusMonths(1).plusDays(30)).setStatus(HolidayStatusType.ACTIVE.getValue())
                        .setReschedulingType(0).setProcessed(false)));
        setBusinessDate(LocalDate.of(2026, 11, 18));

        LoanArrearsData arrears = service.calculateArrearsForLoan(loan);

        assertThat(arrears.getTotalOverdue()).isEqualByComparingTo("120.00");
    }

    @Test
    void paidInstallmentIsNotCounted() {
        when(overdueInstallment.isNotFullyPaidOff()).thenReturn(false);
        setBusinessDate(LocalDate.of(2026, 11, 18));

        LoanArrearsData arrears = service.calculateArrearsForLoan(loan);

        assertThat(arrears.isOverdue()).isFalse();
    }

    private static void stubInstallment(LoanRepaymentScheduleInstallment installment, LocalDate dueDate, String principal,
            String interest) {
        when(installment.getDueDate()).thenReturn(dueDate);
        when(installment.isNotFullyPaidOff()).thenReturn(true);
        Money principalOutstanding = money(principal);
        Money interestOutstanding = money(interest);
        Money zero = money("0");
        when(installment.getPrincipalOutstanding(any())).thenReturn(principalOutstanding);
        when(installment.getInterestOutstanding(any())).thenReturn(interestOutstanding);
        when(installment.getFeeChargesOutstanding(any())).thenReturn(zero);
        when(installment.getPenaltyChargesOutstanding(any())).thenReturn(zero);
    }

    private static Money money(String amount) {
        Money money = mock(Money.class);
        when(money.getAmount()).thenReturn(new BigDecimal(amount));
        return money;
    }

    private static void setBusinessDate(LocalDate date) {
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, date);
        dates.put(BusinessDateType.COB_DATE, date.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);
    }
}
