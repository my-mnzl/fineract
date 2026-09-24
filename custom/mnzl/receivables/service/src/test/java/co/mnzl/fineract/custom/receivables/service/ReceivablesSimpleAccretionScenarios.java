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

import co.mnzl.fineract.receivables.math.CreditAndFunding;
import co.mnzl.fineract.receivables.math.ReceivableEvents;
import co.mnzl.fineract.receivables.math.ReceivablesMath;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A simple-rule account booked, reloaded from the database and serviced by later commands: the pinned version must
 * survive persistence and every later journal, event and lot must carry simple-rule amounts, while a daily account on
 * the same deal keeps its own rule. Runs only with the Docker-backed database integration test.
 */
final class ReceivablesSimpleAccretionScenarios {

    private static final String PREFIX = ReceivablesDatabaseIntegrationTest.PREFIX;
    private static final String SIMPLE = ReceivablesMath.SIMPLE_CALCULATION_VERSION;
    private static final String DAILY = ReceivablesMath.CALCULATION_VERSION;
    private static final BigInteger CHEQUE = BigInteger.valueOf(50_000);

    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesSimpleAccretionScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        LocalDate acquisition = harness.today;
        String simple = "simple-accretion-account";
        String daily = "simple-accretion-daily-account";
        harness.purchase(simple, CHEQUE.toString(), CHEQUE.toString(), "acquisition-simple-" + acquisition, SIMPLE);
        harness.purchase(daily, CHEQUE.toString(), CHEQUE.toString(), "acquisition-simple-daily-" + acquisition, DAILY);
        Segment simpleSegment = ReceivablesMath
                .price(SIMPLE, acquisition, flows(simple, acquisition), new BigDecimal("0.24"), new BigDecimal("0.01")).segment();
        Segment dailySegment = ReceivablesMath
                .price(DAILY, acquisition, flows(daily, acquisition), new BigDecimal("0.24"), new BigDecimal("0.01")).segment();
        pinnedOnBooking(simple, SIMPLE, simpleSegment);
        pinnedOnBooking(daily, DAILY, dailySegment);

        // Every later command reloads the segment snapshot from m_mnzl_r_segment; measure strictly between the cheques.
        LocalDate mid = acquisition.plusDays(20);
        harness.moveDate(mid);
        BigInteger allowance = impairFromReloadedSegment(simple, daily, acquisition, mid, simpleSegment, dailySegment);
        ReceivableEvents.Reset expectedReset = resetFromReloadedSegment(simple, mid, acquisition, simpleSegment, dailySegment);
        assertThat(account(simple).path("position").path("lossAllowanceMinor").asText()).isEqualTo(allowance.toString());
        ObjectNode dailyReset = command("RESET_RATE", "simple-accretion-daily-reset", daily);
        dailyReset.put("corridorObservationId", "simple-accretion-rate");
        dailyReset.put("corridorRate", "0.30");
        dailyReset.put("spread", "0");
        dailyReset.put("developerAdjustmentDueDate", acquisition.plusDays(45).toString());
        assertThat(event(execute(dailyReset)).path("calculationVersion").asText()).isEqualTo(DAILY);

