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
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Real native product population, immutable posting attribution and captured-report evidence. */
final class ReceivablesHelReportingScenarios {

    private static final String ROOT = ReceivablesDatabaseIntegrationTest.PREFIX + "/hel-reporting";
    private final ReceivablesDatabaseIntegrationTest harness;
    private final ReceivablesJson json;

    ReceivablesHelReportingScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
        this.json = harness.json;
    }

    void verify() throws Exception {
        long product = harness.queryLong("select hel_product_id from m_mnzl_r_configuration");
        long client = harness.queryLong("select min(client_id) from m_loan where product_id=" + product);
        long pending = harness.request("POST", "/loans", harness.ordinaryLoanRequest(client, product), 200).path("loanId").asLong();
        long withdrawn = harness.request("POST", "/loans", harness.ordinaryLoanRequest(client, product), 200).path("loanId").asLong();
        ObjectNode withdraw = dated("withdrawnOnDate");
        harness.request("POST", "/loans/" + withdrawn + "?command=withdrawnByApplicant", withdraw, 200);
        var registration = json.object();
        registration.set("scope", harness.scope(false));
        registration.put("registrationId", "reporting-registration");
        registration.put("helProductId", Long.toString(product));
        registration.put("coverageMode", "ALL_CONFIGURED_HEL_PRODUCT_LOANS");
        registration.put("initialSnapshotId", "reporting-initial");
        JsonNode registered = harness.request("POST", ROOT + "/registrations", registration, 200);
        assertThat(harness.request("POST", ROOT + "/registrations", registration, 200)).isEqualTo(registered);
        JsonNode initial = registered.path("initialSnapshot");
        assertThat(initial.path("memberCount").asLong())
                .isEqualTo(harness.queryLong("select count(*) from m_loan where product_id=" + product));
        List<JsonNode> members = members("reporting-initial");
        assertThat(members.stream().map(row -> row.path("nativeLoanId").asLong()).toList()).contains(pending, withdrawn);
        for (JsonNode member : members) {
            long id = member.path("nativeLoanId").asLong();
            assertThat(member.path("nativeStatusId").asLong())
                    .isEqualTo(harness.queryLong("select loan_status_id from m_loan where id=" + id));
            assertThat(member.path("nativeStatusCode").asText()).isNotEmpty();
            hash(member);
        }
        assertThat(initial.path("memberManifestHash").asText()).isEqualTo(manifest(members, "nativeLoanId"));
        JsonNode firstPage = harness.request("GET", ROOT + "/snapshots/reporting-initial/members?limit=1", null, 200);
        String initialCursor = firstPage.path("nextCursor").asText();
        assertThat(initialCursor).isNotBlank();
        var changed = registration.deepCopy();
        changed.put("initialSnapshotId", "changed-initial");
        harness.request("POST", ROOT + "/registrations", changed, 409);
        capture("reporting-before-provision");
        harness.request("GET", ROOT + "/snapshots/reporting-before-provision/members?limit=1&cursor=" + encode(initialCursor), null, 409);
        harness.request("GET", ROOT + "/snapshots/reporting-initial/journals?periodStartDate=" + harness.today.minusDays(1)
                + "&throughDate=" + harness.today, null, 409);

        ObjectNode siblingRequest = harness.ordinaryProductRequest();
        siblingRequest.put("name", "Provision sibling");
        siblingRequest.put("shortName", "PRVS");
        long sibling = harness.request("POST", "/loanproducts", siblingRequest, 200).path("resourceId").asLong();
        long siblingLoan = harness.request("POST", "/loans", harness.ordinaryLoanRequest(client, sibling), 200).path("loanId").asLong();
        harness.request("POST", "/loans/" + siblingLoan + "?command=approve", dated("approvedOnDate"), 200);
        harness.request("POST", "/loans/" + siblingLoan + "?command=disburse", dated("actualDisbursementDate"), 200);
        JsonNode categories = harness.request("GET", "/provisioningcategory", null, 200);
        var criteria = json.object();
        criteria.put("criteriaName", "Immutable product attribution");
        criteria.put("locale", "en");
        criteria.set("loanProducts", json.value(List.of(Map.of("id", product), Map.of("id", sibling))));
        var definitions = criteria.putArray("definitions");
        for (int index = 0; index < categories.size(); index++) {
            JsonNode category = categories.get(index);
            var definition = definitions.addObject();
            definition.set("categoryId", category.get("id"));
            definition.set("categoryName", category.get("categoryName"));
            definition.put("minAge", index == 0 ? 0 : index * 30 + 1);
            definition.put("maxAge", index == categories.size() - 1 ? 90000 : (index + 1) * 30);
            definition.put("provisioningPercentage", 10);
            definition.put("liabilityAccount", harness.accounts.get("lossAllowance"));
            definition.put("expenseAccount", harness.accounts.get("impairmentExpense"));
        }
        harness.request("POST", "/provisioningcriteria", criteria, 200);
        long history = provision();
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_provision_source")).isGreaterThan(0);
        JsonNode posted = capture("reporting-posted");
        JsonNode postedPool = posted.path("productPool");
        assertThat(new BigDecimal(postedPool.path("recordedProductAllowanceMinor").asText())).isPositive();
        List<JsonNode> journals = journals("reporting-posted", harness.today, harness.today);
        assertThat(journals.stream().filter(row -> row.path("sourceKind").asText().equals("PROVISION_POOL"))).allSatisfy(row -> {
            assertThat(row.path("originalProvisionJournalId").asText()).isEqualTo(row.path("nativeJournalId").asText());
            assertThat(row.path("originalPostingProofHash").asText()).hasSize(64);
            assertThat(row.path("aggregateSourceCount").asInt()).isGreaterThan(row.path("targetProvisionComponents").size());
            row.path("targetProvisionComponents").forEach(component -> assertThat(component.path("productId").asLong()).isEqualTo(product));
        });
        long sourceCount = harness.queryLong("select count(*) from m_mnzl_r_provision_source");
        harness.request("POST", "/provisioningentries/" + history + "?command=recreateprovisioningentry", json.object(), 200);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_provision_source")).isEqualTo(sourceCount);
        assertThat(capture("reporting-recreated").path("productPool")).isEqualTo(postedPool);
        // Same aggregate GL, different mutable product split: reporting must retain the posted split.
        long targetComponent = harness.queryLong(
                "select min(id) from m_loanproduct_provisioning_entry where history_id=" + history + " and product_id=" + product);
        long siblingComponent = harness.queryLong(
                "select min(id) from m_loanproduct_provisioning_entry where history_id=" + history + " and product_id=" + sibling);
        assertThat(targetComponent).isPositive();
        assertThat(siblingComponent).isPositive();
        harness.executeSql("update m_loanproduct_provisioning_entry set reseve_amount=reseve_amount+1 where id=?", targetComponent);
        harness.executeSql("update m_loanproduct_provisioning_entry set reseve_amount=reseve_amount-1 where id=?", siblingComponent);
        assertThat(capture("reporting-mutated-components").path("productPool")).isEqualTo(postedPool);
        assertThat(harness.request("GET", ROOT + "/snapshots/reporting-posted", null, 200)).isEqualTo(posted);

        // Temporarily remove only fixture provenance to represent a pre-hook native posting.
        String source = harness.queryText(
                "select source_json from m_mnzl_r_provision_source where native_journal_id=(select min(id) from acc_gl_journal_entry where entity_type_enum=3)");
        String sourceHash = json.hash(json.read(source));
        long originalJournal = json.read(source).path("nativeJournalId").asLong();
        harness.executeSql("delete from m_mnzl_r_provision_source where native_journal_id=?", originalJournal);
        capture("reporting-unproven", 409);
        harness.moveDate(harness.today.plusDays(1));
        provision(); // Native posting reverses the exact prior journals before posting current components.
        JsonNode canceled = capture("reporting-canceled-unknown");
        assertThat(canceled.path("provisionExclusions").size()).isEqualTo(1);
        JsonNode exclusion = canceled.path("provisionExclusions").get(0);
        assertThat(exclusion.path("netAmountMinor").asText()).isEqualTo("0");
        assertThat(exclusion.has("originalJournal")).isFalse();
        assertThat(canceled.path("journalHistoryAvailableFromDate").asText()).isEqualTo(harness.today.plusDays(1).toString());
        harness.request("GET",
                ROOT + "/snapshots/reporting-canceled-unknown/journals?periodStartDate=" + harness.today + "&throughDate=" + harness.today,
                null, 409);
        JsonNode privateProof = json.read(harness.queryText(
                "select provision_exclusions_json from m_mnzl_r_hel_reporting_capture where snapshot_id='reporting-canceled-unknown'"));
        assertThat(privateProof.get(0).path("contentHash")).isEqualTo(exclusion.path("cancellationProofHash"));
        assertThat(privateProof.get(0).path("originalJournal").path("nativeJournalId").asLong()).isEqualTo(originalJournal);
        harness.executeSql("insert into m_mnzl_r_provision_source(native_journal_id,source_json,source_hash) values (?,?,?)",
                originalJournal, source, sourceHash);
        JsonNode knownReversal = capture("reporting-known-reversal");
        assertThat(knownReversal.path("provisionExclusions").isEmpty()).isTrue();
        assertThat(capture("reporting-canceled-unknown")).isEqualTo(canceled);
        hash(knownReversal);
    }

    private ObjectNode dated(String field) {
        var request = json.object();
        request.put(field, harness.today.toString());
        request.put("dateFormat", "yyyy-MM-dd");
        request.put("locale", "en");
        return request;
    }

    private long provision() throws Exception {
        var request = dated("date");
        request.put("createjournalentries", true);
        return harness.request("POST", "/provisioningentries", request, 200).path("resourceId").asLong();
    }

    private JsonNode capture(String id) throws Exception {
        return capture(id, 200);
    }

    private JsonNode capture(String id, int status) throws Exception {
        var request = json.object();
        request.set("scope", harness.scope(false));
        request.put("registrationId", "reporting-registration");
        request.put("snapshotId", id);
        return harness.request("POST", ROOT + "/snapshots", request, status);
    }

    private List<JsonNode> members(String id) throws Exception {
        return pages(ROOT + "/snapshots/" + id + "/members?limit=1");
    }

    private List<JsonNode> journals(String id, LocalDate start, LocalDate through) throws Exception {
        return pages(ROOT + "/snapshots/" + id + "/journals?limit=1&periodStartDate=" + start + "&throughDate=" + through);
    }

    private List<JsonNode> pages(String path) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        String cursor = null;
        JsonNode first = null;
        do {
            JsonNode page = harness.request("GET", path + (cursor == null ? "" : "&cursor=" + encode(cursor)), null, 200);
            if (first == null) {
                first = page;
            }
            assertThat(page.path("selectionHash")).isEqualTo(first.path("selectionHash"));
            assertThat(page.path("itemManifestHash")).isEqualTo(first.path("itemManifestHash"));
            page.path("items").forEach(result::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(result.size()).isEqualTo(first.path("itemCount").asInt());
        assertThat(first.path("itemManifestHash").asText())
                .isEqualTo(manifest(result, path.contains("/members?") ? "nativeLoanId" : "nativeJournalId"));
        result.forEach(this::hash);
        return result;
    }

    private String manifest(List<JsonNode> rows, String identity) {
        var manifest = json.array();
        for (JsonNode row : rows) {
            manifest.add(json.value(Map.of(identity, row.path(identity).asText(), "contentHash", row.path("contentHash").asText())));
        }
        return json.hash(manifest);
    }

    private void hash(JsonNode row) {
        ObjectNode contents = row.deepCopy();
        String expected = contents.remove("contentHash").asText();
        assertThat(json.hash(contents)).isEqualTo(expected);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
