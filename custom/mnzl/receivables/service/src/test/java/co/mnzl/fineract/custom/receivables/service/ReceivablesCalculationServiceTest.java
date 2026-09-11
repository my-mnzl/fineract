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
        var purchase = measurement.purchase(basis, "account-1");
        var position = ReceivablesMath.position(purchase.segment(), LocalDate.parse(basis.path("settlementDate").asText()), Map.of());
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
            row.put("segmentStartDate", position.businessDate().toString());
            row.put("asOfDate", position.businessDate().toString());
            row.put("grossBasisMinor", leg.grossBasis().movePointRight(2).toPlainString());
            row.put("netBasisMinor", leg.netBasis().movePointRight(2).toPlainString());
            row.set("grossYield", wire.get("grossYield"));
            row.set("netEir", wire.get("netEir"));
            row.put("collectedMinor", "0");
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
