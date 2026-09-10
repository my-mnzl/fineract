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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceivablesCloseCommands {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesAccountCommands accounts;
    private final ReceivablesCashCommands cash;
    private final ReceivablesReadService reads;

    public void close(ReceivablesExecution e) {
        require(!e.command.get("scope").has("developerOrganizationId"), "INVALID_DATA");
        LocalDate boundary = LocalDate.parse(text(e.command, "boundaryDate"));
        require(boundary.getDayOfMonth() == 1 && text(e.command, "periodId").equals(boundary.minusDays(1).toString().substring(0, 7)),
                "INVALID_DATA");
        String key = ReceivablesStore.key(e.scope, "period", text(e.command, "periodId"));
        var existing = store.find("period", key);
        if (text(e.command, "phase").equals("FINALIZE")) {
            require(existing != null && string(existing, "status").equals("PREPARING")
                    && number(existing, "event_watermark") == Long.parseLong(text(e.command, "eventWatermark"))
                    && string(existing, "cutoff_hash").equals(text(e.command, "sourceCutoffHash")), "SOURCE_CHANGED");
            require(e.command.get("approverIds").size() > 0, "APPROVAL_SCOPE_CHANGED");
            store.update("period", key, Map.of("status", "CLOSED", "reconciliation_id", text(e.command, "reconciliationId"), "locked_by",
                    text(e.command, "actorId"), "version", number(existing, "version") + 1));
            return;
        }
        require(existing == null, "PERIOD_CLOSED");
        require(Long.parseLong(text(e.command, "eventWatermark")) == reads.watermark(e.command.get("scope")), "SOURCE_CHANGED");
        e.date = boundary;
        e.postingDate = boundary.minusDays(1);
        e.boundarySide = "BEFORE_EVENTS";
        Set<String> forecastIds = new HashSet<>();
        e.command.get("forecastIds").forEach(f -> forecastIds.add(f.asText()));
        for (var account : store.scoped("account", e.scope)) {
            require(!LocalDate.parse(string(account, "last_effective_date")).isAfter(boundary), "SOURCE_CHANGED");
            if (LocalDate.parse(string(account, "activation_date")).isBefore(boundary)) {
                accounts.closeAccrue(e, account, forecastIds);
            }
        }
        for (var facility : store.scoped("funding_facility", e.scope)) {
            cash.accrueFunding(e, facility);
        }
        store.insert("period", key,
                Map.of("scope_key", e.scope, "period_id", text(e.command, "periodId"), "boundary_date", boundary, "status", "PREPARING",
                        "version", 1L, "event_watermark", 0L, "cutoff_hash", text(e.command, "sourceCutoffHash"), "reconciliation_id",
                        "PENDING", "snapshot_json", json.write(e.command)));
    }

    public void afterEvent(ReceivablesExecution e, long watermark) {
        if (!text(e.command, "commandType").equals("CLOSE_PERIOD") || !text(e.command, "phase").equals("PREPARE")) {
            return;
        }
        var controls = reads.controls(e.command.get("scope"), new ReceivablesReadService.Boundary(e.date, "BEFORE_EVENTS", watermark), null,
                null);
        for (var balance : controls.get("balances")) {
            require(text(balance, "differenceMinor").equals("0"), "JOURNAL_MISMATCH");
        }
        store.update("period", ReceivablesStore.key(e.scope, "period", text(e.command, "periodId")),
                Map.of("event_watermark", watermark, "snapshot_json", json.write(controls)));
    }

    public void correct(ReceivablesExecution e) {
        com.fasterxml.jackson.databind.node.ObjectNode correction = (com.fasterxml.jackson.databind.node.ObjectNode) e.command;
        require(text(correction, "executionMode").equals("CORRECTION"), "APPROVAL_SCOPE_CHANGED");
        var originalRow = store.require("event", text(correction, "originalEventId"));
        require(e.scope.equals(string(originalRow, "scope_key")), "OWNERSHIP_CONFLICT");
        var original = json.read(string(originalRow, "event_json"));
        var originalCommand = json.read(string(store.require("command", string(originalRow, "operation_key")), "request_json"));
        String family = text(originalCommand, "commandType");
        require(family.equals("COLLECT")
                || (family.equals("RECOURSE_RECOVERY") && text(originalCommand, "recoveryKind").equals("DUE_REIMBURSEMENT")),
                "RECOVERY_REQUIRED");
        require(text(originalCommand, "subjectId").equals(e.subjectId()) && originalCommand.get("scope").equals(correction.get("scope")),
                "OWNERSHIP_CONFLICT");
        require(text(original, "businessDate").equals(text(correction, "originalValueDate")), "SOURCE_CHANGED");
        Set<String> selected = new HashSet<>();
        correction.get("sourceLineIds").forEach(id -> selected.add(id.asText()));
        Set<String> eligible = new HashSet<>();
        java.math.BigInteger allowance = java.math.BigInteger.ZERO;
        for (var line : original.get("journalLines")) {
            String account = text(line, "accountKey");
            String side = text(line, "side");
            if (text(line, "component").equals("CASH") || (account.equals("lossAllowance") && side.equals("DEBIT"))
                    || (account.equals("impairmentExpense") && side.equals("CREDIT"))) {
                eligible.add(text(line, "sourceLineId"));
            }
            if (account.equals("lossAllowance") && side.equals("DEBIT")) {
                allowance = allowance.add(ReceivablesJson.minor(line, "amountMinor"));
            }
        }
        require(!eligible.isEmpty() && selected.equals(eligible) && selected.size() == correction.get("sourceLineIds").size(),
                "SOURCE_CHANGED");
        var replacement = correction.get("replacementCommand");
        require(text(replacement, "commandType").equals(family) && text(replacement, "subjectId").equals(e.subjectId())
                && replacement.get("scope").equals(correction.get("scope"))
                && text(replacement, "businessDate").equals(text(correction, "businessDate"))
                && text(replacement, "operationId").equals(text(correction, "replacementCommandOperationId"))
                && text(replacement, "operationId").equals(text(correction, "operationId") + ":replacement")
                && replacement.get("affectedAccountVersions").equals(correction.get("affectedAccountVersions"))
                && text(replacement, "expectedVersion").equals(text(correction, "expectedVersion"))
                && replacement.get("approverIds").equals(correction.get("approverIds"))
                && text(replacement, "actorId").equals(text(correction, "actorId")), "APPROVAL_SCOPE_CHANGED");
        require(family.equals("COLLECT") || text(replacement, "recoveryKind").equals("DUE_REIMBURSEMENT"), "RECOVERY_REQUIRED");
        require(replacement.has("riskForecastAfter"), "EVIDENCE_EXPIRED");
        require(store.find("command", ReceivablesStore.key(e.scope, "operation", text(replacement, "operationId"))) == null,
                "IDEMPOTENCY_CONFLICT");
        var reverse = correction.deepCopy();
        reverse.put("commandType", "REVERSE_COLLECTION");
        reverse.put("originalOperationId", text(originalCommand, "operationId"));
        require(original.get("sourceTransactionIds").size() == 1, "RECOVERY_REQUIRED");
        reverse.put("nativeTransactionId", original.get("sourceTransactionIds").get(0).asText());
        var ids = json.object().putArray("ids");
        originalCommand.get("allocations").forEach(a -> ids.add(text(a, "allocationId")));
        reverse.set("allocationIds", ids);
        e.command = reverse;
        e.originalValueDate = LocalDate.parse(text(correction, "originalValueDate"));
        try {
            accounts.reverse(e);
            var account = store.require("account", e.accountKey(e.subjectId()));
            ReceivablesLedger.pair(e.lines, "impairmentExpense", "lossAllowance", allowance, e.subjectId(), string(account, "deal_id"),
                    "IMPAIRMENT");
            store.update("account", e.accountKey(e.subjectId()),
                    Map.of("allowance_minor", ReceivablesMeasurement.amount(account, "allowance_minor").add(allowance).toString()));
            e.command = replacement;
            accounts.collect(e, family.equals("RECOURSE_RECOVERY"));
        } finally {
            e.command = correction;
        }
        e.correctionOf = text(correction, "originalEventId");
    }
}
