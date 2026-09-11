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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReceivablesCommandService {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesMeasurement measurement;
    private final ReceivablesLedger ledger;
    private final ReceivablesAccountCommands accounts;
    private final ReceivablesCashCommands cash;
    private final ReceivablesCloseCommands close;
    private final ReceivablesHelCommands hel;
    private final PlatformSecurityContext security;

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JsonNode execute(String request) {
        security.authenticatedUser().validateHasPermissionTo("EXECUTE_MNZL_RECEIVABLES");
        JsonNode input = json.read(request);
        String type = text(input, "commandType");
        JsonNode command = json.validate(type.equals("FUND_HEL_TO_SETTLEMENT_CLEARING") ? "helFundingCommand"
                : type.equals("ALLOCATE_HEL_DEVELOPER_ADVANCE") ? "helAdvanceAllocationCommand" : "financialCommand", request);
        var config = configuration.authorize(command.get("scope"), true);
        var execution = new ReceivablesExecution(command, configuration.scopeKey(command.get("scope")), config);
        String payloadHash = json.hash(command);
        String idempotencyHash = ReceivablesJson.hashText(text(command, "idempotencyKey"));
        var existingKeys = store.jdbc().queryForList("select record_key from m_mnzl_r_command where scope_key=? and idempotency_hash=?",
                execution.scope, idempotencyHash);
        var existing = existingKeys.isEmpty() ? store.find("command", execution.operationKey)
                : store.require("command", string(existingKeys.getFirst(), "record_key"));
        if (existing != null) {
            require(payloadHash.equals(string(existing, "payload_hash"))
                    && text(command, "operationId").equals(string(existing, "operation_id")), "IDEMPOTENCY_CONFLICT");
            require(existing.get("result_json") != null, "RECOVERY_REQUIRED");
            return json.read(string(existing, "result_json"));
        }
        configuration.validateExecution(command, config);
        validateVersions(execution);
        Instant now = Instant.now();
        var fields = new LinkedHashMap<String, Object>();
        fields.put("scope_key", execution.scope);
        fields.put("operation_id", text(command, "operationId"));
        fields.put("idempotency_hash", idempotencyHash);
        fields.put("payload_hash", payloadHash);
        fields.put("command_type", text(command, "commandType"));
        fields.put("subject_key", execution.subjectKey());
        fields.put("expected_version", Long.parseLong(text(command, "expectedVersion")));
        fields.put("business_date", execution.date);
        fields.put("execution_scope_hash", text(command, "executionScopeHash"));
        fields.put("request_json", json.write(command));
        fields.put("result_json", null);
        fields.put("created_at", java.sql.Timestamp.from(now));
        store.insert("command", execution.operationKey, fields);
        switch (text(command, "commandType")) {
            case "FUND_HEL_TO_SETTLEMENT_CLEARING" -> hel.fund(execution);
            case "ALLOCATE_HEL_DEVELOPER_ADVANCE" -> hel.allocateAdvance(execution);
            case "RECORD_CASH_MOVEMENT" -> cash.recordCash(execution);
            case "RECORD_FUNDING_EVENT" -> cash.recordFunding(execution);
            case "BOOK_PURCHASE" -> accounts.book(execution);
            case "COLLECT" -> accounts.collect(execution, false);
            case "REVERSE_COLLECTION" -> accounts.reverse(execution);
            case "RESET_RATE" -> accounts.reset(execution);
            case "SETTLE_DEVELOPER_ADJUSTMENT" -> accounts.settleDeveloper(execution);
            case "SETTLE_RECEIVABLE" -> accounts.settle(execution, false);
            case "RECOURSE_RECOVERY" -> accounts.recourse(execution);
            case "SUBSTITUTE_RECEIVABLE" -> accounts.substitute(execution);
            case "WORKOUT" -> accounts.workout(execution);
            case "SET_IMPAIRMENT" -> accounts.impair(execution);
            case "RECOVER_WRITTEN_OFF" -> accounts.recoverWrittenOff(execution);
            case "SET_DEVELOPER_IMPAIRMENT" -> {
                accounts.account(execution);
                cash.impairDeveloper(execution);
            }
            case "CLOSE_PERIOD" -> close.close(execution);
            case "CORRECT_EVENT" -> close.correct(execution);
            default -> throw new ReceivablesException("INVALID_DATA");
        }
        if (!text(command, "subjectKind").equals("PERIOD")) {
            Set<String> covered = new HashSet<>();
            command.get("affectedAccountVersions").forEach(v -> covered.add(text(v, "accountId")));
            if (text(command, "subjectKind").equals("RECEIVABLE") && !type.equals("RECORD_CASH_MOVEMENT")) {
                covered.add(execution.subjectId());
            }
            for (String accountKey : execution.accountKeys) {
                var touched = store.require("account", accountKey);
                require(number(touched, "version") == 0 || covered.contains(string(touched, "external_id")), "ACCOUNT_VERSION_CHANGED");
            }
        }
        return finish(execution, payloadHash, now);
    }

    private void validateVersions(ReceivablesExecution e) {
        JsonNode c = e.command;
        String kind = text(c, "subjectKind");
        String commandType = text(c, "commandType");
        Set<String> allowed = switch (commandType) {
            case "RECORD_CASH_MOVEMENT", "SETTLE_DEVELOPER_ADJUSTMENT" -> Set.of("RECEIVABLE", "DEAL");
            case "RECORD_FUNDING_EVENT" -> Set.of("FUNDING_FACILITY");
            case "CLOSE_PERIOD" -> Set.of("PERIOD");
            case "FUND_HEL_TO_SETTLEMENT_CLEARING", "ALLOCATE_HEL_DEVELOPER_ADVANCE" -> Set.of("HEL_LOAN");
            default -> Set.of("RECEIVABLE");
        };
        require(allowed.contains(kind), "INVALID_DATA");
        long expected = Long.parseLong(text(c, "expectedVersion"));
        if (text(c, "commandType").equals("RECORD_CASH_MOVEMENT")) {
            require(expected == 0, "ACCOUNT_VERSION_CHANGED");
        } else if (kind.equals("RECEIVABLE")) {
            var account = store.find("account", e.accountKey(e.subjectId()));
            require(account == null ? expected == 0 : number(account, "version") == expected, "ACCOUNT_VERSION_CHANGED");
        } else if (kind.equals("FUNDING_FACILITY")) {
            var facility = store.find("funding_facility", ReceivablesStore.key(e.scope, "facility", e.subjectId()));
            require(facility == null ? expected == 0 : number(facility, "version") == expected, "ACCOUNT_VERSION_CHANGED");
        } else {
            long version = store.jdbc().queryForObject("select count(*) from m_mnzl_r_command where scope_key=? and subject_key=?",
                    Long.class, e.scope, e.subjectKey());
            require(expected == version, "ACCOUNT_VERSION_CHANGED");
        }
        for (JsonNode version : c.get("affectedAccountVersions")) {
            var account = store.require("account", e.accountKey(text(version, "accountId")));
            require(number(account, "version") == Long.parseLong(text(version, "expectedVersion")), "ACCOUNT_VERSION_CHANGED");
        }
    }

    private JsonNode finish(ReceivablesExecution e, String payloadHash, Instant now) {
        List<JsonNode> positions = new ArrayList<>();
        for (String accountKey : e.accountKeys) {
            var account = store.require("account", accountKey);
            store.update("account", accountKey, Map.of("version", number(account, "version") + 1, "last_event_key", e.eventKey,
                    "last_effective_date", e.date, "last_boundary_side", e.boundarySide));
            account = store.require("account", accountKey);
            positions.add(measurement.wirePosition(account, measurement.position(account, e.date),
                    ReceivablesMeasurement.amount(account, "allowance_minor"), e.boundarySide));
        }
        var journalLines = ledger.post(e.scope, e.eventKey, e.postingDate, number(e.configuration, "office_id"), e.lines);
        var event = json.object();
        event.put("calculationVersion", ReceivablesConfiguration.CALCULATION);
        event.put("productPolicyCode", ReceivablesConfiguration.POLICY);
        event.put("schemaVersion", "1");
        event.put("policyRevisionId", string(e.configuration, "policy_revision"));
        event.put("calculatorBuild", string(e.configuration, "calculator_build"));
        event.put("accountMappingRevisionId", string(e.configuration, "mapping_revision"));
        event.put("eventType", "flex.receivables.financial-event.v1");
        event.put("eventId", e.eventKey);
        event.set("scope", e.command.get("scope"));
        event.put("tenantId", configuration.tenantId());
        String commandType = text(e.command, "commandType");
        event.put("sourceKind",
                commandType.equals("FUND_HEL_TO_SETTLEMENT_CLEARING") ? "HEL"
                        : commandType.equals("CLOSE_PERIOD") ? "CLOSE"
                                : commandType.equals("RECORD_FUNDING_EVENT") ? "FUNDING"
                                        : commandType.equals("RECORD_CASH_MOVEMENT") ? "CASH" : "RECEIVABLE");
        if (e.command.has("dealId")) {
            event.put("dealId", text(e.command, "dealId"));
        } else if (!positions.isEmpty() && e.command.get("scope").has("developerOrganizationId")) {
            event.put("dealId", text(positions.getFirst(), "dealId"));
        }
        if (positions.size() == 1 && e.command.get("scope").has("developerOrganizationId")) {
            event.put("accountId", text(positions.getFirst(), "accountId"));
            event.put("accountSequence", text(positions.getFirst(), "accountVersion"));
        }
        event.set("allocationIds", json.value(e.allocationIds));
        event.put("operationId", text(e.command, "operationId"));
        event.put("causationId", text(e.command, "operationId"));
        event.put("businessDate", e.date.toString());
        if (e.originalValueDate != null) {
            event.put("originalValueDate", e.originalValueDate.toString());
        }
        event.put("postingPeriod", e.postingDate.toString().substring(0, 7));
        event.set("sourceTransactionIds", json.value(e.nativeTransactions));
        event.put("reversalOfEventId", e.reversalOf);
        event.put("correctionOfEventId", e.correctionOf);
        event.set("journalLines", json.value(journalLines));
        event.set("positionsAfter", json.value(positions));
        event.set("bankMovements", json.value(e.bankMovements));
        event.put("contentHash", json.hash(event));
        json.validate("financialEvent", json.write(event));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("scope_key", e.scope);
        fields.put("event_id", e.eventKey);
        fields.put("operation_key", e.operationKey);
        fields.put("account_key", positions.size() == 1 ? e.accountKeys.iterator().next() : null);
        fields.put("account_sequence", positions.size() == 1 ? Long.parseLong(text(positions.getFirst(), "accountVersion")) : null);
        fields.put("boundary_side", e.boundarySide);
        fields.put("business_date", e.date);
        fields.put("posting_period", e.postingDate.toString().substring(0, 7));
        fields.put("event_json", json.write(event));
        fields.put("content_hash", text(event, "contentHash"));
        fields.put("reversal_key", e.reversalOf);
        fields.put("correction_key", e.correctionOf);
        fields.put("created_at", java.sql.Timestamp.from(now));
        fields.put("delivered_at", null);
        store.insert("event", e.eventKey, fields);
        snapshot(e);
        long watermark = number(store.require("event", e.eventKey), "sequence_id");
        close.afterEvent(e, watermark);
        var result = json.object();
        for (String key : List.of("operationId", "commandType", "subjectKind", "subjectId", "scope", "businessDate", "expectedVersion",
                "affectedAccountVersions", "executionScopeHash", "actorId", "approverIds")) {
            result.set(key, e.command.get(key));
        }
        result.put("eventWatermark", Long.toString(watermark));
        result.put("payloadHash", payloadHash);
        result.put("state", "LEDGER_APPLIED");
        result.set("nativeTransactionIds", json.value(e.nativeTransactions));
        result.set("financialEventIds", json.value(List.of(e.eventKey)));
        result.putNull("reconciliationId");
        result.putNull("error");
        result.put("createdAt", now.toString());
        result.put("updatedAt", now.toString());
        JsonNode response = hel.finish(e, result);
        store.update("command", e.operationKey, Map.of("result_json", json.write(response)));
        return response;
    }

    private void snapshot(ReceivablesExecution e) {
        for (String key : e.lotKeys) {
            var lot = store.require("developer_lot", key);
            var account = store.require("account", string(lot, "account_key"));
            var value = json.object();
            value.put("lotId", string(lot, "lot_id"));
            value.put("accountId", string(account, "external_id"));
            value.put("dealId", string(account, "deal_id"));
            value.put("sourceEventId", ReceivablesStore.key(e.scope, "event", string(lot, "operation_key")));
            value.put("effectiveDate", string(lot, "effective_date"));
            value.put("dueDate", string(lot, "due_date"));
            value.put("direction", string(lot, "direction"));
            value.put("originalAmountMinor", string(lot, "initial_minor"));
            value.put("settledMinor", string(lot, "settled_minor"));
            value.put("accruedUnwindMinor", ReceivablesMeasurement.amount(lot, "carrying_minor")
                    .subtract(ReceivablesMeasurement.amount(lot, "initial_minor")).toString());
            value.put("outstandingMinor", ReceivablesMeasurement.amount(lot, "carrying_minor")
                    .subtract(ReceivablesMeasurement.amount(lot, "settled_minor")).toString());
            value.put("allowanceMinor", string(lot, "allowance_minor"));
            value.put("annualNominalRate", string(lot, "rate"));
            saveSnapshot(e, "LOT", key, value);
        }
        for (String key : e.facilityKeys) {
            var facility = store.require("funding_facility", key);
            var value = json.object();
            value.put("facilityId", string(facility, "facility_id"));
            value.put("principalMinor", string(facility, "principal_minor"));
            value.put("interestPayableMinor", string(facility, "interest_minor"));
            value.put("accruedExpenseMinor", string(facility, "accrued_expense_minor"));
            value.put("accruedThroughDate", string(facility, "watermark"));
            value.put("annualNominalRate", string(facility, "rate"));
            var ids = store.children("funding_event", "facility_key", key).stream()
                    .map(row -> ReceivablesStore.key(e.scope, "event", string(row, "operation_key"))).distinct().toList();
            value.set("sourceEventIds", json.value(ids));
            saveSnapshot(e, "FUNDING", key, value);
        }
    }

    private void saveSnapshot(ReceivablesExecution e, String kind, String subject, JsonNode value) {
        store.insert("state_snapshot", ReceivablesStore.key(e.scope, "snapshot", e.eventKey + ":" + subject),
                Map.of("scope_key", e.scope, "subject_kind", kind, "subject_key", subject, "event_key", e.eventKey, "business_date", e.date,
                        "snapshot_json", json.write(value)));
    }

}
