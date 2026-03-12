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
package org.apache.fineract.portfolio.loanaccount.serialization;

import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.springframework.stereotype.Component;

@Component
public class PercentOfAmountAndInterestChargeCalculationValidator implements ChargeCalculationValidator {

    @Override
    public Integer calculationType() {
        return ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST.getValue();
    }

    @Override
    public String validateLoanCharge(LoanCharge loanCharge) {
        if (loanCharge.isInstalmentFee()) {
            return "installment." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_PRINCIPAL_CALCULATION_TYPE;
        }
        if (loanCharge.isSpecifiedDueDate() || loanCharge.isPeriodic()) {
            return "specific." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_INTEREST_CALCULATION_TYPE;
        }
        return null;
    }

    @Override
    public String validateLoanProductRestriction(ChargeTimeType chargeTime, LoanProduct loanProduct) {
        if (chargeTime.isInstalmentFee()) {
            return "installment." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_PRINCIPAL_CALCULATION_TYPE;
        }
        if (chargeTime.isSpecifiedDueDate() || chargeTime.isLoanPeriodic()) {
            return "specific." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_INTEREST_CALCULATION_TYPE;
        }
        return null;
    }
}
