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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Frozen phase evidence separates ordinary account accrual from a developer-only allowance decision. */
@Service
@RequiredArgsConstructor
public class ReceivablesDeveloperEffectProof {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesMeasurement measurement;
    private final ReceivablesAccountCommands accounts;
    private final ReceivablesCashCommands cash;

    public void execute(ReceivablesExecution e) {
        String type = text(e.command, "commandType");
        Set<String> accountKeys = new TreeSet<>();
        Set<String> payableKeys = new TreeSet<>();
        if (type.equals("SET_DEVELOPER_IMPAIRMENT")) {
            accountKeys.add(e.accountKey(e.subjectId()));
        } else if (type.equals("SETTLE_DEVELOPER_ADJUSTMENT")) {
            for (JsonNode requested : e.command.get("lots")) {
                accountKeys.add(string(store.require("developer_lot", ReceivablesStore.key(e.scope, "lot", text(requested, "lotId"))),
                        "account_key"));
            }
        } else {
            for (JsonNode allocation : e.command.get("source").get("allocations")) {
                if (allocation.has("accountId") && Set.of("DEVELOPER_PAYMENT", "RECEIPT_UNAPPLIED").contains(text(allocation, "kind"))) {
                    String accountKey = e.accountKey(text(allocation, "accountId"));
                    accountKeys.add(accountKey);
                    if (text(allocation, "kind").equals("DEVELOPER_PAYMENT")) {
                        payableKeys.add(accountKey);
                    }
                }
            }
            if (accountKeys.isEmpty()) {
                cash.recordCash(e);
                return;
            }
        }
        var frames = json.object();
        frames.set("priorPosted", frames(e, accountKeys));
        switch (type) {
            case "SET_DEVELOPER_IMPAIRMENT" -> accounts.account(e);
            case "SETTLE_DEVELOPER_ADJUSTMENT" -> accounts.prepareDeveloperSettlement(e);
            default -> {
                Set<String> covered = new TreeSet<>();
                e.command.get("affectedAccountVersions").forEach(v -> covered.add(e.accountKey(text(v, "accountId"))));
                require(covered.containsAll(payableKeys), "ACCOUNT_VERSION_CHANGED");
                payableKeys.forEach(key -> accounts.accrueDeveloperAccount(e, key));
            }
        }
        frames.set("afterAccrual", frames(e, accountKeys));
        e.developerEffectAccrualLineCount = e.lines.size();
        switch (type) {
            case "SET_DEVELOPER_IMPAIRMENT" -> cash.impairDeveloper(e);
            case "SETTLE_DEVELOPER_ADJUSTMENT" -> cash.settleDeveloper(e);
            default -> cash.recordCash(e);
        }
        frames.set("afterRequestedEffect", frames(e, accountKeys));
        if (type.equals("SET_DEVELOPER_IMPAIRMENT")) {
            require(frames.get("afterAccrual").get(0).get("accountPosition")
                    .equals(frames.get("afterRequestedEffect").get(0).get("accountPosition")), "JOURNAL_MISMATCH");
            JsonNode beforeLots = frames.get("afterAccrual").get(0).get("lots").deepCopy();
            JsonNode afterLots = frames.get("afterRequestedEffect").get(0).get("lots").deepCopy();
            for (JsonNode phaseLots : List.of(beforeLots, afterLots)) {
                for (JsonNode lot : phaseLots) {
                    if (text(lot.get("position"), "lotId").equals(text(e.command, "lotId"))) {
                        ((ObjectNode) lot.get("position")).remove("allowanceMinor");
                        ((ObjectNode) lot).remove("forecastHash");
                    }
                }
            }
            require(beforeLots.equals(afterLots), "JOURNAL_MISMATCH");
        }
        e.developerEffectFrames = frames;
    }

    private JsonNode frames(ReceivablesExecution e, Set<String> accountKeys) {
        return json.value(accountKeys.stream().map(key -> frame(e, key)).toList());
    }

