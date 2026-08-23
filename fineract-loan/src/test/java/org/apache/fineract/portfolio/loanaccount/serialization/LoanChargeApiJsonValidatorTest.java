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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.exception.LoanChargeCannotBeAddedException;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.Test;

class LoanChargeApiJsonValidatorTest {

    @Test
    void customRestrictionsApplyWhenInterestRecalculationIsDisabled() {
        final String restriction = "custom.charge.not.allowed";
        ChargeCalculationValidator customValidator = mock(ChargeCalculationValidator.class);
        LoanProduct loanProduct = mock(LoanProduct.class);
        when(customValidator.calculationType()).thenReturn(ChargeCalculationType.CUSTOM.getValue());
        when(customValidator.validateLoanProductRestriction(ChargeTimeType.SPECIFIED_DUE_DATE, loanProduct)).thenReturn(restriction);
        when(loanProduct.isInterestRecalculationEnabled()).thenReturn(false);
        LoanChargeApiJsonValidator validator = new LoanChargeApiJsonValidator(mock(FromJsonHelper.class),
                mock(ChargeRepositoryWrapper.class), mock(LoanChargeRepository.class), List.of(customValidator));
        List<ApiParameterError> errors = new ArrayList<>();
        DataValidatorBuilder dataValidator = new DataValidatorBuilder(errors).resource("loan");

        validator.validateInterestBearingLoanProductRestriction(ChargeCalculationType.CUSTOM, ChargeTimeType.SPECIFIED_DUE_DATE,
                loanProduct, dataValidator);

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.getParameterName()).isEqualTo("charges");
            assertThat(error.getUserMessageGlobalisationCode()).isEqualTo("validation.msg.loan.charges." + restriction);
        });
    }

    @Test
    void undatedPeriodicChargeCanReachTheMnzlAssemblerExpansion() {
        FromJsonHelper fromJsonHelper = new FromJsonHelper();
        ChargeRepositoryWrapper chargeRepository = mock(ChargeRepositoryWrapper.class);
        LoanProduct loanProduct = mock(LoanProduct.class);
        Charge charge = mock(Charge.class);
        when(chargeRepository.findOneWithNotFoundDetection(9L)).thenReturn(charge);
        when(charge.getCurrencyCode()).thenReturn("USD");
        when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.LOAN_PERIODIC.getValue());
        when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());
        when(charge.getChargePaymentMode()).thenReturn(ChargePaymentMode.REGULAR.getValue());
        when(loanProduct.hasCurrencyCodeOf("USD")).thenReturn(true);
        LoanChargeApiJsonValidator validator = new LoanChargeApiJsonValidator(fromJsonHelper, chargeRepository,
                mock(LoanChargeRepository.class), List.of());
        JsonElement request = JsonParser.parseString("""
                {
                  "expectedDisbursementDate": "2026-01-01",
                  "dateFormat": "yyyy-MM-dd",
                  "locale": "en",
                  "charges": [{"chargeId": 9, "amount": 25}]
                }
                """);
        List<ApiParameterError> errors = new ArrayList<>();

        assertThatCode(() -> validator.validateLoanCharges(request, loanProduct, new DataValidatorBuilder(errors).resource("loan")))
                .doesNotThrowAnyException();
        assertThat(errors).isEmpty();
    }

    @Test
    void specifiedDueDateChargeStillRequiresADate() {
        FromJsonHelper fromJsonHelper = new FromJsonHelper();
        ChargeRepositoryWrapper chargeRepository = mock(ChargeRepositoryWrapper.class);
        LoanProduct loanProduct = mock(LoanProduct.class);
        Charge charge = mock(Charge.class);
        when(chargeRepository.findOneWithNotFoundDetection(9L)).thenReturn(charge);
        when(charge.getCurrencyCode()).thenReturn("USD");
        when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());
        when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());
        when(charge.getChargePaymentMode()).thenReturn(ChargePaymentMode.REGULAR.getValue());
        when(charge.getName()).thenReturn("specified charge");
        when(loanProduct.hasCurrencyCodeOf("USD")).thenReturn(true);
        LoanChargeApiJsonValidator validator = new LoanChargeApiJsonValidator(fromJsonHelper, chargeRepository,
                mock(LoanChargeRepository.class), List.of());
        JsonElement request = JsonParser.parseString("""
                {
                  "expectedDisbursementDate": "2026-01-01",
                  "dateFormat": "yyyy-MM-dd",
                  "locale": "en",
                  "charges": [{"chargeId": 9, "amount": 25}]
                }
                """);

        assertThatThrownBy(
                () -> validator.validateLoanCharges(request, loanProduct, new DataValidatorBuilder(new ArrayList<>()).resource("loan")))
                .isInstanceOf(LoanChargeCannotBeAddedException.class);
    }
}
