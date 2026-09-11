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
package co.mnzl.fineract.custom.loan.instrument;

import java.math.BigDecimal;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;

public final class PurchasedReceivableProductValidator {

    private PurchasedReceivableProductValidator() {}

    public static void validate(LoanProduct product) {
        LoanProductRelatedDetail detail = product.getLoanProductRelatedDetail();
        if (!product.isAccountingDisabled() || !"EGP".equals(detail.getCurrency().getCode())
                || detail.getCurrency().getDigitsAfterDecimal() != 2 || !zero(detail.getAnnualNominalInterestRate())
                || !zero(detail.getNominalInterestRatePerPeriod()) || (product.getCharges() != null && !product.getCharges().isEmpty())
                || detail.isInterestRecalculationEnabled() || detail.isEnableAccrualActivityPosting() || detail.isEnableDownPayment()
                || detail.isEnableAutoRepaymentForDownPayment() || detail.isEnableIncomeCapitalization() || detail.isEnableBuyDownFee()
                || !zero(detail.getInArrearsTolerance().getAmount()) || positive(detail.getGraceOnPrincipalPayment())
                || positive(detail.getGraceOnInterestPayment()) || positive(detail.getGraceOnInterestCharged())
                || positive(detail.getGraceOnArrearsAgeing()) || positive(detail.getRecurringMoratoriumOnPrincipalPeriods())
                || positive(detail.getInstallmentAmountInMultiplesOf())) {
            throw new IllegalArgumentException(
                    "Purchased receivable product must use EGP, accounting NONE and no borrower charges, grace or normalization");
        }
    }

    public static void validateStrategies(String instrument, String schedule, String charge, String cob) {
        boolean purchased = MnzlLoanProductStrategyCodes.INSTRUMENT_PURCHASED_RECEIVABLE.equals(instrument);
        boolean fixed = MnzlLoanProductStrategyCodes.SCHEDULE_FIXED_RECEIVABLE.equals(schedule);
        boolean noCharges = MnzlLoanProductStrategyCodes.CHARGE_NO_BORROWER_CHARGES.equals(charge);
        if ((purchased || fixed || noCharges)
                && !(purchased && fixed && noCharges && MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS.equals(cob))) {
            throw new IllegalArgumentException("Purchased receivable strategies must be configured together");
        }
    }

    private static boolean zero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    private static boolean positive(Integer value) {
        return value != null && value != 0;
    }
}