    private JsonNode frame(ReceivablesExecution e, String accountKey) {
        var account = store.require("account", accountKey);
        var frame = json.object();
        LocalDate postedDate = LocalDate.parse(string(account, "last_effective_date"));
        frame.set("accountPosition", measurement.wirePosition(account, measurement.position(account, postedDate),
                ReceivablesMeasurement.amount(account, "allowance_minor"), string(account, "last_boundary_side")));
        var lots = frame.putArray("lots");
        store.children("developer_lot", "account_key", accountKey).stream().sorted(Comparator.comparing(lot -> string(lot, "lot_id")))
                .forEach(lot -> {
                    var item = lots.addObject();
                    item.set("position", ReceivablesDeveloperState.lot(json, lot, account));
                    item.put("forecastHash", lot.get("forecast_json") == null ? null : json.hash(json.read(string(lot, "forecast_json"))));
                });
        frame.put("contentHash", json.hash(frame));
        return frame;
    }

    public void persist(ReceivablesExecution e, String payloadHash, JsonNode event, List<JsonNode> journalLines, long watermark) {
        if (e.developerEffectFrames == null) {
            return;
        }
        var proof = json.object();
        proof.put("schemaVersion", "1");
        proof.put("tenantId", configuration.tenantId());
        proof.set("scope", e.command.get("scope"));
        proof.set("commandType", e.command.get("commandType"));
        proof.put("lotId", e.command.has("lotId") ? text(e.command, "lotId") : null);
        proof.put("operationId", text(e.command, "operationId"));
        proof.put("commandPayloadHash", payloadHash);
        var versions = proof.putArray("accountVersions");
        for (JsonNode frame : e.developerEffectFrames.get("priorPosted")) {
            JsonNode position = frame.get("accountPosition");
            var version = versions.addObject();
            version.set("accountId", position.get("accountId"));
            version.set("priorVersion", position.get("accountVersion"));
            version.put("resultingVersion",
                    Long.toString(number(store.require("account", e.accountKey(text(position, "accountId"))), "version")));
        }
        proof.put("businessDate", e.date.toString());
        proof.put("boundarySide", e.boundarySide);
        proof.put("accountMappingRevisionId", string(e.configuration, "mapping_revision"));
        proof.put("eventId", e.eventKey);
        proof.put("eventWatermark", Long.toString(watermark));
        proof.set("eventContentHash", event.get("contentHash"));
        proof.set("frames", e.developerEffectFrames);
        proof.set("ordinaryAccrual", phase(journalLines.subList(0, e.developerEffectAccrualLineCount)));
        proof.set("requestedEffect", phase(journalLines.subList(e.developerEffectAccrualLineCount, journalLines.size())));
        proof.put("contentHash", json.hash(proof));
        json.validate("developerEffectProof", json.write(proof));
        store.update("command", e.operationKey, Map.of("effect_proof_json", json.write(proof)));
    }

    private JsonNode phase(List<JsonNode> lines) {
        var phase = json.object();
        phase.set("journalLines", json.value(lines));
        BigInteger debit = BigInteger.ZERO;
        BigInteger credit = BigInteger.ZERO;
        for (JsonNode line : lines) {
            if (text(line, "side").equals("DEBIT")) {
                debit = debit.add(ReceivablesJson.minor(line, "amountMinor"));
            } else {
                credit = credit.add(ReceivablesJson.minor(line, "amountMinor"));
            }
        }
        require(debit.equals(credit), "JOURNAL_MISMATCH");
        phase.put("grossDebitMinor", debit.toString());
        phase.put("grossCreditMinor", credit.toString());
        phase.put("contentHash", json.hash(phase));
        return phase;
    }

    @Transactional(readOnly = true)
    public JsonNode read(JsonNode scope, String operationId) {
        var command = store.require("command", ReceivablesStore.key(configuration.scopeKey(scope), "operation", operationId));
        require(Set.of("SET_DEVELOPER_IMPAIRMENT", "SETTLE_DEVELOPER_ADJUSTMENT", "RECORD_CASH_MOVEMENT")
                .contains(string(command, "command_type")) && command.get("effect_proof_json") != null, "RECOVERY_REQUIRED");
        return json.read(string(command, "effect_proof_json"));
    }
}
