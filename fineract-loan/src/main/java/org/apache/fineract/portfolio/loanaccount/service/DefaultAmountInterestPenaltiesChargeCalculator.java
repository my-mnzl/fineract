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
package org.apache.fineract.portfolio.loanaccount.service;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;

public class DefaultAmountInterestPenaltiesChargeCalculator implements AmountInterestPenaltiesChargeCalculator {

    @Override
    public BigDecimal calculateCreationAmountPercentageAppliedTo(Loan loan, JsonCommand command,
            LoanRepaymentScheduleInstallment installment) {
        if (command.hasParameter("principal") && command.hasParameter("interest")) {
            return command.bigDecimalValueOfParameterNamed("principal").add(command.bigDecimalValueOfParameterNamed("interest"));
        } else if (installment != null) {
            return installment.getPrincipalOutstanding(loan.getCurrency()).getAmount()
                    .add(installment.getInterestOutstanding(loan.getCurrency()).getAmount());
        } else {
            return loan.getPrincipal().getAmount().add(loan.getTotalInterest());
        }
    }

    @Override
    public BigDecimal calculateAmountPercentageAppliedTo(Loan loan, LoanCharge loanCharge) {
        final BigDecimal totalInterestCharged = loan.getTotalInterest();
        if (loan.isMultiDisburmentLoan() && loanCharge.isDisbursementCharge()) {
            return getTotalAllTrancheDisbursementAmount(loan).getAmount().add(totalInterestCharged);
        } else {
            return loan.getPrincipal().getAmount().add(totalInterestCharged);
        }
    }

    @Override
    public Money calculateOverdueAmountPercentageAppliedTo(Loan loan, LoanRepaymentScheduleInstallment installment) {
        return installment.getPrincipalOutstanding(loan.getCurrency()).plus(installment.getInterestOutstanding(loan.getCurrency()));
    }

    @Override
    public Money calculateInstallmentChargeAmount(Loan loan, BigDecimal percentage, LoanRepaymentScheduleInstallment installment) {
        Money percentOf = installment.getPrincipal(loan.getCurrency()).plus(installment.getInterestCharged(loan.getCurrency()));
        return Money.zero(loan.getCurrency()).plus(LoanCharge.percentageOf(percentOf.getAmount(), percentage));
    }

    private Money getTotalAllTrancheDisbursementAmount(final Loan loan) {
        Money amount = Money.zero(loan.getCurrency());
        if (loan.isMultiDisburmentLoan()) {
            for (final var loanDisbursementDetail : loan.getDisbursementDetails()) {
                amount = amount.plus(loanDisbursementDetail.principal());
            }
        }
        return amount;
    }
}
