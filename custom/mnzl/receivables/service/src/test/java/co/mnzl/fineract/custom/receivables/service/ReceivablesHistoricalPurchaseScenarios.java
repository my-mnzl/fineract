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

    private final ReceivablesDatabaseIntegrationTest harness;
    private static final String ID = "historical-account";
    private static final String BASE = ReceivablesDatabaseIntegrationTest.PREFIX;

    ReceivablesHistoricalPurchaseScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        String legacyEvent = harness.queryText(
                "select event_json from m_mnzl_r_event where operation_key=(select record_key from m_mnzl_r_command where operation_id='account-1-book')");
        harness.migrateLegacyHistoricalPurchases();
        assertThat(harness.queryLong(
                "select count(*) from m_mnzl_r_account where risk_assessment_status<>'ASSESSED' or unreconciled_purchase_minor<>'0'"))
                .isZero();
        assertThat(harness.queryText(
                "select event_json from m_mnzl_r_event where operation_key=(select record_key from m_mnzl_r_command where operation_id='account-1-book')"))
                .isEqualTo(legacyEvent);
        harness.moveDate(LocalDate.parse(harness.queryText("select max(last_effective_date) from m_mnzl_r_account")).plusMonths(2));
        LocalDate acquisitionDate = harness.today;
        ObjectNode book = book();
        book.put("executionMode", "RECONSTRUCTION");
        book.set("executionAuthorization",
                harness.json.value(Map.of("authorizationId", "historical-book-grant", "scopeHash", "0".repeat(64), "approvedBy", "1",
                        "effectiveFrom", acquisitionDate.toString(), "effectiveThrough", acquisitionDate.toString())));
        harness.issueHistory(book);
        harness.moveDate(acquisitionDate.plusDays(1));
        var before = harness.counts();
        var first = CompletableFuture.supplyAsync(() -> execute(book));
        var second = CompletableFuture.supplyAsync(() -> execute(book));
        JsonNode receipt = first.get();
        assertThat(second.get()).isEqualTo(receipt);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_account where external_id='historical-account'")).isEqualTo(1);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_cash_source")).isEqualTo(before.get("m_mnzl_r_cash_source"));
        assertThat(harness.queryLong(
                "select count(*) from m_mnzl_r_risk_forecast where account_key=(select record_key from m_mnzl_r_account where external_id='historical-account')"))
                .isZero();
        BigInteger net = new BigInteger(book.path("basis").path("acceptedAccountPrices").get(0).path("netPurchaseCashMinor").asText());
        JsonNode pending = position(acquisitionDate, null);
        assertThat(pending.path("stage").isNull()).isTrue();
        assertThat(pending.path("riskAssessmentStatus").asText()).isEqualTo("PENDING");
        assertThat(pending.path("lossAllowanceMinor").asText()).isEqualTo("0");
        assertThat(pending.path("unreconciledPurchaseMinor").asText()).isEqualTo(net.toString());
        verifyInvestmentRead();
        String watermark = harness.request("GET", BASE + "/capabilities", null, 200).path("currentEventWatermark").asText();
        var committed = harness.counts();
        assertThat(execute(book)).isEqualTo(receipt);
        assertThat(harness.counts()).isEqualTo(committed);
        ObjectNode conflict = book.deepCopy();
        ((ObjectNode) conflict.get("acknowledgment")).put("reason", "different recorded facts");
        assertThat(harness.request("POST", BASE + "/commands", conflict, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        harness.moveDate(acquisitionDate.plusDays(1));
        cash(net, "historical-payment", ID);
        var reconcile = harness.command("RECONCILE_HISTORICAL_PURCHASE", "historical-reconcile-part", ID, "RECEIVABLE");
        reconcile.put("expectedVersion", "1");
        reconcile.put("amountMinor", "1");
        reconcile.set("acquisitionClearingAllocationIds", harness.json.value(List.of("historical-payment-allocation")));
        JsonNode partial = execute(reconcile);
        assertThat(position(harness.today, null).path("unreconciledPurchaseMinor").asText())
                .isEqualTo(net.subtract(BigInteger.ONE).toString());
        assertThat(execute(reconcile)).isEqualTo(partial);
        reconcile.put("operationId", "historical-reconcile-over");
        reconcile.put("idempotencyKey", "historical-reconcile-over");
        reconcile.put("expectedVersion", "2");
        reconcile.put("amountMinor", net.toString());
        assertThat(harness.request("POST", BASE + "/commands", reconcile, 422).path("code").asText()).isEqualTo("BANK_PROOF_MISMATCH");
        reconcile.put("operationId", "historical-reconcile-rest");
        reconcile.put("idempotencyKey", "historical-reconcile-rest");
        reconcile.put("amountMinor", net.subtract(BigInteger.ONE).toString());
        long journalCount = harness.queryLong("select count(*) from acc_gl_journal_entry");
        long loanCount = harness.queryLong("select count(*) from m_loan");
        execute(reconcile);
        assertThat(harness.queryLong("select count(*) from acc_gl_journal_entry")).isEqualTo(journalCount);
        assertThat(harness.queryLong("select count(*) from m_loan")).isEqualTo(loanCount);
        assertThat(position(harness.today, null).path("unreconciledPurchaseMinor").asText()).isEqualTo("0");
        assertThat(position(acquisitionDate, watermark)).isEqualTo(pending);
        ObjectNode assess = harness.command("SET_IMPAIRMENT", "historical-assess", ID, "RECEIVABLE");
        assess.put("expectedVersion", "3");
        assess.set("qualitativeFindingIds", harness.json.value(List.of()));
        assess.set("cureEvidenceIds", harness.json.value(List.of()));
        var recoveries = new ArrayList<JsonNode>();
        for (JsonNode flow : book.path("basis").path("cashflows")) {
            recoveries.add(harness.json.value(Map.of("sourceCashflowId", flow.path("cashflowId").asText(), "date",
                    flow.path("dueDate").asText(), "amountMinor", flow.path("amountMinor").asText(), "payer", "BORROWER")));
        }
        assess.set("forecast",
                harness.json.value(Map.of("forecastId", "historical-assessment", "forecastVersion", "1", "asOfDate",
                        harness.today.toString(), "validThroughDate", harness.today.plusYears(1).toString(), "stage", "STAGE_1",
                        "contentHash", "0".repeat(64), "scenarios",
                        List.of(Map.of("scenarioId", "assessed-contractual", "probability", "1", "recoveries", recoveries)))));
        ((ObjectNode) assess.path("forecast").path("scenarios").get(0)).putNull("defaultDate");
        execute(assess);
        assertThat(position(harness.today, null).path("riskAssessmentStatus").asText()).isEqualTo("ASSESSED");
        assertThat(position(harness.today, null).path("stage").asText()).isEqualTo("STAGE_1");
        assertThat(position(acquisitionDate, watermark)).isEqualTo(pending);
        assertThat(harness.request("GET", BASE + "/capabilities", null, 200).path("historicalPurchaseVersion").asText()).isEqualTo("1");
    }

    private ObjectNode book() throws Exception {
        ObjectNode template = harness.bookingCommands.get("account-1");
        ObjectNode basis = template.path("basis").deepCopy();
        basis.put("settlementDate", harness.today.toString());
        int index = 0;
        for (JsonNode flow : basis.path("cashflows")) {
            ObjectNode value = (ObjectNode) flow;
            value.put("receivableId", ID);
            value.put("cashflowId", ID + "-" + index);
            value.put("installmentId", ID + "-" + index);
            value.put("dueDate", harness.today.plusDays(++index * 45).toString());
        }
        basis.set("acceptedAccountPrices", harness.json.value(List.of()));
        ObjectNode price = harness.json.object();
        for (String field : List.of("calculationVersion", "productPolicyCode", "schemaVersion", "policyRevisionId", "calculatorBuild")) {
            price.set(field, basis.get(field));
        }
        price.put("calculationType", "PRICE");
        price.put("basisHash", harness.json.hash(basis));
        price.set("basis", basis);
        JsonNode result = harness.request("POST", BASE + "/calculate", price, 200).path("pricing").path("accounts").get(0);
        ObjectNode accepted = harness.json.object();
        for (String field : List.of("accountId", "grossPurchasePriceMinor", "integralFeeMinor", "netPurchaseCashMinor")) {
            accepted.set(field, result.get(field));
        }
        basis.set("acceptedAccountPrices", harness.json.value(List.of(accepted)));
        ObjectNode book = harness.command("BOOK_HISTORICAL_PURCHASE", "historical-book", ID, "RECEIVABLE");
        book.put("accountId", ID);
        book.put("dealId", "historical-deal");
        book.put("customerReferenceId", "historical-customer");
        book.set("basis", basis);
        book.put("basisHash", harness.json.hash(basis));
        book.set("approverIds", harness.json.value(List.of()));
        book.set("approvalEvidenceIds", harness.json.value(List.of()));
        book.set("acknowledgment",
                harness.json.value(Map.of("recordId", "historical-record", "sourceHash", basis.path("sourceHash").asText(), "basisHash",
                        harness.json.hash(basis), "actorId", "operator", "reason",
                        "Recorded completed acquisition; documents, assessment and payment pending")));
        return book;
    }

    private void cash(BigInteger amount, String id, String account) throws Exception {
        ObjectNode command = harness.command("RECORD_CASH_MOVEMENT", id, "historical-deal", "DEAL");
        command.set("source", harness.json.value(Map.of("bankSourceId", id, "bankAccountReference", "test-bank", "verificationEvidenceId",
                "verified-later", "valueDate", harness.today.toString(), "currency", "EGP", "amountMinor", amount.toString(), "direction",
                "OUTGOING", "allocations", List.of(Map.of("allocationId", id + "-allocation", "kind", "ACQUISITION_ADVANCE", "accountId",
                        account, "dealId", "historical-deal", "beneficiaryReferenceId", "developer", "amountMinor", amount.toString())))));
        execute(command);
    }

    private JsonNode execute(ObjectNode command) {
        try {
            return harness.request("POST", BASE + "/commands", command, 200);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void verifyInvestmentRead() throws Exception {
        long loanId = harness.queryLong("select native_loan_id from m_mnzl_r_account where external_id='historical-account'");
        var before = harness.counts();
        String authentication = harness.authentication;
        try {
            // This employee is not the scope integration user; ordinary loan visibility authorizes the new route.
            harness.authentication = "workout-checker:IsolatedChecker123!";
            String path = "/loans/" + loanId + "/mnzl-investment";
            JsonNode summary = harness.request("GET", path, null, 200);
            assertThat(summary.path("applicable").asBoolean()).isTrue();
            assertThat(summary.has("projection")).isFalse();
            assertThat(summary.path("position").path("riskAssessmentStatus").asText()).isEqualTo("PENDING");
            assertThat(summary.path("position").path("stage").isNull()).isTrue();
            JsonNode projected = harness.request("GET", path + "?includeProjections=true", null, 200);
            assertThat(projected.path("position")).isEqualTo(summary.path("position"));
            assertThat(projected.path("projection").path("rows").isEmpty()).isFalse();
            BigInteger recovery = BigInteger.ZERO;
            for (JsonNode row : projected.path("projection").path("rows")) {
                recovery = recovery.add(new BigInteger(row.path("investmentRecoveryMinor").asText()));
                assertThat(
                        new BigInteger(row.path("openingCarryingMinor").asText()).add(new BigInteger(row.path("eirIncomeMinor").asText()))
                                .subtract(new BigInteger(row.path("cashReceiptMinor").asText())))
                        .isEqualTo(new BigInteger(row.path("closingCarryingMinor").asText()));
            }
            assertThat(recovery.toString()).isEqualTo(summary.path("position").path("amortizedCostMinor").asText());
            assertThat(harness.request("GET", BASE + "/accounts/" + ID, null, 409).path("code").asText())
                    .isEqualTo("FINERACT_CAPABILITY_MISSING");
        } finally {
            harness.authentication = authentication;
        }
        assertThat(harness.counts()).isEqualTo(before);
    }

    private JsonNode position(LocalDate date, String watermark) throws Exception {
        return harness.request("GET", BASE + "/accounts/" + ID + "/position?businessDate=" + date + "&boundarySide=AFTER_EVENTS"
                + (watermark == null ? "" : "&eventWatermark=" + watermark), null, 200);
    }
}
