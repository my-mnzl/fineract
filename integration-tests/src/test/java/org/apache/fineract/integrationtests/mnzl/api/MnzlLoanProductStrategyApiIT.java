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
package org.apache.fineract.integrationtests.mnzl.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.Utils;
import org.junit.jupiter.api.Test;

public class MnzlLoanProductStrategyApiIT extends BaseLoanIntegrationTest {

    private static final String STRATEGIES_URL = "/fineract-provider/api/v1/mnzl/loan-products/%d/strategies?" + Utils.TENANT_IDENTIFIER;
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {}.getType();

    private final Gson gson = new Gson();

    @Test
    public void strategyCreateUpdateAndReadBackUsesMigratedTable() {
        Long productId = loanProductHelper.createLoanProduct(createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct()).getResourceId();

        update(productId, "MNZL_STANDARD_LOAN", "MNZL_DECLINING_BALANCE", "MNZL_INTEREST_AND_PENALTIES", "MNZL_DUE_INSTALLMENTS");
        assertStrategy(retrieve(productId), productId, "MNZL_STANDARD_LOAN", "MNZL_DECLINING_BALANCE", "MNZL_INTEREST_AND_PENALTIES",
                "MNZL_DUE_INSTALLMENTS");

        update(productId, "MNZL_BALLOON_LOAN", "CORE_DEFAULT", "CORE_DEFAULT", "CORE_DEFAULT");
        assertStrategy(retrieve(productId), productId, "MNZL_BALLOON_LOAN", "CORE_DEFAULT", "CORE_DEFAULT", "CORE_DEFAULT");
    }

    private void update(Long productId, String instrumentCode, String scheduleStrategyCode, String chargeStrategyCode,
            String cobStrategyCode) {
        Map<String, String> request = Map.of("instrumentCode", instrumentCode, "scheduleStrategyCode", scheduleStrategyCode,
                "chargeStrategyCode", chargeStrategyCode, "cobStrategyCode", cobStrategyCode);
        Utils.performServerPut(requestSpec, responseSpec, String.format(STRATEGIES_URL, productId), gson.toJson(request));
    }

    private Map<String, String> retrieve(Long productId) {
        String response = Utils.performServerGet(requestSpec, responseSpec, String.format(STRATEGIES_URL, productId), null);
        return gson.fromJson(response, STRING_MAP);
    }

    private void assertStrategy(Map<String, String> strategy, Long productId, String instrumentCode, String scheduleStrategyCode,
            String chargeStrategyCode, String cobStrategyCode) {
        assertThat(strategy.get("loanProductId")).isEqualTo(productId.toString());
        assertThat(strategy.get("instrumentCode")).isEqualTo(instrumentCode);
        assertThat(strategy.get("scheduleStrategyCode")).isEqualTo(scheduleStrategyCode);
        assertThat(strategy.get("chargeStrategyCode")).isEqualTo(chargeStrategyCode);
        assertThat(strategy.get("cobStrategyCode")).isEqualTo(cobStrategyCode);
    }
}
