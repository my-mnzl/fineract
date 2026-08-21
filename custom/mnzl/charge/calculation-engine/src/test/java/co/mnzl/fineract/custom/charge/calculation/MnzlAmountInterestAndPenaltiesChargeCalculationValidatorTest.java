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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import java.util.Optional;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.Test;

class MnzlAmountInterestAndPenaltiesChargeCalculationValidatorTest {

    private final MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
    private final MnzlAmountInterestAndPenaltiesChargeCalculationValidator validator = new MnzlAmountInterestAndPenaltiesChargeCalculationValidator(
            strategyReadService);

    @Test
    void identifiesPersistedCalculationType() {
        assertThat(validator.calculationType()).isEqualTo(ChargeCalculationType.CUSTOM.getValue());
    }

    @Test
    void acceptsCustomStrategyAndRejectsCoreStrategy() {
        final LoanProduct product = mock(LoanProduct.class);
        when(product.getId()).thenReturn(7L);
        when(strategyReadService.findChargeStrategyCode(7L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.CHARGE_MNZL_INTEREST_AND_PENALTIES));

        assertThat(validator.validateLoanProductRestriction(ChargeTimeType.OVERDUE_INSTALLMENT, product)).isNull();

        when(strategyReadService.findChargeStrategyCode(7L)).thenReturn(Optional.of(MnzlLoanProductStrategyCodes.CHARGE_CORE));
        assertThat(validator.validateLoanProductRestriction(ChargeTimeType.OVERDUE_INSTALLMENT, product))
                .isEqualTo("loan.product.charge.strategy.not.enabled");
    }

    @Test
    void rejectsSpecifiedDueDateAndInstallmentCustomCharges() {
        final LoanCharge charge = mock(LoanCharge.class);
        final Loan loan = mock(Loan.class);
        when(charge.getLoan()).thenReturn(loan);
        when(loan.productId()).thenReturn(11L);
        when(strategyReadService.findChargeStrategyCode(11L))
                .thenReturn(Optional.of(MnzlLoanProductStrategyCodes.CHARGE_MNZL_INTEREST_AND_PENALTIES));
        when(charge.isSpecifiedDueDate()).thenReturn(true);

        assertThat(validator.validateLoanCharge(charge)).isEqualTo("specific.loancharge.with.calculation.type.interest.not.allowed");

        when(charge.isSpecifiedDueDate()).thenReturn(false);
        when(charge.isInstalmentFee()).thenReturn(true);
        assertThat(validator.validateLoanCharge(charge)).isEqualTo("installment.loancharge.with.calculation.type.principal.not.allowed");
    }
}
