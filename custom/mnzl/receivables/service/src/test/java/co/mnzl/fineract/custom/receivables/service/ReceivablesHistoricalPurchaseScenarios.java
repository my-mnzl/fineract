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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Real native effects, partial cash reconciliation and immutable historical risk/funding reads on every database. */
final class ReceivablesHistoricalPurchaseScenarios {

    private final ReceivablesDatabaseIntegrationTest h;
    private static final String ID = "historical-account";
    private static final String BASE = ReceivablesDatabaseIntegrationTest.PREFIX;

    ReceivablesHistoricalPurchaseScenarios(ReceivablesDatabaseIntegrationTest harness) {
        h = harness;
    }

    void verify() throws Exception {
        String legacyEvent = h.queryText(
                "select event_json from m_mnzl_r_event where operation_key=(select record_key from m_mnzl_r_command where operation_id='account-1-book')");
        h.migrateLegacyHistoricalPurchases();
        assertThat(h.queryLong(
                "select count(*) from m_mnzl_r_account where risk_assessment_status<>'ASSESSED' or unreconciled_purchase_minor<>'0'"))
                .isZero();
        assertThat(h.queryText(
                "select event_json from m_mnzl_r_event where operation_key=(select record_key from m_mnzl_r_command where operation_id='account-1-book')"))
                .isEqualTo(legacyEvent);
        h.moveDate(LocalDate.parse(h.queryText("select max(last_effective_date) from m_mnzl_r_account")).plusMonths(2));
        LocalDate acquisitionDate = h.today;
        ObjectNode book = book();
        book.put("executionMode", "RECONSTRUCTION");
        book.set("executionAuthorization", h.json.value(Map.of("authorizationId", "historical-book-grant", "scopeHash", "0".repeat(64),
                "approvedBy", "1", "effectiveFrom", acquisitionDate.toString(), "effectiveThrough", acquisitionDate.toString())));
        h.issueHistory(book);
        h.moveDate(acquisitionDate.plusDays(1));
        var before = h.counts();
        var first = CompletableFuture.supplyAsync(() -> execute(book));
        var second = CompletableFuture.supplyAsync(() -> execute(book));
        JsonNode receipt = first.get();
        assertThat(second.get()).isEqualTo(receipt);
        assertThat(h.queryLong("select count(*) from m_mnzl_r_account where external_id='historical-account'")).isEqualTo(1);
        assertThat(h.queryLong("select count(*) from m_mnzl_r_cash_source")).isEqualTo(before.get("m_mnzl_r_cash_source"));
        assertThat(h.queryLong(
                "select count(*) from m_mnzl_r_risk_forecast where account_key=(select record_key from m_mnzl_r_account where external_id='historical-account')"))
                .isZero();
        BigInteger net = new BigInteger(book.path("basis").path("acceptedAccountPrices").get(0).path("netPurchaseCashMinor").asText());
        JsonNode pending = position(acquisitionDate, null);
        assertThat(pending.path("stage").isNull()).isTrue();
        assertThat(pending.path("riskAssessmentStatus").asText()).isEqualTo("PENDING");
        assertThat(pending.path("lossAllowanceMinor").asText()).isEqualTo("0");
        assertThat(pending.path("unreconciledPurchaseMinor").asText()).isEqualTo(net.toString());
        String watermark = h.request("GET", BASE + "/capabilities", null, 200).path("currentEventWatermark").asText();
        var committed = h.counts();
        assertThat(execute(book)).isEqualTo(receipt);
        assertThat(h.counts()).isEqualTo(committed);
        ObjectNode conflict = book.deepCopy();
        ((ObjectNode) conflict.get("acknowledgment")).put("reason", "different recorded facts");
        assertThat(h.request("POST", BASE + "/commands", conflict, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        h.moveDate(acquisitionDate.plusDays(1));
        cash(net, "historical-payment", ID);
        var reconcile = h.command("RECONCILE_HISTORICAL_PURCHASE", "historical-reconcile-part", ID, "RECEIVABLE");
        reconcile.put("expectedVersion", "1");
        reconcile.put("amountMinor", "1");
        reconcile.set("acquisitionClearingAllocationIds", h.json.value(List.of("historical-payment-allocation")));
        JsonNode partial = execute(reconcile);
        assertThat(position(h.today, null).path("unreconciledPurchaseMinor").asText()).isEqualTo(net.subtract(BigInteger.ONE).toString());
        assertThat(execute(reconcile)).isEqualTo(partial);
        reconcile.put("operationId", "historical-reconcile-over");
        reconcile.put("idempotencyKey", "historical-reconcile-over");
        reconcile.put("expectedVersion", "2");
        reconcile.put("amountMinor", net.toString());
        assertThat(h.request("POST", BASE + "/commands", reconcile, 422).path("code").asText()).isEqualTo("BANK_PROOF_MISMATCH");
        reconcile.put("operationId", "historical-reconcile-rest");
        reconcile.put("idempotencyKey", "historical-reconcile-rest");
        reconcile.put("amountMinor", net.subtract(BigInteger.ONE).toString());
        long journalCount = h.queryLong("select count(*) from acc_gl_journal_entry");
        long loanCount = h.queryLong("select count(*) from m_loan");
        execute(reconcile);
        assertThat(h.queryLong("select count(*) from acc_gl_journal_entry")).isEqualTo(journalCount);
        assertThat(h.queryLong("select count(*) from m_loan")).isEqualTo(loanCount);
        assertThat(position(h.today, null).path("unreconciledPurchaseMinor").asText()).isEqualTo("0");
        assertThat(position(acquisitionDate, watermark)).isEqualTo(pending);
        ObjectNode assess = h.command("SET_IMPAIRMENT", "historical-assess", ID, "RECEIVABLE");
        assess.put("expectedVersion", "3");
        assess.set("qualitativeFindingIds", h.json.value(List.of()));
        assess.set("cureEvidenceIds", h.json.value(List.of()));
        var recoveries = new ArrayList<JsonNode>();
        for (JsonNode flow : book.path("basis").path("cashflows")) {
            recoveries.add(h.json.value(Map.of("sourceCashflowId", flow.path("cashflowId").asText(), "date", flow.path("dueDate").asText(),
                    "amountMinor", flow.path("amountMinor").asText(), "payer", "BORROWER")));
        }
        assess.set("forecast",
                h.json.value(Map.of("forecastId", "historical-assessment", "forecastVersion", "1", "asOfDate", h.today.toString(),
                        "validThroughDate", h.today.plusYears(1).toString(), "stage", "STAGE_1", "contentHash", "0".repeat(64), "scenarios",
                        List.of(Map.of("scenarioId", "assessed-contractual", "probability", "1", "recoveries", recoveries)))));
        ((ObjectNode) assess.path("forecast").path("scenarios").get(0)).putNull("defaultDate");
        execute(assess);
        assertThat(position(h.today, null).path("riskAssessmentStatus").asText()).isEqualTo("ASSESSED");
        assertThat(position(h.today, null).path("stage").asText()).isEqualTo("STAGE_1");
        assertThat(position(acquisitionDate, watermark)).isEqualTo(pending);
        assertThat(h.request("GET", BASE + "/capabilities", null, 200).path("historicalPurchaseVersion").asText()).isEqualTo("1");
    }

    private ObjectNode book() throws Exception {
        ObjectNode template = h.bookingCommands.get("account-1");
        ObjectNode basis = template.path("basis").deepCopy();
        basis.put("settlementDate", h.today.toString());
        int index = 0;
        for (JsonNode flow : basis.path("cashflows")) {
            ObjectNode value = (ObjectNode) flow;
            value.put("receivableId", ID);
            value.put("cashflowId", ID + "-" + index);
            value.put("installmentId", ID + "-" + index);
            value.put("dueDate", h.today.plusDays(++index * 45).toString());
        }
        basis.set("acceptedAccountPrices", h.json.value(List.of()));
        ObjectNode price = h.json.object();
        for (String field : List.of("calculationVersion", "productPolicyCode", "schemaVersion", "policyRevisionId", "calculatorBuild")) {
            price.set(field, basis.get(field));
        }
        price.put("calculationType", "PRICE");
        price.put("basisHash", h.json.hash(basis));
        price.set("basis", basis);
        JsonNode result = h.request("POST", BASE + "/calculate", price, 200).path("pricing").path("accounts").get(0);
        ObjectNode accepted = h.json.object();
        for (String field : List.of("accountId", "grossPurchasePriceMinor", "integralFeeMinor", "netPurchaseCashMinor")) {
            accepted.set(field, result.get(field));
        }
        basis.set("acceptedAccountPrices", h.json.value(List.of(accepted)));
        ObjectNode book = h.command("BOOK_HISTORICAL_PURCHASE", "historical-book", ID, "RECEIVABLE");
        book.put("accountId", ID);
        book.put("dealId", "historical-deal");
        book.put("customerReferenceId", "historical-customer");
        book.set("basis", basis);
        book.put("basisHash", h.json.hash(basis));
        book.set("approverIds", h.json.value(List.of()));
        book.set("approvalEvidenceIds", h.json.value(List.of()));
        book.set("acknowledgment",
                h.json.value(Map.of("recordId", "historical-record", "sourceHash", basis.path("sourceHash").asText(), "basisHash",
                        h.json.hash(basis), "actorId", "operator", "reason",
                        "Recorded completed acquisition; documents, assessment and payment pending")));
        return book;
    }

    private void cash(BigInteger amount, String id, String account) throws Exception {
        ObjectNode command = h.command("RECORD_CASH_MOVEMENT", id, "historical-deal", "DEAL");
        command.set("source", h.json.value(Map.of("bankSourceId", id, "bankAccountReference", "test-bank", "verificationEvidenceId",
                "verified-later", "valueDate", h.today.toString(), "currency", "EGP", "amountMinor", amount.toString(), "direction",
                "OUTGOING", "allocations", List.of(Map.of("allocationId", id + "-allocation", "kind", "ACQUISITION_ADVANCE", "accountId",
                        account, "dealId", "historical-deal", "beneficiaryReferenceId", "developer", "amountMinor", amount.toString())))));
        execute(command);
    }

    private JsonNode execute(ObjectNode command) {
        try {
            return h.request("POST", BASE + "/commands", command, 200);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private JsonNode position(LocalDate date, String watermark) throws Exception {
        return h.request("GET", BASE + "/accounts/" + ID + "/position?businessDate=" + date + "&boundarySide=AFTER_EVENTS"
                + (watermark == null ? "" : "&eventWatermark=" + watermark), null, 200);
    }
}
