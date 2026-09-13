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

import static co.mnzl.fineract.custom.receivables.service.ReceivablesException.require;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Consumes an absent immutable collection intent once, with no changed account or previously used cash. */
@Service
@RequiredArgsConstructor
public class ReceivablesAbsentCollection {

    private final ReceivablesStore store;
    private final ReceivablesJson json;

    public void rejectConsumedOriginal(ReceivablesExecution e, String idempotencyHash) {
        require(store.jdbc().queryForObject(
                "select count(*) from m_mnzl_r_consumed_intent where scope_key=? " + "and (record_key=? or original_idempotency_hash=?)",
                Long.class, e.scope, e.operationKey, idempotencyHash) == 0, "IDEMPOTENCY_CONFLICT");
    }

    public void consume(ReceivablesExecution e) {
        if (!e.command.has("absentCommandContinuation")) {
            return;
        }
        require(text(e.command, "commandType").equals("COLLECT") && !text(e.command, "executionMode").equals("CURRENT"),
                "APPROVAL_SCOPE_CHANGED");
        JsonNode continuation = e.command.get("absentCommandContinuation");
        JsonNode original = json.validate("financialCommand", text(continuation, "originalCommandJson"));
        require(text(original, "commandType").equals("COLLECT") && text(original, "executionMode").equals("CURRENT")
                && original.get("executionAuthorization").isNull() && !original.has("absentCommandContinuation"), "APPROVAL_SCOPE_CHANGED");
        String originalHash = json.hash(original);
        require(originalHash.equals(text(continuation, "originalCommandHash")), "SOURCE_CHANGED");
        ObjectNode originalFinancial = original.deepCopy();
        ObjectNode continuedFinancial = e.command.deepCopy();
        List<String> envelope = List.of("operationId", "idempotencyKey", "executionMode", "executionAuthorization", "executionScopeHash",
                "actorId", "approverIds", "absentCommandContinuation");
        originalFinancial.remove(envelope);
        continuedFinancial.remove(envelope);
        require(originalFinancial.equals(continuedFinancial), "SOURCE_CHANGED");
        String originalOperation = ReceivablesStore.key(e.scope, "operation", text(original, "operationId"));
        String originalIdempotency = ReceivablesJson.hashText(text(original, "idempotencyKey"));
        require(!originalOperation.equals(e.operationKey)
                && !originalIdempotency.equals(ReceivablesJson.hashText(text(e.command, "idempotencyKey"))), "IDEMPOTENCY_CONFLICT");
        require(store.jdbc().queryForObject(
                "select count(*) from m_mnzl_r_command where scope_key=? " + "and (record_key=? or idempotency_hash=?)", Long.class,
                e.scope, originalOperation, originalIdempotency) == 0, "IDEMPOTENCY_CONFLICT");
        require(store.jdbc().queryForObject(
                "select count(*) from m_mnzl_r_consumed_intent where scope_key=? " + "and (record_key=? or original_idempotency_hash=?)",
                Long.class, e.scope, originalOperation, originalIdempotency) == 0, "IDEMPOTENCY_CONFLICT");
        var sourceKeys = new HashSet<String>();
        for (JsonNode allocation : original.get("allocations")) {
            sourceKeys.add(ReceivablesStore.key(e.scope, "cash", text(allocation, "cashMovementId")));
        }
        for (String sourceKey : sourceKeys) {
            var source = store.require("cash_source", sourceKey);
            require(e.date.toString().equals(string(source, "value_date")) && string(source, "direction").equals("INCOMING"),
                    "BANK_PROOF_MISMATCH");
            require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_collection where cash_source_key=?", Long.class,
                    sourceKey) == 0, "SOURCE_CHANGED");
            for (var allocation : store.children("cash_allocation", "source_key", sourceKey)) {
                if (string(allocation, "kind").equals("RECEIPT_UNAPPLIED")) {
                    require(ReceivablesMeasurement.amount(allocation, "used_minor").signum() == 0, "SOURCE_CHANGED");
                }
            }
        }
        store.insert("consumed_intent", originalOperation,
                Map.of("scope_key", e.scope, "original_idempotency_hash", originalIdempotency, "original_payload_hash", originalHash,
                        "original_request_json", json.write(original), "continuation_operation_key", e.operationKey,
                        "continuation_payload_hash", json.hash(e.command)));
    }
}
