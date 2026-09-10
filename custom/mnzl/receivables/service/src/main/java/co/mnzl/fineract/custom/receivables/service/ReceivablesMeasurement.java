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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import co.mnzl.fineract.receivables.math.CreditAndFunding;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceivablesMeasurement {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;

    public static LocalDate date(JsonNode node, String key) {
        return LocalDate.parse(text(node, key));
    }

    public static BigDecimal decimal(JsonNode node, String key) {
        return new BigDecimal(text(node, key));
    }

    public static BigInteger amount(Map<String, Object> row, String key) {
        return new BigInteger(string(row, key));
    }

    public List<Cashflow> flows(JsonNode flows) {
        List<Cashflow> result = new ArrayList<>();
        for (JsonNode flow : flows) {
            result.add(new Cashflow(text(flow, "cashflowId"), date(flow, "dueDate"), minor(flow, "amountMinor")));
        }
        return result;
    }

    public Purchase purchase(JsonNode basis, String accountId) {
        List<JsonNode> accepted = new ArrayList<>();
        basis.get("acceptedAccountPrices").forEach(accepted::add);
        Purchase purchase = ReceivablesMath.price(date(basis, "settlementDate"),
                flows(json.value(java.util.stream.StreamSupport.stream(basis.get("cashflows").spliterator(), false)
                        .filter(flow -> text(flow, "receivableId").equals(accountId)).toList())),
                decimal(basis, "corridorRate").add(decimal(basis, "spread")), decimal(basis, "feeRate"));
        if (!accepted.isEmpty()) {
            JsonNode price = accepted.stream().filter(p -> text(p, "accountId").equals(accountId)).findFirst()
                    .orElseThrow(() -> new ReceivablesException("SOURCE_CHANGED"));
            require(minor(price, "grossPurchasePriceMinor").equals(purchase.grossPurchasePriceMinor())
                    && minor(price, "integralFeeMinor").equals(purchase.integralFeeMinor())
                    && minor(price, "netPurchaseCashMinor").equals(purchase.netPurchaseCashMinor()), "SOURCE_CHANGED");
        }
        return purchase;
    }

    public MeasurementState state(Map<String, Object> account) {
        var segment = store.require("segment", string(account, "active_segment_key"));
        return json.convert(json.read(string(segment, "snapshot_json")), MeasurementState.class);
    }

    public Position position(Map<String, Object> account, LocalDate date) {
        return ReceivablesMath.position(state(account), date);
    }

    public void saveState(String scope, String accountKey, String operationKey, MeasurementState state, String calculatorBuild) {
        String segmentKey = ReceivablesStore.key(scope, "segment", operationKey + ":" + accountKey);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope_key", scope);
        fields.put("account_key", accountKey);
        fields.put("effective_from", state.boundaryPosition().businessDate());
        fields.put("effective_through", null);
        fields.put("gross_basis", state.segment().grossBasisMinor().toString());
        fields.put("net_basis", state.segment().netBasisMinor().toString());
        fields.put("gross_yield", state.segment().grossYield().rate().toPlainString());
        fields.put("net_eir", state.segment().netEir().rate().toPlainString());
        fields.put("calculator_version", ReceivablesConfiguration.CALCULATION);
        fields.put("calculator_build", calculatorBuild);
        fields.put("snapshot_json", json.write(state));
        fields.put("source_operation_key", operationKey);
        var previous = store.find("account", accountKey);
        if (previous != null && previous.get("active_segment_key") != null) {
            store.update("segment", string(previous, "active_segment_key"),
                    Map.of("effective_through", state.boundaryPosition().businessDate()));
        }
        if (store.find("segment", segmentKey) == null) {
            store.insert("segment", segmentKey, fields);
        } else {
            store.update("segment", segmentKey, fields);
        }
        for (Leg leg : state.boundaryPosition().legs()) {
            String legKey = ReceivablesStore.key(scope, "leg", accountKey + ":" + leg.cashflow().cashflowId());
            String rowKey = ReceivablesStore.key(scope, "segment-leg", segmentKey + ":" + leg.cashflow().cashflowId());
            var legFields = Map.of("scope_key", scope, "segment_key", segmentKey, "leg_key", legKey, "gross_basis",
                    leg.grossBasis().toPlainString(), "net_basis", leg.netBasis().toPlainString(), "snapshot_json", json.write(leg));
            if (store.find("segment_leg", rowKey) == null) {
                store.insert("segment_leg", rowKey, legFields);
            } else {
                store.update("segment_leg", rowKey, legFields);
            }
        }
        if (previous != null) {
            store.update("account", accountKey, Map.of("active_segment_key", segmentKey));
        }
    }

    public List<CreditAndFunding.Scenario> scenarios(JsonNode forecast) {
        List<CreditAndFunding.Scenario> scenarios = new ArrayList<>();
        for (JsonNode scenario : forecast.get("scenarios")) {
            List<CreditAndFunding.Recovery> recoveries = new ArrayList<>();
            for (JsonNode recovery : scenario.get("recoveries")) {
                recoveries.add(new CreditAndFunding.Recovery(text(recovery, "sourceCashflowId"),
                        CreditAndFunding.Payer.valueOf(text(recovery, "payer")), date(recovery, "date"), minor(recovery, "amountMinor")));
            }
            scenarios.add(new CreditAndFunding.Scenario(text(scenario, "scenarioId"), decimal(scenario, "probability"),
                    scenario.get("defaultDate").isNull() ? null : date(scenario, "defaultDate"), recoveries));
        }
        return scenarios;
    }

    public BigInteger allowance(Position position, Segment segment, JsonNode forecast) {
        require(!date(forecast, "asOfDate").isAfter(position.businessDate())
                && !date(forecast, "validThroughDate").isBefore(position.businessDate()), "SOURCE_CHANGED");
        int requested = Integer.parseInt(text(forecast, "stage").substring(6));
        int stage = Math.max(requested, CreditAndFunding.stage(position.daysPastDue(), false, false));
        require(stage == requested, "SOURCE_CHANGED");
        return CreditAndFunding.impairment(position, segment.netEir().rate(), stage, scenarios(forecast)).lossAllowanceMinor();
    }

    public ObjectNode wirePosition(Map<String, Object> account, Position p, BigInteger allowance, String side) {
        ObjectNode result = measures(p, allowance, side);
        result.set("scope", json.read(string(account, "scope_json")));
        result.put("tenantId", configuration.tenantId());
        result.put("dealId", string(account, "deal_id"));
        result.put("accountId", string(account, "external_id"));
        result.put("receivableId", string(account, "external_id"));
        result.put("businessDate", p.businessDate().toString());
        result.put("boundarySide", side);
        result.put("nativeLoanId", Long.toString(number(account, "native_loan_id")));
        result.put("nativeLoanStatus", string(account, "status"));
        result.put("accountVersion", Long.toString(number(account, "version")));
        result.put("stage", string(account, "stage"));
        MeasurementState state = state(account);
        result.put("grossYield", state.segment().grossYield().rate().toPlainString());
        result.put("netEir", state.segment().netEir().rate().toPlainString());
        BigInteger payable = BigInteger.ZERO;
        BigInteger receivable = BigInteger.ZERO;
        for (var lot : store.children("developer_lot", "account_key", string(account, "record_key"))) {
            BigInteger balance = amount(lot, "carrying_minor").subtract(amount(lot, "settled_minor"));
            if (string(lot, "direction").equals("PAYABLE")) {
                payable = payable.add(balance);
            } else {
                receivable = receivable.add(balance);
            }
        }
        result.put("developerPayableMinor", payable.toString());
        result.put("developerReceivableMinor", receivable.toString());
        if (account.get("last_event_key") == null) {
            result.putNull("lastFinancialEventId");
        } else {
            result.put("lastFinancialEventId", string(account, "last_event_key"));
        }
        return result;
    }

    public ObjectNode measures(Position p, BigInteger allowance, String side) {
        ObjectNode result = measures(p, allowance);
        if (side.equals("BEFORE_EVENTS")) {
            BigInteger today = p.legs().stream().filter(leg -> leg.cashflow().dueDate().equals(p.businessDate()))
                    .map(ReceivablesMath.Leg::outstandingMinor).reduce(BigInteger.ZERO, BigInteger::add);
            result.put("notYetDueMinor", p.notYetDueMinor().add(today).toString());
            result.put("pastDueMinor", p.pastDueMinor().subtract(today).toString());
        }
        return result;
    }

    public ObjectNode measures(Position p, BigInteger allowance) {
        ObjectNode result = json.object();
        result.put("contractualOutstandingMinor", p.contractualOutstandingMinor().toString());
        result.put("notYetDueMinor", p.notYetDueMinor().toString());
        result.put("pastDueMinor", p.pastDueMinor().toString());
        result.put("grossPurchaseBasisMinor", p.grossPurchaseBasisMinor().toString());
        result.put("deferredDiscountMinor", p.deferredDiscountMinor().toString());
        result.put("deferredIntegralFeeMinor", p.deferredIntegralFeeMinor().toString());
        result.put("amortizedCostMinor", p.amortizedCostMinor().toString());
        result.put("lossAllowanceMinor", allowance.toString());
        result.put("netCarryingMinor", p.amortizedCostMinor().subtract(allowance).toString());
        result.put("daysPastDue", p.daysPastDue());
        return result;
    }
}
