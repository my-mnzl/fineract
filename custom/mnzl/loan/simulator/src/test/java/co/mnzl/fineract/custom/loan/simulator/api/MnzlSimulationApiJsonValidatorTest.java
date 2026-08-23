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
package co.mnzl.fineract.custom.loan.simulator.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MnzlSimulationApiJsonValidatorTest {

    private MnzlSimulationApiJsonValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MnzlSimulationApiJsonValidator(new FromJsonHelper());
    }

    @Test
    void validRequestPasses() {
        String json = buildValidRequest();
        assertThatCode(() -> validator.validateForCreate(json)).doesNotThrowAnyException();
    }

    @Test
    void repaymentCadenceFieldsPassForCreateAndPreview() {
        String json = """
                {
                  "loanProductId": 1,
                  "principal": "100000",
                  "interestRatePerPeriod": "12",
                  "numberOfRepayments": 12,
                  "repaymentEvery": 2,
                  "repaymentFrequencyType": 2,
                  "disbursementDate": "2026-01-01",
                  "locale": "en",
                  "actions": [{"type": "DISBURSE", "date": "2026-01-01"}]
                }
                """;

        assertThatCode(() -> validator.validateForCreate(json)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForPreview(json)).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveRepaymentEveryFailsForCreateAndPreview() {
        for (int invalidValue : List.of(0, -1)) {
            JsonObject request = JsonParser.parseString(buildValidRequest()).getAsJsonObject();
            request.addProperty("repaymentEvery", invalidValue);
            assertInvalidForCreateAndPreview(request.toString());
        }
    }

    @Test
    void repaymentFrequencyOutsideSupportedRangeFailsForCreateAndPreview() {
        for (int invalidValue : List.of(-1, 4)) {
            JsonObject request = JsonParser.parseString(buildValidRequest()).getAsJsonObject();
            request.addProperty("repaymentFrequencyType", invalidValue);
            assertInvalidForCreateAndPreview(request.toString());
        }
    }

    @Test
    void blankJsonThrowsInvalidJson() {
        assertThatThrownBy(() -> validator.validateForCreate("")).isInstanceOf(InvalidJsonException.class);
        assertThatThrownBy(() -> validator.validateForCreate(null)).isInstanceOf(InvalidJsonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "null", "[]", "\"request\"", "42", "{" })
    void nonObjectOrMalformedJsonThrowsInvalidJson(String json) {
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(InvalidJsonException.class);
        assertThatThrownBy(() -> validator.validateForPreview(json)).isInstanceOf(InvalidJsonException.class);
    }

    @Test
    void missingLoanProductIdFails() {
        String json = new Gson().toJson(Map.of("principal", "100000", "interestRatePerPeriod", "12", "numberOfRepayments", 12,
                "disbursementDate", "2026-01-01", "locale", "en", "actions", List.of(Map.of("type", "DISBURSE", "date", "2026-01-01"))));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void emptyActionsFails() {
        String json = new Gson().toJson(Map.of("loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12",
                "numberOfRepayments", 12, "disbursementDate", "2026-01-01", "locale", "en", "actions", List.of()));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void invalidActionTypeFails() {
        String json = new Gson().toJson(Map.of("loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12",
                "numberOfRepayments", 12, "disbursementDate", "2026-01-01", "locale", "en", "actions",
                List.of(Map.of("type", "INVALID_TYPE", "date", "2026-01-01"))));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "null", "\"PAY\"", "42", "[]" })
    void nonObjectActionFailsValidation(String action) {
        assertThatThrownBy(() -> validator.validateForCreate(requestWithAction(action)))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"type\":null,\"date\":\"2026-01-01\"}", "{\"type\":[],\"date\":\"2026-01-01\"}",
            "{\"type\":\"PAY\",\"date\":null,\"amount\":1}", "{\"type\":\"PAY\",\"date\":{},\"amount\":1}" })
    void nonStringOrNullActionFieldsFailValidation(String action) {
        assertThatThrownBy(() -> validator.validateForCreate(requestWithAction(action)))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"type\":\"PAY\",\"date\":\"not-a-date\",\"amount\":1}",
            "{\"type\":\"PAY\",\"date\":\"2026-01-01\",\"amount\":true}", "{\"type\":\"PAY\",\"date\":\"2026-01-01\",\"amount\":\"abc\"}",
            "{\"type\":\"CHANGE_INTEREST_RATE\",\"date\":\"2026-01-01\",\"rate\":false}",
            "{\"type\":\"CHANGE_INTEREST_RATE\",\"date\":\"2026-01-01\",\"rate\":\"abc\"}",
            "{\"type\":\"ADD_CHARGE\",\"date\":\"2026-01-01\",\"chargeId\":1.5}",
            "{\"type\":\"ADD_CHARGE\",\"date\":\"2026-01-01\",\"chargeId\":true}",
            "{\"type\":\"ADD_CHARGE\",\"date\":\"2026-01-01\",\"chargeId\":\"abc\"}",
            "{\"type\":\"DISBURSE\",\"date\":\"2026-01-01\",\"amount\":\"abc\"}",
            "{\"type\":\"DISBURSE\",\"date\":\"2026-01-01\",\"rate\":[]}",
            "{\"type\":\"DISBURSE\",\"date\":\"2026-01-01\",\"chargeId\":1.5}" })
    void unparseableActionFieldsFailValidation(String action) {
        assertThatThrownBy(() -> validator.validateForCreate(requestWithAction(action)))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void changeInterestRateWithoutRateFails() {
        String json = new Gson().toJson(Map.of("loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12",
                "numberOfRepayments", 12, "disbursementDate", "2026-01-01", "locale", "en", "actions",
                List.of(Map.of("type", "CHANGE_INTEREST_RATE", "date", "2026-06-01"))));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void addChargeWithoutChargeIdFails() {
        String json = new Gson().toJson(Map.of("loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12",
                "numberOfRepayments", 12, "disbursementDate", "2026-01-01", "locale", "en", "actions",
                List.of(Map.of("type", "ADD_CHARGE", "date", "2026-03-02"))));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void payWithoutAmountFails() {
        String json = new Gson()
                .toJson(Map.of("loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12", "numberOfRepayments", 12,
                        "disbursementDate", "2026-01-01", "locale", "en", "actions", List.of(Map.of("type", "PAY", "date", "2026-02-01"))));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void unsupportedParameterFails() {
        String json = new Gson().toJson(Map.of("loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12",
                "numberOfRepayments", 12, "disbursementDate", "2026-01-01", "locale", "en", "unknownField", "value", "actions",
                List.of(Map.of("type", "DISBURSE", "date", "2026-01-01"))));
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(UnsupportedParameterException.class);
    }

    private String buildValidRequest() {
        return new Gson().toJson(Map.of("name", "Test simulation", "loanProductId", 1, "principal", "100000", "interestRatePerPeriod", "12",
                "numberOfRepayments", 12, "disbursementDate", "2026-01-01", "locale", "en", "actions",
                List.of(Map.of("type", "DISBURSE", "date", "2026-01-01"), Map.of("type", "PAY", "date", "2026-02-01", "amount", 9583.33),
                        Map.of("type", "SKIP", "date", "2026-03-01"), Map.of("type", "RUN_COB", "date", "2026-03-02"),
                        Map.of("type", "ADD_CHARGE", "date", "2026-03-02", "chargeId", 1),
                        Map.of("type", "CHANGE_INTEREST_RATE", "date", "2026-06-01", "rate", 15.0),
                        Map.of("type", "WRITE_OFF", "date", "2026-12-01"))));
    }

    private String requestWithAction(String action) {
        return """
                {
                  "loanProductId": 1,
                  "principal": "100000",
                  "numberOfRepayments": 12,
                  "disbursementDate": "2026-01-01",
                  "locale": "en",
                  "actions": [%s]
                }
                """.formatted(action);
    }

    private void assertInvalidForCreateAndPreview(String json) {
        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(PlatformApiDataValidationException.class);
        assertThatThrownBy(() -> validator.validateForPreview(json)).isInstanceOf(PlatformApiDataValidationException.class);
    }
}
