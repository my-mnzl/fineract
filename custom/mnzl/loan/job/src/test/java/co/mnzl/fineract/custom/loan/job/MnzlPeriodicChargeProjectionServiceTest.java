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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
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
class MnzlPeriodicChargeProjectionServiceTest {

    @Mock
    private LoanChargeAssembler loanChargeAssembler;

    @Mock
    private LoanChargeService loanChargeService;

    @Mock
    private ScheduledDateGenerator scheduledDateGenerator;

    @Mock
    private Loan loan;

    @Mock
    private LoanProduct loanProduct;

    @Mock
    private Charge chargeDefinition;

    private MnzlPeriodicChargeProjectionService projectionService;

    @BeforeEach
    void setUp() {
        projectionService = new MnzlPeriodicChargeProjectionService(loanChargeAssembler, loanChargeService, scheduledDateGenerator);
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loan.getCharges()).thenReturn(new ArrayList<>());
    }

    @Test
    void projectsAllOccurrencesUpToMaturityFromExpectedFirstRepaymentDate() {
        final LocalDate anchor = LocalDate.of(2024, 2, 15);
        final LocalDate second = LocalDate.of(2025, 2, 15);
        final LocalDate maturity = LocalDate.of(2026, 1, 15);
        LoanRepaymentScheduleInstallment lastInstallment = installment(2, maturity, false);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(lastInstallment));
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(anchor);
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition));
        whenChargeIsActivePeriodicYearly(1L, 1);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.YEARS, 1, anchor)).thenReturn(second);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.YEARS, 1, second)).thenReturn(second.plusYears(1));
        LoanCharge first = newCharge(anchor);
        LoanCharge secondCharge = newCharge(second);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, anchor)).thenReturn(first);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, second)).thenReturn(secondCharge);

        projectionService.projectFullTermPeriodicCharges(loan);

        verify(loanChargeService, times(1)).addLoanCharge(loan, first);
        verify(loanChargeService, times(1)).addLoanCharge(loan, secondCharge);
        verify(loanChargeAssembler, never()).createNewFromChargeDefinition(loan, chargeDefinition, second.plusYears(1));
    }

    @Test
    void usesRepaymentScheduleFallbackAndSkipsExistingDueDates() {
        final LocalDate anchor = LocalDate.of(2024, 2, 15);
        final LocalDate second = LocalDate.of(2024, 5, 15);
        final LocalDate third = LocalDate.of(2024, 8, 15);
        LoanRepaymentScheduleInstallment downPayment = installment(1, LocalDate.of(2024, 1, 15), true);
        LoanRepaymentScheduleInstallment first = installment(2, anchor, false);
        LoanRepaymentScheduleInstallment last = installment(3, third, false);
        LoanCharge existing = existingCharge(anchor);
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(null);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(first, last, downPayment));
        when(loan.getCharges()).thenReturn(List.of(existing));
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition));
        whenChargeIsActivePeriodicMonthly(1L, 3);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, anchor)).thenReturn(second);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, second)).thenReturn(third);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, third)).thenReturn(third.plusMonths(3));
        LoanCharge secondCharge = newCharge(second);
        LoanCharge thirdCharge = newCharge(third);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, second)).thenReturn(secondCharge);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, third)).thenReturn(thirdCharge);

        projectionService.projectFullTermPeriodicCharges(loan);

        verify(loanChargeAssembler, never()).createNewFromChargeDefinition(loan, chargeDefinition, anchor);
        verify(loanChargeService, times(1)).addLoanCharge(loan, secondCharge);
        verify(loanChargeService, times(1)).addLoanCharge(loan, thirdCharge);
    }

    @Test
    void noOpWhenProductHasNoPeriodicCharges() {
        when(loanProduct.getCharges()).thenReturn(List.of());

        projectionService.projectFullTermPeriodicCharges(loan);

        verifyNoInteractions(loanChargeAssembler);
        verify(loanChargeService, never()).addLoanCharge(any(Loan.class), any(LoanCharge.class));
    }

    private void whenChargeIsActivePeriodicYearly(final long id, final int interval) {
        when(chargeDefinition.isActive()).thenReturn(true);
        when(chargeDefinition.isLoanCharge()).thenReturn(true);
        when(chargeDefinition.isLoanPeriodic()).thenReturn(true);
        when(chargeDefinition.getId()).thenReturn(id);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.YEARS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(interval);
    }

    private void whenChargeIsActivePeriodicMonthly(final long id, final int interval) {
        when(chargeDefinition.isActive()).thenReturn(true);
        when(chargeDefinition.isLoanCharge()).thenReturn(true);
        when(chargeDefinition.isLoanPeriodic()).thenReturn(true);
        when(chargeDefinition.getId()).thenReturn(id);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(interval);
    }

    private LoanCharge newCharge(final LocalDate dueDate) {
        LoanCharge created = org.mockito.Mockito.mock(LoanCharge.class);
        when(created.amount()).thenReturn(BigDecimal.TEN);
        when(created.getDueLocalDate()).thenReturn(dueDate);
        return created;
    }

    private LoanCharge existingCharge(final LocalDate dueDate) {
        LoanCharge existing = org.mockito.Mockito.mock(LoanCharge.class);
        when(existing.getCharge()).thenReturn(chargeDefinition);
        when(existing.getDueLocalDate()).thenReturn(dueDate);
        return existing;
    }

    private LoanRepaymentScheduleInstallment installment(final int number, final LocalDate dueDate, final boolean downPayment) {
        LoanRepaymentScheduleInstallment installment = org.mockito.Mockito.mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getInstallmentNumber()).thenReturn(number);
        when(installment.getDueDate()).thenReturn(dueDate);
        when(installment.isDownPayment()).thenReturn(downPayment);
        return installment;
    }

    // ---------------- C.8 expansions ----------------

    @Test
    void occurrencesBetween_daily_returnsEmptyBecauseDailyIsRejected() {
        // Production explicitly rejects DAILY frequency in occurrencesBetween (treats it like WHOLE_TERM/INVALID).
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.DAYS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(1);

        final LocalDate anchor = LocalDate.of(2026, 1, 1);
        final LocalDate maturity = anchor.plusDays(5);

        final List<LocalDate> dates = projectionService.occurrencesBetween(chargeDefinition, anchor, maturity);

        assertThat(dates).isEmpty();
    }

    @Test
    void occurrencesBetween_weekly_yieldsExpectedCount() {
        final LocalDate anchor = LocalDate.of(2026, 1, 5); // Monday
        final LocalDate maturity = anchor.plusWeeks(4);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.WEEKS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(1);
        // Stub each step (production walks anchor -> anchor+4w inclusive while !isAfter(maturity)).
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.WEEKS, 1, anchor)).thenReturn(anchor.plusWeeks(1));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.WEEKS, 1, anchor.plusWeeks(1)))
                .thenReturn(anchor.plusWeeks(2));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.WEEKS, 1, anchor.plusWeeks(2)))
                .thenReturn(anchor.plusWeeks(3));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.WEEKS, 1, anchor.plusWeeks(3)))
                .thenReturn(anchor.plusWeeks(4));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.WEEKS, 1, anchor.plusWeeks(4)))
                .thenReturn(anchor.plusWeeks(5));

        final List<LocalDate> dates = projectionService.occurrencesBetween(chargeDefinition, anchor, maturity);

        // anchor + each week up to and including maturity (anchor + 4w) -> 5 dates.
        assertThat(dates).hasSize(5);
        assertThat(dates).containsExactly(anchor, anchor.plusWeeks(1), anchor.plusWeeks(2), anchor.plusWeeks(3), anchor.plusWeeks(4));
    }

    @Test
    void occurrencesBetween_monthly_yieldsExpectedCount() {
        final LocalDate anchor = LocalDate.of(2026, 1, 1);
        final LocalDate maturity = LocalDate.of(2026, 12, 1);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(1);
        for (int i = 0; i <= 12; i++) {
            when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, anchor.plusMonths(i)))
                    .thenReturn(anchor.plusMonths(i + 1));
        }

        final List<LocalDate> dates = projectionService.occurrencesBetween(chargeDefinition, anchor, maturity);

        // Jan 1 .. Dec 1 inclusive = 12 occurrences.
        assertThat(dates).hasSize(12);
        assertThat(dates.get(0)).isEqualTo(anchor);
        assertThat(dates.get(dates.size() - 1)).isEqualTo(maturity);
    }

    @Test
    void occurrencesBetween_quarterly_yieldsExpectedCount() {
        final LocalDate anchor = LocalDate.of(2026, 1, 1);
        final LocalDate maturity = LocalDate.of(2026, 12, 31);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(3);
        for (int i = 0; i <= 4; i++) {
            when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, anchor.plusMonths(i * 3L)))
                    .thenReturn(anchor.plusMonths((i + 1) * 3L));
        }

        final List<LocalDate> dates = projectionService.occurrencesBetween(chargeDefinition, anchor, maturity);

        // Jan 1, Apr 1, Jul 1, Oct 1 -> 4 occurrences (Jan 1 next year is past maturity).
        assertThat(dates).hasSize(4);
        assertThat(dates).containsExactly(anchor, anchor.plusMonths(3), anchor.plusMonths(6), anchor.plusMonths(9));
    }

    @Test
    void occurrencesBetween_anchorAfterMaturity_empty() {
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(1);

        final LocalDate anchor = LocalDate.of(2026, 6, 1);
        final LocalDate maturity = LocalDate.of(2026, 1, 1);

        final List<LocalDate> dates = projectionService.occurrencesBetween(chargeDefinition, anchor, maturity);

        assertThat(dates).isEmpty();
    }

    @Test
    void occurrencesBetween_anchorEqualsMaturity_singleOccurrence() {
        final LocalDate anchor = LocalDate.of(2026, 1, 15);
        when(chargeDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(chargeDefinition.feeInterval()).thenReturn(1);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, anchor)).thenReturn(anchor.plusMonths(1));

        final List<LocalDate> dates = projectionService.occurrencesBetween(chargeDefinition, anchor, anchor);

        assertThat(dates).containsExactly(anchor);
    }

    @Test
    void projectFullTermPeriodicCharges_multiCharge_yieldsAllOccurrences() {
        final LocalDate anchor = LocalDate.of(2026, 1, 15);
        final LocalDate maturity = LocalDate.of(2026, 4, 15);
        final Charge secondDefinition = org.mockito.Mockito.mock(Charge.class);
        when(secondDefinition.isActive()).thenReturn(true);
        when(secondDefinition.isLoanCharge()).thenReturn(true);
        when(secondDefinition.isLoanPeriodic()).thenReturn(true);
        when(secondDefinition.getId()).thenReturn(2L);
        when(secondDefinition.feeFrequency()).thenReturn(PeriodFrequencyType.MONTHS.getValue());
        when(secondDefinition.feeInterval()).thenReturn(3);
        whenChargeIsActivePeriodicMonthly(1L, 1);

        // Build the mocks BEFORE calling thenReturn so we don't tear up Mockito's stubbing context.
        final LoanRepaymentScheduleInstallment first = installment(1, anchor, false);
        final LoanRepaymentScheduleInstallment last = installment(2, maturity, false);
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(anchor);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(first, last));
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition, secondDefinition));

        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, anchor)).thenReturn(anchor.plusMonths(1));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, anchor.plusMonths(1)))
                .thenReturn(anchor.plusMonths(2));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, anchor.plusMonths(2))).thenReturn(maturity);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, maturity)).thenReturn(maturity.plusMonths(1));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, anchor)).thenReturn(maturity);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 3, maturity)).thenReturn(maturity.plusMonths(3));

        when(loanChargeAssembler.createNewFromChargeDefinition(eqLoan(), eqCharge(chargeDefinition), any(LocalDate.class)))
                .thenAnswer(inv -> newCharge(inv.getArgument(2)));
        when(loanChargeAssembler.createNewFromChargeDefinition(eqLoan(), eqCharge(secondDefinition), any(LocalDate.class)))
                .thenAnswer(inv -> newCharge(inv.getArgument(2)));

        final int added = projectionService.projectFullTermPeriodicCharges(loan);

        // chargeDefinition (monthly): 4 occurrences (Jan 15, Feb 15, Mar 15, Apr 15). secondDefinition (quarterly): 2
        // (Jan 15, Apr 15).
        assertThat(added).isEqualTo(6);
        verify(loanChargeService, times(6)).addLoanCharge(any(Loan.class), any(LoanCharge.class));
    }

    @Test
    void projectFullTermPeriodicCharges_anchorFromExpectedFirstRepaymentDate() {
        // determineAnchorDate prefers loan.getExpectedFirstRepaymentOnDate() when set.
        final LocalDate explicitAnchor = LocalDate.of(2026, 6, 1);
        final LocalDate maturity = LocalDate.of(2026, 12, 1);
        final LoanRepaymentScheduleInstallment firstInst = installment(1, explicitAnchor, false);
        final LoanRepaymentScheduleInstallment lastInst = installment(2, maturity, false);
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(explicitAnchor);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(firstInst, lastInst));
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition));
        whenChargeIsActivePeriodicMonthly(1L, 6);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 6, explicitAnchor)).thenReturn(maturity);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 6, maturity)).thenReturn(maturity.plusMonths(6));
        // Build LoanCharge mocks BEFORE stubbing to keep Mockito's stubbing chain unbroken.
        final LoanCharge anchorCharge = newCharge(explicitAnchor);
        final LoanCharge maturityCharge = newCharge(maturity);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, explicitAnchor)).thenReturn(anchorCharge);
        when(loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, maturity)).thenReturn(maturityCharge);

        final int added = projectionService.projectFullTermPeriodicCharges(loan);

        assertThat(added).isEqualTo(2);
        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, explicitAnchor);
        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, maturity);
    }

    @Test
    void projectFullTermPeriodicCharges_anchorFromInstallmentsWhenNoExpectedFirstRepaymentDate() {
        // When expectedFirstRepaymentOnDate is null, anchor comes from the lowest-numbered non-downpayment installment.
        final LocalDate firstInstallment = LocalDate.of(2026, 2, 1);
        final LocalDate maturity = LocalDate.of(2026, 5, 1);
        final LoanRepaymentScheduleInstallment inst2 = installment(2, LocalDate.of(2026, 3, 1), false);
        final LoanRepaymentScheduleInstallment inst1 = installment(1, firstInstallment, false);
        final LoanRepaymentScheduleInstallment inst3 = installment(3, maturity, false);
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(null);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(inst2, inst1, inst3));
        when(loanProduct.getCharges()).thenReturn(List.of(chargeDefinition));
        whenChargeIsActivePeriodicMonthly(1L, 1);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, firstInstallment))
                .thenReturn(firstInstallment.plusMonths(1));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, firstInstallment.plusMonths(1)))
                .thenReturn(firstInstallment.plusMonths(2));
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, firstInstallment.plusMonths(2)))
                .thenReturn(maturity);
        when(scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.MONTHS, 1, maturity)).thenReturn(maturity.plusMonths(1));
        when(loanChargeAssembler.createNewFromChargeDefinition(eqLoan(), eqCharge(chargeDefinition), any(LocalDate.class)))
                .thenAnswer(inv -> newCharge(inv.getArgument(2)));

        final int added = projectionService.projectFullTermPeriodicCharges(loan);

        // Feb 1, Mar 1, Apr 1, May 1 -> 4 occurrences from anchor=Feb 1 (the lowest installment number).
        assertThat(added).isEqualTo(4);
        // Anchor must be the lowest-numbered installment date, NOT the earliest by date if numbers differ.
        verify(loanChargeAssembler, times(1)).createNewFromChargeDefinition(loan, chargeDefinition, firstInstallment);
    }

    private Loan eqLoan() {
        return org.mockito.ArgumentMatchers.eq(loan);
    }

    private Charge eqCharge(final Charge c) {
        return org.mockito.ArgumentMatchers.eq(c);
    }
}
