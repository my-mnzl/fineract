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
package org.apache.fineract.portfolio.loanproduct.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingHelper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.floatingrates.domain.FloatingRate;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleTransactionProcessorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleProcessingType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanproduct.domain.AdvancedPaymentAllocationsJsonParser;
import org.apache.fineract.portfolio.loanproduct.domain.AdvancedPaymentAllocationsValidator;
import org.apache.fineract.portfolio.loanproduct.domain.InterestCalculationPeriodMethod;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductFloatingRates;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductInterestRecalculationDetails;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.apache.fineract.portfolio.loanproduct.domain.RecalculationFrequencyType;
import org.apache.fineract.portfolio.loanproduct.domain.RepaymentStartDateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanProductDataValidatorTest {

    @Mock
    private LoanRepaymentScheduleTransactionProcessorFactory loanRepaymentScheduleTransactionProcessorFactory;
    @Mock
    private AdvancedPaymentAllocationsJsonParser advancedPaymentAllocationsJsonParser;
    @Mock
    private AdvancedPaymentAllocationsValidator advancedPaymentAllocationsValidator;
    @Mock
    private ProductToGLAccountMappingHelper productToGLAccountMappingHelper;

    private LoanProductDataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new LoanProductDataValidator(new FromJsonHelper(), loanRepaymentScheduleTransactionProcessorFactory,
                advancedPaymentAllocationsJsonParser, advancedPaymentAllocationsValidator, productToGLAccountMappingHelper);
    }

    @Test
    void validateForCreateAcceptsFloatingProductDifferentialRangeStartingBelowZero() {
        validator.validateForCreate(command(linkedFloatingProductJson("0", "-5", "0", "10")));
    }

    @Test
    void validateForCreateAcceptsProductInterestRateDifferentialInsideNegativeRange() {
        validator.validateForCreate(command(linkedFloatingProductJson("-3", "-5", "-2", "0")));
    }

    @Test
    void validateForCreateRejectsDefaultDifferentialBelowMinimum() {
        assertValidationError(() -> validator.validateForCreate(command(linkedFloatingProductJson("-1", "-1", "-2", "1"))),
                "defaultDifferentialLendingRate");
    }

    @Test
    void validateForCreateRejectsMaximumDifferentialBelowDefault() {
        assertValidationError(() -> validator.validateForCreate(command(linkedFloatingProductJson("0", "-5", "10", "5"))),
                "maxDifferentialLendingRate");
    }

    @Test
    void validateForCreateRejectsMaximumDifferentialBelowMinimum() {
        assertValidationError(() -> validator.validateForCreate(command(linkedFloatingProductJson("5", "5", "6", "4"))),
                "maxDifferentialLendingRate");
    }

    @Test
    void validateForCreateRejectsProductInterestRateDifferentialOutsideRange() {
        assertValidationError(() -> validator.validateForCreate(command(linkedFloatingProductJson("-6", "-5", "0", "10"))),
                "interestRateDifferential");
    }

    @Test
    void validateForCreateRejectsFloatingDifferentialForNonLinkedProduct() {
        assertValidationError(() -> validator.validateForCreate(command(fixedProductJsonWithUnsupportedDifferential())),
                "interestRateDifferential");
    }

    @Test
    void validateForUpdateAcceptsExistingFloatingProductDifferentialInsideNegativeRange() {
        validator.validateForUpdate(command(updateProductJson()), linkedFloatingProduct("-0.25", "-1.00", "0.00", "1.00"));
    }

    @Test
    void validateForUpdateRejectsPartialMaximumNarrowedBelowExistingProductDifferential() {
        LoanProduct loanProduct = linkedFloatingProduct("0.00", "-1.00", "-0.50", "1.00");

        assertValidationError(() -> validator.validateForUpdate(command(updateMaxDifferentialJson("-0.10")), loanProduct),
                "interestRateDifferential");
    }

    private static JsonCommand command(String json) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.json()).thenReturn(json);
        return command;
    }

    private static LoanProduct linkedFloatingProduct(String interestRateDifferential, String minDifferential, String defaultDifferential,
            String maxDifferential) {
        LoanProductRelatedDetail relatedDetail = mock(LoanProductRelatedDetail.class);
        when(relatedDetail.getRepayEvery()).thenReturn(1);
        when(relatedDetail.getGraceOnPrincipalPayment()).thenReturn(0);
        when(relatedDetail.getGraceOnInterestPayment()).thenReturn(0);
        when(relatedDetail.getGraceOnInterestCharged()).thenReturn(0);
        when(relatedDetail.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
        when(relatedDetail.getInterestCalculationPeriodMethod()).thenReturn(InterestCalculationPeriodMethod.SAME_AS_REPAYMENT_PERIOD);
        when(relatedDetail.isAllowPartialPeriodInterestCalculation()).thenReturn(true);
        when(relatedDetail.getLoanScheduleProcessingType()).thenReturn(LoanScheduleProcessingType.HORIZONTAL);
        when(relatedDetail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);

        FloatingRate floatingRate = mock(FloatingRate.class);
        when(floatingRate.getId()).thenReturn(1L);
        LoanProductFloatingRates floatingRates = mock(LoanProductFloatingRates.class);
        when(floatingRates.getFloatingRate()).thenReturn(floatingRate);
        when(floatingRates.getInterestRateDifferential()).thenReturn(new BigDecimal(interestRateDifferential));
        when(floatingRates.getMinDifferentialLendingRate()).thenReturn(new BigDecimal(minDifferential));
        when(floatingRates.getDefaultDifferentialLendingRate()).thenReturn(new BigDecimal(defaultDifferential));
        when(floatingRates.getMaxDifferentialLendingRate()).thenReturn(new BigDecimal(maxDifferential));
        when(floatingRates.isFloatingInterestRateCalculationAllowed()).thenReturn(true);

        LoanProductInterestRecalculationDetails recalculationDetails = mock(LoanProductInterestRecalculationDetails.class);
        when(recalculationDetails.getInterestRecalculationCompoundingMethod()).thenReturn(0);
        when(recalculationDetails.getRestFrequencyType()).thenReturn(RecalculationFrequencyType.SAME_AS_REPAYMENT_PERIOD);

        Money principal = money("1000.00");
        Money minPrincipal = money("100.00");
        Money maxPrincipal = money("10000.00");

        LoanProduct loanProduct = mock(LoanProduct.class);
        when(loanProduct.getLoanProductRelatedDetail()).thenReturn(relatedDetail);
        when(loanProduct.getPrincipalAmount()).thenReturn(principal);
        when(loanProduct.getMinPrincipalAmount()).thenReturn(minPrincipal);
        when(loanProduct.getMaxPrincipalAmount()).thenReturn(maxPrincipal);
        when(loanProduct.getNumberOfRepayments()).thenReturn(12);
        when(loanProduct.getTransactionProcessingStrategyCode()).thenReturn("mifos-standard-strategy");
        when(loanProduct.isInterestRecalculationEnabled()).thenReturn(true);
        when(loanProduct.getProductInterestRecalculationDetails()).thenReturn(recalculationDetails);
        when(loanProduct.isLinkedToFloatingInterestRate()).thenReturn(true);
        when(loanProduct.getFloatingRates()).thenReturn(floatingRates);
        when(loanProduct.getRepaymentStartDateType()).thenReturn(RepaymentStartDateType.DISBURSEMENT_DATE);
        when(loanProduct.getPaymentAllocationRules()).thenReturn(List.of());
        return loanProduct;
    }

    private static Money money(String amount) {
        Money money = mock(Money.class);
        when(money.getAmount()).thenReturn(new BigDecimal(amount));
        return money;
    }

    private static String linkedFloatingProductJson(String interestRateDifferential, String minDifferential, String defaultDifferential,
            String maxDifferential) {
        return """
                {
                  "locale": "en",
                  "name": "Floating Loan Product",
                  "shortName": "FLP",
                  "currencyCode": "USD",
                  "digitsAfterDecimal": 2,
                  "principal": 1000,
                  "numberOfRepayments": 12,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "amortizationType": 1,
                  "interestType": 0,
                  "interestCalculationPeriodType": 1,
                  "allowPartialPeriodInterestCalcualtion": true,
                  "transactionProcessingStrategyCode": "mifos-standard-strategy",
                  "daysInMonthType": 1,
                  "daysInYearType": 365,
                  "isInterestRecalculationEnabled": true,
                  "interestRecalculationCompoundingMethod": 0,
                  "rescheduleStrategyMethod": 1,
                  "recalculationRestFrequencyType": 1,
                  "isLinkedToFloatingInterestRates": true,
                  "floatingRatesId": 1,
                  "interestRateDifferential": __INTEREST_RATE_DIFFERENTIAL__,
                  "minDifferentialLendingRate": __MIN_DIFFERENTIAL__,
                  "defaultDifferentialLendingRate": __DEFAULT_DIFFERENTIAL__,
                  "maxDifferentialLendingRate": __MAX_DIFFERENTIAL__,
                  "isFloatingInterestRateCalculationAllowed": true,
                  "accountingRule": 1
                }
                """.replace("__INTEREST_RATE_DIFFERENTIAL__", interestRateDifferential).replace("__MIN_DIFFERENTIAL__", minDifferential)
                .replace("__DEFAULT_DIFFERENTIAL__", defaultDifferential).replace("__MAX_DIFFERENTIAL__", maxDifferential);
    }

    private static String updateProductJson() {
        return """
                {
                  "locale": "en",
                  "name": "Floating Loan Product Updated"
                }
                """;
    }

    private static String updateMaxDifferentialJson(String maxDifferential) {
        return """
                {
                  "locale": "en",
                  "maxDifferentialLendingRate": __MAX_DIFFERENTIAL__
                }
                """.replace("__MAX_DIFFERENTIAL__", maxDifferential);
    }

    private static String fixedProductJsonWithUnsupportedDifferential() {
        return """
                {
                  "locale": "en",
                  "name": "Fixed Loan Product",
                  "shortName": "FIX",
                  "currencyCode": "USD",
                  "digitsAfterDecimal": 2,
                  "principal": 1000,
                  "numberOfRepayments": 12,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "amortizationType": 1,
                  "interestType": 0,
                  "interestCalculationPeriodType": 1,
                  "transactionProcessingStrategyCode": "mifos-standard-strategy",
                  "daysInMonthType": 1,
                  "daysInYearType": 365,
                  "isInterestRecalculationEnabled": false,
                  "isLinkedToFloatingInterestRates": false,
                  "interestRatePerPeriod": 10,
                  "interestRateFrequencyType": 2,
                  "interestRateDifferential": -0.25,
                  "accountingRule": 1
                }
                """;
    }

    private static void assertValidationError(Runnable action, String parameterName) {
        assertThatThrownBy(action::run).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(error -> assertThat(error.getParameterName()).isEqualTo(parameterName)));
    }
}
