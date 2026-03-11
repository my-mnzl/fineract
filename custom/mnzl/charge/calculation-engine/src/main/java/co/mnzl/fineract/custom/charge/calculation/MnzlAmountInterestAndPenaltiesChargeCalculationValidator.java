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

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.serialization.ChargeCalculationValidator;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MnzlAmountInterestAndPenaltiesChargeCalculationValidator implements ChargeCalculationValidator {

    private static final String CHARGE_STRATEGY_NOT_ENABLED = "loan.product.charge.strategy.not.enabled";

    private final MnzlLoanProductStrategyReadService loanProductStrategyReadService;

    @Override
    public Integer calculationType() {
        return 6;
    }

    @Override
    public String validateLoanCharge(LoanCharge loanCharge) {
        if (loanCharge.getLoan() != null && !usesCustomChargeStrategy(loanCharge.getLoan().productId())) {
            return CHARGE_STRATEGY_NOT_ENABLED;
        }
        if (loanCharge.isInstalmentFee()) {
            return "installment." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_PRINCIPAL_CALCULATION_TYPE;
        }
        if (loanCharge.isSpecifiedDueDate()) {
            return "specific." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_INTEREST_CALCULATION_TYPE;
        }
        return null;
    }

    @Override
    public String validateLoanProductRestriction(ChargeTimeType chargeTime, LoanProduct loanProduct) {
        if (loanProduct != null && !usesCustomChargeStrategy(loanProduct.getId())) {
            return CHARGE_STRATEGY_NOT_ENABLED;
        }
        if (chargeTime.isInstalmentFee()) {
            return "installment." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_PRINCIPAL_CALCULATION_TYPE;
        }
        if (chargeTime.isSpecifiedDueDate()) {
            return "specific." + LoanApiConstants.LOAN_CHARGE_CAN_NOT_BE_ADDED_WITH_INTEREST_CALCULATION_TYPE;
        }
        return null;
    }

    private boolean usesCustomChargeStrategy(Long loanProductId) {
        final String strategyCode = loanProductStrategyReadService.findChargeStrategyCode(loanProductId).orElse(null);
        return strategyCode == null || MnzlLoanProductStrategyCodes.CHARGE_MNZL_INTEREST_AND_PENALTIES.equals(strategyCode);
    }
}
