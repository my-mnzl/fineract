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
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.service.AmountInterestPenaltiesChargeCalculator;
import org.apache.fineract.portfolio.loanaccount.service.DefaultAmountInterestPenaltiesChargeCalculator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CustomAmountInterestPenaltiesChargeCalculator extends DefaultAmountInterestPenaltiesChargeCalculator
        implements AmountInterestPenaltiesChargeCalculator {

    @Override
    public BigDecimal calculateCreationAmountPercentageAppliedTo(Loan loan, JsonCommand command,
            LoanRepaymentScheduleInstallment installment) {
        BigDecimal amountPercentageAppliedTo = super.calculateCreationAmountPercentageAppliedTo(loan, command, installment);
        if (installment != null) {
            amountPercentageAppliedTo = amountPercentageAppliedTo
                    .add(installment.getPenaltyChargesOutstanding(loan.getCurrency()).getAmount());
        } else {
            amountPercentageAppliedTo = amountPercentageAppliedTo.add(loan.getSummary().getTotalPenaltyChargesOutstanding());
        }
        return amountPercentageAppliedTo;
    }

    @Override
    public BigDecimal calculateAmountPercentageAppliedTo(Loan loan, LoanCharge loanCharge) {
        return super.calculateAmountPercentageAppliedTo(loan, loanCharge).add(loan.getSummary().getTotalPenaltyChargesOutstanding());
    }

    @Override
    public Money calculateOverdueAmountPercentageAppliedTo(Loan loan, LoanRepaymentScheduleInstallment installment) {
        return super.calculateOverdueAmountPercentageAppliedTo(loan, installment)
                .plus(installment.getPenaltyChargesOutstanding(loan.getCurrency()));
    }

    @Override
    public Money calculateInstallmentChargeAmount(Loan loan, BigDecimal percentage, LoanRepaymentScheduleInstallment installment) {
        return super.calculateInstallmentChargeAmount(loan, percentage, installment)
                .plus(LoanCharge.percentageOf(installment.getPenaltyChargesOutstanding(loan.getCurrency()).getAmount(), percentage));
    }
}
