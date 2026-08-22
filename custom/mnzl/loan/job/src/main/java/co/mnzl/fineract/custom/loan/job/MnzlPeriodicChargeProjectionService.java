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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;

@Slf4j
@RequiredArgsConstructor
public class MnzlPeriodicChargeProjectionService {

    private final LoanChargeAssembler loanChargeAssembler;
    private final LoanChargeService loanChargeService;
    private final ScheduledDateGenerator scheduledDateGenerator;

    public int projectFullTermPeriodicCharges(final Loan loan) {
        if (loan == null || loan.getLoanProduct() == null || loan.getLoanProduct().getCharges() == null) {
            return 0;
        }
        final List<Charge> periodicCharges = loan.getLoanProduct().getCharges().stream().filter(Charge::isActive)
                .filter(Charge::isLoanCharge).filter(Charge::isLoanPeriodic).toList();
        final LocalDate anchor = determineAnchorDate(loan);
        final LocalDate maturity = determineMaturityDate(loan);
        if (periodicCharges.isEmpty() || anchor == null || maturity == null || DateUtils.isAfter(anchor, maturity)) {
            return 0;
        }

        int added = 0;
        final Collection<LoanCharge> existingCharges = loan.getCharges() == null ? List.of() : loan.getCharges();
        for (final Charge chargeDefinition : periodicCharges) {
            final Set<LocalDate> existingDueDates = existingCharges.stream()
                    .filter(existing -> existing.getCharge() != null && chargeDefinition.getId().equals(existing.getCharge().getId()))
                    .map(LoanCharge::getDueLocalDate).filter(date -> date != null).collect(HashSet::new, HashSet::add, HashSet::addAll);
            for (final LocalDate occurrenceDate : occurrencesBetween(chargeDefinition, anchor, maturity)) {
                if (existingDueDates.contains(occurrenceDate)) {
                    continue;
                }
                final LoanCharge loanCharge = loanChargeAssembler.createNewFromChargeDefinition(loan, chargeDefinition, occurrenceDate);
                if (BigDecimal.ZERO.compareTo(loanCharge.amount()) == 0) {
                    log.debug("Skipping zero-amount periodic charge {} for loan {} on {}", chargeDefinition.getId(), loan.getId(),
                            occurrenceDate);
                    continue;
                }
                loanChargeService.addLoanCharge(loan, loanCharge);
                existingDueDates.add(occurrenceDate);
                added++;
            }
        }
        return added;
    }

    public List<LocalDate> occurrencesBetween(final Charge chargeDefinition, final LocalDate anchor, final LocalDate upperBound) {
        if (chargeDefinition.feeInterval() == null || chargeDefinition.feeInterval() <= 0) {
            return List.of();
        }
        final PeriodFrequencyType frequencyType = PeriodFrequencyType.fromInt(chargeDefinition.feeFrequency());
        if (frequencyType == PeriodFrequencyType.INVALID || frequencyType.isDaily() || frequencyType == PeriodFrequencyType.WHOLE_TERM) {
            return List.of();
        }
        final List<LocalDate> dates = new ArrayList<>();
        LocalDate occurrenceDate = anchor;
        while (!DateUtils.isAfter(occurrenceDate, upperBound)) {
            dates.add(occurrenceDate);
            occurrenceDate = scheduledDateGenerator.getRepaymentPeriodDate(frequencyType, chargeDefinition.feeInterval(), occurrenceDate);
        }
        return dates;
    }

    private LocalDate determineAnchorDate(final Loan loan) {
        if (loan.getExpectedFirstRepaymentOnDate() != null) {
            return loan.getExpectedFirstRepaymentOnDate();
        }
        if (loan.getRepaymentScheduleInstallments() == null) {
            return null;
        }
        return loan.getRepaymentScheduleInstallments().stream().filter(installment -> !installment.isDownPayment())
                .sorted((first, second) -> first.getInstallmentNumber().compareTo(second.getInstallmentNumber()))
                .map(LoanRepaymentScheduleInstallment::getDueDate).findFirst().orElse(null);
    }

    private LocalDate determineMaturityDate(final Loan loan) {
        if (loan.getRepaymentScheduleInstallments() == null) {
            return null;
        }
        return loan.getRepaymentScheduleInstallments().stream().filter(installment -> !installment.isDownPayment())
                .map(LoanRepaymentScheduleInstallment::getDueDate).max(LocalDate::compareTo).orElse(null);
    }
}
