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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;

/**
 * Replaces the upstream "apply charge to overdue loans" step so that the {@code penaltyWaitPeriod} configuration is
 * counted in working days. Same {@link #getEnumStyledName()} as upstream — {@code COBBusinessStepServiceImpl} prefers
 * {@code @Primary} beans when multiple implementations share an enum name. Wired in {@link MnzlGraceConfiguration}.
 */
@RequiredArgsConstructor
public class MnzlApplyChargeToOverdueLoansBusinessStep implements LoanCOBBusinessStep {

    private final ConfigurationDomainService configurationDomainService;
    private final LoanChargeWritePlatformService loanChargeWritePlatformService;
    private final MnzlWorkingDayCalculator workingDayCalculator;

    @Override
    public Loan execute(Loan loan) {
        if (!loan.isOpen()) {
            return loan;
        }
        Optional<Charge> optPenaltyCharge = loan.getLoanProduct().getCharges().stream()
                .filter(c -> ChargeTimeType.OVERDUE_INSTALLMENT.getValue().equals(c.getChargeTimeType()) && c.isLoanCharge()).findFirst();
        if (optPenaltyCharge.isEmpty()) {
            return loan;
        }
        final Charge penaltyCharge = optPenaltyCharge.get();
        final Long penaltyWaitPeriod = configurationDomainService.retrievePenaltyWaitPeriod();
        final boolean backdatePenalties = configurationDomainService.isBackdatePenaltiesEnabled();
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        final Long officeId = loan.getOffice() != null ? loan.getOffice().getId() : null;
        // Hoist working-days config + holiday list once per loan; the per-installment loop reuses them.
        final WorkingDays workingDays = workingDayCalculator.getWorkingDays();
        // Holidays may sit either before or after the business date depending on which installment is in scope, so
        // anchor the lookup at the earliest possible due date in the schedule.
        final LocalDate earliestDueDate = loan.getRepaymentScheduleInstallments().stream().map(LoanRepaymentScheduleInstallment::getDueDate)
                .min(LocalDate::compareTo).orElse(businessDate);
        final List<Holiday> holidays = workingDayCalculator.getActiveHolidaysForOffice(officeId, earliestDueDate);
        final int penaltyWaitPeriodDays = penaltyWaitPeriod == null ? 0 : Math.toIntExact(penaltyWaitPeriod);

        List<OverdueLoanScheduleData> overdueList = new ArrayList<>();
        for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (installment.isObligationsMet() || installment.isRecalculatedInterestComponent()) {
                continue;
            }
            // First calendar date on which the penalty is due, after `penaltyWaitPeriod` working days of grace.
            LocalDate firstPenaltyDate = workingDayCalculator.addWorkingDays(installment.getDueDate(), penaltyWaitPeriodDays, workingDays,
                    holidays);
            boolean isPenaltyDue = !businessDate.isBefore(firstPenaltyDate);
            boolean isFirstPenaltyDay = businessDate.equals(firstPenaltyDate);
            if (!isPenaltyDue) {
                continue;
            }
            if (!backdatePenalties && !isFirstPenaltyDay) {
                continue;
            }
            overdueList.add(new OverdueLoanScheduleData(loan.getId(), penaltyCharge.getId(),
                    DateUtils.DEFAULT_DATE_FORMATTER.format(installment.getDueDate()), penaltyCharge.getAmount(),
                    DateUtils.DEFAULT_DATE_FORMAT, Locale.ENGLISH.toLanguageTag(),
                    installment.getPrincipalOutstanding(loan.getCurrency()).getAmount(),
                    installment.getInterestOutstanding(loan.getCurrency()).getAmount(), installment.getInstallmentNumber()));
        }
        if (!overdueList.isEmpty()) {
            loanChargeWritePlatformService.applyOverdueChargesForLoan(loan.getId(), overdueList);
        }
        return loan;
    }

    @Override
    public String getEnumStyledName() {
        return "APPLY_CHARGE_TO_OVERDUE_LOANS";
    }

    @Override
    public String getHumanReadableName() {
        return "Apply charge to overdue loans";
    }
}