        mixedSettlementRefusedThenSettledPerVersion(simple, daily, acquisition.plusDays(45), expectedReset);
    }

    private void pinnedOnBooking(String id, String version, Segment expected) throws Exception {
        String segmentSql = "select s.%s from m_mnzl_r_segment s join m_mnzl_r_account a on a.active_segment_key=s.record_key "
                + "where a.external_id=?";
        assertThat(harness.queryText(String.format(segmentSql, "calculator_version"), id)).isEqualTo(version);
        JsonNode snapshot = harness.json.read(harness.queryText(String.format(segmentSql, "snapshot_json"), id));
        assertThat(snapshot.path("segment").path("calculationVersion").asText()).isEqualTo(version);
        JsonNode position = account(id).path("position");
        assertThat(new BigDecimal(position.path("grossYield").asText())).isEqualByComparingTo(expected.grossYield().rate());
        assertThat(new BigDecimal(position.path("netEir").asText())).isEqualByComparingTo(expected.netEir().rate());
        assertThat(position.path("grossPurchaseBasisMinor").asText()).isEqualTo(expected.grossBasisMinor().toString());
    }

    /** A Stage 2 shortfall forecast: the allowance is the simple-rule loss, and differs from the daily one. */
    private BigInteger impairFromReloadedSegment(String simple, String daily, LocalDate acquisition, LocalDate mid, Segment simpleSegment,
            Segment dailySegment) throws Exception {
        ObjectNode forecast = harness.json.object();
        forecast.put("forecastId", "simple-accretion-stage-two");
        forecast.put("forecastVersion", "1");
        forecast.put("asOfDate", mid.toString());
        forecast.put("validThroughDate", mid.plusDays(365).toString());
        forecast.put("stage", "STAGE_2");
        List<JsonNode> recoveries = new ArrayList<>();
        recoveries.add(recovery(simple + "-0", acquisition.plusDays(45), CHEQUE));
        recoveries.add(recovery(simple + "-1", acquisition.plusDays(90), BigInteger.valueOf(40_000)));
        ObjectNode scenario = harness.json.object();
        scenario.put("scenarioId", "shortfall");
        scenario.put("probability", "1");
        scenario.put("defaultDate", mid.toString());
        scenario.set("recoveries", harness.json.value(recoveries));
        forecast.set("scenarios", harness.json.value(List.of(scenario)));
        forecast.put("contentHash", harness.json.hash(forecast));
        ObjectNode impairment = command("SET_IMPAIRMENT", "simple-accretion-impair", simple);
        impairment.set("forecast", forecast);
        impairment.set("qualitativeFindingIds", harness.json.value(List.of()));
        impairment.set("cureEvidenceIds", harness.json.value(List.of()));
        JsonNode result = execute(impairment);

        BigInteger expected = CreditAndFunding
                .impairment(simpleSegment, ReceivablesMath.position(simpleSegment, mid, Map.of()), 2, scenarios(simple, acquisition, mid))
                .lossAllowanceMinor();
        BigInteger underDaily = CreditAndFunding
                .impairment(dailySegment, ReceivablesMath.position(dailySegment, mid, Map.of()), 2, scenarios(daily, acquisition, mid))
                .lossAllowanceMinor();
        assertThat(expected).isPositive().isNotEqualTo(underDaily);
        assertThat(journal(result, "lossAllowance", "CREDIT", "IMPAIRMENT")).isEqualTo(expected);
        assertThat(event(result).path("calculationVersion").asText()).isEqualTo(SIMPLE);
        return expected;
    }

    /** The reset is measured on the reloaded simple segment; its journal, lot and event all carry the simple rule. */
    private ReceivableEvents.Reset resetFromReloadedSegment(String simple, LocalDate mid, LocalDate acquisition, Segment simpleSegment,
            Segment dailySegment) throws Exception {
        ObjectNode reset = command("RESET_RATE", "simple-accretion-reset", simple);
        reset.put("corridorObservationId", "simple-accretion-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("developerAdjustmentDueDate", acquisition.plusDays(45).toString());
        JsonNode result = execute(reset);
        var expected = ReceivableEvents.reset(simpleSegment, mid, Map.of(), new BigDecimal("0.30"));
        var underDaily = ReceivableEvents.reset(dailySegment, mid, Map.of(), new BigDecimal("0.30"));
        assertThat(expected.deltaMinor()).isNegative().isNotEqualTo(underDaily.deltaMinor());
        assertThat(journal(result, "deferredDiscount", "CREDIT", "DEVELOPER_ADJUSTMENT")).isEqualTo(expected.deltaMinor().negate());
        assertThat(event(result).path("calculationVersion").asText()).isEqualTo(SIMPLE);
        JsonNode lot = harness.json
                .read(harness.queryText("select snapshot_json from m_mnzl_r_developer_lot where lot_id=?", "simple-accretion-reset:reset"));
        assertThat(lot.path("calculationVersion").asText()).isEqualTo(SIMPLE);
        assertThat(lot.path("direction").asText()).isEqualTo("RECEIVABLE");
        assertThat(lot.path("principalMinor").asText()).isEqualTo(expected.lot().principalMinor().toString());
        assertThat(lot.path("amountDueMinor").asText()).isEqualTo(expected.lot().amountDueMinor().toString());
        String segmentSql = "select s.calculator_version from m_mnzl_r_segment s join m_mnzl_r_account a "
                + "on a.active_segment_key=s.record_key where a.external_id=?";
        assertThat(harness.queryText(segmentSql, simple)).isEqualTo(SIMPLE);
        JsonNode position = account(simple).path("position");
        assertThat(new BigDecimal(position.path("grossYield").asText())).isEqualByComparingTo(expected.futureSegment().grossYield().rate());
        return expected;
    }

    /**
     * One developer settlement across a daily and a simple lot cannot be reported under one version, so it is refused
     * without effects; settled per account, each event names its account's rule.
     */
    private void mixedSettlementRefusedThenSettledPerVersion(String simple, String daily, LocalDate due,
            ReceivableEvents.Reset expectedReset) throws Exception {
        harness.moveDate(due);
        JsonNode lots = harness.request("GET", PREFIX + "/developer-lots?businessDate=" + due + "&boundarySide=AFTER_EVENTS", null, 200);
        long simpleOutstanding = outstanding(lots, simple);
        long dailyOutstanding = outstanding(lots, daily);
        assertThat(simpleOutstanding).isEqualTo(expectedReset.lot().balanceMinor(due).longValueExact());
        harness.recordReceipt("simple-accretion-cash", simple, Long.toString(simpleOutstanding + dailyOutstanding));
        Map<String, Long> before = harness.counts();
        ObjectNode mixed = settlement("simple-accretion-mixed-settle", simple, List
                .of(lot("simple-accretion-reset:reset", simpleOutstanding), lot("simple-accretion-daily-reset:reset", dailyOutstanding)));
        mixed.set("affectedAccountVersions",
                harness.json.value(List.of(Map.of("accountId", daily, "expectedVersion", harness.version(daily)))));
        JsonNode refused = harness.request("POST", PREFIX + "/commands", mixed, 400);
        assertThat(refused.path("code").asText()).isEqualTo("INVALID_DATA");
        assertThat(harness.counts()).isEqualTo(before);

        JsonNode settledSimple = execute(
                settlement("simple-accretion-settle", simple, List.of(lot("simple-accretion-reset:reset", simpleOutstanding))));
        assertThat(event(settledSimple).path("calculationVersion").asText()).isEqualTo(SIMPLE);
        JsonNode settledDaily = execute(
                settlement("simple-accretion-settle-daily", daily, List.of(lot("simple-accretion-daily-reset:reset", dailyOutstanding))));
        assertThat(event(settledDaily).path("calculationVersion").asText()).isEqualTo(DAILY);
        assertThat(outstanding(
                harness.request("GET", PREFIX + "/developer-lots?businessDate=" + due + "&boundarySide=AFTER_EVENTS", null, 200), simple))
                .isZero();
    }

    private ObjectNode settlement(String operation, String account, List<JsonNode> lots) throws Exception {
        ObjectNode payment = command("SETTLE_DEVELOPER_ADJUSTMENT", operation, account);
        payment.put("method", "CASH");
        payment.put("cashMovementId", "simple-accretion-cash");
        payment.set("lots", harness.json.value(lots));
        return payment;
    }

    private JsonNode lot(String lotId, long amount) {
        return harness.json.value(Map.of("lotId", lotId, "amountMinor", Long.toString(amount)));
    }

    private static long outstanding(JsonNode lots, String accountId) {
        for (JsonNode lot : lots.path("items")) {
            if (lot.path("accountId").asText().equals(accountId)) {
                return lot.path("outstandingMinor").asLong();
            }
        }
        throw new AssertionError("Missing developer lot for " + accountId);
    }

    private List<Cashflow> flows(String id, LocalDate acquisition) {
        return List.of(new Cashflow(id + "-0", acquisition.plusDays(45), CHEQUE),
                new Cashflow(id + "-1", acquisition.plusDays(90), CHEQUE));
    }

    private List<CreditAndFunding.Scenario> scenarios(String id, LocalDate acquisition, LocalDate defaultDate) {
        return List.of(new CreditAndFunding.Scenario("shortfall", BigDecimal.ONE, defaultDate,
                List.of(new CreditAndFunding.Recovery(id + "-0", CreditAndFunding.Payer.BORROWER, acquisition.plusDays(45), CHEQUE),
                        new CreditAndFunding.Recovery(id + "-1", CreditAndFunding.Payer.BORROWER, acquisition.plusDays(90),
                                BigInteger.valueOf(40_000)))));
    }

    private ObjectNode recovery(String cashflowId, LocalDate date, BigInteger amount) {
        ObjectNode recovery = harness.json.object();
        recovery.put("sourceCashflowId", cashflowId);
        recovery.put("date", date.toString());
        recovery.put("amountMinor", amount.toString());
        recovery.put("payer", "BORROWER");
        return recovery;
    }

    private BigInteger journal(JsonNode result, String semanticAccount, String side, String component) throws Exception {
        return new BigInteger(harness.queryText(
                "select amount_minor from m_mnzl_r_journal_line where event_key=? and semantic_account=? and side=? and component=?",
                result.path("financialEventIds").get(0).asText(), semanticAccount, side, component));
    }

    private JsonNode event(JsonNode result) throws Exception {
        return harness.json.read(harness.queryText("select event_json from m_mnzl_r_event where record_key=?",
                result.path("financialEventIds").get(0).asText()));
    }

    private ObjectNode command(String type, String operation, String account) throws Exception {
        ObjectNode command = harness.command(type, operation, account, "RECEIVABLE");
        command.put("expectedVersion", harness.version(account));
        return command;
    }

    private JsonNode execute(ObjectNode command) throws Exception {
        JsonNode result = harness.request("POST", PREFIX + "/commands", command, 200);
        long events = harness.queryLong("select count(*) from m_mnzl_r_event");
        assertThat(harness.request("POST", PREFIX + "/commands", command, 200)).isEqualTo(result);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_event")).isEqualTo(events);
        return result;
    }

    private JsonNode account(String id) throws Exception {
        return harness.request("GET", PREFIX + "/accounts/" + id, null, 200);
    }
}
