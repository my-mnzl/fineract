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
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mnzl.loan.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlSimulationApiJsonValidator {

    private static final Set<String> SUPPORTED_PARAMETERS = Set.of("name", "loanProductId", "principal", "interestRatePerPeriod",
            "interestRateDifferential", "numberOfRepayments", "disbursementDate", "submittedOnDate", "approvedOnDate",
            "interestChargedFromDate", "firstRepaymentOnDate", "actions", "locale");

    private static final Set<String> VALID_ACTION_TYPES = Set.of("DISBURSE", "PAY", "SKIP", "RUN_COB", "ADD_CHARGE", "WRITE_OFF",
            "CHANGE_INTEREST_RATE");

    private final FromJsonHelper fromJsonHelper;

    public void validateForCreate(String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        fromJsonHelper.checkForUnsupportedParameters(typeOfMap, json, SUPPORTED_PARAMETERS);

        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("mnzlSimulation");
        final JsonElement element = fromJsonHelper.parse(json);

        final Long loanProductId = fromJsonHelper.extractLongNamed("loanProductId", element);
        validator.reset().parameter("loanProductId").value(loanProductId).notNull().longGreaterThanZero();

        final String principal = fromJsonHelper.extractStringNamed("principal", element);
        validator.reset().parameter("principal").value(principal).notBlank();

        // interestRatePerPeriod and interestRateDifferential are both optional —
        // when omitted, the runner uses defaults from the loan product.

        final Integer numberOfRepayments = fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", element);
        validator.reset().parameter("numberOfRepayments").value(numberOfRepayments).notNull().integerGreaterThanZero();

        final String disbursementDate = fromJsonHelper.extractStringNamed("disbursementDate", element);
        validator.reset().parameter("disbursementDate").value(disbursementDate).notBlank();

        // Validate actions array
        JsonObject root = element.getAsJsonObject();
        if (!root.has("actions") || !root.get("actions").isJsonArray()) {
            validator.reset().parameter("actions").failWithCode("must.be.a.non.empty.array");
        } else {
            JsonArray actions = root.getAsJsonArray("actions");
            if (actions.isEmpty()) {
                validator.reset().parameter("actions").failWithCode("must.be.a.non.empty.array");
            }
            for (int i = 0; i < actions.size(); i++) {
                JsonObject action = actions.get(i).getAsJsonObject();
                String actionType = action.has("type") ? action.get("type").getAsString() : null;
                validator.reset().parameter("actions[" + i + "].type").value(actionType).notBlank();
                if (actionType != null && !VALID_ACTION_TYPES.contains(actionType.toUpperCase())) {
                    validator.reset().parameter("actions[" + i + "].type").failWithCode("invalid.action.type");
                }

                String date = action.has("date") ? action.get("date").getAsString() : null;
                validator.reset().parameter("actions[" + i + "].date").value(date).notBlank();

                if ("CHANGE_INTEREST_RATE".equalsIgnoreCase(actionType)) {
                    validator.reset().parameter("actions[" + i + "].rate").value(action.has("rate") ? action.get("rate") : null).notNull();
                }
                if ("ADD_CHARGE".equalsIgnoreCase(actionType)) {
                    validator.reset().parameter("actions[" + i + "].chargeId").value(action.has("chargeId") ? action.get("chargeId") : null)
                            .notNull();
                }
                if ("PAY".equalsIgnoreCase(actionType)) {
                    validator.reset().parameter("actions[" + i + "].amount").value(action.has("amount") ? action.get("amount") : null)
                            .notNull();
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
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        fromJsonHelper.checkForUnsupportedParameters(typeOfMap, json, SUPPORTED_PARAMETERS);

        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("mnzlSimulation");
        final JsonElement element = fromJsonHelper.parse(json);

        final Long loanProductId = fromJsonHelper.extractLongNamed("loanProductId", element);
        validator.reset().parameter("loanProductId").value(loanProductId).notNull().longGreaterThanZero();

        final String principal = fromJsonHelper.extractStringNamed("principal", element);
        validator.reset().parameter("principal").value(principal).notBlank();

        final Integer numberOfRepayments = fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", element);
        validator.reset().parameter("numberOfRepayments").value(numberOfRepayments).notNull().integerGreaterThanZero();

        final String disbursementDate = fromJsonHelper.extractStringNamed("disbursementDate", element);
        validator.reset().parameter("disbursementDate").value(disbursementDate).notBlank();

        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }
}
