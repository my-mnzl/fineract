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
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.integrationtests.common.Utils;

/** Wraps POST/GET/DELETE /v1/mnzl/simulations. Each public method returns the parsed JSON map for caller assertions. */
public final class MnzlSimulationDriver {

    private static final String BASE_URL = "/fineract-provider/api/v1/mnzl/simulations?" + Utils.TENANT_IDENTIFIER;
    private static final String UUID_URL = "/fineract-provider/api/v1/mnzl/simulations/%s?" + Utils.TENANT_IDENTIFIER;
    private static final String PREVIEW_URL = "/fineract-provider/api/v1/mnzl/simulations/preview-schedule?" + Utils.TENANT_IDENTIFIER;
    private static final String RERUN_URL = "/fineract-provider/api/v1/mnzl/simulations/%s/rerun?" + Utils.TENANT_IDENTIFIER;

    private final RequestSpecification request;
    private final ResponseSpecification response;
    private final Gson gson = new Gson();

    public MnzlSimulationDriver(RequestSpecification request, ResponseSpecification response) {
        this.request = request;
        this.response = response;
    }

    public ScenarioBuilder scenario(String name, Long loanProductId) {
        return new ScenarioBuilder(name, loanProductId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> body) {
        String json = Utils.performServerPost(request, response, BASE_URL, gson.toJson(body));
        return gson.fromJson(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String uuid) {
        String json = Utils.performServerGet(request, response, String.format(UUID_URL, uuid), null);
        return gson.fromJson(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(Map<String, Object> body) {
        String json = Utils.performServerPost(request, response, PREVIEW_URL, gson.toJson(body));
        return gson.fromJson(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> rerun(String uuid) {
        String json = Utils.performServerPost(request, response, String.format(RERUN_URL, uuid), "{}");
        return gson.fromJson(json, Map.class);
    }

    public void delete(String uuid) {
        Utils.performServerDelete(request, response, String.format(UUID_URL, uuid), null);
    }

    /** Fluent builder for SimulationRequest body. */
    public final class ScenarioBuilder {

        private final Map<String, Object> body = new HashMap<>();
        private final List<Map<String, Object>> actions = new ArrayList<>();

        ScenarioBuilder(String name, Long loanProductId) {
            body.put("name", name);
            body.put("loanProductId", loanProductId);
            body.put("actions", actions);
        }

        public ScenarioBuilder principal(double principal) {
            body.put("principal", BigDecimal.valueOf(principal));
            return this;
        }

        public ScenarioBuilder rate(double pct) {
            body.put("interestRatePerPeriod", BigDecimal.valueOf(pct));
            return this;
        }

        public ScenarioBuilder repayments(int n) {
            body.put("numberOfRepayments", n);
            return this;
        }

        public ScenarioBuilder disburseDate(String d) {
            body.put("disbursementDate", d);
            return this;
        }

        public ScenarioBuilder firstRepaymentOn(String d) {
            body.put("firstRepaymentOnDate", d);
            return this;
        }

        public ScenarioBuilder interestChargedFrom(String d) {
            body.put("interestChargedFromDate", d);
            return this;
        }

        public ScenarioBuilder disburse(String date) {
            return action("DISBURSE", date, null);
        }

        public ScenarioBuilder pay(String date, double amount) {
            Map<String, Object> a = baseAction("PAY", date);
            a.put("amount", amount);
            actions.add(a);
            return this;
        }

        public ScenarioBuilder skip(String date) {
            return action("SKIP", date, null);
        }

        public ScenarioBuilder runCob(String date) {
            return action("RUN_COB", date, null);
        }

        public ScenarioBuilder addCharge(String date, Long chargeId, Double amount) {
            Map<String, Object> a = baseAction("ADD_CHARGE", date);
            a.put("chargeId", chargeId);
            if (amount != null) {
                a.put("amount", amount);
            }
            actions.add(a);
            return this;
        }

        public ScenarioBuilder writeOff(String date) {
            return action("WRITE_OFF", date, null);
        }

        public ScenarioBuilder changeRate(String date, double newRate) {
            Map<String, Object> a = baseAction("CHANGE_INTEREST_RATE", date);
            a.put("rate", newRate);
            actions.add(a);
            return this;
        }

        private ScenarioBuilder action(String type, String date, Double amount) {
            Map<String, Object> a = baseAction(type, date);
            if (amount != null) {
                a.put("amount", amount);
            }
            actions.add(a);
            return this;
        }

        private Map<String, Object> baseAction(String type, String date) {
            Map<String, Object> a = new HashMap<>();
            a.put("type", type);
            a.put("date", date);
            return a;
        }

        public Map<String, Object> body() {
            return body;
        }

        public Map<String, Object> run() {
            return MnzlSimulationDriver.this.run(body);
        }
    }
}
