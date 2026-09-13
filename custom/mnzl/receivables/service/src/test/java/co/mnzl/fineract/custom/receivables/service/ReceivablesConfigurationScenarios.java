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
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Exercises the real migration, HTTP authorization, transaction locks and historical ledger after configuration
 * changes.
 */
final class ReceivablesConfigurationScenarios {

    private static final String BASE = ReceivablesDatabaseIntegrationTest.PREFIX;
    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesConfigurationScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        String requestBefore = harness.queryText("select request_json from m_mnzl_r_command where operation_id='account-1-book'");
        String eventsBefore = harness.queryText("select event_json from m_mnzl_r_event where operation_key="
                + "(select record_key from m_mnzl_r_command where operation_id='account-1-book')");
        harness.migrateLegacyConfiguration();
        assertThat(harness.queryText("select request_json from m_mnzl_r_command where operation_id='account-1-book'"))
                .isEqualTo(requestBefore);
        assertThat(harness.queryText("select event_json from m_mnzl_r_event where operation_key="
                + "(select record_key from m_mnzl_r_command where operation_id='account-1-book')")).isEqualTo(eventsBefore);
        JsonNode original = harness.request("GET", BASE + "/configuration", null, 200);
        JsonNode active = harness.request("GET", BASE + "/configuration/active", null, 200);
        assertThat(active.path("configuration")).isEqualTo(original);
        assertThat(active.path("contentHash").asText()).isEqualTo(harness.json.hash(original));
        JsonNode runtime = harness.request("GET", BASE + "/configuration/runtime", null, 200);
        assertThat(runtime.path("sourceRevision").asText()).matches("[a-f0-9]{40}");
        assertThat(runtime.path("openApiSha256").asText()).matches("[a-f0-9]{64}");
        String helOperation = harness.queryText("select operation_key from m_mnzl_r_hel_funding limit 1");
        String helLoan = harness.queryText("select loan_id from m_mnzl_r_hel_funding where operation_key=?", helOperation);
        JsonNode helResult = harness.json
                .read(harness.queryText("select result_json from m_mnzl_r_hel_funding where operation_key=?", helOperation));
        String helPath = BASE + "/hel-journals?loanId=" + helLoan;
        for (JsonNode id : helResult.path("nativeTransactionIds")) {
            helPath += "&transactionIds=" + id.asText();
        }
        helPath += "&eventWatermark=" + harness.queryLong("select max(sequence_id) from m_mnzl_r_event");
        JsonNode helBefore = harness.request("GET", helPath, null, 200);
        ObjectNode changed = original.deepCopy();
        changed.put("calculatorBuild", "build-next");
        assertThat(harness.request("POST", BASE + "/configuration/revisions", changed, 409).path("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        changed.put("accountMappingRevisionId", "mapping-next");
        JsonNode staged = harness.request("POST", BASE + "/configuration/revisions", changed, 200);
        var reversed = new ArrayList<JsonNode>();
        changed.path("accountMap").forEach(reversed::add);
        Collections.reverse(reversed);
        changed.set("accountMap", harness.json.value(reversed));
        assertThat(harness.request("POST", BASE + "/configuration/revisions", changed, 200)).isEqualTo(staged);
        ObjectNode activation = activation("activate-next", "mapping-next", staged, "mapping-1");
        String period = harness.queryText("select record_key from m_mnzl_r_period limit 1");
        String periodStatus = harness.queryText("select status from m_mnzl_r_period where record_key=?", period);
        harness.executeSql("update m_mnzl_r_period set status='PREPARING' where record_key=?", period);
        try {
            assertThat(harness.request("POST", BASE + "/configuration/activations", activation, 409).path("code").asText())
                    .isEqualTo("PERIOD_CLOSED");
        } finally {
            harness.executeSql("update m_mnzl_r_period set status=? where record_key=?", periodStatus, period);
        }
        JsonNode activated = harness.request("POST", BASE + "/configuration/activations", activation, 200);
        assertThat(harness.request("GET", BASE + "/configuration", null, 200)).isEqualTo(original);
        var command = harness.json.read(requestBefore);
        JsonNode replay = harness.request("POST", BASE + "/commands", command, 200);
        assertThat(replay.path("payloadHash").asText()).isEqualTo(harness.json.hash(command));
        ObjectNode stale = command.deepCopy();
        stale.put("operationId", "stale-after-activation").put("idempotencyKey", "stale-after-activation");
        assertThat(harness.request("POST", BASE + "/commands", stale, 409).path("code").asText()).isEqualTo("FINERACT_CAPABILITY_MISSING");
        ObjectNode fresh = harness.receiptCommand("cash-new-configuration", "account-1", "1");
        fresh.put("accountMappingRevisionId", "mapping-next");
        JsonNode freshResult = harness.request("POST", BASE + "/commands", fresh, 200);
        String eventId = freshResult.path("financialEventIds").get(0).asText();
        JsonNode freshEvent = harness.json.read(harness.queryText("select event_json from m_mnzl_r_event where record_key=?", eventId));
        assertThat(freshEvent.path("accountMappingRevisionId").asText()).isEqualTo("mapping-next");
        assertThat(freshEvent.path("calculatorBuild").asText()).isEqualTo("build-next");
        assertThat(harness.queryText(
                "select l.native_gl_id from m_mnzl_r_journal_line l join acc_gl_journal_entry j "
                        + "on j.id=l.native_journal_id and j.account_id=l.native_gl_id where l.event_key=? and l.semantic_account='bank'",
                eventId)).isEqualTo(Long.toString(harness.accounts.get("bank")));
        assertThat(harness.request("GET", helPath, null, 200)).isEqualTo(helBefore);
        new ReceivablesPeriodProofScenarios(harness).verify();
        for (String field : new String[] { "bankAccountReference", "officeId", "productId", "helPaymentTypeId", "helProductId" }) {
            ObjectNode forbidden = original.deepCopy();
            forbidden.put("accountMappingRevisionId", "forbidden-" + field);
            forbidden.put(field, field.equals("bankAccountReference") ? "changed-bank" : "999999999");
            harness.request("POST", BASE + "/configuration/revisions", forbidden, 409);
        }
        long otherOffice = harness.request("POST", "/offices", harness.json.value(java.util.Map.of("name", "Other Flex office", "parentId",
                1, "openingDate", harness.today.toString(), "dateFormat", "yyyy-MM-dd", "locale", "en")), 200).path("officeId").asLong();
        if (otherOffice == 0) {
            otherOffice = harness.queryLong("select id from m_office where name='Other Flex office'");
        }
        ObjectNode movedOffice = original.deepCopy();
        movedOffice.put("accountMappingRevisionId", "forbidden-valid-office").put("officeId", Long.toString(otherOffice));
        harness.request("POST", BASE + "/configuration/revisions", movedOffice, 409);
        long otherPayment = harness.request("POST", "/paymenttypes", harness.json.value(java.util.Map.of("name", "Other noncash route",
                "isCashPayment", false, "position", 99, "description", "Lifecycle freeze test")), 200).path("resourceId").asLong();
        harness.executeSql(
                "insert into acc_product_mapping (gl_account_id,product_id,product_type,financial_account_type,payment_type) "
                        + "select gl_account_id,product_id,product_type,financial_account_type,? from acc_product_mapping "
                        + "where product_id=? and product_type=1 and financial_account_type=1 and payment_type=?",
                otherPayment, Long.parseLong(original.path("helProductId").asText()),
                Long.parseLong(original.path("helPaymentTypeId").asText()));
        ObjectNode rerouted = original.deepCopy();
        rerouted.put("accountMappingRevisionId", "forbidden-valid-route").put("helPaymentTypeId", Long.toString(otherPayment));
        harness.request("POST", BASE + "/configuration/revisions", rerouted, 409);
        ObjectNode remapped = original.deepCopy();
        remapped.put("accountMappingRevisionId", "forbidden-map");
        ((ObjectNode) remapped.path("accountMap").get(0)).set("nativeGlAccountId",
                remapped.path("accountMap").get(1).get("nativeGlAccountId"));
        harness.request("POST", BASE + "/configuration/revisions", remapped, 409);
        ObjectNode left = original.deepCopy();
        left.put("accountMappingRevisionId", "mapping-left");
        ObjectNode right = original.deepCopy();
        right.put("accountMappingRevisionId", "mapping-right");
        var leftStage = harness.request("POST", BASE + "/configuration/revisions", left, 200);
        var rightStage = harness.request("POST", BASE + "/configuration/revisions", right, 200);
        var leftRequest = activation("activate-left", "mapping-left", leftStage, "mapping-next");
        var rightRequest = activation("activate-right", "mapping-right", rightStage, "mapping-next");
        var first = CompletableFuture.supplyAsync(() -> activateRace(leftRequest));
        var second = CompletableFuture.supplyAsync(() -> activateRace(rightRequest));
        var results = java.util.List.of(first.get(), second.get());
        assertThat(results.stream().filter(row -> row.has("activationId")).count()).isEqualTo(1);
        assertThat(results.stream().filter(row -> row.path("code").asText().equals("ACCOUNT_VERSION_CHANGED")).count()).isEqualTo(1);
        JsonNode winner = harness.request("GET", BASE + "/configuration/active", null, 200);
        assertThat(harness.request("POST", BASE + "/configuration/activations", activation, 200)).isEqualTo(activated);
        assertThat(harness.request("GET", BASE + "/configuration/active", null, 200)).isEqualTo(winner);
        var retired = activation("reactivate-retired", "mapping-1", active,
                winner.path("configuration").path("accountMappingRevisionId").asText());
        harness.request("POST", BASE + "/configuration/activations", retired, 409);
        // Persisted historical mapping remains authoritative even when the requested read token is a newer revision.
        String current = winner.path("configuration").path("accountMappingRevisionId").asText();
        assertThat(harness.requestMapping("GET", BASE + "/configuration", null, current, 200).path("accountMappingRevisionId").asText())
                .isEqualTo(current);
        String eventPeriod = harness.queryText("select posting_period from m_mnzl_r_event where operation_key="
                + "(select record_key from m_mnzl_r_command where operation_id='account-1-book')");
        long watermark = harness.queryLong("select max(sequence_id) from m_mnzl_r_event");
        String proofPath = BASE + "/period-activity-proof?postingPeriod=" + eventPeriod + "&eventWatermark=" + watermark;
        harness.requestMapping("GET", proofPath, null, current, 200);
        String historicalJson = harness
                .queryText("select config_json from m_mnzl_r_configuration_revision where mapping_revision='mapping-1'");
        ObjectNode damaged = original.deepCopy();
        for (JsonNode entry : damaged.path("accountMap")) {
            if (entry.path("accountKey").asText().equals("contractualReceivable")) {
                ((ObjectNode) entry).put("nativeGlAccountId", Long.toString(harness.accounts.get("bank")));
            }
        }
        harness.executeSql("update m_mnzl_r_configuration_revision set config_json=? where mapping_revision='mapping-1'",
                harness.json.write(damaged));
        try {
            assertThat(harness.requestMapping("GET", proofPath, null, current, 409).path("code").asText()).isEqualTo("JOURNAL_MISMATCH");
        } finally {
            harness.executeSql("update m_mnzl_r_configuration_revision set config_json=? where mapping_revision='mapping-1'",
                    historicalJson);
        }

    }

    private ObjectNode activation(String id, String target, JsonNode stage, String previous) {
        return harness.json.object().put("activationId", id).put("accountMappingRevisionId", target)
                .put("contentHash", stage.path("contentHash").asText()).put("expectedActiveAccountMappingRevisionId", previous);
    }

    private JsonNode activateRace(ObjectNode request) {
        try {
            return harness.request("POST", BASE + "/configuration/activations", request, 200, 409);
        } catch (Exception exception) {
            throw new java.util.concurrent.CompletionException(exception);
        }
    }
}
