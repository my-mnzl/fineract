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
import java.util.List;
import java.util.Map;

/** MNZL is the only lender: requests may omit the native scope, in headers and bodies, during and after the rollout. */
final class ReceivablesScopelessScenarios {

    private static final String PREFIX = ReceivablesDatabaseIntegrationTest.PREFIX;
    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesScopelessScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify(JsonNode booked) throws Exception {
        JsonNode context = harness.request("GET", PREFIX + "/context", null, 200);
        JsonNode account = harness.request("GET", PREFIX + "/accounts/account-1", null, 200);
        JsonNode operation = harness.request("GET", PREFIX + "/operations/account-1-book", null, 200);
        var before = harness.counts();
        harness.scopeHeaders = false;
        try {
            // Reads resolve the only configured scope and still return it.
            assertThat(harness.request("GET", PREFIX + "/context", null, 200)).isEqualTo(context);
            assertThat(context.path("scope")).isEqualTo(harness.scope(false));
            assertThat(harness.request("GET", PREFIX + "/accounts/account-1", null, 200)).isEqualTo(account);
            assertThat(harness.request("GET", PREFIX + "/operations/account-1-book", null, 200)).isEqualTo(operation);

            // A command that still carries the scope replays exactly without headers.
            ObjectNode command = harness.bookingCommands.get("account-1");
            assertThat(harness.request("POST", PREFIX + "/commands", command, 200)).isEqualTo(booked);

            // The payload hash covers the request as sent, so dropping the scope from a booked command is a different
            // payload.
            ObjectNode unscoped = command.deepCopy();
            unscoped.set("scope", harness.json.object().put("developerOrganizationId", "developer"));
            assertThat(harness.request("POST", PREFIX + "/commands", unscoped, 409).path("code").asText())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");

            // A new command without the scope reaches account validation under the configured scope.
            ObjectNode reset = harness.command("RESET_RATE", "scopeless-reset", "account-1", "RECEIVABLE");
            reset.set("scope", harness.json.object().put("developerOrganizationId", "developer"));
            reset.put("corridorObservationId", "scopeless-rate");
            reset.put("corridorRate", "0.30");
            reset.put("spread", "0");
            reset.put("developerAdjustmentDueDate", harness.today.plusDays(45).toString());
            assertThat(harness.request("POST", PREFIX + "/commands", reset, 409).path("code").asText())
                    .isEqualTo("ACCOUNT_VERSION_CHANGED");
            // A developer the account does not belong to is still refused.
            reset.put("expectedVersion", "1");
            ((ObjectNode) reset.get("scope")).put("developerOrganizationId", "other-developer");
            assertThat(harness.request("POST", PREFIX + "/commands", reset, 403).path("code").asText()).isEqualTo("OWNERSHIP_CONFLICT");
        } finally {
            harness.scopeHeaders = true;
        }
        assertThat(harness.counts()).isEqualTo(before);
    }

    /**
     * A grant the app issued over the full-scope command authorizes the same command sent without the native scope. The
     * command posts under the completed scope, its operation echoes the scope as sent, and it replays exactly.
     */
    void verifyAuthorizedCommand() throws Exception {
        ObjectNode command = harness.command("RECORD_CASH_MOVEMENT", "scopeless-history", "history-deal", "DEAL");
        command.put("executionMode", "CORRECTION");
        command.set("executionAuthorization",
                harness.json.value(Map.of("authorizationId", "scopeless-history-grant", "scopeHash", "0".repeat(64), "approvedBy", "1",
                        "effectiveFrom", harness.today.toString(), "effectiveThrough", harness.today.toString())));
        command.set("source", harness.json.value(Map.of("bankSourceId", "scopeless-history-bank", "bankAccountReference", "test-bank",
                "verificationEvidenceId", "verified", "valueDate", harness.today.toString(), "currency", "EGP", "amountMinor", "1",
                "direction", "INCOMING", "allocations", List.of(Map.of("allocationId", "scopeless-history-allocation", "kind",
                        "RECEIPT_UNAPPLIED", "dealId", "history-deal", "beneficiaryReferenceId", "financier", "amountMinor", "1")))));
        harness.issueHistory(command);
        ObjectNode unscoped = command.deepCopy();
        ObjectNode sentScope = harness.json.object().put("developerOrganizationId", "developer");
        unscoped.set("scope", sentScope);
        var before = harness.counts();
        harness.scopeHeaders = false;
        try {
            // The grant binds the amount: a changed payload is refused without reissuing it.
            ObjectNode changed = unscoped.deepCopy();
            ((ObjectNode) changed.get("source")).put("amountMinor", "2");
            ((ObjectNode) changed.get("source").get("allocations").get(0)).put("amountMinor", "2");
            assertThat(harness.request("POST", PREFIX + "/commands", changed, 409).path("code").asText())
                    .isEqualTo("APPROVAL_SCOPE_CHANGED");
            assertThat(harness.counts()).isEqualTo(before);

            JsonNode result = harness.request("POST", PREFIX + "/commands", unscoped, 200);
            assertThat(result.path("payloadHash").asText()).isEqualTo(harness.json.hash(unscoped)).isNotEqualTo(harness.json.hash(command));
            assertThat(result.path("scope")).isEqualTo(sentScope);
            assertThat(
                    harness.json.read(harness.queryText("select request_json from m_mnzl_r_command where operation_id='scopeless-history'"))
                            .path("scope"))
                    .isEqualTo(harness.scope(true));
            String event = result.path("financialEventIds").get(0).asText();
            assertThat(
                    harness.json.read(harness.queryText("select event_json from m_mnzl_r_event where record_key=?", event)).path("scope"))
                    .isEqualTo(harness.scope(true));
            var committed = harness.counts();
            assertThat(harness.request("POST", PREFIX + "/commands", unscoped, 200)).isEqualTo(result);
            assertThat(harness.request("GET", PREFIX + "/operations/scopeless-history", null, 200)).isEqualTo(result);
            assertThat(harness.counts()).isEqualTo(committed);
        } finally {
            harness.scopeHeaders = true;
        }
    }
}
