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

import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeHelBridge;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge;
import co.mnzl.fineract.receivables.math.ReceivablesMath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.NotFoundException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReceivablesReadService {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesMeasurement measurement;
    private final NativeReceivableBridge nativeBridge;
    private final NativeHelBridge hel;
    private final PlatformSecurityContext security;

    public record Boundary(LocalDate date, String side, long watermark) {

        public Boundary {
            require(date != null && Set.of("BEFORE_EVENTS", "AFTER_EVENTS").contains(side) && watermark >= 0, "INVALID_DATA");
        }

        String comparison() {
            return side.equals("BEFORE_EVENTS") ? "<" : "<=";
        }
    }

    public JsonNode authorize(String platform, String financier, String environment, String epoch, String mapping) {
        security.authenticatedUser().validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        ObjectNode scope = json.object();
        for (String value : new String[] { platform, financier, environment, epoch, mapping }) {
            require(value != null && !value.isBlank(), "INVALID_DATA");
        }
        scope.put("platformId", platform);
        scope.put("financierOrganizationId", financier);
        scope.put("environment", environment);
        scope.put("ledgerEpoch", epoch);
        var config = configuration.authorize(scope, false);
        require(mapping.equals(string(config, "mapping_revision")), "FINERACT_CAPABILITY_MISSING");
        return scope;
    }

    public long watermark(JsonNode scope) {
        Long maximum = store.jdbc().queryForObject("select max(sequence_id) from m_mnzl_r_event where scope_key=?", Long.class, key(scope));
        return maximum == null ? 0 : maximum;
    }

    public JsonNode capabilities(JsonNode scope) {
        return configuration.capabilities(scope, true);
    }

    public JsonNode operation(JsonNode scope, String id, boolean helFunding) {
        Map<String, Object> row = store.find(helFunding ? "hel_funding" : "command",
                ReceivablesStore.key(key(scope), helFunding ? "hel-funding" : "operation", id));
        if (row == null || row.get("result_json") == null) {
            throw new NotFoundException();
        }
        return json.read(string(row, "result_json"));
    }

    public JsonNode account(JsonNode scope, String id) {
        Boundary boundary = new Boundary(DateUtils.getBusinessLocalDate(), "AFTER_EVENTS", watermark(scope));
        var account = accountRow(scope, id);
        ObjectNode result = json.object();
        result.put("externalId", id);
        if (account.get("closure_reason") == null) {
            result.putNull("closureReason");
        } else {
            result.put("closureReason", string(account, "closure_reason"));
        }
        result.put("nativeLoanId", Long.toString(number(account, "native_loan_id")));
        result.set("position", position(scope, id, boundary));
        result.set("schedule", schedule(scope, id, boundary));
        return result;
    }

    public JsonNode position(JsonNode scope, String id, Boundary boundary) {
        var account = accountRow(scope, id);
        ObjectNode issued = issuedPositions(scope, boundary).get(id);
        if (issued == null) {
            throw new NotFoundException();
        }
        var state = historicalState(account, boundary);
        var analytical = ReceivablesMath.position(state, boundary.date());
        ObjectNode result = issued.deepCopy();
        result.setAll(measurement.measures(analytical, new BigInteger(text(issued, "lossAllowanceMinor"))));
        result.put("businessDate", boundary.date().toString());
        result.put("boundarySide", boundary.side());
        result.put("grossYield", state.segment().grossYield().rate().toPlainString());
        result.put("netEir", state.segment().netEir().rate().toPlainString());
        return result;
    }

    public JsonNode measurement(JsonNode scope, String id, Boundary boundary) {
        var account = accountRow(scope, id);
        var state = historicalState(account, boundary);
        var measured = ReceivablesMath.position(state, boundary.date());
        var segment = historicalSegment(account, boundary);
        Map<String, JsonNode> source = new HashMap<>();
        for (var leg : store.children("leg", "account_key", string(account, "record_key"))) {
            source.put(string(leg, "cashflow_id"), json.read(string(leg, "original_json")));
        }
        var nativeState = nativeState(scope, account, boundary);
        Map<Long, NativeReceivableBridge.Period> periods = new HashMap<>();
        nativeState.periods().forEach(p -> periods.put(p.nativePeriodId(), p));
        Map<String, Map<String, Object>> storedLegs = new HashMap<>();
        store.children("leg", "account_key", string(account, "record_key")).forEach(l -> storedLegs.put(string(l, "cashflow_id"), l));
        List<JsonNode> legs = new ArrayList<>();
        for (var leg : measured.legs()) {
            var row = storedLegs.get(leg.cashflow().cashflowId());
            require(row != null, "JOURNAL_MISMATCH");
            var nativePeriod = periods.get(number(row, "native_period_id"));
            require(nativePeriod != null, "JOURNAL_MISMATCH");
            ObjectNode wire = json.object();
            wire.set("cashflow", source.get(leg.cashflow().cashflowId()));
            wire.put("segmentId", string(segment, "record_key"));
            wire.put("segmentStartDate", state.segment().startDate().toString());
            wire.put("asOfDate", boundary.date().toString());
            wire.put("grossBasisMinor", leg.grossBasis().movePointRight(2).toPlainString());
            wire.put("netBasisMinor", leg.netBasis().movePointRight(2).toPlainString());
            wire.put("grossYield", state.segment().grossYield().rate().toPlainString());
            wire.put("netEir", state.segment().netEir().rate().toPlainString());
            wire.put("collectedMinor", nativePeriod.collectedMinor().toString());
            wire.put("adjustedMinor", nativePeriod.adjustedMinor().toString());
            legs.add(wire);
        }
        ObjectNode result = json.object();
        result.set("position", position(scope, id, boundary));
        result.set("measurementLegs", json.value(legs));
        result.put("eventWatermark", Long.toString(boundary.watermark()));
        return result;
    }

    public JsonNode schedule(JsonNode scope, String id, Boundary boundary) {
        var account = accountRow(scope, id);
        var state = nativeState(scope, account, boundary);
        var issued = issuedPositions(scope, boundary).get(id);
        if (issued == null) {
            throw new NotFoundException();
        }
        Map<Long, Map<String, Object>> legs = new HashMap<>();
        store.children("leg", "account_key", string(account, "record_key")).forEach(l -> legs.put(number(l, "native_period_id"), l));
        Set<String> currentCashflows = new HashSet<>();
        historicalState(account, boundary).segment().cashflows().forEach(c -> currentCashflows.add(c.cashflowId()));
        List<JsonNode> rows = new ArrayList<>();
        String version = null;
        for (var period : state.periods()) {
            var leg = legs.get(period.nativePeriodId());
            require(leg != null, "JOURNAL_MISMATCH");
            if (!currentCashflows.contains(string(leg, "cashflow_id"))) {
                continue;
            }
            ObjectNode row = (ObjectNode) json.read(string(leg, "original_json"));
            version = text(row, "scheduleVersionId");
            row.put("nativeRepaymentPeriodId", Long.toString(period.nativePeriodId()));
            row.put("collectedMinor", period.collectedMinor().toString());
            row.put("adjustedMinor", period.adjustedMinor().toString());
            row.put("outstandingMinor", period.outstandingMinor().toString());
            rows.add(row);
        }
        require(version != null, "RECOVERY_REQUIRED");
        ObjectNode result = json.object();
        result.put("accountId", id);
        result.set("accountVersion", issued.get("accountVersion"));
        result.put("scheduleVersionId", version);
        result.put("businessDate", boundary.date().toString());
        result.put("boundarySide", boundary.side());
        result.set("rows", json.value(rows));
        return result;
    }

    public JsonNode events(JsonNode scope, String cursor, int limit, long maximum) {
        List<Map<String, Object>> rows = store.jdbc().queryForList(
                "select * from m_mnzl_r_event where scope_key=? and sequence_id<=? order by created_at,record_key", key(scope), maximum);
        return page(rows, cursor, limit, row -> json.read(string(row, "event_json")));
    }

    public JsonNode transactions(JsonNode scope, String id, String cursor, int limit) {
        var account = accountRow(scope, id);
        Boundary boundary = new Boundary(DateUtils.getBusinessLocalDate(), "AFTER_EVENTS", watermark(scope));
        var nativeState = nativeState(scope, account, boundary);
        Map<String, NativeReceivableBridge.NativeTransaction> nativeTransactions = new HashMap<>();
        nativeState.transactions().forEach(tx -> nativeTransactions.put(Long.toString(tx.id()), tx));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var eventRow : eventRows(scope, boundary)) {
            JsonNode event = json.read(string(eventRow, "event_json"));
            if (event.path("sourceTransactionIds").isEmpty()) {
                String type = switch (string(eventRow, "command_type")) {
                    case "CLOSE_PERIOD" -> "ACCRUAL";
                    case "SET_IMPAIRMENT" -> "IMPAIRMENT";
                    case "CORRECT_EVENT" -> "CORRECTION";
                    case "RESET_RATE", "WORKOUT" -> "MODIFICATION";
                    default -> null;
                };
                if (type != null) {
                    var journals = store.jdbc().queryForList(
                            "select j.transaction_id,j.amount,j.type_enum from m_mnzl_r_journal_line l join acc_gl_journal_entry j on j.id=l.native_journal_id where l.event_key=? and l.account_key=? order by j.id",
                            string(eventRow, "record_key"), string(account, "record_key"));
                    if (!journals.isEmpty()) {
                        BigInteger amount = journals.stream().filter(j -> number(j, "type_enum") == 2)
                                .map(j -> new BigDecimal(string(j, "amount")).movePointRight(2).toBigIntegerExact())
                                .reduce(BigInteger.ZERO, BigInteger::add);
                        ObjectNode wire = json.object();
                        wire.put("transactionId", string(journals.getFirst(), "transaction_id"));
                        wire.put("accountId", id);
                        wire.set("operationId", event.get("operationId"));
                        wire.set("eventId", event.get("eventId"));
                        wire.put("createdAt", instant(eventRow.get("created_at")));
                        wire.set("businessDate", event.get("businessDate"));
                        wire.put("amountMinor", amount.toString());
                        wire.put("currency", "EGP");
                        wire.put("type", type);
                        rows.add(Map.of("created_at", eventRow.get("created_at"), "record_key", string(eventRow, "record_key"), "wire",
                                wire));
                    }
                }
            }
            for (JsonNode txId : event.path("sourceTransactionIds")) {
                var tx = nativeTransactions.get(txId.asText());
                if (tx == null) {
                    continue;
                }
                ObjectNode row = json.object();
                row.put("transactionId", txId.asText());
                row.put("accountId", id);
                row.set("operationId", event.get("operationId"));
                row.set("eventId", event.get("eventId"));
                row.put("createdAt", instant(eventRow.get("created_at")));
                row.set("businessDate", event.get("businessDate"));
                row.put("amountMinor", tx.amountMinor().toString());
                row.put("currency", "EGP");
                boolean reversal = event.hasNonNull("reversalOfEventId");
                row.put("type", reversal ? "REVERSAL" : switch (tx.type()) {
                    case "PURCHASED_RECEIVABLE_ACTIVATION" -> "PURCHASE";
                    case "REPAYMENT" -> "COLLECTION";
                    case "RECEIVABLE_MODIFICATION" -> "MODIFICATION";
                    case "WRITEOFF" -> "WRITE_OFF";
                    default -> tx.type();
                });
                rows.add(Map.of("created_at", eventRow.get("created_at"), "record_key",
                        string(eventRow, "record_key") + ":" + txId.asText(), "wire", row));
            }
        }
        rows.sort(rowOrder());
        return page(rows, cursor, limit, row -> (JsonNode) row.get("wire"));
    }

    public JsonNode accounts(JsonNode scope, LocalDate asOf, LocalDate periodStart, long maximum, String cursor, int limit) {
        Boundary boundary = new Boundary(asOf, "AFTER_EVENTS", maximum);
        var positions = issuedPositions(scope, boundary);
        List<Map<String, Object>> rows = store.jdbc().queryForList(
                "select * from m_mnzl_r_account where scope_key=? and activation_date<=? order by created_at,record_key", key(scope), asOf);
        rows = rows.stream().filter(row -> {
            JsonNode p = positions.get(string(row, "external_id"));
            return p != null
                    && (text(p, "nativeLoanStatus").equals("ACTIVE") || !LocalDate.parse(text(p, "businessDate")).isBefore(periodStart));
        }).toList();
        ObjectNode result = (ObjectNode) page(rows, cursor, limit, row -> {
            JsonNode p = positions.get(string(row, "external_id"));
            ObjectNode item = json.object();
            item.put("externalId", string(row, "external_id"));
            item.put("nativeLoanId", Long.toString(number(row, "native_loan_id")));
            item.set("scope", json.read(string(row, "scope_json")));
            item.put("dealId", string(row, "deal_id"));
            item.set("status", p.get("nativeLoanStatus"));
            item.put("createdAt", instant(row.get("created_at")));
            item.set("accountVersion", p.get("accountVersion"));
            item.set("lastFinancialEventId", p.get("lastFinancialEventId"));
            item.set("lastMovementDate", p.get("businessDate"));
            return item;
        });
        result.set("scope", scope);
        result.put("productCode", "MNZL_PURCHASED_RECEIVABLE");
        result.put("asOfDate", asOf.toString());
        result.put("eventWatermark", Long.toString(maximum));
        result.put("includesClosedPeriodMovements", true);
        return result;
    }

    private List<Map<String, Object>> snapshotRows(JsonNode scope, Boundary boundary, String kind) {
        var rows = store.jdbc().queryForList(
                "select s.*,e.created_at,e.sequence_id from m_mnzl_r_state_snapshot s join m_mnzl_r_event e on e.record_key=s.event_key "
                        + "where s.scope_key=? and s.subject_kind=? and e.sequence_id<=? and s.business_date" + boundary.comparison()
                        + "? order by e.sequence_id",
                key(scope), kind, boundary.watermark(), boundary.date());
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        rows.forEach(row -> latest.put(string(row, "subject_key"), row));
        List<Map<String, Object>> sorted = new ArrayList<>(latest.values());
        sorted.sort(rowOrder());
        return sorted;
    }

    public JsonNode snapshots(JsonNode scope, Boundary boundary, String kind, String cursor, int limit) {
        ObjectNode result = (ObjectNode) page(snapshotRows(scope, boundary, kind), cursor, limit,
                row -> json.read(string(row, "snapshot_json")));
        result.set("scope", scope);
        result.put("businessDate", boundary.date().toString());
        result.put("boundarySide", boundary.side());
        result.put("eventWatermark", Long.toString(boundary.watermark()));
        return result;
    }

    public JsonNode journals(JsonNode scope, List<String> operationIds, long maximum) {
        require(!operationIds.isEmpty() && operationIds.size() <= 200, "INVALID_DATA");
        String placeholders = String.join(",", operationIds.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>(List.of(key(scope), maximum));
        args.addAll(operationIds);
        var rows = store.jdbc().queryForList(
                "select l.*,j.id actual_id,j.account_id actual_gl,j.type_enum actual_side,j.amount actual_amount,j.currency_code,c.operation_id "
                        + "from m_mnzl_r_journal_line l join m_mnzl_r_event e on e.record_key=l.event_key join m_mnzl_r_command c on c.record_key=e.operation_key "
                        + "join acc_gl_journal_entry j on j.id=l.native_journal_id where l.scope_key=? and e.sequence_id<=? and c.operation_id in ("
                        + placeholders + ") order by j.id",
                args.toArray());
        List<JsonNode> lines = new ArrayList<>();
        for (var row : rows) {
            ObjectNode line = json.object();
            line.put("nativeJournalId", Long.toString(number(row, "actual_id")));
            line.put("sourceLineId", string(row, "source_line_id"));
            line.put("nativeGlAccountId", Long.toString(number(row, "actual_gl")));
            line.put("accountKey", string(row, "semantic_account"));
            line.put("side", number(row, "actual_side") == 2 ? "DEBIT" : "CREDIT");
            line.put("amountMinor", new BigDecimal(string(row, "actual_amount")).movePointRight(2).toBigIntegerExact().toString());
            line.put("currency", string(row, "currency_code"));
            line.put("operationId", string(row, "operation_id"));
            if (row.get("deal_id") != null) {
                line.put("dealId", string(row, "deal_id"));
            }
            if (row.get("account_key") != null) {
                line.put("accountId", string(store.require("account", string(row, "account_key")), "external_id"));
            }
            lines.add(line);
        }
        ObjectNode result = json.object();
        result.set("scope", scope);
        result.put("eventWatermark", Long.toString(maximum));
        result.set("lines", json.value(lines));
        return result;
    }

    public JsonNode helTerms(JsonNode scope, String id) {
        configuration.authorize(scope, false);
        var terms = hel.readTerms(id);
        ObjectNode result = json.object();
        result.put("nativeLoanId", Long.toString(terms.nativeLoanId()));
        result.put("nativeClientId", Long.toString(terms.clientId()));
        result.put("status", terms.status());
        result.put("principalMinor", terms.principalMinor().toString());
        result.put("annualNominalRate", terms.annualNominalRate());
        result.put("currency", "EGP");
        result.put("tenureMonths", terms.tenure());
        result.put("businessDate", terms.businessDate().toString());
        List<JsonNode> fees = terms.financedFees().stream().map(fee -> {
            ObjectNode node = json.object();
            node.put("nativeChargeId", Long.toString(fee.feeId()));
            node.put("componentId", Long.toString(fee.feeId()));
            node.put("amountMinor", fee.amountMinor().toString());
            return (JsonNode) node;
        }).toList();
        result.set("financedFees", json.value(fees));
        List<JsonNode> schedule = terms.repaymentSchedule().stream().map(row -> {
            ObjectNode node = json.object();
            node.put("dueDate", row.dueDate().toString());
            node.put("principalMinor", row.principalMinor().toString());
            node.put("interestMinor", row.interestMinor().toString());
            node.put("feesMinor", row.feesMinor().toString());
            node.put("totalMinor", row.totalMinor().toString());
            return (JsonNode) node;
        }).toList();
        result.set("repaymentSchedule", json.value(schedule));
        return result;
    }

    public JsonNode helJournals(JsonNode scope, long loanId, List<Long> ids, long maximum) {
        require(!ids.isEmpty() && ids.size() <= 200, "INVALID_DATA");
        var rows = store.jdbc().queryForList(
                "select h.*,c.operation_id,c.business_date from m_mnzl_r_hel_funding h join m_mnzl_r_command c on c.record_key=h.operation_key join m_mnzl_r_event e on e.operation_key=h.operation_key where h.scope_key=? and h.loan_id=? and e.sequence_id<=?",
                key(scope), loanId, maximum);
        if (rows.size() != 1) {
            throw new NotFoundException();
        }
        var funding = rows.getFirst();
        JsonNode funded = json.read(string(funding, "result_json"));
        Set<String> recorded = new HashSet<>();
        funded.path("nativeTransactionIds").forEach(id -> recorded.add(id.asText()));
        require(ids.stream().allMatch(id -> recorded.contains(id.toString())), "INVALID_DATA");
        long clearing = number(store.require("account_map", ReceivablesStore.key(key(scope), "map", "helSettlementClearing")),
                "native_gl_id");
        List<JsonNode> lines = hel.readJournals(loanId, ids, clearing).stream().map(line -> {
            ObjectNode node = json.object();
            node.put("nativeGlAccountId", Long.toString(line.nativeGlAccountId()));
            node.put("journalId", Long.toString(line.journalId()));
            node.put("sourceLineId", Long.toString(line.journalId()));
            node.put("side", line.side());
            node.put("amountMinor", line.amountMinor().toString());
            node.put("currency", line.currency());
            node.put("transactionId", Long.toString(line.transactionId()));
            node.put("operationId", string(funding, "operation_id"));
            node.put("sourceReferenceId", string(funding, "loan_external_id"));
            node.put("role", line.role());
            node.put("businessDate", line.businessDate().toString());
            return (JsonNode) node;
        }).toList();
        ObjectNode result = json.object();
        result.set("scope", scope);
        result.put("nativeLoanId", Long.toString(loanId));
        result.put("businessDate", string(funding, "business_date"));
        result.put("eventWatermark", Long.toString(maximum));
        result.set("lines", json.value(lines));
        result.set("nativeTransactionIds", json.value(ids.stream().map(Object::toString).toList()));
        return result;
    }

    public JsonNode controls(JsonNode scope, Boundary boundary, String dealId, String accountId) {
        Map<String, ObjectNode> positions = issuedPositions(scope, boundary);
        List<Map<String, Object>> accounts = store.scoped("account", key(scope)).stream()
                .filter(row -> (dealId == null || dealId.equals(string(row, "deal_id")))
                        && (accountId == null || accountId.equals(string(row, "external_id")))
                        && positions.containsKey(string(row, "external_id")))
                .toList();
        if (accountId != null && accounts.isEmpty()) {
            throw new NotFoundException();
        }
        BigInteger nativeFace = BigInteger.ZERO;
        BigInteger subledgerFace = BigInteger.ZERO;
        List<String> activeIds = new ArrayList<>();
        for (var account : accounts) {
            String id = string(account, "external_id");
            JsonNode p = positions.get(id);
            nativeFace = nativeFace.add(nativeState(scope, account, boundary).outstandingMinor());
            subledgerFace = subledgerFace.add(new BigInteger(text(p, "contractualOutstandingMinor")));
            if (text(p, "nativeLoanStatus").equals("ACTIVE")) {
                activeIds.add(id);
            }
        }
        List<Object> args = new ArrayList<>(List.of(key(scope), boundary.watermark(), boundary.date()));
        String filter = "";
        if (dealId != null) {
            filter += " and l.deal_id=?";
            args.add(dealId);
        }
        if (accountId != null) {
            filter += " and l.account_key=?";
            args.add(ReceivablesStore.key(key(scope), "account", accountId));
        }
        var rows = store.jdbc().queryForList(
                "select l.*,j.amount actual_amount,j.type_enum actual_side,j.account_id actual_gl,j.currency_code,j.transaction_id actual_source "
                        + "from m_mnzl_r_journal_line l join m_mnzl_r_event e on e.record_key=l.event_key left join acc_gl_journal_entry j on j.id=l.native_journal_id "
                        + "where l.scope_key=? and e.sequence_id<=? and e.business_date" + boundary.comparison() + "?" + filter
                        + " order by l.record_key",
                args.toArray());
        // Independently find native rows in the event's actual accounting transaction, including unregistered extras.
        Long extra = store.jdbc().queryForObject("select count(*) from acc_gl_journal_entry j join m_mnzl_r_event e "
                + "on j.transaction_id=concat('R',substring(e.record_key,1,40)) left join m_mnzl_r_journal_line l on l.native_journal_id=j.id "
                + "where e.scope_key=? and e.sequence_id<=? and e.business_date" + boundary.comparison() + "? and l.record_key is null",
                Long.class, key(scope), boundary.watermark(), boundary.date());
        require(extra != null && extra == 0, "JOURNAL_MISMATCH");
        Map<String, BigInteger> sub = new HashMap<>();
        Map<String, BigInteger> actual = new HashMap<>();
        Map<String, List<String>> sourceIds = new HashMap<>();
        Map<String, List<String>> journalIds = new HashMap<>();
        Map<String, List<String>> eventIds = new HashMap<>();
        for (var row : rows) {
            String semantic = string(row, "semantic_account");
            BigInteger amount = new BigInteger(string(row, "amount_minor"));
            sub.merge(semantic, string(row, "side").equals("DEBIT") ? amount : amount.negate(), BigInteger::add);
            sourceIds.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(string(row, "source_line_id"));
            if (row.get("actual_gl") != null) {
                eventIds.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(string(row, "actual_source"));
                require(number(row, "native_gl_id") == number(row, "actual_gl") && string(row, "currency_code").equals("EGP"),
                        "JOURNAL_MISMATCH");
                BigInteger value = new BigDecimal(string(row, "actual_amount")).movePointRight(2).toBigIntegerExact();
                actual.merge(semantic, number(row, "actual_side") == 2 ? value : value.negate(), BigInteger::add);
                journalIds.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(Long.toString(number(row, "native_journal_id")));
            }
        }
        if (accountId == null && dealId == null) {
            String semantic = "helSettlementClearing";
            long clearing = number(store.require("account_map", ReceivablesStore.key(key(scope), "map", semantic)), "native_gl_id");
            var fundingRows = store.jdbc().queryForList(
                    "select h.* from m_mnzl_r_hel_funding h join m_mnzl_r_event e on e.operation_key=h.operation_key "
                            + "where h.scope_key=? and e.sequence_id<=? and e.business_date" + boundary.comparison() + "?",
                    key(scope), boundary.watermark(), boundary.date());
            for (var funding : fundingRows) {
                BigInteger expected = new BigInteger(string(funding, "principal_minor"))
                        .subtract(new BigInteger(string(funding, "fees_minor"))).negate();
                sub.merge(semantic, expected, BigInteger::add);
                List<Long> ids = new ArrayList<>();
                json.read(string(funding, "result_json")).path("nativeTransactionIds").forEach(id -> ids.add(Long.parseLong(id.asText())));
                for (var line : hel.readJournals(number(funding, "loan_id"), ids, clearing)) {
                    if (line.nativeGlAccountId() != clearing) {
                        continue;
                    }
                    require(line.currency().equals("EGP"), "JOURNAL_MISMATCH");
                    actual.merge(semantic, line.side().equals("DEBIT") ? line.amountMinor() : line.amountMinor().negate(), BigInteger::add);
                    sourceIds.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(Long.toString(line.journalId()));
                    journalIds.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(Long.toString(line.journalId()));
                    eventIds.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(Long.toString(line.transactionId()));
                }
            }
        }
        List<JsonNode> balances = new ArrayList<>();
        for (String semantic : ReceivablesConfiguration.ACCOUNTS.stream().sorted().toList()) {
            BigInteger expected = sub.getOrDefault(semantic, BigInteger.ZERO);
            BigInteger observed = actual.getOrDefault(semantic, BigInteger.ZERO);
            ObjectNode balance = json.object();
            balance.put("accountKey", semantic);
            balance.put("currency", "EGP");
            balance.put("subledgerMinor", expected.toString());
            balance.put("nativeGlMinor", observed.toString());
            balance.put("differenceMinor", expected.subtract(observed).toString());
            balance.set("nativeSourceIds", json.value(eventIds.getOrDefault(semantic, List.of()).stream().distinct().sorted().toList()));
            balance.set("glSourceIds", json.value(journalIds.getOrDefault(semantic, List.of())));
            balance.set("journalIds", json.value(journalIds.getOrDefault(semantic, List.of())));
            balance.set("sourceLineIds", json.value(sourceIds.getOrDefault(semantic, List.of())));
            balances.add(balance);
        }
        ObjectNode result = json.object();
        result.set("scope", scope);
        if (dealId != null) {
            result.put("dealId", dealId);
        }
        if (accountId != null) {
            result.put("accountId", accountId);
        }
        result.put("businessDate", boundary.date().toString());
        result.put("boundarySide", boundary.side());
        result.put("eventWatermark", Long.toString(boundary.watermark()));
        result.set("balances", json.value(balances));
        result.set("activeAccountIds", json.value(activeIds.stream().sorted().toList()));
        List<String> lots = new ArrayList<>();
        List<String> facilities = new ArrayList<>();
        for (var snapshot : snapshotRows(scope, boundary, "LOT")) {
            JsonNode lot = json.read(string(snapshot, "snapshot_json"));
            if ((accountId == null || accountId.equals(text(lot, "accountId"))) && (dealId == null || dealId.equals(text(lot, "dealId")))) {
                lots.add(text(lot, "lotId"));
            }
        }
        if (accountId == null && dealId == null) {
            for (var snapshot : snapshotRows(scope, boundary, "FUNDING")) {
                facilities.add(text(json.read(string(snapshot, "snapshot_json")), "facilityId"));
            }
        }
        result.set("developerLotIds", json.value(lots.stream().sorted().toList()));
        result.set("fundingFacilityIds", json.value(facilities.stream().sorted().toList()));
        result.put("nativeContractualOutstandingMinor", nativeFace.toString());
        result.put("subledgerContractualOutstandingMinor", subledgerFace.toString());
        result.put("snapshotHash", json.hash(result));
        return result;
    }

    private String key(JsonNode scope) {
        return configuration.scopeKey(scope);
    }

    private Map<String, Object> accountRow(JsonNode scope, String id) {
        var row = store.find("account", ReceivablesStore.key(key(scope), "account", id));
        if (row == null) {
            throw new NotFoundException();
        }
        return row;
    }

    private List<Map<String, Object>> eventRows(JsonNode scope, Boundary boundary) {
        return store.jdbc()
                .queryForList("select e.*,c.command_type from m_mnzl_r_event e join m_mnzl_r_command c on c.record_key=e.operation_key "
                        + "where e.scope_key=? and e.sequence_id<=? and e.business_date" + boundary.comparison()
                        + "? order by e.business_date,e.sequence_id", key(scope), boundary.watermark(), boundary.date());
    }

    private Map<String, ObjectNode> issuedPositions(JsonNode scope, Boundary boundary) {
        Map<String, ObjectNode> latest = new LinkedHashMap<>();
        for (var row : eventRows(scope, boundary)) {
            JsonNode event = json.read(string(row, "event_json"));
            event.path("positionsAfter").forEach(position -> latest.put(text(position, "accountId"), (ObjectNode) position));
        }
        return latest;
    }

    private NativeReceivableBridge.NativeState nativeState(JsonNode scope, Map<String, Object> account, Boundary boundary) {
        Set<Long> transactions = new HashSet<>();
        Set<Long> reversals = new HashSet<>();
        for (var row : eventRows(scope, boundary)) {
            JsonNode event = json.read(string(row, "event_json"));
            for (JsonNode id : event.path("sourceTransactionIds")) {
                transactions.add(Long.parseLong(id.asText()));
                if (string(row, "command_type").equals("REVERSE_COLLECTION")) {
                    reversals.add(Long.parseLong(id.asText()));
                }
            }
        }
        return nativeBridge.readIndependentState(number(account, "native_loan_id"), boundary.date(), boundary.side(), transactions,
                reversals);
    }

    private Map<String, Object> historicalSegment(Map<String, Object> account, Boundary boundary) {
        var rows = store.jdbc().queryForList(
                "select s.* from m_mnzl_r_segment s join m_mnzl_r_event e on e.operation_key=s.source_operation_key "
                        + "where s.account_key=? and e.sequence_id<=? and s.effective_from" + boundary.comparison()
                        + "? order by s.effective_from desc,e.sequence_id desc",
                string(account, "record_key"), boundary.watermark(), boundary.date());
        if (rows.isEmpty()) {
            throw new NotFoundException();
        }
        return rows.getFirst();
    }

    private ReceivablesMath.MeasurementState historicalState(Map<String, Object> account, Boundary boundary) {
        return json.convert(json.read(string(historicalSegment(account, boundary), "snapshot_json")),
                ReceivablesMath.MeasurementState.class);
    }

    private static String instant(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().toString() : value.toString();
    }

    private static Comparator<Map<String, Object>> rowOrder() {
        return Comparator.comparing((Map<String, Object> row) -> instant(row.get("created_at")))
                .thenComparing(row -> string(row, "record_key"));
    }

    private JsonNode page(List<Map<String, Object>> rows, String cursor, int limit,
            java.util.function.Function<Map<String, Object>, JsonNode> map) {
        require(limit >= 1 && limit <= 200, "INVALID_DATA");
        String after = cursor == null ? null : decode(cursor);
        List<Map<String, Object>> remaining = rows.stream().filter(row -> after == null || cursorKey(row).compareTo(after) > 0)
                .limit(limit + 1L).toList();
        ObjectNode result = json.object();
        result.set("items", json.value(remaining.stream().limit(limit).map(map).toList()));
        if (remaining.size() > limit) {
            result.put("nextCursor", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(cursorKey(remaining.get(limit - 1)).getBytes(StandardCharsets.UTF_8)));
        } else {
            result.putNull("nextCursor");
        }
        return result;
    }

    private static String cursorKey(Map<String, Object> row) {
        return instant(row.get("created_at")) + "|" + string(row, "record_key");
    }

    @SuppressWarnings("AvoidHidingCauseException") // Cause retained through initCause; domain exception has a code-only
                                                   // constructor.
    private static String decode(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            var failure = new ReceivablesException("INVALID_DATA");
            failure.initCause(e);
            throw failure;
        }
    }
}
