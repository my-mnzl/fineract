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
package org.apache.fineract.portfolio.charge.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.charge.domain.BasicChargeCalculationDescriptor;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.SimpleChargeCalculationRegistry;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChargeDefinitionCommandFromApiJsonDeserializerTest {

    private final ChargeDefinitionCommandFromApiJsonDeserializer underTest = new ChargeDefinitionCommandFromApiJsonDeserializer(
            new FromJsonHelper(),
            new SimpleChargeCalculationRegistry(List.of(new BasicChargeCalculationDescriptor(ChargeCalculationType.FLAT.getValue(),
                    ChargeCalculationType.FLAT.getCode(), "Flat", true, true, true, true, true, true))));

    @ParameterizedTest
    @MethodSource("validPeriodicFrequencies")
    void validateForCreateAllowsLoanPeriodicCharge(final PeriodFrequencyType frequencyType) {
        assertDoesNotThrow(() -> underTest.validateForCreate(periodicLoanChargeJson(frequencyType.getValue(), 1, null)));
    }

    @Test
    void validateForCreateRejectsLoanPeriodicChargeWithoutFeeFrequency() {
        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateForCreate(periodicLoanChargeJson(null, 1, null)));

        assertThat(exception.getErrors()).extracting("parameterName").contains("feeFrequency");
    }

    @Test
    void validateForCreateRejectsLoanPeriodicChargeWithUnsupportedFeeFrequency() {
        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateForCreate(periodicLoanChargeJson(PeriodFrequencyType.DAYS.getValue(), 1, null)));

        assertThat(exception.getErrors()).extracting("parameterName").contains("feeFrequency");
    }

    @Test
    void validateForCreateRejectsLoanPeriodicChargeWithFeeOnMonthDay() {
        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateForCreate(periodicLoanChargeJson(PeriodFrequencyType.YEARS.getValue(), 1, "04 March")));

        assertThat(exception.getErrors()).extracting("parameterName").contains("feeOnMonthDay");
    }

    private static Stream<Arguments> validPeriodicFrequencies() {
        return Stream.of(Arguments.of(PeriodFrequencyType.WEEKS), Arguments.of(PeriodFrequencyType.MONTHS),
                Arguments.of(PeriodFrequencyType.YEARS));
    }

    private String periodicLoanChargeJson(final Integer feeFrequency, final Integer feeInterval, final String feeOnMonthDay) {
        final String feeFrequencyJson = feeFrequency == null ? "null" : feeFrequency.toString();
        final String feeOnMonthDayJson = feeOnMonthDay == null ? "null" : "\"" + feeOnMonthDay + "\"";
        return """
                {
                  "name": "Periodic Insurance",
                  "amount": 10,
                  "locale": "en",
                  "currencyCode": "USD",
                  "chargeAppliesTo": 1,
                  "chargeCalculationType": 1,
                  "chargeTimeType": 17,
                  "chargePaymentMode": 0,
                  "penalty": false,
                  "active": true,
                  "monthDayFormat": "dd MMM",
                  "feeFrequency": %s,
                  "feeInterval": %d,
                  "feeOnMonthDay": %s
                }
                """.formatted(feeFrequencyJson, feeInterval, feeOnMonthDayJson);
    }
}
