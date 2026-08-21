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
package org.apache.fineract.integrationtests.mnzl.helpers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.integrationtests.common.Utils;

/** Wraps PUT/GET /v1/mnzl/loan-products/{id}/strategies. */
public final class MnzlProductStrategyHelper {

    public static final String INSTRUMENT_STANDARD = "MNZL_STANDARD_LOAN";
    public static final String INSTRUMENT_BALLOON = "MNZL_BALLOON_LOAN";
    public static final String SCHEDULE_CORE = "CORE_DEFAULT";
    public static final String SCHEDULE_MNZL_DECLINING = "MNZL_DECLINING_BALANCE";
    public static final String CHARGE_CORE = "CORE_DEFAULT";
    public static final String CHARGE_MNZL_INTEREST_PENALTIES = "MNZL_INTEREST_AND_PENALTIES";
    public static final String COB_CORE = "CORE_DEFAULT";
    public static final String COB_MNZL_DUE = "MNZL_DUE_INSTALLMENTS";

    private static final String URL = "/fineract-provider/api/v1/mnzl/loan-products/%d/strategies?" + Utils.TENANT_IDENTIFIER;
    private final RequestSpecification request;
    private final ResponseSpecification response;

    public MnzlProductStrategyHelper(RequestSpecification request, ResponseSpecification response) {
        this.request = request;
        this.response = response;
    }

    public void setStrategy(Long productId, String instrumentCode, String scheduleCode, String chargeCode, String cobCode) {
        Map<String, String> body = new HashMap<>();
        body.put("instrumentCode", instrumentCode);
        body.put("scheduleStrategyCode", scheduleCode);
        body.put("chargeStrategyCode", chargeCode);
        body.put("cobStrategyCode", cobCode);
        Utils.performServerPut(request, response, String.format(URL, productId), new Gson().toJson(body));
    }

    /** Convenience: full mnzl override (declining balance + interest&penalties + custom COB). */
    public void setMnzl(Long productId) {
        setStrategy(productId, INSTRUMENT_STANDARD, SCHEDULE_MNZL_DECLINING, CHARGE_MNZL_INTEREST_PENALTIES, COB_MNZL_DUE);
    }

    /** Convenience: balloon variant. */
    public void setMnzlBalloon(Long productId) {
        setStrategy(productId, INSTRUMENT_BALLOON, SCHEDULE_MNZL_DECLINING, CHARGE_MNZL_INTEREST_PENALTIES, COB_MNZL_DUE);
    }

    /** Convenience: pin product to core/upstream behavior (for diff tests). */
    public void setCore(Long productId) {
        setStrategy(productId, INSTRUMENT_STANDARD, SCHEDULE_CORE, CHARGE_CORE, COB_CORE);
    }

    public Map<String, String> getStrategy(Long productId) {
        String body = Utils.performServerGet(request, response, String.format(URL, productId), null);
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();
        return new Gson().fromJson(body, mapType);
    }
}
