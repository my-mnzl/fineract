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
package co.mnzl.fineract.custom.loan.schedule;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.OutstandingAmountsDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.LoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanScheduleDTO;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;

/** Exact legal dates and amounts enter only through the purchased-receivable command boundary. */
public final class FixedReceivableScheduleGenerator implements LoanScheduleGenerator {

    public record PrincipalLeg(LocalDate dueDate, BigDecimal principal) {
    }

    public List<LoanRepaymentScheduleInstallment> construct(Loan loan, LocalDate activationDate, List<PrincipalLeg> legs,
            BigDecimal expectedFace) {
        if (!loan.isPurchasedReceivable() || legs.isEmpty()) {
            throw new IllegalArgumentException("Exact purchased schedule required");
        }
        BigDecimal total = BigDecimal.ZERO;
        LocalDate previous = activationDate;
        java.util.ArrayList<LoanRepaymentScheduleInstallment> result = new java.util.ArrayList<>();
        for (PrincipalLeg leg : legs) {
            if (leg.dueDate().isBefore(previous) || !leg.dueDate().isAfter(activationDate) || leg.principal().signum() <= 0
                    || leg.principal().stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Invalid exact principal leg");
            }
            result.add(new LoanRepaymentScheduleInstallment(loan, result.size() + 1, previous, leg.dueDate(), leg.principal(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO));
            total = total.add(leg.principal());
            previous = leg.dueDate();
        }
        if (total.compareTo(expectedFace) != 0) {
            throw new IllegalArgumentException("Principal legs do not equal contractual face");
        }
        return List.copyOf(result);
    }

    private IllegalStateException commandRequired() {
        return new IllegalStateException(
                "MNZL_FIXED_RECEIVABLE requires an exact receivables command; ordinary schedule changes are disabled");
    }

    @Override
    public LoanScheduleModel generate(MathContext mc, LoanApplicationTerms terms, Set<LoanCharge> charges, HolidayDetailDTO holidays) {
        throw commandRequired();
    }

    @Override
    public LoanScheduleDTO rescheduleNextInstallments(MathContext mc, LoanApplicationTerms terms, Loan loan, HolidayDetailDTO holidays,
            LoanRepaymentScheduleTransactionProcessor processor, LocalDate from) {
        throw commandRequired();
    }

    @Override
    public LoanScheduleDTO rescheduleNextInstallments(MathContext mc, LoanApplicationTerms terms, Loan loan, HolidayDetailDTO holidays,
            LoanRepaymentScheduleTransactionProcessor processor, LocalDate from, LocalDate until) {
        throw commandRequired();
    }

    @Override
    public OutstandingAmountsDTO calculatePrepaymentAmount(MonetaryCurrency currency, LocalDate date, LoanApplicationTerms terms,
            MathContext mc, Loan loan, HolidayDetailDTO holidays, LoanRepaymentScheduleTransactionProcessor processor) {
        throw commandRequired();
    }

    @Override
    public Money getPeriodInterestTillDate(LoanRepaymentScheduleInstallment installment, LocalDate date) {
        return Money.zero(installment.getLoan().getCurrency());
    }
}
