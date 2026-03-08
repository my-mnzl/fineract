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

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mnzl.loan.instrument.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlLoanProductStrategyApiJsonValidator {

    private static final Set<String> SUPPORTED_PARAMETERS = Set.of("instrumentCode", "scheduleStrategyCode", "chargeStrategyCode",
            "cobStrategyCode");

    private final FromJsonHelper fromJsonHelper;

    public MnzlLoanProductStrategyApiJsonValidator(FromJsonHelper fromJsonHelper) {
        this.fromJsonHelper = fromJsonHelper;
    }

    public void validateForUpdate(String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        fromJsonHelper.checkForUnsupportedParameters(typeOfMap, json, SUPPORTED_PARAMETERS);

        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("mnzlLoanProductStrategy");
        final JsonElement element = fromJsonHelper.parse(json);

        validateCode(element, validator, "instrumentCode");
        validateCode(element, validator, "scheduleStrategyCode");
        validateCode(element, validator, "chargeStrategyCode");
        validateCode(element, validator, "cobStrategyCode");

        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }

    private void validateCode(JsonElement element, DataValidatorBuilder validator, String parameter) {
        final String value = fromJsonHelper.extractStringNamed(parameter, element);
        validator.reset().parameter(parameter).value(value).notBlank().notExceedingLengthOf(100);
    }
}
