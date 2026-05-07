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
}
