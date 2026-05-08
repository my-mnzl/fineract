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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * POST a simulation and block until it reaches a terminal state (COMPLETED or FAILED). The simulator runs the
     * scenario asynchronously, so a bare POST returns the record with {@code status="RUNNING"} before the lifecycle has
     * actually executed. Tests need the final snapshot list, so we poll the GET endpoint until the runner exits the
     * RUNNING state, then return that final response.
     */
    public Map<String, Object> run(Map<String, Object> body) {
        Map<String, Object> initial = postRaw(body);
        return awaitTerminal(initial);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String uuid) {
        String json = Utils.performServerGet(request, response, String.format(UUID_URL, uuid), null);
        return gson.fromJson(json, Map.class);
    }

    public List<Map<String, Object>> preview(Map<String, Object> body) {
        String json = Utils.performServerPost(request, response, PREVIEW_URL, gson.toJson(body));
        Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public Map<String, Object> rerun(String uuid) {
        Map<String, Object> initial = postRawRerun(uuid);
        return awaitTerminal(initial);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postRaw(Map<String, Object> body) {
        String json = Utils.performServerPost(request, response, BASE_URL, gson.toJson(body));
        return gson.fromJson(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postRawRerun(String uuid) {
        String json = Utils.performServerPost(request, response, String.format(RERUN_URL, uuid), "{}");
        return gson.fromJson(json, Map.class);
    }

    /**
     * Poll the simulation's GET endpoint until status leaves {@code RUNNING}. Returns the final response (the same
     * shape as the POST, plus a populated snapshots array on success). Times out after 60 seconds — simulations of the
     * size we drive in ITs typically complete within a few seconds.
     */
    private Map<String, Object> awaitTerminal(Map<String, Object> initial) {
        Object uuidObj = initial.get("uuid");
        if (uuidObj == null) {
            return initial;
        }
        String uuid = uuidObj.toString();
        long deadline = System.currentTimeMillis() + 60_000L;
        Map<String, Object> latest = initial;
        while (System.currentTimeMillis() < deadline) {
            Object status = latest.get("status");
            if (status != null && !"RUNNING".equals(status) && !"PENDING".equals(status)) {
                return latest;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return latest;
            }
            latest = get(uuid);
        }
        return latest;
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
            // The validator pulls numberOfRepayments via extractIntegerWithLocaleNamed which requires a "locale"
            // field on the JSON, and pulls principal via extractStringNamed which only accepts JSON string values.
            // We default both to keep happy-path requests valid; callers can still override via the setters below.
            body.put("locale", "en");
            body.put("actions", actions);
        }

        public ScenarioBuilder principal(double principal) {
            // Validator uses extractStringNamed("principal", ...) and notBlank() — must serialize as a JSON string.
            body.put("principal", BigDecimal.valueOf(principal).toPlainString());
            return this;
        }

        public ScenarioBuilder rate(double pct) {
            // interestRatePerPeriod is also commonly extracted as a locale-aware decimal string — send as string.
            body.put("interestRatePerPeriod", BigDecimal.valueOf(pct).toPlainString());
            return this;
        }

        public ScenarioBuilder repayments(int n) {
            body.put("numberOfRepayments", n);
            return this;
        }

        public ScenarioBuilder disburseDate(String d) {
            body.put("disbursementDate", toIso(d));
            return this;
        }

        public ScenarioBuilder firstRepaymentOn(String d) {
            body.put("firstRepaymentOnDate", toIso(d));
            return this;
        }

        public ScenarioBuilder interestChargedFrom(String d) {
            body.put("interestChargedFromDate", toIso(d));
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
            a.put("date", toIso(date));
            return a;
        }

        /**
         * Normalize a date string to ISO {@code yyyy-MM-dd} which the simulator's runner parses. Accepts the IT
         * framework's canonical {@code dd MMMM yyyy} ("01 January 2026") and already-ISO inputs.
         */
        private String toIso(String date) {
            if (date == null) {
                return null;
            }
            try {
                LocalDate.parse(date, ISO);
                return date;
            } catch (DateTimeParseException ignored) {
                // fall through to the human-readable parser
            }
            return LocalDate.parse(date, HUMAN).format(ISO);
        }

        public Map<String, Object> body() {
            return body;
        }

        public Map<String, Object> run() {
            return MnzlSimulationDriver.this.run(body);
        }
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
}
