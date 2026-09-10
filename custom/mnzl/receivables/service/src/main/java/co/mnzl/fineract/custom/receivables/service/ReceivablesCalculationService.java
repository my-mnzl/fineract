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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesMeasurement.date;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesMeasurement.decimal;

import co.mnzl.fineract.receivables.math.CreditAndFunding;
import co.mnzl.fineract.receivables.math.ReceivableEvents;
import co.mnzl.fineract.receivables.math.ReceivablesMath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Pure request-to-math adapter. No repository reads, financial events, or native writes. */
@Service
@RequiredArgsConstructor
public class ReceivablesCalculationService {

    private final ReceivablesJson json;
    private final ReceivablesMeasurement measurement;

    public JsonNode calculate(String request) {
        JsonNode input = json.validate("calculationRequest", request);
        ObjectNode result = json.object();
        for (String key : List.of("calculationVersion", "productPolicyCode", "schemaVersion", "policyRevisionId", "calculatorBuild",
                "basisHash", "calculationType")) {
            result.set(key, input.get(key));
        }
        result.set("validationErrors", json.value(List.of()));
        String type = text(input, "calculationType");
        ObjectNode hashInput = input.deepCopy();
        hashInput.remove("basisHash");
        if (input.has("basis")) {
            hashInput = normalizedBasis(input.get("basis"));
            for (String key : List.of("calculationVersion", "productPolicyCode", "schemaVersion", "policyRevisionId", "calculatorBuild")) {
                require(input.get(key).equals(hashInput.get(key)), "SOURCE_CHANGED");
            }
        }
        require(json.hash(hashInput).equals(text(input, "basisHash")), "SOURCE_CHANGED");
        try {
            switch (type) {
                case "PRICE", "SCHEDULE" -> price(input, result, hashInput);
                case "RESET" -> reset(input, result);
                case "SETTLEMENT" -> settlement(input, result);
                case "IMPAIRMENT" -> impairment(input, result);
                case "FUNDING" -> funding(input, result);
                default -> throw new ReceivablesException("INVALID_DATA");
            }
        } catch (IllegalArgumentException exception) {
            throw (ReceivablesException) new ReceivablesException("INVALID_DATA").initCause(exception);
        }
        return json.validate("calculationResult", json.write(result));
    }

    private ObjectNode normalizedBasis(JsonNode basis) {
        ObjectNode normalized = basis.deepCopy();
        for (String field : List.of("cashflows", "acceptedAccountPrices")) {
            List<JsonNode> rows = new ArrayList<>();
            basis.path(field).forEach(rows::add);
            String id = field.equals("cashflows") ? "cashflowId" : "accountId";
            rows.sort(Comparator.comparing(row -> text(row, id)));
            require(rows.stream().map(row -> text(row, id)).distinct().count() == rows.size(), "INVALID_DATA");
            normalized.set(field, json.value(rows));
        }
        return normalized;
    }

