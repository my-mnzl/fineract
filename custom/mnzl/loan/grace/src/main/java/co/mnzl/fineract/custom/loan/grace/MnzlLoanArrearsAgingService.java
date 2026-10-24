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
import java.util.List;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.arrears.LoanArrearsData;
import org.apache.fineract.portfolio.loanaccount.service.LoanArrearsAgingServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Counts {@code graceOnArrearsAgeing} in working days on upstream's event-driven arrears refresh.
 *
 * <p>
 * After a repayment, charge, waiver and similar events, upstream rebuilds the loan's {@code m_loan_arrears_aging} row
 * from {@link #calculateArrearsForLoan}, which treats every installment past its due date as in arrears and ignores the
 * grace altogether, while the COB step and the arrears job apply it in calendar days. Overriding it here (upstream's
 * event listeners reach it through ordinary virtual dispatch) makes all three paths agree with
 * {@link MnzlLoanArrearsAgeingUpdateHandler}.
 * </p>
 *
 * <p>
 * Registered under upstream's bean name in {@link MnzlGraceConfiguration} so it replaces that instance instead of
 * sitting beside it: two instances would both register the event listeners and race to write the same row.
 * </p>
 */
public class MnzlLoanArrearsAgingService extends LoanArrearsAgingServiceImpl {

    private final MnzlWorkingDayCalculator workingDayCalculator;

    public MnzlLoanArrearsAgingService(JdbcTemplate jdbcTemplate, BusinessEventNotifierService businessEventNotifierService,
            DatabaseSpecificSQLGenerator sqlGenerator, MnzlWorkingDayCalculator workingDayCalculator) {
        super(jdbcTemplate, businessEventNotifierService, sqlGenerator);
        this.workingDayCalculator = workingDayCalculator;
    }

    @Override
    public LoanArrearsData calculateArrearsForLoan(Loan loan) {
        final Integer graceDays = loan.getLoanProductRelatedDetail().getGraceOnArrearsAgeing();
        if (loan.isPurchasedReceivable() || graceDays == null || graceDays <= 0) {
            return super.calculateArrearsForLoan(loan);
        }

        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        final Long officeId = loan.getOffice() != null ? loan.getOffice().getId() : null;
        // Hoist working-days config + holiday list once per loan; anchor the holiday lookup at the earliest due date.
        final WorkingDays workingDays = workingDayCalculator.getWorkingDays();
        final LocalDate earliestDueDate = installments.stream().map(LoanRepaymentScheduleInstallment::getDueDate).min(LocalDate::compareTo)
                .orElse(businessDate);
        final List<Holiday> holidays = workingDayCalculator.getActiveHolidaysForOffice(officeId, earliestDueDate);
        final MonetaryCurrency currency = loan.getCurrency();

        // Same accumulation as upstream, with the arrears cut-off moved from the due date to the end of the grace.
        BigDecimal principalOverdue = BigDecimal.ZERO;
        BigDecimal interestOverdue = BigDecimal.ZERO;
        BigDecimal feeOverdue = BigDecimal.ZERO;
        BigDecimal penaltyOverdue = BigDecimal.ZERO;
        LocalDate overDueSince = businessDate;
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (workingDayCalculator.isPastWorkingDayGrace(installment.getDueDate(), graceDays, businessDate, workingDays, holidays)) {
                principalOverdue = principalOverdue.add(installment.getPrincipalOutstanding(currency).getAmount());
                interestOverdue = interestOverdue.add(installment.getInterestOutstanding(currency).getAmount());
                feeOverdue = feeOverdue.add(installment.getFeeChargesOutstanding(currency).getAmount());
                penaltyOverdue = penaltyOverdue.add(installment.getPenaltyChargesOutstanding(currency).getAmount());
                if (installment.isNotFullyPaidOff() && DateUtils.isAfter(overDueSince, installment.getDueDate())) {
                    overDueSince = installment.getDueDate();
                }
            }
        }
        final BigDecimal totalOverdue = principalOverdue.add(interestOverdue).add(feeOverdue).add(penaltyOverdue);
        final boolean isOverdue = totalOverdue.compareTo(BigDecimal.ZERO) > 0;

        LoanArrearsData result = new LoanArrearsData();
        result.setPrincipalOverdue(principalOverdue);
        result.setInterestOverdue(interestOverdue);
        result.setFeeOverdue(feeOverdue);
        result.setPenaltyOverdue(penaltyOverdue);
        result.setTotalOverdue(totalOverdue);
        result.setOverDueSince(isOverdue ? overDueSince : null);
        result.setOverdue(isOverdue);
        return result;
    }
}
