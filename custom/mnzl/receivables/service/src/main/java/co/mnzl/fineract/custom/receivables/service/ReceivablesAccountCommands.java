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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesLedger.line;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesLedger.pair;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesMeasurement.amount;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesMeasurement.date;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesMeasurement.decimal;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.AdjustmentType;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.Allocation;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.Booking;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.ClientIdentity;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.FaceLeg;
import co.mnzl.fineract.receivables.math.CreditAndFunding;
import co.mnzl.fineract.receivables.math.ReceivableEvents;
import co.mnzl.fineract.receivables.math.ReceivablesMath;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Leg;
import co.mnzl.fineract.receivables.math.ReceivablesMath.MeasurementState;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Position;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Purchase;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceivablesAccountCommands {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesMeasurement measurement;
    private final NativeReceivableBridge nativeBridge;
    private final ReceivablesCashCommands cash;
    private final ReceivablesConfiguration configuration;

    public void book(ReceivablesExecution e) {
        String id = text(e.command, "accountId");
        require(id.equals(e.subjectId()), "INVALID_DATA");
        String key = e.accountKey(id);
        require(store.find("account", key) == null, "IDEMPOTENCY_CONFLICT");
        JsonNode basis = e.command.get("basis");
        require(date(basis, "settlementDate").equals(e.date) && json.hash(json.normalizedBasis(basis)).equals(text(e.command, "basisHash")),
                "SOURCE_CHANGED");
        require(text(basis, "policyRevisionId").equals(string(e.configuration, "policy_revision"))
                && text(basis, "calculatorBuild").equals(string(e.configuration, "calculator_build")), "UNSUPPORTED_VERSION");
        require(text(e.command.get("riskForecast"), "stage").equals("STAGE_1") && basis.get("acceptedAccountPrices").size() > 0,
                "INVALID_DATA");
        Purchase purchase = measurement.purchase(basis, id);
        cash.consumeAllocations(e, e.command.get("acquisitionClearingAllocationIds"), purchase.netPurchaseCashMinor(),
                "ACQUISITION_ADVANCE", text(e.command, "dealId"));
        long client = nativeBridge
                .createClient(new ClientIdentity("R" + ReceivablesStore.key(e.scope, "customer", text(e.command, "customerReferenceId")),
                        text(e.command, "customerReferenceId"), number(e.configuration, "office_id")));
        Booking nativeBook = nativeBridge.bookExactFace(client, number(e.configuration, "product_id"), "R" + key, e.date,
                purchase.segment().cashflows().stream().map(f -> new FaceLeg(f.cashflowId(), f.dueDate(), f.amountMinor())).toList());
        e.nativeTransactions.add(Long.toString(nativeBook.activationTransactionId()));
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        purchase.segment().cashflows().forEach(f -> outstanding.put(f.cashflowId(), f.amountMinor()));
        Position position = ReceivablesMath.position(purchase.segment(), e.date, outstanding);
        var state = new MeasurementState(purchase.segment(), position, outstanding);
        String segmentKey = ReceivablesStore.key(e.scope, "segment", e.operationKey + ":" + key);
        measurement.saveState(e.scope, key, e.operationKey, state, string(e.configuration, "calculator_build"));
        BigInteger allowance = measurement.allowance(position, purchase.segment(), e.command.get("riskForecast"));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("scope_key", e.scope);
        fields.put("external_id", id);
        fields.put("deal_id", text(e.command, "dealId"));
        fields.put("customer_ref", text(e.command, "customerReferenceId"));
        fields.put("native_loan_id", nativeBook.loanId());
        fields.put("native_client_id", client);
        fields.put("scope_json", json.write(e.command.get("scope")));
        fields.put("terms_json", json.write(basis));
        fields.put("source_hash", text(basis, "sourceHash"));
        fields.put("basis_hash", text(e.command, "basisHash"));
        fields.put("version", 0L);
        fields.put("activation_date", e.date);
        fields.put("last_effective_date", e.date);
        fields.put("status", "ACTIVE");
        fields.put("face_minor", position.contractualOutstandingMinor().toString());
        fields.put("gross_minor", position.grossPurchaseBasisMinor().toString());
        fields.put("net_minor", position.amortizedCostMinor().toString());
        fields.put("allowance_minor", allowance.toString());
        fields.put("stage", "STAGE_1");
        fields.put("active_segment_key", segmentKey);
        fields.put("last_event_key", null);
        fields.put("created_at", java.sql.Timestamp.from(java.time.Instant.now()));
        store.insert("account", key, fields);
        saveLegs(e, key, basis.get("cashflows"), nativeBook.sourcePeriodIds());
        saveForecast(e, key, e.command.get("riskForecast"), allowance);
        String deal = text(e.command, "dealId");
        line(e.lines, "contractualReceivable", "DEBIT", purchase.contractualFaceMinor(), id, deal, "FACE");
        line(e.lines, "deferredDiscount", "CREDIT", position.deferredDiscountMinor(), id, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "CREDIT", position.deferredIntegralFeeMinor(), id, deal, "INTEGRAL_FEE");
        line(e.lines, "acquisitionClearing", "CREDIT", purchase.netPurchaseCashMinor(), id, deal, "CASH");
        pair(e.lines, "impairmentExpense", "lossAllowance", allowance, id, deal, "IMPAIRMENT");
        e.accountKeys.add(key);
        reconcileNative(store.require("account", key), position, e.date);
    }

    private void saveLegs(ReceivablesExecution e, String accountKey, JsonNode flows, Map<String, Long> periods) {
        for (JsonNode flow : flows) {
            String cashflow = text(flow, "cashflowId");
            if (!periods.containsKey(cashflow)) {
                continue;
            }
            store.insert("leg", ReceivablesStore.key(e.scope, "leg", accountKey + ":" + cashflow), Map.ofEntries(
                    Map.entry("scope_key", e.scope), Map.entry("account_key", accountKey), Map.entry("cashflow_id", cashflow),
                    Map.entry("installment_id", text(flow, "installmentId")), Map.entry("receivable_id", text(flow, "receivableId")),
                    Map.entry("schedule_version", text(flow, "scheduleVersionId")), Map.entry("native_period_id", periods.get(cashflow)),
                    Map.entry("due_date", date(flow, "dueDate")), Map.entry("face_minor", text(flow, "amountMinor")),
                    Map.entry("collected_minor", "0"), Map.entry("adjusted_minor", "0"), Map.entry("original_json", json.write(flow))));
        }
    }

    public Map<String, Object> account(ReceivablesExecution e) {
        Map<String, Object> account = store.require("account", e.accountKey(e.subjectId()));
        require(json.read(string(account, "scope_json")).equals(e.command.get("scope")), "OWNERSHIP_CONFLICT");
        require(!e.date.isBefore(LocalDate.parse(string(account, "last_effective_date"))), "PERIOD_CLOSED");
        if (text(e.command, "executionMode").equals("RECONSTRUCTION")) {
            long current = store.jdbc().queryForObject(
                    "select count(*) from m_mnzl_r_command where subject_key=? and request_json like ? and record_key<>?", Long.class,
                    e.subjectKey(), "%\"executionMode\":\"CURRENT\"%", e.operationKey);
            require(current == 0, "APPROVAL_SCOPE_CHANGED");
        }
        accrue(e, account);
        return store.require("account", string(account, "record_key"));
    }

    public void accrue(ReceivablesExecution e, Map<String, Object> account) {
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        LocalDate previous = LocalDate.parse(string(account, "last_effective_date"));
        require(!e.date.isBefore(previous), "PERIOD_CLOSED");
        Position p = measurement.position(account, e.date);
        if (e.date.isAfter(previous) && p.contractualOutstandingMinor().signum() > 0) {
            measurement.allowance(p, measurement.state(account).segment(), latestForecast(key));
        }
        BigInteger grossIncome = p.grossPurchaseBasisMinor().subtract(amount(account, "gross_minor"));
        BigInteger netIncome = p.amortizedCostMinor().subtract(amount(account, "net_minor"));
        pair(e.lines, "deferredDiscount", "portfolioInterestIncome", grossIncome, id, deal, "DISCOUNT");
        pair(e.lines, "deferredIntegralFee", "portfolioInterestIncome", netIncome.subtract(grossIncome), id, deal, "INTEGRAL_FEE");
        for (var leg : store.children("leg", "account_key", key)) {
            LocalDate due = LocalDate.parse(string(leg, "due_date"));
            if ((due.isAfter(previous) || (due.equals(previous) && "BEFORE_EVENTS".equals(string(account, "last_boundary_side"))))
                    && (due.isBefore(e.date) || (due.equals(e.date) && e.boundarySide.equals("AFTER_EVENTS")))) {
                pair(e.lines, "installmentDues", "contractualReceivable", outstanding(leg), id, deal, "FACE");
            }
        }
        BigInteger allowance = amount(account, "allowance_minor");
        if ("STAGE_3".equals(string(account, "stage")) && e.date.isAfter(previous)) {
            JsonNode forecast = latestForecast(key);
            var state = measurement.state(account);
            var unwind = CreditAndFunding.stage3Unwind(state.segment(), previous, e.date, state.outstandingMinor(),
                    measurement.scenarios(forecast));
            BigInteger bridge = netIncome.subtract(unwind.interestIncomeMinor());
            pair(e.lines, "portfolioInterestIncome", "lossAllowance", bridge, id, deal, "IMPAIRMENT");
            allowance = allowance.add(bridge);
        }
        store.update("account", key,
                Map.of("gross_minor", p.grossPurchaseBasisMinor().toString(), "net_minor", p.amortizedCostMinor().toString(), "face_minor",
                        p.contractualOutstandingMinor().toString(), "allowance_minor", allowance.toString(), "last_effective_date", e.date,
                        "last_boundary_side", e.boundarySide));
        cash.accrueLots(e, key);
        e.accountKeys.add(key);
    }

    public void closeAccrue(ReceivablesExecution e, Map<String, Object> account, Set<String> approvedForecastIds) {
        accrue(e, account);
        account = store.require("account", string(account, "record_key"));
        if (amount(account, "face_minor").signum() == 0) {
            return;
        }
        JsonNode forecast = latestForecast(string(account, "record_key"));
        require(approvedForecastIds.contains(text(forecast, "forecastId")), "EVIDENCE_EXPIRED");
        BigInteger target = measurement.allowance(measurement.position(account, e.date), measurement.state(account).segment(), forecast);
        pair(e.lines, "impairmentExpense", "lossAllowance", target.subtract(amount(account, "allowance_minor")),
                string(account, "external_id"), string(account, "deal_id"), "IMPAIRMENT");
        store.update("account", string(account, "record_key"), Map.of("allowance_minor", target.toString()));
    }

    public void collect(ReceivablesExecution e, boolean developer) {
        var account = account(e);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        Position p = measurement.position(account, e.date);
        Map<String, BigInteger> selected = new LinkedHashMap<>();
        Map<Long, BigInteger> nativeAmounts = new LinkedHashMap<>();
        List<JsonNode> allocations = new ArrayList<>();
        e.command.get("allocations").forEach(allocations::add);
        Set<String> ids = new HashSet<>();
        BigInteger total = BigInteger.ZERO;
        for (JsonNode allocation : allocations) {
            require(ids.add(text(allocation, "allocationId")), "INVALID_DATA");
            String legKey = ReceivablesStore.key(e.scope, "leg", key + ":" + text(allocation, "cashflowId"));
            var leg = store.require("leg", legKey);
            require(text(allocation, "installmentId").equals(string(leg, "installment_id"))
                    && !LocalDate.parse(string(leg, "due_date")).isAfter(e.date), "SOURCE_CHANGED");
            BigInteger amount = minor(allocation, "amountMinor");
            require(amount.compareTo(outstanding(leg)) <= 0, "BANK_PROOF_MISMATCH");
            cash.consumeReceipt(e, text(allocation, "cashMovementId"), amount, deal);
            selected.merge(text(allocation, "cashflowId"), amount, BigInteger::add);
            total = total.add(amount);
            require(selected.get(text(allocation, "cashflowId")).compareTo(outstanding(leg)) <= 0, "BANK_PROOF_MISMATCH");
            nativeAmounts.merge(number(leg, "native_period_id"), amount, BigInteger::add);
            e.allocationIds.add(text(allocation, "allocationId"));
        }
        var effect = nativeBridge.collect(number(account, "native_loan_id"), e.date,
                nativeAmounts.entrySet().stream().map(a -> new Allocation(a.getKey(), a.getValue())).toList(), "R" + e.operationKey);
        e.nativeTransactions.add(Long.toString(effect.transactionId()));
        BigInteger released = allocateAllowance(account, p, selected);
        var partial = ReceivableEvents.settlePortions(p, selected, total, released);
        applyRemaining(e, account, partial.remainingMeasurement(measurement.state(account).segment()), released);
        for (JsonNode allocation : allocations) {
            String legKey = ReceivablesStore.key(e.scope, "leg", key + ":" + text(allocation, "cashflowId"));
            var leg = store.require("leg", legKey);
            BigInteger amount = minor(allocation, "amountMinor");
            store.update("leg", legKey, Map.of("collected_minor", amount(leg, "collected_minor").add(amount).toString()));
            var fields = new LinkedHashMap<String, Object>();
            fields.put("scope_key", e.scope);
            fields.put("account_key", key);
            fields.put("leg_key", legKey);
            fields.put("allocation_id", text(allocation, "allocationId"));
            fields.put("cash_source_key", ReceivablesStore.key(e.scope, "cash", text(allocation, "cashMovementId")));
            fields.put("operation_key", e.operationKey);
            fields.put("native_transaction_id", effect.transactionId());
            fields.put("amount_minor", amount.toString());
            fields.put("value_date", e.originalValueDate == null ? e.date : e.originalValueDate);
            fields.put("reversed_by", null);
            fields.put("payer", developer ? "DEVELOPER" : "BORROWER");
            store.insert("collection", ReceivablesStore.key(e.scope, "collection", text(allocation, "allocationId")), fields);
        }
        pair(e.lines, "cashUnapplied", "installmentDues", total, id, deal, "CASH");
        pair(e.lines, "lossAllowance", "impairmentExpense", released, id, deal, "IMPAIRMENT");
        remeasureAfter(e, key, false);
        reconcileNative(store.require("account", key), partial.retainedPosition(), e.date);
    }

    private BigInteger allocateAllowance(Map<String, Object> account, Position p, Map<String, BigInteger> selected) {
        BigInteger allowance = amount(account, "allowance_minor");
        if (allowance.signum() == 0) {
            return allowance;
        }
        JsonNode forecast = latestForecast(string(account, "record_key"));
        var losses = CreditAndFunding.impairment(p, measurement.state(account).segment().netEir().rate(),
                Integer.parseInt(text(forecast, "stage").substring(6)), measurement.scenarios(forecast)).lossByCashflow();
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (Leg leg : p.legs()) {
            BigDecimal loss = losses.getOrDefault(leg.cashflow().cashflowId(), BigDecimal.ZERO);
            BigDecimal fraction = leg.outstandingMinor().signum() == 0 ? BigDecimal.ZERO
                    : new BigDecimal(selected.getOrDefault(leg.cashflow().cashflowId(), BigInteger.ZERO))
                            .divide(new BigDecimal(leg.outstandingMinor()), ReceivablesMath.MC);
            BigDecimal picked = loss.multiply(fraction, ReceivablesMath.MC);
            weights.put(leg.cashflow().cashflowId() + ":selected", picked);
            weights.put(leg.cashflow().cashflowId() + ":retained", loss.subtract(picked));
        }
        var allocated = ReceivablesMath.allocate(allowance, weights);
        return allocated.entrySet().stream().filter(x -> x.getKey().endsWith(":selected")).map(Map.Entry::getValue).reduce(BigInteger.ZERO,
                BigInteger::add);
    }

    private void applyRemaining(ReceivablesExecution e, Map<String, Object> account, MeasurementState remaining, BigInteger released) {
        String key = string(account, "record_key");
        measurement.saveState(e.scope, key, e.operationKey, remaining, string(e.configuration, "calculator_build"));
        Position p = remaining.boundaryPosition();
        store.update("account", key,
                Map.of("gross_minor", p.grossPurchaseBasisMinor().toString(), "net_minor", p.amortizedCostMinor().toString(), "face_minor",
                        p.contractualOutstandingMinor().toString(), "allowance_minor",
                        amount(account, "allowance_minor").subtract(released).toString(), "status",
                        p.contractualOutstandingMinor().signum() == 0 ? "CLOSED" : "ACTIVE"));
        e.accountKeys.add(key);
    }

    private static BigInteger outstanding(Map<String, Object> leg) {
        return amount(leg, "face_minor").subtract(amount(leg, "collected_minor")).subtract(amount(leg, "adjusted_minor"));
    }

    private void reconcileNative(Map<String, Object> account, Position p, LocalDate date) {
        var nativeState = nativeBridge.readIndependentState(number(account, "native_loan_id"), date, "AFTER_EVENTS");
        require(nativeState.outstandingMinor().equals(p.contractualOutstandingMinor()), "JOURNAL_MISMATCH");
        var periods = new java.util.HashMap<Long, NativeReceivableBridge.Period>();
        nativeState.periods().forEach(period -> periods.put(period.nativePeriodId(), period));
        for (var leg : store.children("leg", "account_key", string(account, "record_key"))) {
            var nativePeriod = periods.get(number(leg, "native_period_id"));
            require(nativePeriod != null && nativePeriod.outstandingMinor().equals(outstanding(leg)), "JOURNAL_MISMATCH");
        }
    }

    private JsonNode latestForecast(String accountKey) {
        var rows = store.jdbc().queryForList(
                "select snapshot_json from m_mnzl_r_risk_forecast where account_key=? order by as_of_date desc,forecast_version desc limit 1",
                accountKey);
        require(!rows.isEmpty(), "SOURCE_CHANGED");
        return json.read(string(rows.getFirst(), "snapshot_json"));
    }

    private void saveForecast(ReceivablesExecution e, String key, JsonNode forecast, BigInteger allowance) {
        store.insert("risk_forecast",
                ReceivablesStore.key(e.scope, "forecast", key + ":" + text(forecast, "forecastId") + ":" + e.operationKey),
                Map.ofEntries(Map.entry("scope_key", e.scope), Map.entry("account_key", key),
                        Map.entry("forecast_id", text(forecast, "forecastId")),
                        Map.entry("forecast_version", Long.parseLong(text(forecast, "forecastVersion"))),
                        Map.entry("as_of_date", date(forecast, "asOfDate")), Map.entry("stage", text(forecast, "stage")),
                        Map.entry("allowance_minor", allowance.toString()), Map.entry("snapshot_json", json.write(forecast)),
                        Map.entry("operation_key", e.operationKey)));
    }

    public void impair(ReceivablesExecution e) {
        var account = account(e);
        Position p = measurement.position(account, e.date);
        JsonNode forecast = e.command.get("forecast");
        BigInteger target = measurement.allowance(p, measurement.state(account).segment(), forecast);
        pair(e.lines, "impairmentExpense", "lossAllowance", target.subtract(amount(account, "allowance_minor")),
                string(account, "external_id"), string(account, "deal_id"), "IMPAIRMENT");
        store.update("account", string(account, "record_key"),
                Map.of("allowance_minor", target.toString(), "stage", text(forecast, "stage")));
        saveForecast(e, string(account, "record_key"), forecast, target);
    }

    private void remeasureAfter(ReceivablesExecution e, String key, boolean required) {
        var account = store.require("account", key);
        Position position = measurement.position(account, e.date);
        if (position.contractualOutstandingMinor().signum() == 0) {
            return;
        }
        JsonNode forecast = e.command.get("riskForecastAfter");
        require(!required || forecast != null, "SOURCE_CHANGED");
        if (forecast == null) {
            return;
        }
        require(date(forecast, "asOfDate").equals(e.date), "SOURCE_CHANGED");
        BigInteger allowance = measurement.allowance(position, measurement.state(account).segment(), forecast);
        pair(e.lines, "impairmentExpense", "lossAllowance", allowance.subtract(amount(account, "allowance_minor")),
                string(account, "external_id"), string(account, "deal_id"), "IMPAIRMENT");
        store.update("account", key, Map.of("allowance_minor", allowance.toString(), "stage", text(forecast, "stage")));
        saveForecast(e, key, forecast, allowance);
    }

    public void settle(ReceivablesExecution e, boolean recourse) {
        var account = account(e);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        Position before = measurement.position(account, e.date);
        var selected = new LinkedHashMap<String, BigInteger>();
        for (JsonNode flow : e.command.get("cashflowIds")) {
            var leg = store.require("leg", ReceivablesStore.key(e.scope, "leg", key + ":" + flow.asText()));
            require(selected.put(flow.asText(), outstanding(leg)) == null && outstanding(leg).signum() > 0, "INVALID_DATA");
        }
        BigInteger payoff = minor(e.command, "payoffMinor");
        BigInteger released = allocateAllowance(account, before, selected);
        var partial = ReceivableEvents.settlePortions(before, selected, payoff, released);
        var result = partial.settlement();
        JsonNode source = e.command.get("settlementSource");
        String control;
        if (text(source, "kind").equals("BANK_CASH")) {
            cash.consumeReceipt(e, text(source, "cashMovementId"), payoff, deal);
            control = "cashUnapplied";
        } else {
            cash.consumeHelClearing(e, text(source, "helFundingOperationId"), payoff, deal);
            control = "helSettlementClearing";
        }
        var payments = new ArrayList<Allocation>();
        var adjustments = new ArrayList<Allocation>();
        BigInteger remaining = payoff;
        for (String flow : selected.keySet().stream().sorted().toList()) {
            var leg = store.require("leg", ReceivablesStore.key(e.scope, "leg", key + ":" + flow));
            BigInteger face = selected.get(flow);
            BigInteger paid = remaining.min(face);
            BigInteger adjusted = face.subtract(paid);
            if (paid.signum() > 0) {
                payments.add(new Allocation(number(leg, "native_period_id"), paid));
            }
            if (adjusted.signum() > 0) {
                adjustments.add(new Allocation(number(leg, "native_period_id"), adjusted));
            }
            remaining = remaining.subtract(paid);
            store.update("leg", string(leg, "record_key"), Map.of("collected_minor", amount(leg, "collected_minor").add(paid).toString(),
                    "adjusted_minor", amount(leg, "adjusted_minor").add(adjusted).toString()));
            line(e.lines, LocalDate.parse(string(leg, "due_date")).isAfter(e.date) ? "contractualReceivable" : "installmentDues", "CREDIT",
                    face, id, deal, "FACE");
        }
        if (!payments.isEmpty()) {
            e.nativeTransactions.add(Long.toString(nativeBridge
                    .collect(number(account, "native_loan_id"), e.date, payments, "R" + e.operationKey + ":cash").transactionId()));
        }
        if (!adjustments.isEmpty()) {
            e.nativeTransactions.add(Long.toString(nativeBridge.adjustFace(number(account, "native_loan_id"), e.date,
                    AdjustmentType.COMMERCIAL_SETTLEMENT_ADJUSTMENT, adjustments, "R" + e.operationKey + ":adjust").transactionId()));
        }
        line(e.lines, control, "DEBIT", payoff, id, deal, "SETTLEMENT");
        line(e.lines, "deferredDiscount", "DEBIT", result.deferredDiscountMinor(), id, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "DEBIT", result.deferredIntegralFeeMinor(), id, deal, "INTEGRAL_FEE");
        BigInteger share = recourse ? BigInteger.ZERO : result.developerShareMinor();
        line(e.lines, "developerPayable", "CREDIT", share, id, deal, "DEVELOPER_ADJUSTMENT");
        line(e.lines, "portfolioInterestIncome", "CREDIT",
                result.financierIncomeMinor().add(recourse ? result.developerShareMinor() : BigInteger.ZERO), id, deal, "SETTLEMENT");
        pair(e.lines, "lossAllowance", "impairmentExpense", released, id, deal, "IMPAIRMENT");
        applyRemaining(e, account, partial.remainingMeasurement(measurement.state(account).segment()), released);
        if (share.signum() > 0) {
            createLot(e, key,
                    new ReceivableEvents.AdjustmentLot(e.date, e.date, BigDecimal.ZERO, ReceivableEvents.Direction.PAYABLE, share, share),
                    ":settlement");
        }
        if (partial.retainedPosition().contractualOutstandingMinor().signum() == 0) {
            store.update("account", key, Map.of("closure_reason", text(source, "closureReason")));
        }
        remeasureAfter(e, key, true);
        reconcileNative(store.require("account", key), partial.retainedPosition(), e.date);
    }

    public void reset(ReceivablesExecution e) {
        var account = account(e);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        var state = measurement.state(account);
        Position before = ReceivablesMath.position(state, e.date);
        var reset = ReceivableEvents.reset(state, e.date, decimal(e.command, "corridorRate").add(decimal(e.command, "spread")));
        if (reset.futureSegment() == null) {
            return;
        }
        require(reset.lot() == null || reset.lot().dueDate().equals(date(e.command, "developerAdjustmentDueDate")), "SOURCE_CHANGED");
        var flows = new ArrayList<Cashflow>(reset.futureSegment().cashflows());
        for (Leg leg : before.legs()) {
            if (leg.discountDays() == 0 && leg.outstandingMinor().signum() > 0) {
                flows.add(new Cashflow(leg.cashflow().cashflowId(), leg.cashflow().dueDate(), leg.outstandingMinor()));
            }
        }
        Segment next = new Segment(e.date, flows, reset.futureSegment().grossBasisMinor().add(before.pastDueMinor()),
                reset.futureSegment().netBasisMinor().add(before.pastDueMinor()), reset.futureSegment().grossYield(),
                reset.futureSegment().netEir());
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        flows.forEach(f -> outstanding.put(f.cashflowId(), f.amountMinor()));
        Position after = ReceivablesMath.position(next, e.date, outstanding);
        measurement.saveState(e.scope, key, e.operationKey, new MeasurementState(next, after, outstanding),
                string(e.configuration, "calculator_build"));
        store.update("account", key,
                Map.of("gross_minor", after.grossPurchaseBasisMinor().toString(), "net_minor", after.amortizedCostMinor().toString()));
        if (reset.deltaMinor().signum() > 0) {
            pair(e.lines, "deferredDiscount", "developerPayable", reset.deltaMinor(), id, deal, "DEVELOPER_ADJUSTMENT");
        } else {
            pair(e.lines, "developerReceivable", "deferredDiscount", reset.deltaMinor().negate(), id, deal, "DEVELOPER_ADJUSTMENT");
        }
        if (reset.lot() != null) {
            createLot(e, key, reset.lot(), ":reset");
        }
    }

    private void createLot(ReceivablesExecution e, String accountKey, ReceivableEvents.AdjustmentLot lot, String suffix) {
        String id = text(e.command, "operationId") + suffix;
        String key = ReceivablesStore.key(e.scope, "lot", id);
        store.insert("developer_lot", key, Map.ofEntries(Map.entry("scope_key", e.scope), Map.entry("account_key", accountKey),
                Map.entry("lot_id", id), Map.entry("operation_key", e.operationKey), Map.entry("direction", lot.direction().name()),
                Map.entry("effective_date", lot.effectiveDate()), Map.entry("due_date", lot.dueDate()),
                Map.entry("initial_minor", lot.principalMinor().toString()), Map.entry("carrying_minor", lot.principalMinor().toString()),
                Map.entry("settled_minor", "0"), Map.entry("allowance_minor", "0"), Map.entry("rate", lot.rate().toPlainString()),
                Map.entry("watermark", e.date), Map.entry("snapshot_json", json.write(lot))));
        e.lotKeys.add(key);
    }

    public void reverse(ReceivablesExecution e) {
        var account = account(e);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        require(!Set.of("ASSIGNED_OUT", "WRITTEN_OFF").contains(string(account, "status")), "RECOVERY_REQUIRED");
        String original = ReceivablesStore.key(e.scope, "operation", text(e.command, "originalOperationId"));
        var rows = store.children("collection", "operation_key", original);
        require(!rows.isEmpty(), "SOURCE_CHANGED");
        Set<String> ids = new HashSet<>();
        e.command.get("allocationIds").forEach(v -> ids.add(v.asText()));
        require(rows.size() == ids.size() && rows.stream()
                .allMatch(r -> ids.contains(string(r, "allocation_id")) && key.equals(string(r, "account_key"))
                        && r.get("reversed_by") == null
                        && Long.toString(number(r, "native_transaction_id")).equals(text(e.command, "nativeTransactionId"))),
                "SOURCE_CHANGED");
        e.nativeTransactions.add(Long.toString(
                nativeBridge.reverse(number(account, "native_loan_id"), Long.parseLong(text(e.command, "nativeTransactionId")), e.date)
                        .transactionId()));
        var state = measurement.state(account);
        var outstanding = new LinkedHashMap<>(state.outstandingMinor());
        BigInteger total = BigInteger.ZERO;
        for (var row : rows) {
            var leg = store.require("leg", string(row, "leg_key"));
            BigInteger value = amount(row, "amount_minor");
            total = total.add(value);
            outstanding.merge(string(leg, "cashflow_id"), value, BigInteger::add);
            store.update("leg", string(row, "leg_key"),
                    Map.of("collected_minor", amount(leg, "collected_minor").subtract(value).toString()));
            store.update("collection", string(row, "record_key"), Map.of("reversed_by", e.operationKey));
            cash.restoreReceipt(e, string(row, "cash_source_key"), value, deal);
        }
        Position before = ReceivablesMath.position(state, e.date);
        Position raw = ReceivablesMath.position(state.segment(), e.date, outstanding);
        Position restored = new Position(e.date, before.contractualOutstandingMinor().add(total), before.notYetDueMinor(),
                before.pastDueMinor().add(total), before.grossPurchaseBasisMinor().add(total), before.amortizedCostMinor().add(total),
                before.deferredDiscountMinor(), before.deferredIntegralFeeMinor(), raw.daysPastDue(), raw.analyticalGrossBasis(),
                raw.analyticalNetBasis(), raw.legs());
        measurement.saveState(e.scope, key, e.operationKey, new MeasurementState(state.segment(), restored, outstanding),
                string(e.configuration, "calculator_build"));
        var update = new LinkedHashMap<String, Object>();
        update.put("face_minor", restored.contractualOutstandingMinor().toString());
        update.put("gross_minor", restored.grossPurchaseBasisMinor().toString());
        update.put("net_minor", restored.amortizedCostMinor().toString());
        update.put("status", "ACTIVE");
        update.put("closure_reason", null);
        store.update("account", key, update);
        pair(e.lines, "installmentDues", "cashUnapplied", total, id, deal, "CASH");
        e.reversalOf = ReceivablesStore.key(e.scope, "event", original);
        reconcileNative(store.require("account", key), restored, e.date);
    }

    public void recourse(ReceivablesExecution e) {
        require(text(e.command, "developerOrganizationId").equals(text(e.command.get("scope"), "developerOrganizationId")),
                "OWNERSHIP_CONFLICT");
        if (text(e.command, "recoveryKind").equals("DUE_REIMBURSEMENT")) {
            collect(e, true);
            remeasureAfter(e, e.accountKey(e.subjectId()), true);
            return;
        }
        var account = account(e);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        Position position = measurement.position(account, e.date);
        BigInteger payoff = BigInteger.ZERO;
        Set<String> allocationIds = new HashSet<>();
        for (JsonNode allocation : e.command.get("allocations")) {
            require(allocationIds.add(text(allocation, "allocationId"))
                    && text(allocation, "cashMovementId").equals(text(e.command, "cashMovementId")), "BANK_PROOF_MISMATCH");
            var leg = store.require("leg", ReceivablesStore.key(e.scope, "leg", key + ":" + text(allocation, "cashflowId")));
            require(text(allocation, "installmentId").equals(string(leg, "installment_id")), "SOURCE_CHANGED");
            payoff = payoff.add(minor(allocation, "amountMinor"));
            e.allocationIds.add(text(allocation, "allocationId"));
        }
        boolean approved = payoff.compareTo(position.grossPurchaseBasisMinor()) < 0;
        if (approved) {
            configuration.requireWorkout(e, "BUYBACK", payoff);
        }
        var result = ReceivableEvents.settleBuyback(position.contractualOutstandingMinor(), position.grossPurchaseBasisMinor(),
                position.amortizedCostMinor(), payoff, amount(account, "allowance_minor"), approved);
        cash.consumeReceipt(e, text(e.command, "cashMovementId"), payoff, deal);
        var assignments = new ArrayList<Allocation>();
        for (var leg : store.children("leg", "account_key", key)) {
            if (outstanding(leg).signum() > 0) {
                BigInteger face = outstanding(leg);
                assignments.add(new Allocation(number(leg, "native_period_id"), face));
                line(e.lines, LocalDate.parse(string(leg, "due_date")).isAfter(e.date) ? "contractualReceivable" : "installmentDues",
                        "CREDIT", face, id, deal, "FACE");
                store.update("leg", string(leg, "record_key"),
                        Map.of("adjusted_minor", amount(leg, "adjusted_minor").add(face).toString()));
            }
        }
        e.nativeTransactions.add(Long.toString(nativeBridge
                .adjustFace(number(account, "native_loan_id"), e.date, AdjustmentType.ASSIGNMENT_OUT, assignments, "R" + e.operationKey)
                .transactionId()));
        line(e.lines, "cashUnapplied", "DEBIT", payoff, id, deal, "SETTLEMENT");
        line(e.lines, "deferredDiscount", "DEBIT", result.deferredDiscountMinor(), id, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "DEBIT", result.deferredIntegralFeeMinor(), id, deal, "INTEGRAL_FEE");
        line(e.lines, "portfolioInterestIncome", "CREDIT", result.financierIncomeMinor(), id, deal, "SETTLEMENT");
        pair(e.lines, "lossAllowance", "impairmentExpense", amount(account, "allowance_minor"), id, deal, "IMPAIRMENT");
        closeAccountState(e, account, "ASSIGNED_OUT", "ASSIGNED_OUT");
        reconcileNative(store.require("account", key), measurement.position(store.require("account", key), e.date), e.date);
    }

    public void substitute(ReceivablesExecution e) {
        var old = account(e);
        String oldKey = string(old, "record_key");
        String oldId = string(old, "external_id");
        String newId = text(e.command, "replacementAccountId");
        String newKey = e.accountKey(newId);
        String deal = string(old, "deal_id");
        require(store.find("account", newKey) == null, "OWNERSHIP_CONFLICT");
        Position before = measurement.position(old, e.date);
        var replacement = ReceivableEvents.substitute(before, measurement.flows(e.command.get("replacementCashflows")),
                amount(old, "allowance_minor"));
        var allocations = new ArrayList<Allocation>();
        for (var leg : store.children("leg", "account_key", oldKey)) {
            if (outstanding(leg).signum() > 0) {
                allocations.add(new Allocation(number(leg, "native_period_id"), outstanding(leg)));
                line(e.lines, LocalDate.parse(string(leg, "due_date")).isAfter(e.date) ? "contractualReceivable" : "installmentDues",
                        "CREDIT", outstanding(leg), oldId, deal, "FACE");
                store.update("leg", string(leg, "record_key"),
                        Map.of("adjusted_minor", amount(leg, "adjusted_minor").add(outstanding(leg)).toString()));
            }
        }
        e.nativeTransactions.add(Long.toString(nativeBridge.adjustFace(number(old, "native_loan_id"), e.date, AdjustmentType.ASSIGNMENT_OUT,
                allocations, "R" + e.operationKey + ":old").transactionId()));
        long client = nativeBridge.createClient(
                new ClientIdentity("R" + ReceivablesStore.key(e.scope, "customer", text(e.command, "replacementCustomerReferenceId")),
                        text(e.command, "replacementCustomerReferenceId"), number(e.configuration, "office_id")));
        Booking booking = nativeBridge.bookExactFace(client, number(e.configuration, "product_id"), "R" + newKey, e.date, replacement
                .replacement().cashflows().stream().map(f -> new FaceLeg(f.cashflowId(), f.dueDate(), f.amountMinor())).toList());
        e.nativeTransactions.add(Long.toString(booking.activationTransactionId()));
        Map<String, BigInteger> newAmounts = new LinkedHashMap<>();
        replacement.replacement().cashflows().forEach(f -> newAmounts.put(f.cashflowId(), f.amountMinor()));
        Position after = ReceivablesMath.position(replacement.replacement(), e.date, newAmounts);
        BigInteger allowance = measurement.allowance(after, replacement.replacement(), e.command.get("replacementRiskForecast"));
        var row = new LinkedHashMap<String, Object>(old);
        row.remove("record_key");
        row.put("external_id", newId);
        row.put("customer_ref", text(e.command, "replacementCustomerReferenceId"));
        row.put("native_loan_id", booking.loanId());
        row.put("native_client_id", client);
        row.put("activation_date", e.date);
        row.put("last_effective_date", e.date);
        row.put("version", 0L);
        row.put("face_minor", after.contractualOutstandingMinor().toString());
        row.put("gross_minor", after.grossPurchaseBasisMinor().toString());
        row.put("net_minor", after.amortizedCostMinor().toString());
        row.put("allowance_minor", allowance.toString());
        row.put("active_segment_key", ReceivablesStore.key(e.scope, "segment", e.operationKey + ":" + newKey));
        row.put("last_event_key", null);
        row.put("created_at", java.sql.Timestamp.from(java.time.Instant.now()));
        ObjectNode terms = (ObjectNode) json.read(string(old, "terms_json"));
        terms.set("cashflows", e.command.get("replacementCashflows"));
        row.put("terms_json", json.write(terms));
        measurement.saveState(e.scope, newKey, e.operationKey, new MeasurementState(replacement.replacement(), after, newAmounts),
                string(e.configuration, "calculator_build"));
        store.insert("account", newKey, row);
        saveLegs(e, newKey, e.command.get("replacementCashflows"), booking.sourcePeriodIds());
        saveForecast(e, newKey, e.command.get("replacementRiskForecast"), allowance);
        line(e.lines, "deferredDiscount", "DEBIT", before.deferredDiscountMinor(), oldId, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "DEBIT", before.deferredIntegralFeeMinor(), oldId, deal, "INTEGRAL_FEE");
        line(e.lines, "contractualReceivable", "DEBIT", after.contractualOutstandingMinor(), newId, deal, "FACE");
        line(e.lines, "deferredDiscount", "CREDIT", after.deferredDiscountMinor(), newId, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "CREDIT", after.deferredIntegralFeeMinor(), newId, deal, "INTEGRAL_FEE");
        pair(e.lines, "lossAllowance", "impairmentExpense", amount(old, "allowance_minor"), oldId, deal, "IMPAIRMENT");
        pair(e.lines, "impairmentExpense", "lossAllowance", allowance, newId, deal, "IMPAIRMENT");
        closeAccountState(e, old, "ASSIGNED_OUT", "ASSIGNED_OUT");
        Set<String> suppliedLots = new HashSet<>();
        e.command.get("developerAdjustmentAllocationIds").forEach(v -> suppliedLots.add(v.asText()));
        Set<String> actualLots = new HashSet<>();
        store.children("developer_lot", "account_key", oldKey).forEach(v -> actualLots.add(string(v, "lot_id")));
        require(suppliedLots.equals(actualLots), "SOURCE_CHANGED");
        for (JsonNode lotId : e.command.get("developerAdjustmentAllocationIds")) {
            var lot = store.require("developer_lot", ReceivablesStore.key(e.scope, "lot", lotId.asText()));
            require(oldKey.equals(string(lot, "account_key")), "OWNERSHIP_CONFLICT");
            store.update("developer_lot", string(lot, "record_key"), Map.of("account_key", newKey));
            e.lotKeys.add(string(lot, "record_key"));
        }
        e.accountKeys.add(newKey);
        reconcileNative(store.require("account", newKey), after, e.date);
    }

    private void closeAccountState(ReceivablesExecution e, Map<String, Object> account, String status, String reason) {
        var state = measurement.state(account);
        var empty = new LinkedHashMap<String, BigInteger>();
        state.outstandingMinor().keySet().forEach(id -> empty.put(id, BigInteger.ZERO));
        Position position = ReceivablesMath.position(state.segment(), e.date, empty);
        measurement.saveState(e.scope, string(account, "record_key"), e.operationKey,
                new MeasurementState(state.segment(), position, empty), string(e.configuration, "calculator_build"));
        store.update("account", string(account, "record_key"), Map.of("face_minor", "0", "gross_minor", "0", "net_minor", "0",
                "allowance_minor", "0", "status", status, "closure_reason", reason));
    }

    public void workout(ReceivablesExecution e) {
        var account = account(e);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        Position before = measurement.position(account, e.date);
        String classification = text(e.command, "classification");
        var oldAllocations = new ArrayList<Allocation>();
        for (var leg : store.children("leg", "account_key", key)) {
            if (outstanding(leg).signum() > 0) {
                oldAllocations.add(new Allocation(number(leg, "native_period_id"), outstanding(leg)));
                line(e.lines, LocalDate.parse(string(leg, "due_date")).isAfter(e.date) ? "contractualReceivable" : "installmentDues",
                        "CREDIT", outstanding(leg), id, deal, "FACE");
                store.update("leg", string(leg, "record_key"),
                        Map.of("adjusted_minor", amount(leg, "adjusted_minor").add(outstanding(leg)).toString()));
            }
        }
        line(e.lines, "deferredDiscount", "DEBIT", before.deferredDiscountMinor(), id, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "DEBIT", before.deferredIntegralFeeMinor(), id, deal, "INTEGRAL_FEE");
        if (classification.equals("WRITE_OFF")) {
            e.nativeTransactions.add(Long.toString(nativeBridge
                    .adjustFace(number(account, "native_loan_id"), e.date, AdjustmentType.WRITE_OFF, oldAllocations, "R" + e.operationKey)
                    .transactionId()));
            line(e.lines, "lossAllowance", "DEBIT", amount(account, "allowance_minor"), id, deal, "IMPAIRMENT");
            line(e.lines, "impairmentExpense", "DEBIT", before.amortizedCostMinor().subtract(amount(account, "allowance_minor")), id, deal,
                    "IMPAIRMENT");
            closeAccountState(e, account, "WRITTEN_OFF", "WRITE_OFF");
            return;
        }
        if (classification.equals("DERECOGNITION")) {
            derecognize(e, account, before, oldAllocations);
            return;
        }
        require(classification.equals("MODIFICATION"), "RECOVERY_REQUIRED");
        var oldState = measurement.state(account);
        List<Cashflow> flows = measurement.flows(e.command.get("modifiedCashflows"));
        var modification = ReceivableEvents.modify(e.date, flows, oldState.segment().netEir().rate(), before.amortizedCostMinor());
        BigInteger gross = ReceivablesMath.minor(ReceivablesMath.pv(e.date, flows, oldState.segment().grossYield().rate()));
        Segment next = new Segment(e.date, flows, gross, modification.modifiedNetBasisMinor(), oldState.segment().grossYield(),
                oldState.segment().netEir());
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        flows.forEach(f -> outstanding.put(f.cashflowId(), f.amountMinor()));
        Position after = ReceivablesMath.position(next, e.date, outstanding);
        for (Cashflow flow : flows) {
            require(store.find("leg", ReceivablesStore.key(e.scope, "leg", key + ":" + flow.cashflowId())) == null, "SOURCE_CHANGED");
        }
        Booking replaced = nativeBridge.replaceSchedule(number(account, "native_loan_id"), e.date,
                flows.stream().map(f -> new FaceLeg(f.cashflowId(), f.dueDate(), f.amountMinor())).toList(), "R" + e.operationKey);
        e.nativeTransactions.add(Long.toString(replaced.activationTransactionId()));
        saveLegs(e, key, e.command.get("modifiedCashflows"), replaced.sourcePeriodIds());
        measurement.saveState(e.scope, key, e.operationKey, new MeasurementState(next, after, outstanding),
                string(e.configuration, "calculator_build"));
        BigInteger allowance = measurement.allowance(after, next, e.command.get("riskForecast"));
        line(e.lines, "contractualReceivable", "DEBIT", after.contractualOutstandingMinor(), id, deal, "FACE");
        line(e.lines, "deferredDiscount", "CREDIT", after.deferredDiscountMinor(), id, deal, "DISCOUNT");
        line(e.lines, "deferredIntegralFee", "CREDIT", after.deferredIntegralFeeMinor(), id, deal, "INTEGRAL_FEE");
        line(e.lines, "modificationGainLoss", "CREDIT", after.amortizedCostMinor().subtract(before.amortizedCostMinor()), id, deal,
                "MODIFICATION");
        pair(e.lines, "impairmentExpense", "lossAllowance", allowance.subtract(amount(account, "allowance_minor")), id, deal, "IMPAIRMENT");
        store.update("account", key,
                Map.of("face_minor", after.contractualOutstandingMinor().toString(), "gross_minor",
                        after.grossPurchaseBasisMinor().toString(), "net_minor", after.amortizedCostMinor().toString(), "allowance_minor",
                        allowance.toString()));
        saveForecast(e, key, e.command.get("riskForecast"), allowance);
    }

    private void derecognize(ReceivablesExecution e, Map<String, Object> account, Position before, List<Allocation> assignments) {
        JsonNode outcome = e.command.get("legalOutcome");
        require(outcome != null, "APPROVAL_SCOPE_CHANGED");
        String kind = text(outcome, "kind");
        BigInteger consideration = minor(e.command, "approvedConsiderationMinor");
        configuration.requireWorkout(e, kind, consideration);
        String key = string(account, "record_key");
        String id = string(account, "external_id");
        String deal = string(account, "deal_id");
        e.nativeTransactions.add(Long.toString(nativeBridge.adjustFace(number(account, "native_loan_id"), e.date,
                AdjustmentType.ASSIGNMENT_OUT, assignments, "R" + e.operationKey + ":old").transactionId()));
        pair(e.lines, "lossAllowance", "impairmentExpense", amount(account, "allowance_minor"), id, deal, "IMPAIRMENT");
        line(e.lines, "modificationGainLoss", "CREDIT", consideration.subtract(before.amortizedCostMinor()), id, deal, "MODIFICATION");
        closeAccountState(e, account, "ASSIGNED_OUT", "WORKOUT");
        if (kind.equals("RELEASE")) {
            require(e.command.get("modifiedCashflows").isEmpty(), "SOURCE_CHANGED");
            JsonNode source = outcome.get("consideration");
            if (text(source, "kind").equals("NONE")) {
                require(consideration.signum() == 0, "BANK_PROOF_MISMATCH");
            } else {
                require(consideration.equals(minor(source, "amountMinor")), "BANK_PROOF_MISMATCH");
                cash.consumeAllocations(e, json.value(List.of(text(source, "bankAllocationId"))), consideration, "RECEIPT_UNAPPLIED", deal);
                line(e.lines, "cashUnapplied", "DEBIT", consideration, id, deal, "MODIFICATION");
            }
            return;
        }
        String newId = text(outcome, "replacementAccountId");
        String newKey = e.accountKey(newId);
        require(store.find("account", newKey) == null && consideration.signum() > 0, "OWNERSHIP_CONFLICT");
        var flows = measurement.flows(e.command.get("modifiedCashflows"));
        for (JsonNode flow : e.command.get("modifiedCashflows")) {
            require(text(flow, "receivableId").equals(newId)
                    && text(flow, "scheduleVersionId").equals(text(outcome, "replacementScheduleVersionId")), "SOURCE_CHANGED");
        }
        Segment segment = ReceivablesMath.segment(e.date, flows, consideration, consideration);
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        flows.forEach(flow -> outstanding.put(flow.cashflowId(), flow.amountMinor()));
        Position position = ReceivablesMath.position(segment, e.date, outstanding);
        BigInteger allowance = measurement.allowance(position, segment, e.command.get("riskForecast"));
        Booking booking = nativeBridge.bookExactFace(number(account, "native_client_id"), number(e.configuration, "product_id"),
                "R" + newKey, e.date,
                flows.stream().map(flow -> new FaceLeg(flow.cashflowId(), flow.dueDate(), flow.amountMinor())).toList());
        e.nativeTransactions.add(Long.toString(booking.activationTransactionId()));
        var row = new LinkedHashMap<String, Object>(account);
        row.remove("record_key");
        row.put("external_id", newId);
        row.put("native_loan_id", booking.loanId());
        row.put("activation_date", e.date);
        row.put("last_effective_date", e.date);
        row.put("version", 0L);
        row.put("status", "ACTIVE");
        row.put("closure_reason", null);
        row.put("last_event_key", null);
        row.put("face_minor", position.contractualOutstandingMinor().toString());
        row.put("gross_minor", consideration.toString());
        row.put("net_minor", consideration.toString());
        row.put("allowance_minor", allowance.toString());
        row.put("stage", text(e.command.get("riskForecast"), "stage"));
        row.put("source_hash", text(outcome, "replacementSourceHash"));
        row.put("basis_hash", text(outcome, "replacementSourceHash"));
        row.put("active_segment_key", ReceivablesStore.key(e.scope, "segment", e.operationKey + ":" + newKey));
        row.put("created_at", java.sql.Timestamp.from(java.time.Instant.now()));
        var terms = (ObjectNode) json.read(string(account, "terms_json"));
        terms.set("cashflows", e.command.get("modifiedCashflows"));
        terms.put("settlementDate", e.date.toString());
        terms.put("sourceHash", text(outcome, "replacementSourceHash"));
        terms.set("acceptedAccountPrices", json.value(List.of(Map.of("accountId", newId, "grossPurchasePriceMinor",
                consideration.toString(), "integralFeeMinor", "0", "netPurchaseCashMinor", consideration.toString()))));
        row.put("terms_json", json.write(terms));
        measurement.saveState(e.scope, newKey, e.operationKey, new MeasurementState(segment, position, outstanding),
                string(e.configuration, "calculator_build"));
        store.insert("account", newKey, row);
        saveLegs(e, newKey, e.command.get("modifiedCashflows"), booking.sourcePeriodIds());
        saveForecast(e, newKey, e.command.get("riskForecast"), allowance);
        line(e.lines, "contractualReceivable", "DEBIT", position.contractualOutstandingMinor(), newId, deal, "FACE");
        line(e.lines, "deferredDiscount", "CREDIT", position.deferredDiscountMinor(), newId, deal, "DISCOUNT");
        pair(e.lines, "impairmentExpense", "lossAllowance", allowance, newId, deal, "IMPAIRMENT");
        e.accountKeys.add(newKey);
        reconcileNative(store.require("account", newKey), position, e.date);
    }

}
