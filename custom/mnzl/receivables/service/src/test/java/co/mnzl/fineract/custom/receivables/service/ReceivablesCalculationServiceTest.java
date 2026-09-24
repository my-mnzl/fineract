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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.mnzl.fineract.receivables.math.ReceivableEvents;
import co.mnzl.fineract.receivables.math.ReceivablesMath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivablesCalculationServiceTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesMeasurement measurement = new ReceivablesMeasurement(null, json, null);
    private final ReceivablesCalculationService service = new ReceivablesCalculationService(json, measurement);

    ReceivablesCalculationServiceTest() throws Exception {}

    private ObjectNode context() {
        return (ObjectNode) json.read(
                "{\"calculationVersion\":\"EG_RECEIVABLES_ACT360_DAILY_V1\",\"productPolicyCode\":\"EG_RECEIVABLES_V1\",\"schemaVersion\":\"1\",\"policyRevisionId\":\"policy-1\",\"calculatorBuild\":\"build-1\"}");
    }

    private JsonNode vector(String id) throws Exception {
        JsonNode vectors = json.read(Files.readString(Path.of("../math/src/test/resources/reference-vectors.json")));
        for (JsonNode vector : vectors.path("vectors")) {
            if (id.equals(vector.path("id").asText())) {
                return vector;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private ObjectNode basis(JsonNode vector) {
        JsonNode source = vector.get("input");
        ObjectNode basis = context();
        basis.set("settlementDate", source.get("settlementDate"));
        basis.set("corridorRate", source.get("nominalAnnualRate"));
        basis.set("feeRate", source.get("feeRate"));
        basis.put("corridorObservationId", "rate-1");
        basis.put("spread", "0");
        basis.put("sourceVersion", "1");
        basis.put("sourceHash", "0".repeat(64));
        basis.set("acceptedAccountPrices", json.value(List.of()));
        List<JsonNode> flows = new ArrayList<>();
        for (JsonNode row : source.path("cashflows")) {
            ObjectNode flow = json.object();
            flow.set("cashflowId", row.get("id"));
            flow.set("installmentId", row.get("id"));
            flow.put("receivableId", "account-1");
            flow.put("currency", "EGP");
            flow.put("scheduleVersionId", "schedule-1");
            flow.set("dueDate", row.get("dueDate"));
            flow.set("amountMinor", row.get("amountMinor"));
            flows.add(flow);
        }
        basis.set("cashflows", json.value(flows));
        return basis;
    }

    private JsonNode calculate(ObjectNode request) {
        request.put("basisHash", json.hash(request.has("basis") ? request.get("basis") : request));
        return service.calculate(json.write(request));
    }

    @Test
    void priceAndProjectedIncomeMatchAcceptedReferenceVectors() throws Exception {
        for (String id : List.of("zero-rate", "irregular-integral-fee")) {
            JsonNode vector = vector(id);
            ObjectNode request = context();
            request.put("calculationType", "PRICE");
            request.set("basis", basis(vector));
            JsonNode result = calculate(request).get("pricing");
            JsonNode expected = vector.get("expected");
            assertThat(result.path("totals").path("grossPurchasePriceMinor").asText()).isEqualTo(expected.path("grossPriceMinor").asText());
            assertThat(result.path("totals").path("integralFeeMinor").asText()).isEqualTo(expected.path("feeMinor").asText());
            assertThat(result.path("totals").path("netPurchaseCashMinor").asText())
                    .isEqualTo(expected.path("netPurchaseCashMinor").asText());
            BigInteger income = BigInteger.ZERO;
            for (JsonNode row : result.path("accounts").get(0).path("monthlyIncome")) {
                income = income.add(new BigInteger(row.path("interestIncomeMinor").asText()));
            }
            assertThat(income).isEqualTo(new BigInteger(result.path("totals").path("contractualFaceMinor").asText())
                    .subtract(new BigInteger(expected.path("netPurchaseCashMinor").asText())));
            ObjectNode schedule = context();
            schedule.put("calculationType", "SCHEDULE");
            schedule.set("basis", basis(vector));
            schedule.put("throughDate", "2031-02-01");
            assertThat(calculate(schedule).path("positions").isEmpty()).isFalse();
            request.put("basisHash", "0".repeat(64));
            assertThatThrownBy(() -> service.calculate(json.write(request))).isInstanceOf(ReceivablesException.class);
        }
    }

    private ObjectNode positioned(ObjectNode basis) {
        return positioned(basis, LocalDate.parse(basis.path("settlementDate").asText()), Map.of());
    }

    /**
     * The wire position and legs the app holds for the account on {@code date}, after the given cheques were collected.
     */
    private ObjectNode positioned(ObjectNode basis, LocalDate date, Map<String, BigInteger> collected) {
        var purchase = measurement.purchase(basis, "account-1");
        var position = ReceivablesMath.position(purchase.segment(), date, collected);
        ObjectNode wire = measurement.measures(position, BigInteger.ZERO);
        wire.set("scope", json.read(
                "{\"platformId\":\"mnzl\",\"financierOrganizationId\":\"financier\",\"environment\":\"test\",\"developerOrganizationId\":\"developer\"}"));
        for (String field : List.of("tenantId", "dealId", "accountId", "receivableId", "nativeLoanId")) {
            wire.put(field, "account-1");
        }
        wire.put("businessDate", position.businessDate().toString());
        wire.put("boundarySide", "AFTER_EVENTS");
        wire.put("nativeLoanStatus", "ACTIVE");
        wire.put("stage", "STAGE_1");
        wire.put("accountVersion", "1");
        wire.putNull("lastFinancialEventId");
        wire.put("developerPayableMinor", "0");
        wire.put("developerReceivableMinor", "0");
        wire.put("grossYield", purchase.segment().grossYield().rate().toPlainString());
        wire.put("netEir", purchase.segment().netEir().rate().toPlainString());
        List<JsonNode> legs = new ArrayList<>();
        for (int i = 0; i < position.legs().size(); i++) {
            var leg = position.legs().get(i);
            ObjectNode row = json.object();
            row.set("cashflow", basis.get("cashflows").get(i));
            row.put("segmentId", "segment");
            row.put("segmentStartDate", purchase.segment().startDate().toString());
            row.put("asOfDate", position.businessDate().toString());
            row.put("grossBasisMinor", leg.grossBasis().movePointRight(2).toPlainString());
            row.put("netBasisMinor", leg.netBasis().movePointRight(2).toPlainString());
            row.set("grossYield", wire.get("grossYield"));
            row.set("netEir", wire.get("netEir"));
            row.put("collectedMinor", leg.cashflow().amountMinor().subtract(leg.outstandingMinor()).toString());
            row.put("adjustedMinor", "0");
            legs.add(row);
        }
        ObjectNode request = context();
        request.set("position", wire);
        request.set("measurementLegs", json.value(legs));
        return request;
    }

    @Test
    void partialSettlementKeepsAcceptedMathAllocationAndPreciseBases() throws Exception {
        ObjectNode basis = basis(vector("irregular-integral-fee"));
        ObjectNode request = positioned(basis);
        request.put("calculationType", "SETTLEMENT");
        request.set("settlementDate", basis.get("settlementDate"));
        request.set("cashflowIds", json.value(List.of("cf-01")));
        request.put("payoffMinor", "4000000");
        JsonNode result = calculate(request);
        var purchase = measurement.purchase(basis, "account-1");
        var position = ReceivablesMath.position(purchase.segment(), LocalDate.parse(basis.path("settlementDate").asText()), Map.of());
        var expected = ReceivableEvents.settlePortions(position, Map.of("cf-01", BigInteger.valueOf(4000000)), BigInteger.valueOf(4000000),
                BigInteger.ZERO);
        assertThat(result.path("grossPurchaseBasisMinor").asText()).isEqualTo(expected.settlement().grossBasisMinor().toString());
        assertThat(result.path("positionAfter").path("amortizedCostMinor").asText())
                .isEqualTo(expected.retainedPosition().amortizedCostMinor().toString());
    }

    @Test
    void partialLegSettlementRetainsUnselectedFaceAndRejectsInvalidPortions() throws Exception {
        ObjectNode basis = basis(vector("irregular-integral-fee"));
        ObjectNode request = positioned(basis);
        request.put("calculationType", "SETTLEMENT");
        request.set("settlementDate", basis.get("settlementDate"));
        request.set("cashflowIds", json.value(List.of("cf-01")));
        request.set("cashflowPortions", json.value(List.of(Map.of("cashflowId", "cf-01", "faceMinor", "2000000"))));
        request.put("payoffMinor", "1900000");
        JsonNode result = calculate(request);
        var purchase = measurement.purchase(basis, "account-1");
        var position = ReceivablesMath.position(purchase.segment(), LocalDate.parse(basis.path("settlementDate").asText()), Map.of());
        var expected = ReceivableEvents.settlePortions(position, Map.of("cf-01", BigInteger.valueOf(2000000)), BigInteger.valueOf(1900000),
                BigInteger.ZERO);
        assertThat(result.path("extinguishedFaceMinor").asText()).isEqualTo("2000000");
        assertThat(result.path("grossPurchaseBasisMinor").asText()).isEqualTo(expected.settlement().grossBasisMinor().toString());
        assertThat(result.path("positionAfter").path("amortizedCostMinor").asText())
                .isEqualTo(expected.retainedPosition().amortizedCostMinor().toString());
        for (var portions : List.of(List.of(Map.of("cashflowId", "cf-01", "faceMinor", "4000001")),
                List.of(Map.of("cashflowId", "unknown", "faceMinor", "1")),
                List.of(Map.of("cashflowId", "cf-01", "faceMinor", "1"), Map.of("cashflowId", "cf-01", "faceMinor", "1")))) {
            request.remove("basisHash");
            request.set("cashflowPortions", json.value(portions));
            assertThatThrownBy(() -> calculate(request)).isInstanceOf(ReceivablesException.class);
        }
    }

    @Test
    void resetAndInstantaneousImpairmentUseSameNativeMath() throws Exception {
        ObjectNode basis = basis(vector("irregular-integral-fee"));
        ObjectNode request = positioned(basis);
        request.put("calculationType", "RESET");
        request.set("remainingCashflows", basis.get("cashflows"));
        request.set("effectiveDate", basis.get("settlementDate"));
        request.put("corridorObservationId", "new-rate");
        request.put("corridorRate", "0.30");
        request.put("spread", "0");
        request.put("adjustmentDueDate", "2030-04-01");
        JsonNode result = calculate(request);
        var purchase = measurement.purchase(basis, "account-1");
        var expected = ReceivableEvents.reset(purchase.segment(), purchase.segment().startDate(), Map.of(), new BigDecimal("0.30"));
        assertThat(result.path("grossBasisChangeMinor").asText()).isEqualTo(expected.deltaMinor().toString());
        request = positioned(basis);
        request.put("calculationType", "IMPAIRMENT");
        request.set("cashflows", basis.get("cashflows"));
        ObjectNode forecast = json.object();
        forecast.put("forecastId", "forecast");
        forecast.put("forecastVersion", "1");
        forecast.put("asOfDate", "2030-01-01");
        forecast.put("validThroughDate", "2030-01-31");
        forecast.put("stage", "STAGE_1");
        forecast.put("contentHash", "0".repeat(64));
        List<JsonNode> recoveries = new ArrayList<>();
        for (JsonNode flow : basis.path("cashflows")) {
            ObjectNode recovery = json.object();
            recovery.set("sourceCashflowId", flow.get("cashflowId"));
            recovery.set("date", flow.get("dueDate"));
            recovery.set("amountMinor", flow.get("amountMinor"));
            recovery.put("payer", "BORROWER");
            recoveries.add(recovery);
        }
        ObjectNode scenario = json.object();
        scenario.put("scenarioId", "contractual");
        scenario.put("probability", "1");
        scenario.putNull("defaultDate");
        scenario.set("recoveries", json.value(recoveries));
        forecast.set("scenarios", json.value(List.of(scenario)));
        request.set("forecast", forecast);
        result = calculate(request);
        assertThat(result.path("lossAllowanceMinor").asText()).isEqualTo("0");
        assertThat(result.path("scheduledIncomeMinor").asText()).isEqualTo("0");
    }

    @Test
    void simpleVersionPricesIdenticallyPinsItsOwnYieldsAndResetsUnderTheSameRule() throws Exception {
        String simple = ReceivablesMath.SIMPLE_CALCULATION_VERSION;
        JsonNode daily = vector("irregular-integral-fee");
        JsonNode expected = vector("simple-irregular-integral-fee").get("expected");
        ObjectNode basis = basis(daily);
        basis.put("calculationVersion", simple);
        ObjectNode request = context();
        request.put("calculationVersion", simple);
        request.put("calculationType", "PRICE");
        request.set("basis", basis);
        JsonNode result = calculate(request);
        assertThat(result.path("calculationVersion").asText()).isEqualTo(simple);
        JsonNode totals = result.path("pricing").path("totals");
        assertThat(totals.path("grossPurchasePriceMinor").asText()).isEqualTo(daily.path("expected").path("grossPriceMinor").asText());
        assertThat(totals.path("netPurchaseCashMinor").asText()).isEqualTo(daily.path("expected").path("netPurchaseCashMinor").asText());
        JsonNode account = result.path("pricing").path("accounts").get(0);
        for (String yield : List.of("grossYield", "netEir")) {
            assertThat(new BigDecimal(account.path(yield).asText()).subtract(new BigDecimal(expected.path(yield).asText())).abs())
                    .isLessThanOrEqualTo(new BigDecimal("5e-17"));
        }
        BigInteger income = BigInteger.ZERO;
        for (JsonNode row : account.path("monthlyIncome")) {
            income = income.add(new BigInteger(row.path("interestIncomeMinor").asText()));
        }
        assertThat(income).isEqualTo(new BigInteger(totals.path("contractualFaceMinor").asText())
                .subtract(new BigInteger(totals.path("netPurchaseCashMinor").asText())));
        var purchase = measurement.purchase(basis, "account-1");
        assertThat(purchase.segment().calculationVersion()).isEqualTo(simple);
        assertThat(ReceivablesMeasurement.calculationVersion(context())).isEqualTo(ReceivablesConfiguration.CALCULATION);
        // A mixed request is refused: the basis names the rule the account is booked under.
        ObjectNode mixed = context();
        mixed.put("calculationType", "PRICE");
        mixed.set("basis", basis);
        assertThatThrownBy(() -> calculate(mixed)).isInstanceOf(ReceivablesException.class);
        // The wire position rebuilds a simple segment, so a reset uses the simple present value and lot accrual.
        ObjectNode reset = positioned(basis);
        reset.put("calculationVersion", simple);
        reset.put("calculationType", "RESET");
        reset.set("remainingCashflows", basis.get("cashflows"));
        reset.set("effectiveDate", basis.get("settlementDate"));
        reset.put("corridorObservationId", "new-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("adjustmentDueDate", "2030-04-01");
        JsonNode outcome = calculate(reset);
        var simpleReset = ReceivableEvents.reset(purchase.segment(), purchase.segment().startDate(), Map.of(), new BigDecimal("0.30"));
        var dailyReset = ReceivableEvents.reset(measurement.purchase(basis(daily), "account-1").segment(), purchase.segment().startDate(),
                Map.of(), new BigDecimal("0.30"));
        assertThat(outcome.path("calculationVersion").asText()).isEqualTo(simple);
        assertThat(outcome.path("grossBasisChangeMinor").asText()).isEqualTo(simpleReset.deltaMinor().toString());
        assertThat(simpleReset.deltaMinor()).isNotEqualTo(dailyReset.deltaMinor());
        assertThat(simpleReset.futureSegment().calculationVersion()).isEqualTo(simple);
    }

    private ObjectNode contractualForecast(ObjectNode basis, LocalDate asOf, Map<String, BigInteger> collected) {
        ObjectNode forecast = json.object();
        forecast.put("forecastId", "forecast");
        forecast.put("forecastVersion", "1");
        forecast.put("asOfDate", asOf.toString());
        forecast.put("validThroughDate", asOf.plusDays(30).toString());
        forecast.put("stage", "STAGE_1");
        forecast.put("contentHash", "0".repeat(64));
        List<JsonNode> recoveries = new ArrayList<>();
        for (JsonNode flow : basis.path("cashflows")) {
            if (collected.containsKey(flow.path("cashflowId").asText())) {
                continue;
            }
            ObjectNode recovery = json.object();
            recovery.set("sourceCashflowId", flow.get("cashflowId"));
            recovery.set("date", flow.get("dueDate"));
            recovery.set("amountMinor", flow.get("amountMinor"));
            recovery.put("payer", "BORROWER");
            recoveries.add(recovery);
        }
        ObjectNode scenario = json.object();
        scenario.put("scenarioId", "contractual");
        scenario.put("probability", "1");
        scenario.putNull("defaultDate");
        scenario.set("recoveries", json.value(recoveries));
        forecast.set("scenarios", json.value(List.of(scenario)));
        return forecast;
    }

    private ObjectNode resetRequest(ObjectNode basis, LocalDate date, Map<String, BigInteger> collected, String dueDate) {
        ObjectNode request = positioned(basis, date, collected);
        request.set("calculationVersion", basis.get("calculationVersion"));
        request.put("calculationType", "RESET");
        request.set("remainingCashflows", basis.get("cashflows"));
        request.put("effectiveDate", date.toString());
        request.put("corridorObservationId", "new-rate");
        request.put("corridorRate", "0.20");
        request.put("spread", "0");
        request.put("adjustmentDueDate", dueDate);
        return request;
    }

    /**
     * Between two cheques the app's legs are shares of the walked simple balance, not a fresh discounting from the
     * measurement date; the rebuilt segment must reproduce them exactly for every calculation type.
     */
    @Test
    void simpleAccountCalculatesBetweenChequesAndOnChequeDatesAfterEvents() throws Exception {
        String simple = ReceivablesMath.SIMPLE_CALCULATION_VERSION;
        ObjectNode basis = basis(vector("irregular-integral-fee"));
        basis.put("calculationVersion", simple);
        var segment = measurement.purchase(basis, "account-1").segment();
        LocalDate mid = LocalDate.of(2030, 5, 1);
        Map<String, BigInteger> afterFirst = Map.of("cf-01", BigInteger.ZERO);
        JsonNode vector = vector("simple-reset-fall").get("expected");

        JsonNode reset = calculate(resetRequest(basis, mid, afterFirst, "2030-07-30"));
        var expectedReset = ReceivableEvents.reset(segment, mid, afterFirst, new BigDecimal("0.20"));
        assertThat(reset.path("grossBasisChangeMinor").asText()).isEqualTo(expectedReset.deltaMinor().toString())
                .isEqualTo(vector.path("developerAdjustmentMinor").asText());
        assertThat(reset.path("developerAdjustment").path("initialAmountMinor").asText())
                .isEqualTo(expectedReset.lot().principalMinor().toString());
        assertThat(reset.path("positionAfter").path("grossPurchaseBasisMinor").asText())
                .isEqualTo(vector.path("newGrossBasisMinor").asText());

        ObjectNode settlement = positioned(basis, mid, afterFirst);
        settlement.put("calculationVersion", simple);
        settlement.put("calculationType", "SETTLEMENT");
        settlement.put("settlementDate", mid.toString());
        settlement.set("cashflowIds", json.value(List.of("cf-02")));
        settlement.put("payoffMinor", "5000000");
        JsonNode settled = calculate(settlement);
        var expectedSettlement = ReceivableEvents.settlePortions(ReceivablesMath.position(segment, mid, afterFirst),
                Map.of("cf-02", BigInteger.valueOf(5000000)), BigInteger.valueOf(5000000), BigInteger.ZERO);
        assertThat(settled.path("grossPurchaseBasisMinor").asText())
                .isEqualTo(expectedSettlement.settlement().grossBasisMinor().toString());
        assertThat(settled.path("positionAfter").path("amortizedCostMinor").asText())
                .isEqualTo(expectedSettlement.retainedPosition().amortizedCostMinor().toString());

        ObjectNode impairment = positioned(basis, mid, afterFirst);
        impairment.put("calculationVersion", simple);
        impairment.put("calculationType", "IMPAIRMENT");
        impairment.set("cashflows", basis.get("cashflows"));
        impairment.set("forecast", contractualForecast(basis, mid, afterFirst));
        JsonNode impaired = calculate(impairment);
        assertThat(impaired.path("lossAllowanceMinor").asText()).isEqualTo("0");

        // On a cheque date after its collection the anchor is that cheque date; the legs equal the closed form.
        LocalDate second = LocalDate.of(2030, 7, 30);
        Map<String, BigInteger> afterSecond = Map.of("cf-01", BigInteger.ZERO, "cf-02", BigInteger.ZERO);
        JsonNode onCheque = calculate(resetRequest(basis, second, afterSecond, "2031-01-26"));
        var expectedOnCheque = ReceivableEvents.reset(segment, second, afterSecond, new BigDecimal("0.20"));
        assertThat(onCheque.path("grossBasisChangeMinor").asText()).isEqualTo(expectedOnCheque.deltaMinor().toString());
        assertThat(expectedOnCheque.futureSegment().calculationVersion()).isEqualTo(simple);

        // Legs claiming a later segment start than the measurement date are a changed measurement, not a silent rebase.
        ObjectNode stale = resetRequest(basis, mid, afterFirst, "2030-07-30");
        for (JsonNode row : stale.path("measurementLegs")) {
            ((ObjectNode) row).put("segmentStartDate", mid.plusDays(1).toString());
        }
        assertThatThrownBy(() -> calculate(stale)).isInstanceOf(ReceivablesException.class);
    }

    /**
     * The daily rebuild still starts on the measurement date: the same mid-period request reproduces the daily vector.
     */
    @Test
    void dailyAccountStillCalculatesBetweenChequesFromTheMeasurementDate() throws Exception {
        ObjectNode basis = basis(vector("irregular-integral-fee"));
        var segment = measurement.purchase(basis, "account-1").segment();
        LocalDate mid = LocalDate.of(2030, 5, 1);
        Map<String, BigInteger> afterFirst = Map.of("cf-01", BigInteger.ZERO);
        JsonNode reset = calculate(resetRequest(basis, mid, afterFirst, "2030-07-30"));
        var expected = ReceivableEvents.reset(segment, mid, afterFirst, new BigDecimal("0.20"));
        assertThat(reset.path("calculationVersion").asText()).isEqualTo(ReceivablesMath.CALCULATION_VERSION);
        assertThat(reset.path("grossBasisChangeMinor").asText()).isEqualTo(expected.deltaMinor().toString())
                .isEqualTo(vector("reset-fall").path("expected").path("developerAdjustmentMinor").asText());
        assertThat(expected.futureSegment().calculationVersion()).isEqualTo(ReceivablesMath.CALCULATION_VERSION);
    }

    @Test
    void partialImpairedSettlementRequiresAndAllocatesActualForecastLosses() throws Exception {
        ObjectNode basis = basis(vector("irregular-integral-fee"));
        ObjectNode request = positioned(basis);
        request.put("calculationType", "SETTLEMENT");
        ObjectNode position = (ObjectNode) request.get("position");
        position.set("lossAllowanceMinor", position.get("amortizedCostMinor"));
        position.put("netCarryingMinor", "0");
        position.put("stage", "STAGE_2");
        request.set("settlementDate", basis.get("settlementDate"));
        request.set("cashflowIds", json.value(List.of("cf-01")));
        request.put("payoffMinor", "4000000");
        assertThatThrownBy(() -> calculate(request)).isInstanceOf(ReceivablesException.class);
        request.remove("basisHash");
        ObjectNode forecast = json.object();
        forecast.put("forecastId", "forecast");
        forecast.put("forecastVersion", "1");
        forecast.put("asOfDate", "2030-01-01");
        forecast.put("validThroughDate", "2030-01-31");
        forecast.put("stage", "STAGE_2");
        forecast.put("contentHash", "0".repeat(64));
        forecast.set("scenarios",
                json.read("[{\"scenarioId\":\"loss\",\"probability\":\"1\",\"defaultDate\":\"2030-01-01\",\"recoveries\":[]}]"));
        request.set("currentForecast", forecast);
        request.set("cashflowPortions", json.value(List.of(Map.of("cashflowId", "cf-01", "faceMinor", "2000000"))));
        request.put("payoffMinor", "2000000");
        JsonNode result = calculate(request);
        assertThat(result.path("positionAfter").path("netCarryingMinor").asText()).isEqualTo("0");
        assertThat(new BigInteger(result.path("releasedAllowanceMinor").asText())).isPositive();
    }

    @Test
    void fundingIsSimpleActual360WithoutCapitalization() {
        ObjectNode request = context();
        request.put("calculationType", "FUNDING");
        request.set("terms", json.read(
                "{\"facilityId\":\"facility\",\"currency\":\"EGP\",\"annualNominalRate\":\"0.36\",\"dayCount\":\"ACTUAL_360_SIMPLE\",\"capitalizeInterest\":false}"));
        request.put("fromDate", "2028-02-28");
        request.put("throughDate", "2028-03-01");
        request.put("openingPrincipalMinor", "10000000");
        request.set("events", json.value(List.of()));
        JsonNode result = calculate(request);
        assertThat(result.path("fundingExpenseMinor").asText()).isEqualTo("20000");
        assertThat(result.path("closingPrincipalMinor").asText()).isEqualTo("10000000");
    }
}
