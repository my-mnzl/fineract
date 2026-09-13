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
import java.util.function.Consumer;

/**
 * Real-database coverage of complete-command binding; this is not a cross-day continuation protocol.
 */
final class ReceivablesHistoricalAuthorizationScenarios {

    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesHistoricalAuthorizationScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify(String database) throws Exception {
        var originalDate = harness.today;
        ObjectNode command = cash("bound-history");
        ObjectNode grant = harness.issueHistory(command);
        // The configured integration user is also the authenticated CONFIGURE user. Preserve this policy explicitly.
        assertThat(grant.path("approvedBy").asText()).isEqualTo("1");
        assertThat(harness.queryLong("select integration_user_id from m_mnzl_r_configuration where integration_user_id=1")).isEqualTo(1);
        assertThat(post("/authorizations/v2", grant, 200)).isEqualTo(grant);
        ObjectNode legacyRequest = grant.deepCopy();
        legacyRequest.remove("commandPayloadHash");
        assertThat(post("/authorizations", legacyRequest, 409).path("code").asText()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(post("/authorizations/v2", legacyRequest, 400).path("code").asText()).isEqualTo("INVALID_DATA");
        for (String field : List.of("scopeHash", "commandPayloadHash", "mode", "effectiveFrom", "effectiveThrough")) {
            ObjectNode changed = grant.deepCopy();
            changed.put(field, switch (field) {
                case "mode" -> "RECONSTRUCTION";
                case "effectiveFrom" -> harness.today.minusDays(1).toString();
                case "effectiveThrough" -> harness.today.plusDays(1).toString();
                default -> "1".repeat(64);
            });
            assertThat(post("/authorizations/v2", changed, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
        ObjectNode wrongIssuer = grant.deepCopy();
        wrongIssuer.put("approvedBy", "2");
        assertThat(post("/authorizations/v2", wrongIssuer, 409).path("code").asText()).isEqualTo("APPROVAL_SCOPE_CHANGED");
        var before = harness.counts();
        List<Consumer<ObjectNode>> changes = List.of(c -> c.put("operationId", "changed-operation"),
                c -> c.put("idempotencyKey", "changed-idempotency"), c -> c.put("subjectId", "changed-deal"),
                c -> c.put("actorId", "changed-actor"), c -> c.put("expectedVersion", "1"),
                c -> c.put("businessDate", harness.today.minusDays(1).toString()), c -> c.put("basisHash", "1".repeat(64)),
                c -> c.put("executionScopeHash", "1".repeat(64)),
                c -> c.set("approverIds", harness.json.value(List.of("changed-approver"))),
                c -> ((ObjectNode) c.get("executionAuthorization")).put("effectiveThrough", harness.today.plusDays(1).toString()),
                c -> ((ObjectNode) c.get("source")).put("amountMinor", "2"),
                c -> ((ObjectNode) c.get("source")).put("bankSourceId", "changed-bank-source"));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode changed = command.deepCopy();
            change.accept(changed);
            assertThat(post("/commands", changed, 409).path("code").asText()).isEqualTo("APPROVAL_SCOPE_CHANGED");
            assertThat(harness.counts()).isEqualTo(before);
        }
        ObjectNode wrongMapping = command.deepCopy();
        wrongMapping.put("accountMappingRevisionId", "changed-mapping");
        assertThat(post("/commands", wrongMapping, 409).path("code").asText()).isEqualTo("FINERACT_CAPABILITY_MISSING");
        ObjectNode wrongScope = command.deepCopy();
        ((ObjectNode) wrongScope.get("scope")).put("financierOrganizationId", "other-financier");
        assertThat(post("/commands", wrongScope, 403).path("code").asText()).isEqualTo("OWNERSHIP_CONFLICT");
        harness.executeSql("update m_mnzl_r_authorization set revoked=true where authorization_id='bound-history-grant'");
        assertThat(post("/commands", command, 409).path("code").asText()).isEqualTo("APPROVAL_SCOPE_CHANGED");
        harness.executeSql("update m_mnzl_r_authorization set revoked=false where authorization_id='bound-history-grant'");
        harness.moveDate(originalDate.plusDays(1));
        verifyRollback(command, database);
        JsonNode result = post("/commands", command, 200);
        assertThat(result.path("payloadHash").asText()).isEqualTo(harness.json.hash(command));
        assertThat(result.path("businessDate").asText()).isEqualTo(command.path("businessDate").asText());
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_command where operation_id='bound-history'")).isEqualTo(1);
        assertThat(harness.queryLong("select sum(cast(l.amount_minor as decimal(19,0))) from m_mnzl_r_journal_line l "
                + "join m_mnzl_r_event e on e.record_key=l.event_key join m_mnzl_r_command c on c.record_key=e.operation_key "
                + "where c.operation_id='bound-history'")).isEqualTo(2);
        var committed = harness.counts();
        harness.executeSql("update m_mnzl_r_authorization set revoked=true, command_payload_hash=null, payload_json=null "
                + "where authorization_id='bound-history-grant'");
        harness.moveDate(originalDate.plusDays(2));
        assertThat(post("/commands", command, 200)).isEqualTo(result);
        assertThat(harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/operations/bound-history", null, 200))
                .isEqualTo(result);
        assertThat(harness.counts()).isEqualTo(committed);
        ObjectNode conflict = command.deepCopy();
        conflict.put("actorId", "changed-after-commit");
        assertThat(post("/commands", conflict, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        verifyLegacyGrant();
        verifyDateAndMode();
        harness.moveDate(originalDate);
    }

    private void verifyDateAndMode() throws Exception {
        ObjectNode outOfRange = cash("history-outside-range");
        ObjectNode authorization = (ObjectNode) outOfRange.get("executionAuthorization");
        authorization.put("effectiveFrom", harness.today.plusDays(1).toString());
        authorization.put("effectiveThrough", harness.today.plusDays(1).toString());
        harness.issueHistory(outOfRange);
        var before = harness.counts();
        assertThat(post("/commands", outOfRange, 409).path("code").asText()).isEqualTo("APPROVAL_SCOPE_CHANGED");
        assertThat(harness.counts()).isEqualTo(before);
        ObjectNode reconstruction = cash("bound-reconstruction");
        reconstruction.put("executionMode", "RECONSTRUCTION");
        harness.issueHistory(reconstruction);
        JsonNode result = post("/commands", reconstruction, 200);
        assertThat(result.path("payloadHash").asText()).isEqualTo(harness.json.hash(reconstruction));
    }

    private void verifyLegacyGrant() throws Exception {
        ObjectNode command = cash("legacy-history");
        ObjectNode grant = harness.issueHistory(command);
        harness.executeSql("update m_mnzl_r_authorization set command_payload_hash=null, payload_json=null "
                + "where authorization_id='legacy-history-grant'");
        var before = harness.counts();
        assertThat(post("/commands", command, 409).path("code").asText()).isEqualTo("APPROVAL_SCOPE_CHANGED");
        assertThat(post("/authorizations/v2", grant, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(harness.counts()).isEqualTo(before);
    }

    private void verifyRollback(ObjectNode command, String database) throws Exception {
        var before = harness.counts();
        if (database.equals("postgresql")) {
            harness.executeSql("CREATE FUNCTION flex_fail_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION "
                    + "'flex-injected-history-failure'; END $$");
            harness.executeSql("CREATE TRIGGER flex_fail_history BEFORE INSERT ON m_mnzl_r_event FOR EACH ROW "
                    + "EXECUTE FUNCTION flex_fail_history()");
        } else {
            harness.executeSql("CREATE TRIGGER flex_fail_history BEFORE INSERT ON m_mnzl_r_event FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='flex-injected-history-failure'");
        }
        try {
            post("/commands", command, 500);
            assertThat(harness.counts()).isEqualTo(before);
        } finally {
            harness.executeSql(
                    database.equals("postgresql") ? "DROP TRIGGER flex_fail_history ON m_mnzl_r_event" : "DROP TRIGGER flex_fail_history");
            if (database.equals("postgresql")) {
                harness.executeSql("DROP FUNCTION flex_fail_history()");
            }
        }
    }

    private ObjectNode cash(String operation) throws Exception {
        ObjectNode command = harness.command("RECORD_CASH_MOVEMENT", operation, "history-deal", "DEAL");
        command.put("executionMode", "CORRECTION");
        command.set("executionAuthorization",
                harness.json.value(Map.of("authorizationId", operation + "-grant", "scopeHash", "0".repeat(64), "approvedBy", "1",
                        "effectiveFrom", harness.today.toString(), "effectiveThrough", harness.today.toString())));
        command.set("source", harness.json.value(Map.of("bankSourceId", operation + "-bank", "bankAccountReference", "test-bank",
                "verificationEvidenceId", "verified", "valueDate", harness.today.toString(), "currency", "EGP", "amountMinor", "1",
                "direction", "INCOMING", "allocations", List.of(Map.of("allocationId", operation + "-allocation", "kind",
                        "RECEIPT_UNAPPLIED", "dealId", "history-deal", "beneficiaryReferenceId", "financier", "amountMinor", "1")))));
        return command;
    }

    private JsonNode post(String path, JsonNode request, int status) throws Exception {
        return harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + path, request, status);
    }
}
