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
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;

public class PercentOfAmountChargeAmountCalculator implements ChargeAmountCalculator {

    @Override
    public Integer calculationType() {
        return ChargeCalculationType.PERCENT_OF_AMOUNT.getValue();
    }

    @Override
    public BigDecimal calculateCreationAmountPercentageAppliedTo(Loan loan, JsonCommand command,
            LoanRepaymentScheduleInstallment installment) {
        if (command.hasParameter("principal")) {
            return command.bigDecimalValueOfParameterNamed("principal");
        }
        return loan.getPrincipal().getAmount();
    }

    @Override
    public BigDecimal calculateAmountPercentageAppliedTo(Loan loan, LoanCharge loanCharge) {
        BigDecimal amount = BigDecimal.ZERO;
        if (loan.isMultiDisburmentLoan() && loanCharge.getCharge().getChargeTimeType().equals(ChargeTimeType.DISBURSEMENT.getValue())) {
            amount = loan.getApprovedPrincipal();
        } else if ((loanCharge.isSpecifiedDueDate() || loanCharge.isPeriodic()) && loan.isMultiDisburmentLoan()) {
            for (final LoanDisbursementDetails loanDisbursementDetails : loan.getDisbursementDetails()) {
                if (!DateUtils.isAfter(loanDisbursementDetails.expectedDisbursementDate(), loanCharge.getDueDate())) {
                    amount = amount.add(loanDisbursementDetails.principal());
                }
            }
        } else {
            amount = loan.getPrincipal().getAmount();
        }
        return amount;
    }

    @Override
    public Money calculateOverdueAmountPercentageAppliedTo(Loan loan, LoanRepaymentScheduleInstallment installment) {
        return installment.getPrincipalOutstanding(loan.getCurrency());
    }

    @Override
    public Money calculateInstallmentChargeAmount(Loan loan, BigDecimal percentage, LoanRepaymentScheduleInstallment installment) {
        return Money.zero(loan.getCurrency())
                .plus(LoanCharge.percentageOf(installment.getPrincipal(loan.getCurrency()).getAmount(), percentage));
    }
}
