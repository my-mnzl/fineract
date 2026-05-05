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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.repayment.LoanRepaymentOverdueBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;

/**
 * Replaces the upstream "check loan repayment overdue" step so that the configured "days after due date to raise event"
 * count is interpreted as working days (skipping weekends and active holidays for the loan's office). Same
 * {@link #getEnumStyledName()} as upstream — {@code COBBusinessStepServiceImpl} prefers {@code @Primary} beans when
 * multiple implementations share an enum name. Wired in {@link MnzlGraceConfiguration}.
 */
@Slf4j
@RequiredArgsConstructor
public class MnzlCheckLoanRepaymentOverdueBusinessStep implements LoanCOBBusinessStep {

    private final ConfigurationDomainService configurationDomainService;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final MnzlWorkingDayCalculator workingDayCalculator;

    @Override
    public Loan execute(Loan loan) {
        List<LoanStatus> nonDisbursedStatuses = Arrays.asList(LoanStatus.INVALID, LoanStatus.SUBMITTED_AND_PENDING_APPROVAL,
                LoanStatus.APPROVED);
        if (nonDisbursedStatuses.contains(loan.getStatus()) || loan.getSummary().getTotalOutstanding().compareTo(BigDecimal.ZERO) <= 0) {
            return loan;
        }
        log.debug("start processing loan repayment overdue business step (working-day grace) for loan with Id [{}]", loan.getId());
        Long numberOfDaysAfterDueDateToRaiseEvent = configurationDomainService.retrieveRepaymentOverdueDays();
        if (loan.getLoanProduct().getOverDueDaysForRepaymentEvent() != null
                && loan.getLoanProduct().getOverDueDaysForRepaymentEvent() > 0) {
            numberOfDaysAfterDueDateToRaiseEvent = loan.getLoanProduct().getOverDueDaysForRepaymentEvent().longValue();
        }
        final LocalDate currentDate = DateUtils.getBusinessLocalDate();
        final Long officeId = loan.getOffice() != null ? loan.getOffice().getId() : null;
        // Hoist working-days config + holiday list once per loan; the per-installment loop reuses them.
        final WorkingDays workingDays = workingDayCalculator.getWorkingDays();
        final List<Holiday> holidays = workingDayCalculator.getActiveHolidaysForOffice(officeId, currentDate);
        for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (installment.isObligationsMet()) {
                continue;
            }
            LocalDate triggerDate = computeTriggerDate(installment.getDueDate(), numberOfDaysAfterDueDateToRaiseEvent, workingDays,
                    holidays);
            if (triggerDate.equals(currentDate) && installment.getTotalOutstanding(loan.getCurrency()).isGreaterThanZero()) {
                businessEventNotifierService.notifyPostBusinessEvent(new LoanRepaymentOverdueBusinessEvent(installment));
                break;
            }
        }
        log.debug("end processing loan repayment overdue business step (working-day grace) for loan with Id [{}]", loan.getId());
        return loan;
    }

    private LocalDate computeTriggerDate(LocalDate dueDate, Long offsetDays, WorkingDays workingDays, List<Holiday> holidays) {
        // Negative offsets ("raise event N days BEFORE due date") aren't expressible as a working-day shift; preserve
        // upstream calendar behaviour for that case so we don't silently change semantics.
        if (offsetDays == null || offsetDays < 0) {
            return dueDate.plusDays(offsetDays == null ? 0L : offsetDays);
        }
        return workingDayCalculator.addWorkingDays(dueDate, Math.toIntExact(offsetDays), workingDays, holidays);
    }

    @Override
    public String getEnumStyledName() {
        return "CHECK_LOAN_REPAYMENT_OVERDUE";
    }

    @Override
    public String getHumanReadableName() {
        return "Check loan repayment overdue";
    }
}
