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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.minor;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesLedger.pair;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesMeasurement.amount;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import co.mnzl.fineract.receivables.math.CreditAndFunding;
import co.mnzl.fineract.receivables.math.ReceivableEvents;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceivablesCashCommands {

    private final ReceivablesStore store;
    private final ReceivablesJson json;

    public void recordCash(ReceivablesExecution e) {
        JsonNode source = e.command.get("source");
        recordSource(e, source);
        BigInteger allocated = BigInteger.ZERO;
        var ids = new HashSet<String>();
        for (JsonNode allocation : source.get("allocations")) {
            String id = text(allocation, "allocationId");
            String kind = text(allocation, "kind");
            String deal = text(allocation, "dealId");
            require(ids.add(id), "BANK_PROOF_MISMATCH");
            BigInteger amount = minor(allocation, "amountMinor");
            allocated = allocated.add(amount);
            String account = allocation.has("accountId") ? text(allocation, "accountId") : null;
            boolean incoming = text(source, "direction").equals("INCOMING");
            if (account != null && !kind.equals("ACQUISITION_ADVANCE")) {
                var owner = store.require("account", e.accountKey(account));
                require(deal.equals(string(owner, "deal_id")) && json.read(string(owner, "scope_json")).equals(e.command.get("scope")),
                        "OWNERSHIP_CONFLICT");
            }
            if (kind.equals("CUSTOMER_PAYOUT")) {
                consumeHelClearing(e, text(allocation, "helFundingOperationId"), amount, deal);
            }
            if (kind.equals("DEVELOPER_PAYMENT")) {
                require(account != null, "BANK_PROOF_MISMATCH");
                BigInteger payable = BigInteger.ZERO;
                for (var lot : store.children("developer_lot", "account_key", e.accountKey(account))) {
                    if (string(lot, "direction").equals("PAYABLE")) {
                        payable = payable.add(amount(lot, "carrying_minor").subtract(amount(lot, "settled_minor")));
                    }
                }
                BigInteger reserved = BigInteger.ZERO;
                for (var old : store.children("cash_allocation", "account_key", e.accountKey(account))) {
                    if (string(old, "kind").equals("DEVELOPER_PAYMENT")) {
                        reserved = reserved.add(amount(old, "amount_minor").subtract(amount(old, "used_minor")));
                    }
                }
                require(amount.compareTo(payable.subtract(reserved)) <= 0, "BANK_PROOF_MISMATCH");
            }
            String control = switch (kind) {
                case "ACQUISITION_ADVANCE" -> "acquisitionClearing";
                case "DEVELOPER_SETTLEMENT_ADVANCE" -> "developerSettlementAdvance";
                case "RECEIPT_UNAPPLIED" -> "cashUnapplied";
                case "DEVELOPER_PAYMENT" -> "developerPayable";
                case "CUSTOMER_PAYOUT" -> "helSettlementClearing";
                default -> throw new ReceivablesException("INVALID_DATA");
            };
            require(incoming == kind.equals("RECEIPT_UNAPPLIED"), "BANK_PROOF_MISMATCH");
            if (incoming) {
                ReceivablesLedger.bankPair(e.lines, "bank", control, amount, account, deal, "CASH", text(e.command, "operationId"));
            } else {
                ReceivablesLedger.bankPair(e.lines, control, "bank", amount, account, deal, "CASH", text(e.command, "operationId"));
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("scope_key", e.scope);
            fields.put("source_key", ReceivablesStore.key(e.scope, "cash", e.command.get("operationId").asText()));
            fields.put("allocation_id", id);
            fields.put("kind", kind);
            fields.put("deal_id", deal);
            fields.put("account_key", account == null ? null : e.accountKey(account));
            fields.put("amount_minor", amount.toString());
            fields.put("used_minor", "0");
            fields.put("allocation_json", json.write(allocation));
            store.insert("cash_allocation", ReceivablesStore.key(e.scope, "cash-allocation", id), fields);
            e.allocationIds.add(id);
        }
        require(allocated.equals(minor(source, "amountMinor")), "BANK_PROOF_MISMATCH");
    }

    private void recordSource(ReceivablesExecution e, JsonNode source) {
        String movement = text(e.command, "operationId");
        String sourceId = text(source, "bankSourceId");
        require(e.date.equals(LocalDate.parse(text(source, "valueDate"))), "BANK_PROOF_MISMATCH");
        require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_cash_source where scope_key=? and bank_source_id=?", Long.class,
                e.scope, sourceId) == 0, "IDEMPOTENCY_CONFLICT");
        JsonNode config = json.read(string(e.configuration, "config_json"));
        require(text(config, "bankAccountReference").equals(text(source, "bankAccountReference")), "BANK_PROOF_MISMATCH");
        var fields = Map.<String, Object>of("scope_key", e.scope, "bank_source_id", sourceId, "cash_movement_id", movement,
                "verification_ref", text(source, "verificationEvidenceId"), "value_date", LocalDate.parse(text(source, "valueDate")),
                "direction", text(source, "direction"), "amount_minor", text(source, "amountMinor"), "source_json", json.write(source),
                "operation_key", e.operationKey);
        store.insert("cash_source", ReceivablesStore.key(e.scope, "cash", movement), fields);
        var bank = json.object();
        bank.put("cashMovementId", movement);
        bank.put("bankSourceId", sourceId);
        bank.put("bankAccountReference", text(source, "bankAccountReference"));
        bank.put("verificationEvidenceId", text(source, "verificationEvidenceId"));
        bank.put("valueDate", text(source, "valueDate"));
        bank.put("direction", text(source, "direction"));
        bank.put("amountMinor", text(source, "amountMinor"));
        bank.put("currency", "EGP");
        List<String> ids = new ArrayList<>();
        if (source.has("allocations")) {
            source.get("allocations").forEach(a -> ids.add(text(a, "allocationId")));
        }
        bank.set("allocationIds", json.value(ids));
        e.bankMovements.add(bank);
    }

    public void consumeAllocations(ReceivablesExecution e, JsonNode ids, BigInteger amount, String kind, String deal) {
        BigInteger remaining = amount;
        var unique = new HashSet<String>();
        for (JsonNode id : ids) {
            require(unique.add(id.asText()), "BANK_PROOF_MISMATCH");
            String key = ReceivablesStore.key(e.scope, "cash-allocation", id.asText());
            var row = store.require("cash_allocation", key);
            require(kind.equals(string(row, "kind")) && deal.equals(string(row, "deal_id")), "BANK_PROOF_MISMATCH");
            BigInteger available = amount(row, "amount_minor").subtract(amount(row, "used_minor"));
            BigInteger take = remaining.min(available);
            require(take.signum() > 0, "BANK_PROOF_MISMATCH");
            store.update("cash_allocation", key, Map.of("used_minor", amount(row, "used_minor").add(take).toString()));
            remaining = remaining.subtract(take);
            e.allocationIds.add(id.asText());
        }
        require(remaining.signum() == 0, "BANK_PROOF_MISMATCH");
    }

    public void consumeReceipt(ReceivablesExecution e, String movementId, BigInteger amount, String deal) {
        String sourceKey = ReceivablesStore.key(e.scope, "cash", movementId);
        var source = store.require("cash_source", sourceKey);
        require("INCOMING".equals(string(source, "direction"))
                && (e.originalValueDate == null ? e.date : e.originalValueDate).equals(LocalDate.parse(string(source, "value_date"))),
                "BANK_PROOF_MISMATCH");
        BigInteger remaining = amount;
        for (var allocation : store.children("cash_allocation", "source_key", sourceKey)) {
            if (!deal.equals(string(allocation, "deal_id")) || !"RECEIPT_UNAPPLIED".equals(string(allocation, "kind"))) {
                continue;
            }
            BigInteger available = amount(allocation, "amount_minor").subtract(amount(allocation, "used_minor"));
            BigInteger take = remaining.min(available);
            if (take.signum() > 0) {
                store.update("cash_allocation", string(allocation, "record_key"),
                        Map.of("used_minor", amount(allocation, "used_minor").add(take).toString()));
            }
            remaining = remaining.subtract(take);
            if (remaining.signum() == 0) {
                break;
            }
        }
        require(remaining.signum() == 0, "BANK_PROOF_MISMATCH");
    }

    public void consumeHelClearing(ReceivablesExecution e, String operationId, BigInteger amount, String deal) {
        String operationKey = ReceivablesStore.key(e.scope, "operation", operationId);
        var rows = store.children("hel_funding", "operation_key", operationKey);
        require(rows.size() == 1, "BANK_PROOF_MISMATCH");
        var row = rows.getFirst();
        require(e.scope.equals(string(row, "scope_key")) && deal.equals(string(row, "deal_id")), "BANK_PROOF_MISMATCH");
        BigInteger used = amount(row, "used_minor");
        require(amount.signum() > 0 && used.add(amount).compareTo(amount(row, "clearing_minor")) <= 0, "BANK_PROOF_MISMATCH");
        store.update("hel_funding", string(row, "record_key"), Map.of("used_minor", used.add(amount).toString()));
    }

    public void restoreReceipt(ReceivablesExecution e, String sourceKey, BigInteger amount, String deal) {
        BigInteger remaining = amount;
        for (var allocation : store.children("cash_allocation", "source_key", sourceKey)) {
            if (!deal.equals(string(allocation, "deal_id")) || !"RECEIPT_UNAPPLIED".equals(string(allocation, "kind"))) {
                continue;
            }
            BigInteger take = remaining.min(amount(allocation, "used_minor"));
            if (take.signum() > 0) {
                store.update("cash_allocation", string(allocation, "record_key"),
                        Map.of("used_minor", amount(allocation, "used_minor").subtract(take).toString()));
            }
            remaining = remaining.subtract(take);
            if (remaining.signum() == 0) {
                break;
            }
        }
        require(remaining.signum() == 0, "BANK_PROOF_MISMATCH");
    }

    public void accrueLots(ReceivablesExecution e, String accountKey) {
        for (var row : store.children("developer_lot", "account_key", accountKey)) {
            if (amount(row, "carrying_minor").equals(amount(row, "settled_minor"))) {
                continue;
            }
            var lot = json.convert(json.read(string(row, "snapshot_json")), ReceivableEvents.AdjustmentLot.class);
            BigInteger target = lot.balanceMinor(e.date);
            BigInteger delta = target.subtract(amount(row, "carrying_minor"));
            var account = store.require("account", accountKey);
            if (lot.direction() == ReceivableEvents.Direction.PAYABLE) {
                pair(e.lines, "developerAdjustmentExpense", "developerPayable", delta, string(account, "external_id"),
                        string(account, "deal_id"), "UNWIND");
            } else {
                pair(e.lines, "developerReceivable", "developerAdjustmentIncome", delta, string(account, "external_id"),
                        string(account, "deal_id"), "UNWIND");
            }
            store.update("developer_lot", string(row, "record_key"), Map.of("carrying_minor", target.toString(), "watermark", e.date));
            e.lotKeys.add(string(row, "record_key"));
        }
    }

    public void settleDeveloper(ReceivablesExecution e) {
        BigInteger payable = BigInteger.ZERO;
        BigInteger receivable = BigInteger.ZERO;
        var lots = new ArrayList<Map<String, Object>>();
        var requestedLots = new HashSet<String>();
        for (JsonNode requested : e.command.get("lots")) {
            require(requestedLots.add(text(requested, "lotId")), "INVALID_DATA");
            var row = store.require("developer_lot", ReceivablesStore.key(e.scope, "lot", text(requested, "lotId")));
            accrueLots(e, string(row, "account_key"));
            row = store.require("developer_lot", string(row, "record_key"));
            require(!e.date.isBefore(LocalDate.parse(string(row, "due_date"))), "APPROVAL_SCOPE_CHANGED");
            BigInteger requestedAmount = minor(requested, "amountMinor");
            require(requestedAmount.compareTo(amount(row, "carrying_minor").subtract(amount(row, "settled_minor"))) <= 0,
                    "BANK_PROOF_MISMATCH");
            if (string(row, "direction").equals("PAYABLE")) {
                payable = payable.add(requestedAmount);
            } else {
                receivable = receivable.add(requestedAmount);
            }
            var account = store.require("account", string(row, "account_key"));
            require(json.read(string(account, "scope_json")).equals(e.command.get("scope")), "OWNERSHIP_CONFLICT");
            row = new LinkedHashMap<>(row);
            row.put("requested_minor", requestedAmount);
            lots.add(row);
        }
        String method = text(e.command, "method");
        require(!lots.isEmpty(), "INVALID_DATA");
        if (method.equals("SETOFF")) {
            require(payable.signum() > 0 && payable.equals(receivable) && e.command.has("setoffEvidenceId"), "BANK_PROOF_MISMATCH");
        } else {
            require((payable.signum() == 0) != (receivable.signum() == 0) && e.command.has("cashMovementId"), "BANK_PROOF_MISMATCH");
        }
        for (var row : lots) {
            var account = store.require("account", string(row, "account_key"));
            String deal = string(account, "deal_id");
            String accountId = string(account, "external_id");
            BigInteger requested = (BigInteger) row.get("requested_minor");
            boolean isPayable = string(row, "direction").equals("PAYABLE");
            if (method.equals("CASH") && !isPayable) {
                consumeReceipt(e, text(e.command, "cashMovementId"), requested, deal);
                pair(e.lines, "cashUnapplied", "developerReceivable", requested, accountId, deal, "DEVELOPER_ADJUSTMENT");
            }
            if (method.equals("CASH") && isPayable) {
                String sourceKey = ReceivablesStore.key(e.scope, "cash", text(e.command, "cashMovementId"));
                List<String> ids = store.children("cash_allocation", "source_key", sourceKey).stream().filter(
                        a -> string(a, "kind").equals("DEVELOPER_PAYMENT") && string(a, "account_key").equals(string(row, "account_key")))
                        .map(a -> string(a, "allocation_id")).toList();
                consumeAllocations(e, json.value(ids), requested, "DEVELOPER_PAYMENT", deal);
            }
            if (method.equals("SETOFF")) {
                ReceivablesLedger.line(e.lines, isPayable ? "developerPayable" : "developerReceivable", isPayable ? "DEBIT" : "CREDIT",
                        requested, accountId, deal, "DEVELOPER_ADJUSTMENT");
            }
            store.update("developer_lot", string(row, "record_key"),
                    Map.of("settled_minor", amount(row, "settled_minor").add(requested).toString()));
            var fields = new LinkedHashMap<String, Object>();
            fields.put("scope_key", e.scope);
            fields.put("lot_key", string(row, "record_key"));
            fields.put("operation_key", e.operationKey);
            fields.put("cash_source_key",
                    method.equals("CASH") ? ReceivablesStore.key(e.scope, "cash", text(e.command, "cashMovementId")) : null);
            fields.put("amount_minor", requested.toString());
            fields.put("method", method);
            fields.put("evidence_ref", method.equals("SETOFF") ? text(e.command, "setoffEvidenceId") : text(e.command, "cashMovementId"));
            store.insert("lot_allocation",
                    ReceivablesStore.key(e.scope, "lot-settlement", e.operationKey + ":" + string(row, "record_key")), fields);
            e.lotKeys.add(string(row, "record_key"));
            e.accountKeys.add(string(row, "account_key"));
        }
    }

    public void accrueFunding(ReceivablesExecution e, Map<String, Object> facility) {
        String key = string(facility, "record_key");
        LocalDate from = LocalDate.parse(string(facility, "watermark"));
        require(!e.date.isBefore(from), "PERIOD_CLOSED");
        var intervals = new ArrayList<CreditAndFunding.FundingInterval>();
        var history = store.jdbc().queryForList(
                "select snapshot_json from m_mnzl_r_funding_event where facility_key=? order by business_date,record_key", key);
        for (var event : history) {
            JsonNode snapshot = json.read(string(event, "snapshot_json"));
            if (snapshot.has("intervals")) {
                intervals.clear();
                for (JsonNode interval : snapshot.get("intervals")) {
                    intervals.add(json.convert(interval, CreditAndFunding.FundingInterval.class));
                }
            }
        }
        intervals.add(new CreditAndFunding.FundingInterval(from, e.date, amount(facility, "principal_minor"),
                new BigDecimal(string(facility, "rate"))));
        BigInteger total = CreditAndFunding.funding(intervals).expenseMinor();
        BigInteger posted = amount(facility, "accrued_expense_minor");
        BigInteger delta = total.subtract(posted);
        pair(e.lines, "fundingExpense", "fundingInterestPayable", delta, null, null, "FUNDING");
        store.update("funding_facility", key, Map.of("interest_minor", amount(facility, "interest_minor").add(delta).toString(),
                "watermark", e.date, "accrued_expense_minor", total.toString()));
        var snapshot = json.object();
        snapshot.set("intervals", json.value(intervals));
        store.insert("funding_event", ReceivablesStore.key(e.scope, "funding-accrual", e.operationKey + ":" + key),
                Map.of("scope_key", e.scope, "facility_key", key, "operation_key", e.operationKey, "business_date", e.date, "kind",
                        "ACCRUAL", "principal_delta_minor", "0", "rate", string(facility, "rate"), "snapshot_json", json.write(snapshot)));
        e.facilityKeys.add(key);
    }

    public void recordFunding(ReceivablesExecution e) {
        JsonNode terms = e.command.get("terms");
        JsonNode event = e.command.get("event");
        String id = text(terms, "facilityId");
        String key = ReceivablesStore.key(e.scope, "facility", id);
        require(id.equals(e.subjectId()), "INVALID_DATA");
        var facility = store.find("funding_facility", key);
        if (facility == null) {
            store.insert("funding_facility", key, Map.of("scope_key", e.scope, "facility_id", id, "version", 0L, "principal_minor", "0",
                    "interest_minor", "0", "rate", text(terms, "annualNominalRate"), "watermark", e.date, "terms_json", json.write(terms)));
            facility = store.require("funding_facility", key);
        }
        require(json.read(string(facility, "terms_json")).equals(terms), "SOURCE_CHANGED");
        accrueFunding(e, facility);
        facility = store.require("funding_facility", key);
        String kind = text(event, "kind");
        BigInteger principal = amount(facility, "principal_minor");
        BigInteger interest = amount(facility, "interest_minor");
        BigInteger delta = BigInteger.ZERO;
        String rate = string(facility, "rate");
        if (kind.equals("RATE_CHANGE")) {
            rate = text(event, "annualNominalRate");
        } else {
            JsonNode source = event.get("cashSource");
            require(text(source, "facilityId").equals(id), "BANK_PROOF_MISMATCH");
            recordSource(e, source);
            if (kind.equals("DRAW")) {
                delta = minor(event, "principalMinor");
                require(text(source, "direction").equals("INCOMING") && delta.equals(minor(source, "amountMinor")), "BANK_PROOF_MISMATCH");
                principal = principal.add(delta);
                ReceivablesLedger.bankPair(e.lines, "bank", "fundingPrincipal", delta, null, null, "FUNDING",
                        text(e.command, "operationId"));
            } else if (kind.equals("REPAYMENT")) {
                delta = minor(event, "principalMinor").negate();
                require(text(source, "direction").equals("OUTGOING") && delta.negate().equals(minor(source, "amountMinor"))
                        && principal.add(delta).signum() >= 0, "BANK_PROOF_MISMATCH");
                principal = principal.add(delta);
                ReceivablesLedger.bankPair(e.lines, "fundingPrincipal", "bank", delta.negate(), null, null, "FUNDING",
                        text(e.command, "operationId"));
            } else {
                BigInteger paid = minor(event, "interestMinor");
                require(text(source, "direction").equals("OUTGOING") && paid.equals(minor(source, "amountMinor"))
                        && paid.compareTo(interest) <= 0, "BANK_PROOF_MISMATCH");
                interest = interest.subtract(paid);
                ReceivablesLedger.bankPair(e.lines, "fundingInterestPayable", "bank", paid, null, null, "FUNDING",
                        text(e.command, "operationId"));
            }
        }
        store.update("funding_facility", key, Map.of("principal_minor", principal.toString(), "interest_minor", interest.toString(), "rate",
                rate, "version", number(facility, "version") + 1));
        store.insert("funding_event", ReceivablesStore.key(e.scope, "funding-event", e.operationKey),
                Map.of("scope_key", e.scope, "facility_key", key, "operation_key", e.operationKey, "business_date", e.date, "kind", kind,
                        "principal_delta_minor", delta.toString(), "rate", rate, "snapshot_json", json.write(event)));
        e.facilityKeys.add(key);
    }
}