    private void price(JsonNode input, ObjectNode result, JsonNode basis) {
        Map<String, List<JsonNode>> groups = new java.util.TreeMap<>();
        basis.path("cashflows").forEach(flow -> groups.computeIfAbsent(text(flow, "receivableId"), ignored -> new ArrayList<>()).add(flow));
        List<ReceivablesMath.Purchase> purchases = new ArrayList<>();
        List<JsonNode> accounts = new ArrayList<>();
        List<JsonNode> allPositions = new ArrayList<>();
        List<JsonNode> allIncome = new ArrayList<>();
        for (var group : groups.entrySet()) {
            ObjectNode accountBasis = basis.deepCopy();
            accountBasis.set("cashflows", json.value(group.getValue()));
            var purchase = measurement.purchase(accountBasis, group.getKey());
            purchases.add(purchase);
            LocalDate through = input.has("throughDate") ? date(input, "throughDate")
                    : purchase.segment().cashflows().stream().map(ReceivablesMath.Cashflow::dueDate).max(LocalDate::compareTo)
                            .orElseThrow();
            var projection = project(group.getKey(), purchase.segment(), through);
            allPositions.addAll(projection.positions());
            allIncome.addAll(projection.income());
            ObjectNode account = totals(purchase.contractualFaceMinor(), purchase.grossPurchasePriceMinor(), purchase.integralFeeMinor(),
                    purchase.netPurchaseCashMinor());
            account.put("accountId", group.getKey());
            account.put("grossYield", purchase.segment().grossYield().rate().toPlainString());
            account.put("netEir", purchase.segment().netEir().rate().toPlainString());
            account.set("schedule", json.value(group.getValue()));
            account.set("positions", json.value(projection.positions()));
            account.set("monthlyIncome", json.value(projection.income()));
            accounts.add(account);
        }
        require(basis.path("acceptedAccountPrices").isEmpty() || basis.path("acceptedAccountPrices").size() == groups.size(),
                "SOURCE_CHANGED");
        if (text(input, "calculationType").equals("SCHEDULE")) {
            result.set("positions", json.value(allPositions));
            result.set("monthlyIncome", json.value(allIncome));
        } else {
            var portfolio = ReceivablesMath.portfolio(purchases);
            ObjectNode pricing = json.object();
            pricing.set("basis", basis);
            pricing.set("basisHash", input.get("basisHash"));
            pricing.set("accounts", json.value(accounts));
            pricing.set("totals", totals(portfolio.contractualFaceMinor(), portfolio.grossPurchasePriceMinor(),
                    portfolio.integralFeeMinor(), portfolio.netPurchaseCashMinor()));
            pricing.set("validationErrors", json.value(List.of()));
            result.set("pricing", pricing);
        }
    }

    private record Projection(List<JsonNode> positions, List<JsonNode> income) {
    }

