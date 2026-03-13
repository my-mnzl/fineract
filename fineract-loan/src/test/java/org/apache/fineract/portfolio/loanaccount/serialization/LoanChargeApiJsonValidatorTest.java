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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanChargeApiJsonValidatorTest {

    private static final Long CHARGE_ID = 1L;

    @Mock
    private ChargeRepositoryWrapper chargeRepository;

    @Mock
    private LoanChargeRepository loanChargeRepository;

    @Mock
    private Charge charge;

    @Mock
    private LoanProduct loanProduct;

    private FromJsonHelper fromJsonHelper;
    private LoanChargeApiJsonValidator underTest;

    @BeforeEach
    void setUp() {
        fromJsonHelper = new FromJsonHelper();
        underTest = new LoanChargeApiJsonValidator(fromJsonHelper, chargeRepository, loanChargeRepository, List.of());

        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(charge);
    }

    @Test
    void validateAddLoanChargeRejectsPeriodicCharges() {
        when(charge.isLoanPeriodic()).thenReturn(true);

        assertThatThrownBy(() -> underTest.validateAddLoanCharge("""
                {
                  "chargeId": 1,
                  "amount": 100,
                  "locale": "en"
                }
                """)).isInstanceOf(LoanChargeCannotBeAddedException.class)
                .hasMessageContaining("Periodic charge cannot be added to the loan manually.");
    }

    @Test
    void validateLoanChargesRejectsPeriodicChargesInLoanPayload() {
        when(charge.isLoanPeriodic()).thenReturn(true);
        when(charge.getCurrencyCode()).thenReturn("USD");
        when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.LOAN_PERIODIC.getValue());
        when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());
        when(charge.getChargePaymentMode()).thenReturn(ChargePaymentMode.REGULAR.getValue());
        when(loanProduct.hasCurrencyCodeOf("USD")).thenReturn(true);

        JsonElement element = fromJsonHelper.parse("""
                {
                  "locale": "en",
                  "dateFormat": "dd MMMM yyyy",
                  "expectedDisbursementDate": "01 January 2024",
                  "charges": [
                    {
                      "chargeId": 1,
                      "amount": 100,
                      "dueDate": "15 January 2024"
                    }
                  ]
                }
                """);
        DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(new ArrayList<ApiParameterError>()).resource("loan");

        assertThatThrownBy(() -> underTest.validateLoanCharges(element, loanProduct, baseDataValidator))
                .isInstanceOf(LoanChargeCannotBeAddedException.class)
                .hasMessageContaining("Periodic charge cannot be added to the loan manually.");
    }

    @Test
    void validateAddLoanChargeAllowsNonPeriodicCharges() {
        when(charge.isLoanPeriodic()).thenReturn(false);

        assertDoesNotThrow(() -> underTest.validateAddLoanCharge("""
                {
                  "chargeId": 1,
                  "amount": 100,
                  "locale": "en"
                }
                """));
    }
}
