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
package co.mnzl.fineract.custom.receivables.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;

/** Actual frozen purchased-population responses for independent SDK reader replay. */
final class ReceivablesPurchasedPopulationEvidence {

    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesPurchasedPopulationEvidence(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    JsonNode capture() throws Exception {
        ObjectNode result = harness.json.object();
        String watermark = get("/developer-lots").path("eventWatermark").asText();
        result.set("current", selection(harness.today, harness.today.withDayOfMonth(1), watermark));
        JsonNode empty = selection(LocalDate.of(1900, 1, 1), LocalDate.of(1900, 1, 1), watermark);
        assertThat(empty.path("members")).isEmpty();
        assertThat(empty.path("accountsPages")).hasSize(1);
        assertThat(empty.path("controls").path("activeAccountIds")).isEmpty();
        result.set("empty", empty);
        return result;
    }

    private JsonNode selection(LocalDate date, LocalDate start, String watermark) throws Exception {
        ObjectNode result = harness.json.object();
        result.putObject("selection").put("asOfDate", date.toString()).put("periodStartDate", start.toString())
                .put("eventWatermark", watermark).put("boundarySide", "AFTER_EVENTS");
        result.set("configuration", get("/configuration"));
        var pages = result.putArray("accountsPages");
        var members = result.putArray("members");
        String boundary = "?businessDate=" + date + "&boundarySide=AFTER_EVENTS&eventWatermark=" + watermark;
        String cursor = "";
        do {
            JsonNode page = get("/accounts?asOfDate=" + date + "&periodStartDate=" + start + "&eventWatermark=" + watermark + "&limit=1"
                    + (cursor.isEmpty() ? "" : "&cursor=" + cursor));
            pages.add(page);
            for (JsonNode account : page.path("items")) {
                ObjectNode member = members.addObject();
                member.set("account", account);
                String path = "/accounts/" + account.path("externalId").asText();
                member.set("position", get(path + "/position" + boundary));
                member.set("schedule", get(path + "/schedule" + boundary));
            }
            cursor = page.path("nextCursor").asText("");
        } while (!cursor.isEmpty());
        result.set("controls", get("/controls" + boundary));
        return result;
    }

    private JsonNode get(String path) throws Exception {
        return harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + path, null, 200);
    }
}
