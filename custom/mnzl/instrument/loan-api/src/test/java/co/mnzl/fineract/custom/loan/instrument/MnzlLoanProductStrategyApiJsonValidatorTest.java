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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

class MnzlLoanProductStrategyApiJsonValidatorTest {

    private final MnzlLoanProductStrategyApiJsonValidator validator = new MnzlLoanProductStrategyApiJsonValidator(new FromJsonHelper());

    @Test
    void validateForUpdateRejectsBlankStrategyCodes() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "BALLOON_LOAN",
                  "scheduleStrategyCode": " ",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void validateForUpdateAcceptsCompleteStrategyPayload() {
        validator.validateForUpdate("""
                {
                  "instrumentCode": "BALLOON_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """);
    }

    // ---- Per-code-type: missing field (notBlank) -------------------------------------------------

    @Test
    void instrumentCode_missing_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """)).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("instrumentCode")));
    }

    @Test
    void scheduleStrategyCode_missing_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """)).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("scheduleStrategyCode")));
    }

    @Test
    void chargeStrategyCode_missing_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """)).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("chargeStrategyCode")));
    }

    @Test
    void cobStrategyCode_missing_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES"
                }
                """)).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("cobStrategyCode")));
    }

    // ---- Per-code-type: invalid value (length > 100) ---------------------------------------------
    // Production note: the validator only enforces notBlank + notExceedingLengthOf(100). It does
    // NOT cross-check against the allowed values in MnzlLoanProductStrategyCodes, so payloads such
    // as "NONSENSE" pass validation and would only be rejected (if at all) downstream. To satisfy
    // the per-type "invalid value" cases we use over-length strings, which the validator does
    // reject.

    private static String overLength() {
        return "X".repeat(101);
    }

    @Test
    void instrumentCode_invalidValue_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "%s",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """.formatted(overLength()))).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("instrumentCode")));
    }

    @Test
    void scheduleStrategyCode_invalidValue_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "%s",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """.formatted(overLength()))).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("scheduleStrategyCode")));
    }

    @Test
    void chargeStrategyCode_invalidValue_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "%s",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """.formatted(overLength()))).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("chargeStrategyCode")));
    }

    @Test
    void cobStrategyCode_invalidValue_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "%s"
                }
                """.formatted(overLength()))).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getParameterName()).isEqualTo("cobStrategyCode")));
    }

    // ---- Per-code-type: valid value (each in isolation) ------------------------------------------

    @Test
    void instrumentCode_validValue_passes() {
        validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_BALLOON_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """);
    }

    @Test
    void scheduleStrategyCode_validValue_passes() {
        validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "CORE_DEFAULT",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """);
    }

    @Test
    void chargeStrategyCode_validValue_passes() {
        validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "CORE_DEFAULT",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """);
    }

    @Test
    void cobStrategyCode_validValue_passes() {
        validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "CORE_DEFAULT"
                }
                """);
    }

    // ---- Cross-cutting --------------------------------------------------------------------------

    @Test
    void unsupportedParameter_throws() {
        assertThatThrownBy(() -> validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS",
                  "bogusField": "anything"
                }
                """)).isInstanceOf(UnsupportedParameterException.class);
    }

    @Test
    void allFourFieldsValid_passes() {
        validator.validateForUpdate("""
                {
                  "instrumentCode": "MNZL_STANDARD_LOAN",
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
                  "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
                  "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
                }
                """);
    }
}
