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
package co.mnzl.fineract.custom.charge.calculation;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculator;
import org.springframework.stereotype.Component;

@Component
public class CustomAmountInterestPenaltiesChargeCalculator implements ChargeAmountCalculator {

    @Override
    public Integer calculationType() {
        return ChargeCalculationType.CUSTOM.getValue();
    }

    @Override
    public BigDecimal calculateCreationAmountPercentageAppliedTo(final Loan loan, final JsonCommand command,
            final LoanRepaymentScheduleInstallment installment) {
        BigDecimal amountPercentageAppliedTo;
        if (command.hasParameter("principal") && command.hasParameter("interest")) {
            amountPercentageAppliedTo = command.bigDecimalValueOfParameterNamed("principal")
                    .add(command.bigDecimalValueOfParameterNamed("interest"));
        } else if (installment != null) {
            amountPercentageAppliedTo = installment.getPrincipalOutstanding(loan.getCurrency()).getAmount()
                    .add(installment.getInterestOutstanding(loan.getCurrency()).getAmount());
        } else {
            amountPercentageAppliedTo = loan.getPrincipal().getAmount().add(loan.getTotalInterest());
        }
        if (installment != null) {
            return amountPercentageAppliedTo.add(installment.getPenaltyChargesOutstanding(loan.getCurrency()).getAmount());
        }
        return amountPercentageAppliedTo.add(loan.getSummary().getTotalPenaltyChargesOutstanding());
    }

    @Override
    public BigDecimal calculateAmountPercentageAppliedTo(final Loan loan, final LoanCharge loanCharge) {
        BigDecimal amountPercentageAppliedTo = loan.getPrincipal().getAmount().add(loan.getTotalInterest());
        if (loan.isMultiDisburmentLoan() && loanCharge.isDisbursementCharge()) {
            amountPercentageAppliedTo = loan.getDisbursementDetails().stream().map(detail -> detail.getPrincipal())
                    .reduce(BigDecimal.ZERO, BigDecimal::add).add(loan.getTotalInterest());
        }
        return amountPercentageAppliedTo.add(loan.getSummary().getTotalPenaltyChargesOutstanding());
    }

    @Override
    public Money calculateOverdueAmountPercentageAppliedTo(final Loan loan, final LoanRepaymentScheduleInstallment installment) {
        return installment.getPrincipalOutstanding(loan.getCurrency()).plus(installment.getInterestOutstanding(loan.getCurrency()))
                .plus(installment.getPenaltyChargesOutstanding(loan.getCurrency()));
    }

    @Override
    public Money calculateInstallmentChargeAmount(final Loan loan, final BigDecimal percentage,
            final LoanRepaymentScheduleInstallment installment) {
        final Money percentOf = installment.getPrincipal(loan.getCurrency()).plus(installment.getInterestCharged(loan.getCurrency()))
                .plus(installment.getPenaltyChargesOutstanding(loan.getCurrency()));
        return Money.zero(loan.getCurrency()).plus(LoanCharge.percentageOf(percentOf.getAmount(), percentage));
    }
}
