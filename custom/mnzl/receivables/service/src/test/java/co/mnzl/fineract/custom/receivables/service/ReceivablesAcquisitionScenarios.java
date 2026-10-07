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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Scoped closing membership, original cost and account-attributed controls against real native bookings. */
final class ReceivablesAcquisitionScenarios {

    private final ReceivablesDatabaseIntegrationTest harness;
    private static final String BASE = ReceivablesDatabaseIntegrationTest.PREFIX;

    ReceivablesAcquisitionScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        assertThat(get("/capabilities").path("acquisitionGroupingVersion").asText()).isEqualTo("1");
        assertThat(get("/capabilities").path("customerDisplayNameVersion").asText()).isEqualTo("1");
        verifyProjectRateBookings();
        var firstCommand = harness.preparePurchase("group-first", "50000", "50000", "closing-one");
        firstCommand.put("customerDisplayName", "Group Buyer");
        var secondCommand = harness.preparePurchase("group-second", "30000", "70000", "closing-one");
        var firstBooking = CompletableFuture.supplyAsync(() -> book(firstCommand));
        var secondBooking = CompletableFuture.supplyAsync(() -> book(secondCommand));
        firstBooking.get();
        secondBooking.get();
        // The second closing's purchase is a later purchase for the first buyer under a different name:
        // the native client is reused by its reference and keeps the name it was created with.
        var otherCommand = harness.preparePurchase("group-other", "40000", "60000", "closing-two");
        otherCommand.put("customerReferenceId", "group-first-customer");
        otherCommand.put("customerDisplayName", "Renamed Group Buyer");
        book(otherCommand);
        long buyer = get("/accounts/group-first").path("nativeClientId").asLong();
        assertThat(get("/accounts/group-other").path("nativeClientId").asLong()).isEqualTo(buyer);
        assertThat(harness.request("GET", "/clients/" + buyer, null, 200).path("displayName").asText()).isEqualTo("Group Buyer");
        JsonNode group = get("/acquisitions/closing-one");
        assertThat(group.path("originalAccountCount").asInt()).isEqualTo(2);
        assertThat(group.path("originalFaceMinor").asText()).isEqualTo("200000");
        assertThat(group.path("currentContractualOutstandingMinor").asText()).isEqualTo("200000");
        BigInteger cash = BigInteger.ZERO;
        for (String id : new String[] { "group-first", "group-second" }) {
            var price = harness.bookingCommands.get(id).path("basis").path("acceptedAccountPrices").get(0);
            cash = cash.add(new BigInteger(price.path("netPurchaseCashMinor").asText()));
            assertThat(get("/accounts/" + id).path("acquisitionId").asText()).isEqualTo("closing-one");
        }
        assertThat(group.path("originalPurchaseCashMinor").asText()).isEqualTo(cash.toString());
        assertThat(new BigInteger(group.path("originalGrossPurchasePriceMinor").asText())
                .subtract(new BigInteger(group.path("originalAdminFeeMinor").asText()))).isEqualTo(cash);
        JsonNode first = get("/acquisitions/closing-one/accounts?limit=1");
        JsonNode second = get("/acquisitions/closing-one/accounts?limit=1&cursor=" + first.path("nextCursor").asText());
        assertThat(first.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("accountId")).isNotEqualTo(first.path("items").get(0).path("accountId"));
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(get("/acquisitions/closing-two").path("originalAccountCount").asInt()).isEqualTo(1);
        JsonNode controls = get("/controls?acquisitionId=closing-one");
        assertThat(controls.path("attribution").asText()).isEqualTo("ACQUISITION_ACCOUNTS");
        assertThat(controls.path("nativeContractualOutstandingMinor").asText()).isEqualTo("200000");
        assertThat(controls.path("activeAccountIds").size()).isEqualTo(2);
        for (JsonNode balance : controls.path("balances")) {
            assertThat(balance.path("differenceMinor").asText()).isEqualTo("0");
        }
        harness.request("GET", BASE + "/controls?acquisitionId=closing-one&accountId=group-other", null, 400);
        harness.request("GET", BASE + "/acquisitions/missing/accounts", null, 404);
        assertThat(get("/acquisitions?dealId=missing").path("items").size()).isZero();
        verifyListing();
        verifyScopedUnregisteredJournals(controls);
        var collision = harness.preparePurchase("group-conflict", "50000", "50000", "closing-one");
        ((ObjectNode) collision.path("acquisition")).put("sourceReferenceId", "different-offer");
        var counts = harness.counts();
        assertThat(harness.request("POST", BASE + "/commands", collision, 409).path("code").asText()).isEqualTo("SOURCE_CHANGED");
        assertThat(harness.counts()).isEqualTo(counts);
        ObjectNode changed = harness.bookingCommands.get("group-first").deepCopy();
        ((ObjectNode) changed.path("acquisition")).put("sourceReferenceId", "changed");
        assertThat(harness.request("POST", BASE + "/commands", changed, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(harness.counts()).isEqualTo(counts);
        assertThat(get("/acquisitions/closing-one")).isEqualTo(group);
    }

    private void verifyProjectRateBookings() throws Exception {
        assertThat(get("/capabilities").path("projectAcquisitionRateVersion").asText()).isEqualTo("1");
        // mapping-1 is the legacy account set, so only deferred-fee versions can book here; DAILY_V2 books after
        // migration.
        for (String version : List.of(ReceivablesConfiguration.CALCULATION,
                co.mnzl.fineract.receivables.math.ReceivablesMath.SIMPLE_CALCULATION_VERSION)) {
            String id = version.equals(ReceivablesConfiguration.CALCULATION) ? "project-daily" : "project-simple";
            ObjectNode command = harness.preparePurchase(id, "50000", "50000", id + "-acquisition", version);
            ObjectNode basis = (ObjectNode) command.get("basis");
            // The issued rate was selected before settlement. Only the provenance changes from the equivalent legacy
            // price.
            basis.remove(List.of("corridorObservationId", "corridorRate", "spread"));
            ObjectNode rate = basis.putObject("acquisitionRateBasis");
            rate.put("kind", "PROJECT_RATE");
            rate.put("projectId", "project-1");
            rate.put("projectRateRevisionId", "issued-revision");
            rate.put("projectRateContentHash", "a".repeat(64));
            rate.put("effectiveDate", harness.today.minusDays(30).toString());
            rate.put("pricingDate", harness.today.minusDays(20).toString());
            rate.put("rateLookupDate", harness.today.minusDays(19).toString());
            rate.put("annualNominalRate", "0.24");
            command.put("basisHash", harness.json.hash(harness.json.normalizedBasis(basis)));
            var result = book(command);
            var counts = harness.counts();
            assertThat(book(command)).isEqualTo(result);
            assertThat(harness.counts()).isEqualTo(counts);
            assertThat(harness.json.read(harness.queryText("select terms_json from m_mnzl_r_account where external_id=?", id)))
                    .isEqualTo(basis);
            assertThat(harness.queryText("select s.calculator_version from m_mnzl_r_segment s join m_mnzl_r_account a "
                    + "on a.active_segment_key=s.record_key where a.external_id=?", id)).isEqualTo(version);
            JsonNode account = get("/accounts/" + id);
            ObjectNode reset = harness.command("RESET_RATE", id + "-reset", id, "RECEIVABLE");
            reset.put("expectedVersion", harness.version(id));
            reset.put("corridorObservationId", "later-corridor");
            reset.put("corridorRate", "0.30");
            reset.put("spread", "0.02");
            reset.put("developerAdjustmentDueDate", harness.today.plusDays(45).toString());
            assertThat(harness.request("POST", BASE + "/commands", reset, 400).path("code").asText()).isEqualTo("INVALID_DATA");
            assertThat(harness.counts()).isEqualTo(counts);
            assertThat(get("/accounts/" + id)).isEqualTo(account);
            assertThat(harness.queryText("select count(*) from m_mnzl_r_developer_lot l join m_mnzl_r_account a "
                    + "on l.account_key=a.record_key where a.external_id=?", id)).isEqualTo("0");
        }
    }

    private void verifyListing() throws Exception {
        Set<String> expected = new HashSet<>();
        for (JsonNode acquisition : get("/acquisitions?dealId=deal").path("items")) {
            assertThat(acquisition.path("dealId").asText()).isEqualTo("deal");
            expected.add(acquisition.path("id").asText());
        }
        assertThat(expected).contains("closing-one", "closing-two").doesNotContain("historical-acquisition");
        for (JsonNode acquisition : get("/acquisitions?developerReferenceId=developer").path("items")) {
            assertThat(acquisition.path("developerReferenceId").asText()).isEqualTo("developer");
        }
        Set<String> paged = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = get(
                    "/acquisitions?dealId=deal&developerReferenceId=developer&limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
            for (JsonNode acquisition : page.path("items")) {
                assertThat(paged.add(acquisition.path("id").asText())).isTrue();
                assertThat(acquisition.path("dealId").asText()).isEqualTo("deal");
                assertThat(acquisition.path("developerReferenceId").asText()).isEqualTo("developer");
                assertThat(acquisition).isEqualTo(get("/acquisitions/" + acquisition.path("id").asText()));
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            pages++;
            assertThat(pages).isLessThanOrEqualTo(expected.size());
        } while (cursor != null);
        assertThat(pages).isGreaterThanOrEqualTo(2);
        assertThat(paged).isEqualTo(expected);
        assertThat(get("/acquisitions?dealId=deal&developerReferenceId=missing").path("items")).isEmpty();
    }

    private void verifyScopedUnregisteredJournals(JsonNode firstControls) throws Exception {
        JsonNode secondControls = get("/controls?acquisitionId=closing-two");
        for (String operation : new String[] { "group-other-cash", "group-first-cash" }) {
            // Remove every registry line of a cash event: attribution must survive in immutable event JSON.
            harness.executeSql("create table flex_acq_journal_backup as select l.* from m_mnzl_r_journal_line l "
                    + "join m_mnzl_r_event e on e.record_key=l.event_key join m_mnzl_r_command c on c.record_key=e.operation_key "
                    + "where c.operation_id=?", operation);
            try {
                assertThat(harness.queryLong("select count(*) from flex_acq_journal_backup")).isGreaterThan(0);
                harness.executeSql(
                        "delete from m_mnzl_r_journal_line where record_key in (select record_key from flex_acq_journal_backup)");
                boolean first = operation.equals("group-first-cash");
                String affected = first ? "closing-one" : "closing-two";
                String unaffected = first ? "closing-two" : "closing-one";
                assertThat(get("/controls?acquisitionId=" + unaffected)).isEqualTo(first ? secondControls : firstControls);
                assertThat(harness.request("GET", BASE + "/controls?acquisitionId=" + affected, null, 409).path("code").asText())
                        .isEqualTo("JOURNAL_MISMATCH");
                assertThat(harness.request("GET", BASE + "/controls", null, 409).path("code").asText()).isEqualTo("JOURNAL_MISMATCH");
            } finally {
                harness.executeSql("insert into m_mnzl_r_journal_line select * from flex_acq_journal_backup");
                harness.executeSql("drop table flex_acq_journal_backup");
            }
        }
    }

    private JsonNode book(ObjectNode command) {
        try {
            return harness.request("POST", BASE + "/commands", command, 200);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private JsonNode get(String path) throws Exception {
        return harness.request("GET", BASE + path, null, 200);
    }
}