    private Projection project(String accountId, ReceivablesMath.Segment segment, LocalDate through) {
        ReceivablesMath.days(segment.startDate(), through);
        TreeSet<LocalDate> boundaries = new TreeSet<>(List.of(segment.startDate(), through));
        segment.cashflows().stream().map(ReceivablesMath.Cashflow::dueDate).filter(d -> !d.isAfter(through)).forEach(boundaries::add);
        for (LocalDate month = YearMonth.from(segment.startDate()).plusMonths(1).atDay(1); !month.isAfter(through); month = month
                .plusMonths(1)) {
            boundaries.add(month);
        }
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        List<JsonNode> positions = new ArrayList<>();
        Map<YearMonth, ReceivablesMath.Income> monthly = new LinkedHashMap<>();
        ReceivablesMath.Position previous = ReceivablesMath.position(segment, segment.startDate(), outstanding);
        for (LocalDate boundary : boundaries) {
            var before = ReceivablesMath.position(segment, boundary, outstanding);
            if (boundary.isAfter(previous.businessDate())) {
                var income = ReceivablesMath.income(previous, before, BigInteger.ZERO);
                YearMonth period = YearMonth.from(boundary.minusDays(1));
                var old = monthly.getOrDefault(period, new ReceivablesMath.Income(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
                monthly.put(period,
                        new ReceivablesMath.Income(old.grossDiscountIncomeMinor().add(income.grossDiscountIncomeMinor()),
                                old.integralFeeIncomeMinor().add(income.integralFeeIncomeMinor()),
                                old.interestIncomeMinor().add(income.interestIncomeMinor())));
            }
            positions.add(analytical(accountId, before, segment, "BEFORE_EVENTS"));
            boolean collected = false;
            for (var flow : segment.cashflows()) {
                if (flow.dueDate().equals(boundary)) {
                    outstanding.put(flow.cashflowId(), BigInteger.ZERO);
                    collected = true;
                }
            }
            previous = ReceivablesMath.position(segment, boundary, outstanding);
            if (collected) {
                positions.add(analytical(accountId, previous, segment, "AFTER_EVENTS"));
            }
        }
        List<JsonNode> income = new ArrayList<>();
        monthly.forEach((period, amount) -> {
            ObjectNode row = json.object();
            row.put("accountId", accountId);
            row.put("period", period.toString());
            row.put("grossDiscountIncomeMinor", amount.grossDiscountIncomeMinor().toString());
            row.put("integralFeeIncomeMinor", amount.integralFeeIncomeMinor().toString());
            row.put("interestIncomeMinor", amount.interestIncomeMinor().toString());
            income.add(row);
        });
        return new Projection(positions, income);
    }

    private ObjectNode totals(BigInteger face, BigInteger gross, BigInteger fee, BigInteger net) {
        ObjectNode result = json.object();
        result.put("contractualFaceMinor", face.toString());
        result.put("grossPurchasePriceMinor", gross.toString());
        result.put("integralFeeMinor", fee.toString());
        result.put("netPurchaseCashMinor", net.toString());
        return result;
    }

    private ObjectNode analytical(String accountId, ReceivablesMath.Position position, ReceivablesMath.Segment segment, String side) {
        ObjectNode result = measurement.measures(position, BigInteger.ZERO);
        result.remove(List.of("lossAllowanceMinor", "netCarryingMinor"));
        result.put("accountId", accountId);
        result.put("businessDate", position.businessDate().toString());
        result.put("boundarySide", side);
        result.put("grossYield", segment.grossYield().rate().toPlainString());
        result.put("netEir", segment.netEir().rate().toPlainString());
        return result;
    }

    private void funding(JsonNode input, ObjectNode result) {
        LocalDate from = date(input, "fromDate");
        LocalDate through = date(input, "throughDate");
        BigInteger principal = minor(input, "openingPrincipalMinor");
        BigInteger paid = BigInteger.ZERO;
        BigDecimal rate = decimal(input.get("terms"), "annualNominalRate");
        List<CreditAndFunding.FundingInterval> intervals = new ArrayList<>();
        LocalDate cursor = from;
        for (JsonNode dated : input.path("events")) {
            LocalDate date = date(dated, "date");
            require(!date.isBefore(cursor) && !date.isAfter(through), "INVALID_DATA");
            intervals.add(new CreditAndFunding.FundingInterval(cursor, date, principal, rate));
            JsonNode event = dated.get("event");
            switch (text(event, "kind")) {
                case "DRAW" -> principal = principal.add(minor(event, "principalMinor"));
                case "REPAYMENT" -> principal = principal.subtract(minor(event, "principalMinor"));
                case "RATE_CHANGE" -> rate = decimal(event, "annualNominalRate");
                case "INTEREST_SETTLEMENT" -> paid = paid.add(minor(event, "interestMinor"));
                default -> throw new ReceivablesException("INVALID_DATA");
            }
            require(principal.signum() >= 0 && paid.compareTo(CreditAndFunding.funding(intervals).expenseMinor()) <= 0, "INVALID_DATA");
            cursor = date;
        }
        intervals.add(new CreditAndFunding.FundingInterval(cursor, through, principal, rate));
        BigInteger expense = CreditAndFunding.funding(intervals).expenseMinor();
        result.set("facilityId", input.get("terms").get("facilityId"));
        result.set("fromDate", input.get("fromDate"));
        result.set("throughDate", input.get("throughDate"));
        result.set("openingPrincipalMinor", input.get("openingPrincipalMinor"));
        result.put("closingPrincipalMinor", principal.toString());
        result.put("fundingExpenseMinor", expense.toString());
        result.put("interestPaidMinor", paid.toString());
        result.put("closingInterestPayableMinor", expense.subtract(paid).toString());
    }

    private ReceivablesMath.MeasurementState state(JsonNode input) {
        JsonNode wire = input.get("position");
        LocalDate date = date(wire, "businessDate");
        List<ReceivablesMath.Leg> legs = new ArrayList<>();
        List<ReceivablesMath.Cashflow> flows = new ArrayList<>();
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigInteger future = BigInteger.ZERO;
        BigInteger due = BigInteger.ZERO;
        int dpd = 0;
        for (JsonNode row : input.path("measurementLegs")) {
            require(date(row, "asOfDate").equals(date), "SOURCE_CHANGED");
            require(decimal(row, "grossYield").compareTo(decimal(wire, "grossYield")) == 0
                    && decimal(row, "netEir").compareTo(decimal(wire, "netEir")) == 0, "SOURCE_CHANGED");
            var flow = measurement.flows(json.value(List.of(row.get("cashflow")))).getFirst();
            BigInteger remaining = flow.amountMinor().subtract(minor(row, "collectedMinor")).subtract(minor(row, "adjustedMinor"));
            require(remaining.signum() >= 0 && outstanding.put(flow.cashflowId(), remaining) == null, "INVALID_DATA");
            BigDecimal g = decimal(row, "grossBasisMinor").movePointLeft(2);
            BigDecimal n = decimal(row, "netBasisMinor").movePointLeft(2);
            require(n.signum() >= 0 && n.compareTo(g) <= 0 && g.compareTo(ReceivablesMath.major(remaining)) <= 0, "INVALID_DATA");
            long days = flow.dueDate().isAfter(date) ? ReceivablesMath.days(date, flow.dueDate()) : 0;
            if (days > 0) {
                future = future.add(remaining);
            } else {
                due = due.add(remaining);
                if (remaining.signum() > 0) {
                    dpd = Math.max(dpd, Math.toIntExact(ReceivablesMath.days(flow.dueDate(), date)));
                }
            }
            legs.add(new ReceivablesMath.Leg(flow, days, remaining, g, n));
            flows.add(flow);
            gross = gross.add(g);
            net = net.add(n);
        }
        require(!legs.isEmpty() && future.equals(minor(wire, "notYetDueMinor")) && due.equals(minor(wire, "pastDueMinor"))
                && future.add(due).equals(minor(wire, "contractualOutstandingMinor")), "SOURCE_CHANGED");
        BigInteger g = minor(wire, "grossPurchaseBasisMinor");
        BigInteger n = minor(wire, "amortizedCostMinor");
        BigInteger allowance = minor(wire, "lossAllowanceMinor");
        require(n.compareTo(g) <= 0 && g.compareTo(future.add(due)) <= 0 && allowance.compareTo(n) <= 0
                && n.subtract(allowance).equals(minor(wire, "netCarryingMinor")) && wire.path("daysPastDue").asInt() == dpd, "SOURCE_CHANGED");
        require(future.add(due).subtract(g).equals(minor(wire, "deferredDiscountMinor"))
                && g.subtract(n).equals(minor(wire, "deferredIntegralFeeMinor")), "SOURCE_CHANGED");
        var position = new ReceivablesMath.Position(date, future.add(due), future, due, g, n, minor(wire, "deferredDiscountMinor"),
                minor(wire, "deferredIntegralFeeMinor"), dpd, gross, net, legs);
        ReceivablesMath.allocateGross(position);
        ReceivablesMath.allocateNet(position);
        var segment = new ReceivablesMath.Segment(date, flows, g, n,
                new ReceivablesMath.Yield(decimal(wire, "grossYield"), BigDecimal.ZERO, 0),
                new ReceivablesMath.Yield(decimal(wire, "netEir"), BigDecimal.ZERO, 0));
        return new ReceivablesMath.MeasurementState(segment, position, outstanding);
    }

    private ObjectNode after(JsonNode before, ReceivablesMath.Position position, BigInteger allowance) {
        require(allowance.signum() >= 0 && allowance.compareTo(position.amortizedCostMinor()) <= 0, "INVALID_DATA");
        ObjectNode result = before.deepCopy();
        result.setAll(measurement.measures(position, allowance));
        result.put("businessDate", position.businessDate().toString());
        result.put("boundarySide", "AFTER_EVENTS");
        if (position.contractualOutstandingMinor().signum() == 0) {
            result.put("nativeLoanStatus", "CLOSED");
        }
        return result;
    }

    private void reset(JsonNode input, ObjectNode result) {
        var state = state(input);
        LocalDate effective = date(input, "effectiveDate");
        var before = ReceivablesMath.position(state, effective);
        var reset = ReceivableEvents.reset(state, effective, decimal(input, "corridorRate").add(decimal(input, "spread")));
        require(new HashSet<>(measurement.flows(input.get("remainingCashflows"))).equals(new HashSet<>(state.segment().cashflows())),
                "SOURCE_CHANGED");
        BigInteger allowance = minor(input.get("position"), "lossAllowanceMinor");
        ObjectNode beforeWire = after(input.get("position"), before, allowance);
        beforeWire.put("boundarySide", "BEFORE_EVENTS");
        result.set("positionBefore", beforeWire);
        ObjectNode after = beforeWire.deepCopy();
        after.put("boundarySide", "AFTER_EVENTS");
        after.put("grossPurchaseBasisMinor", before.grossPurchaseBasisMinor().add(reset.deltaMinor()).toString());
        after.put("amortizedCostMinor", before.amortizedCostMinor().add(reset.deltaMinor()).toString());
        after.put("netCarryingMinor", before.amortizedCostMinor().add(reset.deltaMinor()).subtract(allowance).toString());
        after.put("deferredDiscountMinor", before.deferredDiscountMinor().subtract(reset.deltaMinor()).toString());
        require(before.amortizedCostMinor().add(reset.deltaMinor()).compareTo(allowance) >= 0, "INVALID_DATA");
        var segment = reset.futureSegment() == null ? state.segment() : reset.futureSegment();
        after.put("grossYield", segment.grossYield().rate().toPlainString());
        after.put("netEir", segment.netEir().rate().toPlainString());
        result.set("positionAfter", after);
        result.put("grossBasisChangeMinor", reset.deltaMinor().toString());
        result.put("grossYield", segment.grossYield().rate().toPlainString());
        result.put("netEir", segment.netEir().rate().toPlainString());
        if (reset.lot() == null) {
            result.putNull("developerAdjustment");
        } else {
            var lot = reset.lot();
            require(lot.dueDate().equals(date(input, "adjustmentDueDate")), "INVALID_DATA");
            ObjectNode wire = json.object();
            wire.put("lotId", "preview:" + text(input, "basisHash"));
            wire.set("accountId", input.get("position").get("accountId"));
            wire.put("effectiveDate", lot.effectiveDate().toString());
            wire.put("dueDate", lot.dueDate().toString());
            wire.put("direction", lot.direction().name());
            wire.put("initialAmountMinor", lot.principalMinor().toString());
            wire.put("currentAmountMinor", lot.principalMinor().toString());
            wire.put("annualNominalRate", lot.rate().toPlainString());
            String field = lot.direction() == ReceivableEvents.Direction.PAYABLE ? "developerPayableMinor" : "developerReceivableMinor";
            after.put(field, minor(after, field).add(lot.principalMinor()).toString());
            result.set("developerAdjustment", wire);
        }
    }

    private void settlement(JsonNode input, ObjectNode result) {
        var state = state(input);
        var position = ReceivablesMath.position(state, date(input, "settlementDate"));
        Map<String, BigInteger> selected = new LinkedHashMap<>();
        for (JsonNode id : input.path("cashflowIds")) {
            var leg = position.legs().stream().filter(l -> l.cashflow().cashflowId().equals(id.asText())).findFirst().orElseThrow(() -> new ReceivablesException("INVALID_DATA"));
            require(selected.put(id.asText(), leg.outstandingMinor()) == null, "INVALID_DATA");
        }
        BigInteger oldAllowance = minor(input.get("position"), "lossAllowanceMinor");
        BigInteger selectedFace = selected.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger released = oldAllowance;
        if (!selectedFace.equals(position.contractualOutstandingMinor()) && oldAllowance.signum() > 0) {
            require(input.hasNonNull("currentForecast"), "POLICY_INCOMPLETE");
            JsonNode forecast = input.get("currentForecast");
            var losses = CreditAndFunding.impairment(position, state.segment().netEir().rate(),
                    Integer.parseInt(text(forecast, "stage").substring(6)), measurement.scenarios(forecast));
            require(!date(forecast, "asOfDate").isAfter(position.businessDate())
                    && !date(forecast, "validThroughDate").isBefore(position.businessDate()), "SOURCE_CHANGED");
            Map<String, BigDecimal> weights = new LinkedHashMap<>();
            position.legs().forEach(leg -> {
                String id = leg.cashflow().cashflowId();
                BigDecimal loss = losses.lossByCashflow().getOrDefault(id, BigDecimal.ZERO);
                weights.put(id + ":selected", selected.containsKey(id) ? loss : BigDecimal.ZERO);
                weights.put(id + ":retained", selected.containsKey(id) ? BigDecimal.ZERO : loss);
            });
            var allocations = ReceivablesMath.allocate(oldAllowance, weights);
            released = selected.keySet().stream().map(id -> allocations.get(id + ":selected")).reduce(BigInteger.ZERO, BigInteger::add);
        }
        var partial = ReceivableEvents.settlePortions(position, selected, minor(input, "payoffMinor"), released);
        var settlement = partial.settlement();
        result.put("payoffMinor", settlement.payoffMinor().toString());
        result.put("extinguishedFaceMinor", settlement.faceExtinguishedMinor().toString());
        result.put("grossPurchaseBasisMinor", settlement.grossBasisMinor().toString());
        result.put("deferredDiscountMinor", settlement.deferredDiscountMinor().toString());
        result.put("deferredIntegralFeeMinor", settlement.deferredIntegralFeeMinor().toString());
        result.put("developerShareMinor", settlement.developerShareMinor().toString());
        result.put("financierIncomeMinor", settlement.financierIncomeMinor().toString());
        result.put("releasedAllowanceMinor", released.toString());
        result.put("commercialAdjustmentMinor", settlement.faceExtinguishedMinor().subtract(settlement.payoffMinor()).toString());
        ObjectNode after = after(input.get("position"), partial.retainedPosition(), oldAllowance.subtract(released));
        after.put("developerPayableMinor", minor(after, "developerPayableMinor").add(settlement.developerShareMinor()).toString());
        result.set("positionAfter", after);
    }

    private void impairment(JsonNode input, ObjectNode result) {
        var state = state(input);
        JsonNode forecast = input.get("forecast");
        require(date(forecast, "asOfDate").equals(state.boundaryPosition().businessDate())
                && !date(forecast, "validThroughDate").isBefore(date(forecast, "asOfDate")), "SOURCE_CHANGED");
        require(new HashSet<>(measurement.flows(input.get("cashflows"))).equals(new HashSet<>(state.segment().cashflows())),
                "SOURCE_CHANGED");
        int stage = Integer.parseInt(text(forecast, "stage").substring(6));
        require(stage >= CreditAndFunding.stage(state.boundaryPosition().daysPastDue(), false, false), "SOURCE_CHANGED");
        var impairment = CreditAndFunding.impairment(state.boundaryPosition(), state.segment().netEir().rate(), stage,
                measurement.scenarios(forecast));
        result.set("forecastId", forecast.get("forecastId"));
        result.put("lossAllowanceMinor", impairment.lossAllowanceMinor().toString());
        result.put("allowanceChangeMinor",
                impairment.lossAllowanceMinor().subtract(minor(input.get("position"), "lossAllowanceMinor")).toString());
        result.put("scheduledIncomeMinor", "0");
        result.put("expectedRecoveryUnwindMinor", "0");
        result.put("stage3BridgeMinor", "0");
        ObjectNode after = after(input.get("position"), state.boundaryPosition(), impairment.lossAllowanceMinor());
        after.set("stage", forecast.get("stage"));
        result.set("positionAfter", after);
    }
}
