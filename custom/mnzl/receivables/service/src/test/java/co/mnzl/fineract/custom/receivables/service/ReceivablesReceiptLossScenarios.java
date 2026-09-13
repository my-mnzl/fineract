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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real bank ownership, immutable transfer history and full-hash authority checks for the bounded no-right loss case.
 */
final class ReceivablesReceiptLossScenarios {

    private static final String ROOT = ReceivablesDatabaseIntegrationTest.PREFIX;
    private final ReceivablesDatabaseIntegrationTest harness;
    private JsonNode extension;
    private long lossGl;

    ReceivablesReceiptLossScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        JsonNode base = get(ROOT + "/configuration");
        ObjectNode gl = harness.json.object();
        gl.put("name", "Approved receipt return loss");
        gl.put("glCode", "99001");
        gl.put("type", 5);
        gl.put("usage", 1);
        gl.put("manualEntriesAllowed", true);
        gl.put("description", "Isolated receipt-return loss extension");
        lossGl = post("/glaccounts", gl, 200).path("resourceId").asLong();
        ObjectNode mapping = harness.json.object();
        mapping.set("scope", harness.scope(false));
        mapping.put("extensionId", "receipt-loss-extension-1");
        mapping.put("accountMappingRevisionId", "mapping-1");
        mapping.put("nativeLossGlAccountId", Long.toString(lossGl));
        mapping.put("approvedBy", "1");
        mapping.set("approvalEvidenceIds", harness.json.value(List.of("approved-separate-loss-account")));
        extension = post(ROOT + "/receipt-loss-configuration", mapping, 200);
        assertThat(post(ROOT + "/receipt-loss-configuration", mapping, 200)).isEqualTo(extension);
        assertThat(get(ROOT + "/receipt-loss-configuration")).isEqualTo(extension);
        assertThat(get(ROOT + "/configuration")).isEqualTo(base);
        ObjectNode changed = mapping.deepCopy().put("nativeLossGlAccountId", harness.accounts.get("impairmentExpense").toString());
        assertThat(post(ROOT + "/receipt-loss-configuration", changed, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        for (boolean hel : List.of(false, true)) {
            scenario(hel);
        }
        assertThat(get(ROOT + "/configuration")).isEqualTo(base);
    }

    private void scenario(boolean hel) throws Exception {
        String id = hel ? "returned-hel-receipt" : "returned-assigned-receipt";
        harness.purchase(id, "50000", "50000");
        LocalDate due = harness.today.plusDays(45);
        ObjectNode reset = command("RESET_RATE", id + "-reset", id);
        reset.put("corridorObservationId", id + "-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("developerAdjustmentDueDate", due.toString());
        post(ROOT + "/commands", reset, 200);
        harness.moveDate(due);
        ObjectNode receipt = harness.receiptCommand(id + "-receipt", id, "50000");
        post(ROOT + "/commands", receipt, 200);
        ObjectNode collect = command("COLLECT", id + "-collect", id);
        collect.set("allocations", harness.json.value(List.of(Map.of("allocationId", id + "-allocation", "cashMovementId", id + "-receipt",
                "cashflowId", id + "-0", "installmentId", id + "-0", "instrumentId", id + "-cheque", "amountMinor", "50000"))));
        JsonNode collected = post(ROOT + "/commands", collect, 200);
        ObjectNode downstream;
        String successor = null;
        Long helLoan = null;
        if (hel) {
            long client = get(ROOT + "/accounts/" + id).path("nativeClientId").asLong();
            ObjectNode loan = harness.ordinaryLoanRequest(client, get(ROOT + "/configuration").path("helProductId").asLong());
            loan.put("externalId", id + "-loan");
            helLoan = post("/loans", loan, 200).path("loanId").asLong();
            post("/loans/" + helLoan + "?command=approve",
                    harness.json.value(Map.of("approvedOnDate", harness.today.toString(), "dateFormat", "yyyy-MM-dd", "locale", "en")),
                    200);
            ObjectNode fund = harness.command("FUND_HEL_TO_SETTLEMENT_CLEARING", id + "-fund", id + "-loan", "HEL_LOAN");
            fund.put("applicationId", id + "-application");
            fund.put("dealId", "deal");
            fund.put("expectedNativeClientId", Long.toString(client));
            fund.put("loanExternalId", id + "-loan");
            fund.put("expectedPrincipalMinor", "100000");
            fund.put("expectedFinancedFeesMinor", "0");
            fund.set("financedFeeIds", harness.json.array());
            post(ROOT + "/hel-funding/commands", fund, 200);
            downstream = command("SETTLE_RECEIVABLE", id + "-transfer", id);
            downstream.set("settlementSource", harness.json
                    .value(Map.of("kind", "HEL_CLEARING", "helFundingOperationId", id + "-fund", "closureReason", "CONVERTED_TO_HEL")));
            downstream.set("allocationIds", harness.json.value(List.of(id + "-settlement")));
            downstream.set("cashflowIds", harness.json.value(List.of(id + "-1")));
            downstream.put("payoffMinor", "50000");
            downstream.put("acceptedCustomerTermsHash", "0".repeat(64));
            downstream.put("settlementApprovalId", id + "-settlement-approval");
            downstream.set("riskForecastAfter", forecast(id, List.of()));
        } else {
            successor = id + "-successor";
            List<JsonNode> flows = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ObjectNode flow = harness.bookingCommands.get(id).path("basis").path("cashflows").get(i).deepCopy();
                flow.put("receivableId", successor);
                flow.put("cashflowId", successor + "-" + i);
                flow.put("installmentId", successor + "-" + i);
                flow.put("dueDate", harness.today.plusDays(30L * (i + 1)).toString());
                flow.put("amountMinor", "60000");
                flows.add(flow);
            }
            downstream = command("SUBSTITUTE_RECEIVABLE", id + "-transfer", id);
            downstream.put("replacementAccountId", successor);
            downstream.put("replacementCustomerReferenceId", successor + "-customer");
            downstream.set("replacementCashflows", harness.json.value(flows));
            downstream.set("developerAdjustmentAllocationIds", harness.json.value(List.of(id + "-reset:reset")));
            downstream.set("replacementRiskForecast", forecast(id, flows));
            downstream.set("assignmentEvidenceIds", harness.json.value(List.of(id + "-assignment")));
        }
        JsonNode transferred = post(ROOT + "/commands", downstream, 200);
        if (hel) {
            ObjectNode payout = harness.command("RECORD_CASH_MOVEMENT", id + "-payout", "deal", "DEAL");
            payout.set("source",
                    harness.json.value(Map.of("bankSourceId", id + "-payout-bank", "bankAccountReference", "test-bank",
                            "verificationEvidenceId", "verified-payout", "valueDate", harness.today.toString(), "currency", "EGP",
                            "direction", "OUTGOING", "amountMinor", "50000", "allocations",
                            List.of(Map.of("allocationId", id + "-payout-allocation", "kind", "CUSTOMER_PAYOUT", "dealId", "deal",
                                    "beneficiaryReferenceId", id + "-customer", "helFundingOperationId", id + "-fund", "amountMinor",
                                    "50000")))));
            post(ROOT + "/commands", payout, 200);
        }
        LocalDate returnDate = harness.today;
        harness.moveDate(harness.today.plusDays(1));
        JsonNode sourceBefore = get(ROOT + "/accounts/" + id);
        JsonNode successorBefore = successor == null ? null : get(ROOT + "/accounts/" + successor);
        JsonNode helBefore = helLoan == null ? null : get("/loans/" + helLoan);
        JsonNode lotsBefore = get(ROOT + "/developer-lots").path("items");
        long transactions = harness.queryLong("select count(*) from m_loan_transaction");
        long bankBefore = balance(harness.accounts.get("bank"));
        long lossBefore = balance(lossGl);
        ObjectNode loss = command("RECORD_POST_TRANSFER_RECEIPT_LOSS", id + "-loss", id);
        loss.put("dealId", "deal");
        loss.put("dispositionId", id + "-no-right");
        loss.put("originalCollectionOperationId", id + "-collect");
        loss.put("originalCollectionCommandHash", collected.path("payloadHash").asText());
        JsonNode originalEvent = event(collected);
        JsonNode downstreamEvent = event(transferred);
        loss.set("originalCollectionEventHash", originalEvent.get("contentHash"));
        loss.put("originalBankSourceId", id + "-receipt-bank");
        loss.put("originalBankSourceHash", harness.json.hash(receipt.get("source")));
        loss.put("downstreamOperationId", id + "-transfer");
        loss.set("downstreamCommandHash", transferred.get("payloadHash"));
        loss.set("downstreamEventHash", downstreamEvent.get("contentHash"));
        loss.set("lossMappingExtensionId", extension.get("extensionId"));
        loss.set("lossMappingExtensionHash", extension.get("contentHash"));
        ObjectNode approval = loss.putObject("lossApproval");
        approval.put("legalDetermination", "NO_SURVIVING_ENFORCEABLE_RIGHT");
        approval.put("approvalDate", harness.today.toString());
        approval.put("legalApproverId", "legal");
        approval.put("financeApproverId", "finance");
        approval.put("creditApproverId", "credit");
        for (String prefix : List.of("signedLegal", "financeApproval", "creditApproval")) {
            approval.put(prefix + "EvidenceId", id + "-" + prefix);
            approval.put(prefix + "EvidenceHash", "a".repeat(64));
        }
        approval.set("reviewedCounterpartyReferences", harness.json.value(List.of(id + "-customer", "developer")));
        loss.set("approverIds", harness.json.value(List.of("legal", "finance", "credit")));
        loss.set("bankDebit",
                harness.json.value(Map.of("bankSourceId", id + "-return-bank", "bankAccountReference", "test-bank",
                        "verificationEvidenceId", id + "-verified-bank-debit", "valueDate", returnDate.toString(), "currency", "EGP",
                        "direction", "OUTGOING", "amountMinor", "50000")));
        ObjectNode partial = loss.deepCopy();
        ((ObjectNode) partial.get("bankDebit")).put("amountMinor", "25000");
        assertThat(post(ROOT + "/commands", authorize(partial, id + "-partial"), 422).path("code").asText())
                .isEqualTo("BANK_PROOF_MISMATCH");
        ObjectNode unseparated = loss.deepCopy();
        ((ObjectNode) unseparated.get("lossApproval")).put("financeApproverId", "legal");
        assertThat(post(ROOT + "/commands", authorize(unseparated, id + "-roles"), 409).path("code").asText())
                .isEqualTo("APPROVAL_SCOPE_CHANGED");
        ObjectNode alreadyOwned = loss.deepCopy();
        ((ObjectNode) alreadyOwned.get("bankDebit")).put("bankSourceId", id + "-receipt-bank");
        assertThat(post(ROOT + "/commands", authorize(alreadyOwned, id + "-owned"), 409).path("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        authorize(loss, id + "-approved");
        JsonNode result = post(ROOT + "/commands", loss, 200);
        JsonNode outcomeEvent = event(result);
        assertThat(result.path("nativeTransactionIds")).isEmpty();
        assertThat(outcomeEvent.path("positionsAfter")).isEmpty();
        assertThat(outcomeEvent.path("journalLines")).hasSize(2);
        assertThat(outcomeEvent.path("receiptLossDisposition").path("returnValueDate").asText()).isEqualTo(returnDate.toString());
        assertThat(outcomeEvent.path("businessDate").asText()).isEqualTo(harness.today.toString());
        assertThat(balance(harness.accounts.get("bank"))).isEqualTo(bankBefore - 50000);
        assertThat(balance(lossGl)).isEqualTo(lossBefore + 50000);
        assertThat(harness.queryLong("select count(*) from m_loan_transaction")).isEqualTo(transactions);
        assertThat(get(ROOT + "/accounts/" + id)).isEqualTo(sourceBefore);
        if (successor != null) {
            assertThat(get(ROOT + "/accounts/" + successor)).isEqualTo(successorBefore);
        }
        if (helLoan != null) {
            assertThat(get("/loans/" + helLoan)).isEqualTo(helBefore);
        }
        assertThat(get(ROOT + "/developer-lots").path("items")).isEqualTo(lotsBefore);
        assertThat(event(collected)).isEqualTo(originalEvent);
        assertThat(event(transferred)).isEqualTo(downstreamEvent);
        assertThat(post(ROOT + "/commands", loss, 200)).isEqualTo(result);
        ObjectNode duplicate = loss.deepCopy().put("operationId", id + "-duplicate").put("idempotencyKey", id + "-duplicate")
                .put("dispositionId", id + "-duplicate");
        assertThat(post(ROOT + "/commands", authorize(duplicate, id + "-duplicate-grant"), 409).path("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        JsonNode periodProof = get(ROOT + "/period-activity-proof?postingPeriod=" + harness.today.toString().substring(0, 7)
                + "&eventWatermark=" + result.path("eventWatermark").asText());
        List<JsonNode> lossControls = new ArrayList<>();
        periodProof.path("observedGl").path("accounts").forEach(control -> {
            if (control.path("accountKey").asText().equals("receiptReturnLoss")) {
                lossControls.add(control);
            }
        });
        assertThat(lossControls).hasSize(1);
        JsonNode lossControl = lossControls.getFirst();
        assertThat(lossControl.path("originalMappingRevisionId").asText()).isEqualTo("mapping-1");
        assertThat(lossControl.path("mappingExtensionId")).isEqualTo(extension.get("extensionId"));
        assertThat(lossControl.path("mappingExtensionHash")).isEqualTo(extension.get("contentHash"));
        assertThat(lossControl.path("nativeGlAccountId").asText()).isEqualTo(Long.toString(lossGl));
        assertThat(lossControl.path("creditMinor").asText()).isEqualTo("0");
        assertThat(Long.parseLong(lossControl.path("debitMinor").asText())).isGreaterThanOrEqualTo(50000);
        var evidence = harness.receiptLossEvidence.putObject(hel ? "hel" : "substitution");
        evidence.set("periodProof", periodProof);
        evidence.set("extension", extension);
        evidence.set("originalReceipt", receipt);
        evidence.set("collection", collect);
        evidence.set("originalEvent", originalEvent);
        evidence.set("downstreamCommand", downstream);
        evidence.set("downstreamEvent", downstreamEvent);
        evidence.set("command", loss);
        evidence.set("operation", result);
        evidence.set("event", outcomeEvent);
        evidence.set("sourceAccountBefore", sourceBefore);
        evidence.set("sourceAccountAfter", get(ROOT + "/accounts/" + id));
        evidence.set("lotsBefore", lotsBefore);
        evidence.set("lotsAfter", get(ROOT + "/developer-lots").path("items"));
        evidence.set("journals",
                get(ROOT + "/journals?operationIds=" + id + "-loss&eventWatermark=" + result.path("eventWatermark").asText()));
    }

    private ObjectNode forecast(String id, List<JsonNode> flows) {
        ObjectNode forecast = harness.bookingCommands.get(id).path("riskForecast").deepCopy();
        forecast.put("forecastId", id + "-replacement-forecast");
        forecast.put("asOfDate", harness.today.toString());
        forecast.put("stage", "STAGE_2");
        ObjectNode scenario = (ObjectNode) forecast.path("scenarios").get(0);
        scenario.put("defaultDate", harness.today.toString());
        var recoveries = harness.json.array();
        for (JsonNode flow : flows) {
            recoveries.addObject().put("sourceCashflowId", flow.path("cashflowId").asText()).put("date", flow.path("dueDate").asText())
                    .put("amountMinor", flow.path("amountMinor").asText()).put("payer", "BORROWER");
        }
        scenario.set("recoveries", recoveries);
        forecast.remove("contentHash");
        forecast.put("contentHash", harness.json.hash(forecast));
        return forecast;
    }

    private ObjectNode command(String type, String operation, String id) throws Exception {
        return harness.command(type, operation, id, "RECEIVABLE").put("expectedVersion", harness.version(id));
    }

    private ObjectNode authorize(ObjectNode command, String id) throws Exception {
        command.put("executionMode", "CORRECTION");
        command.set("executionAuthorization", harness.json.value(Map.of("authorizationId", id, "scopeHash", "0".repeat(64), "approvedBy",
                "1", "effectiveFrom", harness.today.toString(), "effectiveThrough", harness.today.toString())));
        harness.issueHistory(command);
        return command;
    }

    private JsonNode event(JsonNode operation) throws Exception {
        return harness.json.read(harness.queryText("select event_json from m_mnzl_r_event where record_key=?",
                operation.path("financialEventIds").get(0).asText()));
    }

    private long balance(long gl) throws Exception {
        return harness.queryLong(
                "select coalesce(sum(case when type_enum=2 then amount*100 else -amount*100 end),0) from acc_gl_journal_entry where account_id="
                        + gl);
    }

    private JsonNode get(String path) throws Exception {
        return harness.request("GET", path, null, 200);
    }

    private JsonNode post(String path, JsonNode value, int status) throws Exception {
        return harness.request("POST", path, value, status);
    }
}
