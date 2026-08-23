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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MnzlSimulationApiJsonValidator {

    private static final Set<String> SUPPORTED_PARAMETERS = Set.of("name", "loanProductId", "principal", "interestRatePerPeriod",
            "interestRateDifferential", "numberOfRepayments", "repaymentEvery", "repaymentFrequencyType", "disbursementDate",
            "submittedOnDate", "approvedOnDate", "interestChargedFromDate", "firstRepaymentOnDate", "actions", "locale");

    private static final Set<String> VALID_ACTION_TYPES = Set.of("DISBURSE", "PAY", "SKIP", "RUN_COB", "ADD_CHARGE", "WRITE_OFF",
            "CHANGE_INTEREST_RATE");

    private final FromJsonHelper fromJsonHelper;

    public void validateForCreate(String json) {
        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("mnzlSimulation");
        final JsonObject root = parseRequest(json);

        final Long loanProductId = fromJsonHelper.extractLongNamed("loanProductId", root);
        validator.reset().parameter("loanProductId").value(loanProductId).notNull().longGreaterThanZero();

        final String principal = fromJsonHelper.extractStringNamed("principal", root);
        validator.reset().parameter("principal").value(principal).notBlank();

        // interestRatePerPeriod and interestRateDifferential are both optional —
        // when omitted, the runner uses defaults from the loan product.

        final Integer numberOfRepayments = fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", root);
        validator.reset().parameter("numberOfRepayments").value(numberOfRepayments).notNull().integerGreaterThanZero();
        validateRepaymentCadence(root, validator);

        final String disbursementDate = fromJsonHelper.extractStringNamed("disbursementDate", root);
        validator.reset().parameter("disbursementDate").value(disbursementDate).notBlank();

        // Validate actions array
        if (!root.has("actions") || !root.get("actions").isJsonArray()) {
            validator.reset().parameter("actions").failWithCode("must.be.a.non.empty.array");
        } else {
            JsonArray actions = root.getAsJsonArray("actions");
            if (actions.isEmpty()) {
                validator.reset().parameter("actions").failWithCode("must.be.a.non.empty.array");
            }
            for (int i = 0; i < actions.size(); i++) {
                JsonElement actionElement = actions.get(i);
                if (!actionElement.isJsonObject()) {
                    validator.reset().parameter("actions[" + i + "]").failWithCode("must.be.an.object");
                    continue;
                }
                JsonObject action = actionElement.getAsJsonObject();
                String actionType = extractString(action, "type");
                validator.reset().parameter("actions[" + i + "].type").value(actionType).notBlank();
                if (actionType != null && !VALID_ACTION_TYPES.contains(actionType.toUpperCase(Locale.ROOT))) {
                    validator.reset().parameter("actions[" + i + "].type").failWithCode("invalid.action.type");
                }

                String date = extractString(action, "date");
                validateActionDate(date, "actions[" + i + "].date", validator);

                if ("CHANGE_INTEREST_RATE".equalsIgnoreCase(actionType)) {
                    validateDecimal(action, "rate", "actions[" + i + "].rate", validator);
                }
                if ("ADD_CHARGE".equalsIgnoreCase(actionType)) {
                    validateLong(action, "chargeId", "actions[" + i + "].chargeId", validator);
                }
                if ("PAY".equalsIgnoreCase(actionType)) {
                    validateDecimal(action, "amount", "actions[" + i + "].amount", validator);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }

    /**
     * Validate the subset of fields needed for a schedule preview (no actions required).
     */
    public void validateForPreview(String json) {
        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("mnzlSimulation");
        final JsonObject root = parseRequest(json);

        final Long loanProductId = fromJsonHelper.extractLongNamed("loanProductId", root);
        validator.reset().parameter("loanProductId").value(loanProductId).notNull().longGreaterThanZero();

        final String principal = fromJsonHelper.extractStringNamed("principal", root);
        validator.reset().parameter("principal").value(principal).notBlank();

        final Integer numberOfRepayments = fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", root);
        validator.reset().parameter("numberOfRepayments").value(numberOfRepayments).notNull().integerGreaterThanZero();
        validateRepaymentCadence(root, validator);

        final String disbursementDate = fromJsonHelper.extractStringNamed("disbursementDate", root);
        validator.reset().parameter("disbursementDate").value(disbursementDate).notBlank();

        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }

    private void validateRepaymentCadence(JsonElement element, DataValidatorBuilder validator) {
        final Integer repaymentEvery = fromJsonHelper.extractIntegerWithLocaleNamed("repaymentEvery", element);
        validator.reset().parameter("repaymentEvery").value(repaymentEvery).ignoreIfNull().integerGreaterThanZero();

        final Integer repaymentFrequencyType = fromJsonHelper.extractIntegerWithLocaleNamed("repaymentFrequencyType", element);
        validator.reset().parameter("repaymentFrequencyType").value(repaymentFrequencyType).ignoreIfNull().inMinMaxRange(0, 3);
    }

    private String extractString(JsonObject object, String parameterName) {
        JsonElement value = extractPrimitive(object, parameterName);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private JsonElement extractPrimitive(JsonObject object, String parameterName) {
        JsonElement value = object.get(parameterName);
        return value != null && value.isJsonPrimitive() ? value : null;
    }

    private void validateActionDate(String value, String parameterName, DataValidatorBuilder validator) {
        validator.reset().parameter(parameterName).value(value).notBlank();
        if (StringUtils.isNotBlank(value)) {
            try {
                LocalDate.parse(value);
            } catch (DateTimeParseException exception) {
                validator.reset().parameter(parameterName).value(value).failWithCode("invalid.date.format");
            }
        }
    }

    private void validateDecimal(JsonObject object, String fieldName, String parameterName, DataValidatorBuilder validator) {
        JsonElement value = extractPrimitive(object, fieldName);
        validator.reset().parameter(parameterName).value(value).notNull();
        if (value != null) {
            try {
                value.getAsBigDecimal();
            } catch (NumberFormatException exception) {
                validator.reset().parameter(parameterName).value(value).failWithCode("must.be.a.number");
            }
        }
    }

    private void validateLong(JsonObject object, String fieldName, String parameterName, DataValidatorBuilder validator) {
        JsonElement value = extractPrimitive(object, fieldName);
        validator.reset().parameter(parameterName).value(value).notNull();
        if (value != null) {
            try {
                value.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                validator.reset().parameter(parameterName).value(value).failWithCode("must.be.an.integer");
            }
        }
    }

    private JsonObject parseRequest(String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }
        try {
            JsonElement element = fromJsonHelper.parse(json);
            if (element == null || !element.isJsonObject()) {
                throw new InvalidJsonException();
            }
            JsonObject root = element.getAsJsonObject();
            fromJsonHelper.checkForUnsupportedParameters(root, SUPPORTED_PARAMETERS);
            return root;
        } catch (JsonParseException exception) {
            throw new InvalidJsonException();
        }
    }
}
