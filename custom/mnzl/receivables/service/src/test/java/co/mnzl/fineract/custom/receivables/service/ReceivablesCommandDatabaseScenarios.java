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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command-family scenarios against the same real tenant, native services and ledger as the database integration test.
 */
final class ReceivablesCommandDatabaseScenarios {

    private final ReceivablesDatabaseIntegrationTest harness;
    private long resolvedBorrower;
    private long transferredLotAmount;
    private java.time.LocalDate transferredLotDue;
    private List<JsonNode> replacementFlows;

    ReceivablesCommandDatabaseScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        borrowerIdentity();
        settlementAndDeveloperPayment();
        substitution();
        recourse();
        workout();
        modificationAndWriteOff();
        correction();
        funding();
        developerSettlementAcrossDueDate();
        impairedReversal();
        corruptedMirrorsCannotHidePositionMismatch();
        missingNativeSourcesCannotCancel();
        writtenOffRecovery();
        developerImpairmentAfterDue();
        assertThat(harness
                .queryLong("select count(*) from m_mnzl_r_journal_line l left join acc_gl_journal_entry j on j.id=l.native_journal_id "
                        + "where j.id is null or l.native_gl_id<>j.account_id or cast(l.amount_minor as decimal(19,0))<>j.amount*100 "
                        + "or j.type_enum<>case when l.side='DEBIT' then 2 else 1 end or j.currency_code<>'EGP'"))
                .isZero();
    }

    private void borrowerIdentity() throws Exception {
        ObjectNode input = harness.json.object();
        input.set("scope", harness.scope(false));
        input.put("accountMappingRevisionId", "mapping-1");
        input.put("customerReferenceId", "settlement-account-customer");
        long loans = harness.queryLong("select count(*) from m_loan");
        long journals = harness.queryLong("select count(*) from acc_gl_journal_entry");
        long events = harness.queryLong("select count(*) from m_mnzl_r_event");
        JsonNode resolved = harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/borrowers/resolve", input, 200);
        assertThat(harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/borrowers/resolve", input, 200))
                .isEqualTo(resolved);
        assertThat(harness.queryLong("select count(*) from m_loan")).isEqualTo(loans);
        assertThat(harness.queryLong("select count(*) from acc_gl_journal_entry")).isEqualTo(journals);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_event")).isEqualTo(events);
        resolvedBorrower = resolved.path("nativeClientId").asLong();
        assertThat(resolvedBorrower).isPositive();
    }

    private void workout() throws Exception {
        ObjectNode user = harness.json.object();
        user.put("username", "workout-checker");
        user.put("password", "IsolatedChecker123!");
        user.put("repeatPassword", "IsolatedChecker123!");
        user.put("firstname", "Isolated");
        user.put("lastname", "Checker");
        user.put("email", "checker@example.invalid");
        user.put("officeId", 1);
        user.put("sendPasswordToEmail", false);
        user.set("roles", harness.json.value(List.of(1)));
        String checkerId = harness.request("POST", "/users", user, 200).path("resourceId").asText();
        for (String kind : List.of("RELEASE", "EXCHANGE")) {
            String id = "workout-" + kind.toLowerCase(java.util.Locale.ROOT);
            harness.purchase(id);
            long net = account(id).path("position").path("amortizedCostMinor").asLong();
            String replacement = id + "-replacement";
            List<JsonNode> flows = new ArrayList<>();
            if (kind.equals("EXCHANGE")) {
                ObjectNode flow = harness.bookingCommands.get(id).path("basis").path("cashflows").get(1).deepCopy();
                flow.put("cashflowId", replacement + "-flow");
                flow.put("installmentId", replacement + "-flow");
                flow.put("receivableId", replacement);
                flow.put("scheduleVersionId", "workout-schedule");
                flow.put("amountMinor", "80000");
                flows.add(flow);
            }
            String consideration = kind.equals("RELEASE") ? "0" : "70000";
            ObjectNode c = command("WORKOUT", id + "-operation", id);
            c.put("classification", "DERECOGNITION");
            c.put("approvedWorkoutCaseId", id + "-approval");
            c.put("originalScheduleVersionId", "schedule-1");
            c.put("modifiedScheduleVersionId", "workout-schedule");
            c.put("approvedConsiderationMinor", consideration);
            c.set("modifiedCashflows", harness.json.value(flows));
            c.set("riskForecast", forecast(id, id + "-forecast", flows));
            c.set("legalEvidenceIds", harness.json.value(List.of("signed-" + kind)));
            ObjectNode outcome = harness.json.object();
            outcome.put("kind", kind);
            String hash = null;
            if (kind.equals("RELEASE")) {
                outcome.set("consideration", harness.json.value(Map.of("kind", "NONE")));
            } else {
                ObjectNode basis = harness.json.object();
                basis.set("cashflows", c.get("modifiedCashflows"));
                basis.set("riskForecast", c.get("riskForecast"));
                basis.put("approvedConsiderationMinor", consideration);
                hash = harness.json.hash(basis);
                outcome.put("replacementAccountId", replacement);
                outcome.put("replacementScheduleVersionId", "workout-schedule");
                outcome.put("replacementSourceHash", hash);
            }
            c.set("legalOutcome", outcome);
            ObjectNode approval = harness.json.object();
            approval.set("scope", harness.scope(true));
            approval.put("approvedWorkoutCaseId", id + "-approval");
            approval.put("accountId", id);
            approval.put("businessDate", harness.today.toString());
            approval.put("approvedConsiderationMinor", consideration);
            approval.put("classification", kind);
            approval.set("legalEvidenceIds", c.get("legalEvidenceIds"));
            approval.put("replacementAccountId", kind.equals("EXCHANGE") ? replacement : null);
            approval.put("replacementSourceHash", hash);
            approval.put("approvedBy", checkerId);
            harness.authentication = "workout-checker:IsolatedChecker123!";
            try {
                harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/workout-authorizations", approval, 200);
            } finally {
                harness.authentication = "mifos:password";
            }
            long bank = glBalance("bank");
            long loss = glBalance("modificationGainLoss");
            execute(c);
            assertThat(account(id).path("closureReason").asText()).isEqualTo("WORKOUT");
            assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("0");
            assertThat(glBalance("bank")).isEqualTo(bank);
            assertThat(glBalance("modificationGainLoss") - loss).isEqualTo(net - Long.parseLong(consideration));
            if (kind.equals("EXCHANGE")) {
                JsonNode position = account(replacement).path("position");
                assertThat(position.path("contractualOutstandingMinor").asText()).isEqualTo("80000");
                assertThat(position.path("amortizedCostMinor").asText()).isEqualTo("70000");
                assertThat(position.path("deferredIntegralFeeMinor").asText()).isEqualTo("0");
            }
        }
    }

    private void modificationAndWriteOff() throws Exception {
        String id = "modified-account";
        harness.purchase(id);
        List<JsonNode> flows = new ArrayList<>();
        for (JsonNode original : harness.bookingCommands.get(id).path("basis").path("cashflows")) {
            ObjectNode flow = original.deepCopy();
            flow.put("cashflowId", id + "-modified-" + flows.size());
            flow.put("installmentId", id + "-modified-" + flows.size());
            flow.put("scheduleVersionId", "modified-schedule");
            flow.put("amountMinor", "45000");
            flows.add(flow);
        }
        long before = account(id).path("position").path("amortizedCostMinor").asLong();
        long loss = glBalance("modificationGainLoss");
        long bank = glBalance("bank");
        ObjectNode c = command("WORKOUT", "modify-contract", id);
        c.put("classification", "MODIFICATION");
        c.put("originalScheduleVersionId", "schedule-1");
        c.put("modifiedScheduleVersionId", "modified-schedule");
        c.put("approvedConsiderationMinor", "0");
        c.set("modifiedCashflows", harness.json.value(flows));
        c.set("riskForecast", forecast(id, "modified-forecast", flows).put("stage", "STAGE_2"));
        c.set("legalEvidenceIds", harness.json.value(List.of("signed-modification")));
        execute(c);
        JsonNode modified = account(id);
        assertThat(modified.path("position").path("stage").asText()).isEqualTo("STAGE_2");
        controls(id);
        assertThat(modified.path("position").path("contractualOutstandingMinor").asText()).isEqualTo("90000");
        long net = modified.path("position").path("amortizedCostMinor").asLong();
        assertThat(glBalance("modificationGainLoss") - loss).isEqualTo(before - net);
        assertThat(glBalance("bank")).isEqualTo(bank);
        assertThat(
                harness.queryLong("select count(*) from m_loan_repayment_schedule where loan_id=" + modified.path("nativeLoanId").asLong()))
                .isEqualTo(4);
        ObjectNode stageThree = command("SET_IMPAIRMENT", "written-off-stage-three", id);
        ObjectNode stageForecast = forecast(id, "stage-three-forecast", List.of()).put("stage", "STAGE_3").put("forecastVersion", "2");
        ((ObjectNode) stageForecast.path("scenarios").get(0)).put("defaultDate", harness.today.toString());
        stageThree.set("forecast", stageForecast);
        stageThree.set("qualitativeFindingIds", harness.json.value(List.of("default-confirmed")));
        stageThree.set("cureEvidenceIds", harness.json.value(List.of()));
        execute(stageThree);
        assertThat(account(id).path("position").path("stage").asText()).isEqualTo("STAGE_3");
        long allowance = account(id).path("position").path("lossAllowanceMinor").asLong();
        long impairment = glBalance("impairmentExpense");
        c = command("WORKOUT", "write-off", id);
        c.put("classification", "WRITE_OFF");
        c.put("originalScheduleVersionId", "modified-schedule");
        c.put("modifiedScheduleVersionId", "written-off-schedule");
        c.put("approvedConsiderationMinor", "0");
        c.set("modifiedCashflows", harness.json.value(List.of()));
        c.set("riskForecast", forecast(id, "written-off-forecast", List.of()));
        c.set("legalEvidenceIds", harness.json.value(List.of("approved-writeoff")));
        execute(c);
        assertThat(account(id).path("closureReason").asText()).isEqualTo("WRITE_OFF");
        assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("0");
        assertThat(glBalance("impairmentExpense") - impairment).isEqualTo(net - allowance);
        assertThat(glBalance("bank")).isEqualTo(bank);
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE", justification = "Isolated test queries interpolate only native SHA-256 event IDs and numeric native transaction IDs")
    private void correction() throws Exception {
        String id = "account-1";
        ObjectNode collect = command("COLLECT", "correction-original", id);
        ObjectNode allocation = harness.json.object();
        allocation.put("allocationId", "correction-original-allocation");
        allocation.put("cashMovementId", "due-receipt");
        allocation.put("cashflowId", "account-1-0");
        allocation.put("installmentId", "account-1-0");
        allocation.put("instrumentId", "correction-cheque");
        allocation.put("amountMinor", "1");
        collect.set("allocations", harness.json.value(List.of(allocation)));
        JsonNode original = execute(collect);
        String eventId = original.path("financialEventIds").get(0).asText();
        String eventText = harness.queryText("select event_json from m_mnzl_r_event where record_key='" + eventId + "'");
        JsonNode event = harness.json.read(eventText);
        List<String> lineIds = new ArrayList<>();
        for (JsonNode line : event.path("journalLines")) {
            if (line.path("component").asText().equals("CASH")
                    || line.path("accountKey").asText().equals("lossAllowance") && line.path("side").asText().equals("DEBIT")
                    || line.path("accountKey").asText().equals("impairmentExpense") && line.path("side").asText().equals("CREDIT")) {
                lineIds.add(line.path("sourceLineId").asText());
            }
        }
        ObjectNode authorization = harness.json.object();
        authorization.set("scope", harness.scope(false));
        authorization.put("authorizationId", "correction-authorization");
        authorization.put("scopeHash", "0".repeat(64));
        authorization.put("approvedBy", "1");
        authorization.put("effectiveFrom", harness.today.toString());
        authorization.put("effectiveThrough", harness.today.toString());
        authorization.put("mode", "CORRECTION");
        harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/authorizations", authorization, 200);
        authorization.remove(List.of("scope", "mode"));
        ObjectNode c = command("CORRECT_EVENT", "correction", id);
        c.put("executionMode", "CORRECTION");
        c.set("executionAuthorization", authorization);
        c.put("originalEventId", eventId);
        c.put("originalValueDate", harness.today.toString());
        c.set("sourceLineIds", harness.json.value(lineIds));
        c.put("replacementCommandOperationId", "correction:replacement");
        c.put("correctionEvidenceId", "bank-correction");
        ObjectNode replacement = command("COLLECT", "correction:replacement", id);
        allocation.put("allocationId", "correction-replacement-allocation");
        replacement.set("allocations", harness.json.value(List.of(allocation)));
        replacement.set("riskForecastAfter",
                forecast(id, "correction-forecast", List.of(harness.bookingCommands.get(id).path("basis").path("cashflows").get(1))));
        c.set("replacementCommand", replacement);
        execute(c);
        assertThat(harness.queryText("select event_json from m_mnzl_r_event where record_key='" + eventId + "'")).isEqualTo(eventText);
        assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("99999");
        assertThat(harness.queryLong("select count(*) from m_loan_transaction where id="
                + original.path("nativeTransactionIds").get(0).asLong() + " and is_reversed=true")).isEqualTo(1);
    }

    private ObjectNode command(String type, String operation, String account) throws Exception {
        ObjectNode c = harness.command(type, operation, account, "RECEIVABLE");
        c.put("expectedVersion", harness.version(account));
        return c;
    }

    private JsonNode execute(ObjectNode command) throws Exception {
        JsonNode result = harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/commands", command, 200);
        long journals = harness.queryLong("select count(*) from acc_gl_journal_entry");
        long events = harness.queryLong("select count(*) from m_mnzl_r_event");
        assertThat(harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/commands", command, 200)).isEqualTo(result);
        assertThat(harness.queryLong("select count(*) from acc_gl_journal_entry")).isEqualTo(journals);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_event")).isEqualTo(events);
        assertThat(harness.queryLong("select sum(case when type_enum=2 then amount*100 else -amount*100 end) from acc_gl_journal_entry"))
                .isZero();
        return result;
    }

    private JsonNode account(String id) throws Exception {
        return harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/accounts/" + id, null, 200);
    }

    private ObjectNode forecast(String source, String id, List<JsonNode> flows) {
        ObjectNode forecast = harness.bookingCommands.get(source).path("riskForecast").deepCopy();
        forecast.put("forecastId", id);
        forecast.put("asOfDate", harness.today.toString());
        forecast.put("validThroughDate", harness.today.plusDays(365).toString());
        List<JsonNode> recoveries = new ArrayList<>();
        for (JsonNode flow : flows) {
            ObjectNode recovery = harness.json.object();
            recovery.set("sourceCashflowId", flow.get("cashflowId"));
            recovery.set("date", flow.get("dueDate"));
            recovery.set("amountMinor", flow.get("amountMinor"));
            recovery.put("payer", "BORROWER");
            recoveries.add(recovery);
        }
        ((ObjectNode) forecast.path("scenarios").get(0)).set("recoveries", harness.json.value(recoveries));
        return forecast;
    }

    private void settle(String id, String operation, String flow, String payoff, List<JsonNode> retained) throws Exception {
        harness.recordReceipt(operation + "-cash", id, payoff);
        ObjectNode c = command("SETTLE_RECEIVABLE", operation, id);
        c.set("settlementSource", harness.json
                .value(Map.of("kind", "BANK_CASH", "cashMovementId", operation + "-cash", "closureReason", "VOLUNTARY_SETTLEMENT")));
        c.set("allocationIds", harness.json.value(List.of(operation + "-allocation")));
        c.set("cashflowIds", harness.json.value(List.of(flow)));
        c.put("payoffMinor", payoff);
        c.put("acceptedCustomerTermsHash", "0".repeat(64));
        c.put("settlementApprovalId", operation + "-approval");
        c.set("riskForecastAfter", forecast(id, operation + "-forecast", retained));
        execute(c);
    }

    private void settlementAndDeveloperPayment() throws Exception {
        String id = "settlement-account";
        harness.purchase(id, "50000", "50000");
        assertThat(account(id).path("nativeClientId").asLong()).isEqualTo(resolvedBorrower);
        JsonNode remaining = harness.bookingCommands.get(id).path("basis").path("cashflows").get(1);
        settle(id, "partial-settlement", id + "-0", "50000", List.of(remaining));
        assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("50000");
        settle(id, "full-settlement", id + "-1", "48000", List.of());
        JsonNode closed = account(id);
        for (String field : List.of("contractualOutstandingMinor", "grossPurchaseBasisMinor", "amortizedCostMinor")) {
            assertThat(closed.path("position").path(field).asText()).isEqualTo("0");
        }
        assertThat(closed.path("closureReason").asText()).isEqualTo("VOLUNTARY_SETTLEMENT");
        long loan = closed.path("nativeLoanId").asLong();
        assertThat(harness.queryLong("select sum(principal_completed_derived*100) from m_loan_repayment_schedule where loan_id=" + loan))
                .isEqualTo(98000);
        JsonNode lots = harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/developer-lots", null, 200);
        int settledLots = 0;
        for (JsonNode lot : lots.path("items")) {
            if (!lot.path("accountId").asText().equals(id)) {
                continue;
            }
            settledLots++;
            String amount = lot.path("outstandingMinor").asText();
            ObjectNode cash = harness.command("RECORD_CASH_MOVEMENT", "pay-" + lot.path("lotId").asText(), "deal", "DEAL");
            String operation = cash.path("operationId").asText();
            ObjectNode source = bankSource(operation, amount, "OUTGOING");
            source.set("allocations",
                    harness.json.value(List.of(Map.of("allocationId", operation + "-allocation", "kind", "DEVELOPER_PAYMENT", "dealId",
                            "deal", "accountId", id, "beneficiaryReferenceId", "developer", "amountMinor", amount))));
            cash.set("source", source);
            execute(cash);
            ObjectNode payment = command("SETTLE_DEVELOPER_ADJUSTMENT", operation + "-settle", id);
            payment.put("method", "CASH");
            payment.put("cashMovementId", operation);
            payment.set("lots", harness.json.value(List.of(Map.of("lotId", lot.path("lotId").asText(), "amountMinor", amount))));
            execute(payment);
        }
        assertThat(settledLots).isGreaterThan(0);
    }

    private void substitution() throws Exception {
        String id = "substitution-account";
        harness.purchase(id);
        transferredLotDue = harness.today.plusDays(45);
        ObjectNode reset = command("RESET_RATE", "substitution-reset", id);
        reset.put("corridorObservationId", "substitution-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("developerAdjustmentDueDate", transferredLotDue.toString());
        execute(reset);
        transferredLotAmount = balance(controls(id), "developerReceivable");
        assertThat(transferredLotAmount).isPositive();
        JsonNode before = account(id).path("position");
        String replacement = "substitution-replacement";
        List<JsonNode> flows = new ArrayList<>();
        for (JsonNode original : harness.bookingCommands.get(id).path("basis").path("cashflows")) {
            ObjectNode flow = original.deepCopy();
            flow.put("receivableId", replacement);
            flow.put("cashflowId", replacement + "-" + flows.size());
            flow.put("installmentId", replacement + "-" + flows.size());
            flow.put("amountMinor", "60000");
            flows.add(flow);
        }
        replacementFlows = List.copyOf(flows);
        long bankBefore = glBalance("bank");
        long incomeBefore = glBalance("portfolioInterestIncome");
        ObjectNode c = command("SUBSTITUTE_RECEIVABLE", "substitute", id);
        c.put("replacementAccountId", replacement);
        c.put("replacementCustomerReferenceId", "replacement-customer");
        c.set("replacementCashflows", harness.json.value(flows));
        c.set("developerAdjustmentAllocationIds", harness.json.value(List.of("substitution-reset:reset")));
        c.set("replacementRiskForecast", forecast(id, "substitution-forecast", flows).put("stage", "STAGE_2"));
        c.set("assignmentEvidenceIds", harness.json.value(List.of("signed-assignment")));
        execute(c);
        JsonNode after = account(replacement).path("position");
        assertThat(after.path("contractualOutstandingMinor").asText()).isEqualTo("120000");
        assertThat(after.path("stage").asText()).isEqualTo("STAGE_2");
        assertThat(balance(controls(id), "developerReceivable")).isZero();
        assertThat(balance(controls(replacement), "developerReceivable")).isEqualTo(transferredLotAmount);
        for (String field : List.of("grossPurchaseBasisMinor", "amortizedCostMinor", "deferredIntegralFeeMinor")) {
            assertThat(after.path(field)).isEqualTo(before.path(field));
        }
        assertThat(account(id).path("closureReason").asText()).isEqualTo("ASSIGNED_OUT");
        assertThat(glBalance("bank")).isEqualTo(bankBefore);
        assertThat(glBalance("portfolioInterestIncome")).isEqualTo(incomeBefore);
    }

    private void recourse() throws Exception {
        String id = "buyback-account";
        harness.purchase(id);
        harness.recordReceipt("buyback-cash", id, "100000");
        long income = glBalance("portfolioInterestIncome");
        long payable = glBalance("developerPayable");
        long net = account(id).path("position").path("amortizedCostMinor").asLong();
        ObjectNode c = command("RECOURSE_RECOVERY", "buyback", id);
        c.put("developerOrganizationId", "developer");
        c.put("cashMovementId", "buyback-cash");
        c.put("recoveryKind", "FULL_BUYBACK");
        c.put("subrogationScopeHash", "0".repeat(64));
        c.set("legalEvidenceIds", harness.json.value(List.of("signed-buyback")));
        c.set("allocations", harness.json.value(List.of(Map.of("allocationId", "buyback-allocation", "cashMovementId", "buyback-cash",
                "cashflowId", id + "-0", "installmentId", id + "-0", "instrumentId", "buyback-instrument", "amountMinor", "100000"))));
        execute(c);
        assertThat(account(id).path("closureReason").asText()).isEqualTo("ASSIGNED_OUT");
        assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("0");
        assertThat(glBalance("developerPayable")).isEqualTo(payable);
        assertThat(income - glBalance("portfolioInterestIncome")).isEqualTo(100000 - net);
    }

    private ObjectNode bankSource(String id, String amount, String direction) {
        ObjectNode source = harness.json.object();
        source.put("bankSourceId", id + "-bank");
        source.put("bankAccountReference", "test-bank");
        source.put("verificationEvidenceId", "verified");
        source.put("valueDate", harness.today.toString());
        source.put("currency", "EGP");
        source.put("amountMinor", amount);
        source.put("direction", direction);
        return source;
    }

    private long glBalance(String account) throws Exception {
        return harness.queryLong(
                "select coalesce(sum(case when type_enum=2 then amount*100 else -amount*100 end),0) from acc_gl_journal_entry where account_id="
                        + harness.accounts.get(account));
    }

    private JsonNode controls(String accountId) throws Exception {
        JsonNode controls = harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/controls?accountId=" + accountId, null,
                200);
        for (JsonNode balance : controls.path("balances")) {
            assertThat(balance.path("differenceMinor").asText()).describedAs(accountId + ":" + balance.path("accountKey").asText())
                    .isEqualTo("0");
        }
        assertThat(controls.path("nativeContractualOutstandingMinor")).isEqualTo(controls.path("subledgerContractualOutstandingMinor"));
        return controls;
    }

    private long balance(JsonNode controls, String semantic) {
        for (JsonNode balance : controls.path("balances")) {
            if (balance.path("accountKey").asText().equals(semantic)) {
                return balance.path("nativeGlMinor").asLong();
            }
        }
        throw new AssertionError("Missing native control " + semantic);
    }

    private void developerSettlementAcrossDueDate() throws Exception {
        harness.moveDate(transferredLotDue);
        String id = "substitution-replacement";
        harness.recordReceipt("due-developer-cash", id, Long.toString(transferredLotAmount));
        ObjectNode payment = command("SETTLE_DEVELOPER_ADJUSTMENT", "due-developer-settle", id);
        payment.put("method", "CASH");
        payment.put("cashMovementId", "due-developer-cash");
        payment.set("lots", harness.json
                .value(List.of(Map.of("lotId", "substitution-reset:reset", "amountMinor", Long.toString(transferredLotAmount)))));
        execute(payment);
        JsonNode controls = controls(id);
        assertThat(balance(controls, "installmentDues")).isEqualTo(60000);
        assertThat(balance(controls, "contractualReceivable")).isEqualTo(60000);
        assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("120000");
    }

    private void impairedReversal() throws Exception {
        String id = "substitution-replacement";
        ObjectNode risk = forecast("substitution-account", "impaired-reversal-forecast", replacementFlows);
        risk.put("stage", "STAGE_2");
        risk.put("forecastVersion", "2");
        ((ObjectNode) risk.path("scenarios").get(0).path("recoveries").get(0)).put("amountMinor", "30000");
        ObjectNode impair = command("SET_IMPAIRMENT", "impaired-reversal", id);
        impair.set("forecast", risk);
        impair.set("qualitativeFindingIds", harness.json.value(List.of("credit-review")));
        impair.set("cureEvidenceIds", harness.json.value(List.of()));
        execute(impair);
        assertThat(account(id).path("position").path("lossAllowanceMinor").asText()).isEqualTo("30000");
        harness.recordReceipt("impaired-cash", id, "10000");
        ObjectNode collect = command("COLLECT", "impaired-collection", id);
        collect.set("allocations",
                harness.json.value(List.of(Map.of("allocationId", "impaired-allocation", "cashMovementId", "impaired-cash", "cashflowId",
                        id + "-0", "installmentId", id + "-0", "instrumentId", "impaired-cheque", "amountMinor", "10000"))));
        JsonNode collected = execute(collect);
        assertThat(account(id).path("position").path("lossAllowanceMinor").asText()).isEqualTo("25000");
        ObjectNode reverse = command("REVERSE_COLLECTION", "impaired-reverse", id);
        reverse.put("originalOperationId", "impaired-collection");
        reverse.set("nativeTransactionId", collected.path("nativeTransactionIds").get(0));
        reverse.set("allocationIds", harness.json.value(List.of("impaired-allocation")));
        reverse.put("reasonCode", "bank-return");
        execute(reverse);
        assertThat(account(id).path("position").path("lossAllowanceMinor").asText()).isEqualTo("30000");
        assertThat(account(id).path("position").path("contractualOutstandingMinor").asText()).isEqualTo("120000");
        assertThat(balance(controls(id), "lossAllowance")).isEqualTo(-30000);
    }

    private void corruptedMirrorsCannotHidePositionMismatch() throws Exception {
        String id = "substitution-replacement";
        long face = harness.queryLong(
                "select min(l.native_journal_id) from m_mnzl_r_journal_line l join m_mnzl_r_account a on a.record_key=l.account_key where a.external_id='substitution-replacement' and l.semantic_account='contractualReceivable' and l.side='DEBIT'");
        long discount = harness.queryLong(
                "select min(l.native_journal_id) from m_mnzl_r_journal_line l join m_mnzl_r_account a on a.record_key=l.account_key where a.external_id='substitution-replacement' and l.semantic_account='deferredDiscount' and l.side='CREDIT'");
        controls(id);
        try {
            for (long journal : List.of(face, discount)) {
                harness.executeSql(
                        "update m_mnzl_r_journal_line set amount_minor=cast(amount_minor as decimal(19,0))+1 where native_journal_id="
                                + journal);
                harness.executeSql("update acc_gl_journal_entry set amount=amount+0.01 where id=" + journal);
            }
            JsonNode corrupt = harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/controls?accountId=" + id, null, 200);
            assertThat(corrupt.path("balances"))
                    .anySatisfy(balance -> assertThat(balance.path("differenceMinor").asText()).isNotEqualTo("0"));
        } finally {
            for (long journal : List.of(face, discount)) {
                harness.executeSql(
                        "update m_mnzl_r_journal_line set amount_minor=cast(amount_minor as decimal(19,0))-1 where native_journal_id="
                                + journal);
                harness.executeSql("update acc_gl_journal_entry set amount=amount-0.01 where id=" + journal);
            }
        }
        controls(id);
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE", justification = "Isolated fault injection restores only a native event JSON value escaped as an SQL string")
    private void missingNativeSourcesCannotCancel() throws Exception {
        String id = "substitution-replacement";
        String condition = "id in (select l.native_journal_id from m_mnzl_r_journal_line l join m_mnzl_r_event e on e.record_key=l.event_key join m_mnzl_r_command c on c.record_key=e.operation_key where c.operation_id='impaired-collection' and l.component='CASH')";
        // Copy before removal so both opposing native rows can be restored on every engine.
        harness.executeSql("create table flex_missing_journal_backup as select * from acc_gl_journal_entry where " + condition);
        try {
            assertThat(harness.queryLong("select count(*) from flex_missing_journal_backup")).isEqualTo(2);
            assertThat(harness
                    .queryLong("select sum(case when type_enum=2 then amount*100 else -amount*100 end) from flex_missing_journal_backup"))
                    .isZero();
            harness.executeSql("delete from acc_gl_journal_entry where " + condition);
            assertThat(harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/controls?accountId=" + id, null, 409)
                    .path("code").asText()).isEqualTo("JOURNAL_MISMATCH");
        } finally {
            harness.executeSql(
                    "insert into acc_gl_journal_entry select b.* from flex_missing_journal_backup b left join acc_gl_journal_entry j on j.id=b.id where j.id is null");
            harness.executeSql("drop table flex_missing_journal_backup");
        }
        String event = harness.queryText(
                "select e.event_json from m_mnzl_r_event e join m_mnzl_r_command c on c.record_key=e.operation_key where c.operation_id='substitute'");
        ObjectNode missing = (ObjectNode) harness.json.read(event);
        assertThat(missing.path("sourceTransactionIds")).isNotEmpty();
        missing.set("sourceTransactionIds", harness.json.value(List.of()));
        String update = "update m_mnzl_r_event set event_json='%s' where operation_key=(select record_key from m_mnzl_r_command where operation_id='substitute')";
        try {
            harness.executeSql(update.formatted(harness.json.write(missing).replace("'", "''")));
            assertThat(harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/controls?accountId=" + id, null, 409)
                    .path("code").asText()).isEqualTo("JOURNAL_MISMATCH");
        } finally {
            harness.executeSql(update.formatted(event.replace("'", "''")));
        }
        controls(id);
    }

    private void writtenOffRecovery() throws Exception {
        String id = "modified-account";
        harness.moveDate(harness.today.plusDays(1));
        ObjectNode cash = harness.command("RECORD_CASH_MOVEMENT", "recovery-cash", "deal", "DEAL");
        ObjectNode source = bankSource("recovery-cash", "5000", "INCOMING");
        source.set("allocations",
                harness.json.value(List
                        .of(Map.of("allocationId", "recovery-bank-allocation", "kind", "RECEIPT_UNAPPLIED", "dealId", "deal", "accountId",
                                id, "beneficiaryReferenceId", "financier", "payerReferenceId", id + "-customer", "amountMinor", "5000"))));
        cash.set("source", source);
        execute(cash);
        ObjectNode recovery = command("RECOVER_WRITTEN_OFF", "written-off-recovery", id);
        recovery.put("originalWriteOffOperationId", "write-off");
        recovery.put("cashMovementId", "recovery-cash");
        recovery.put("bankAllocationId", "recovery-bank-allocation");
        recovery.put("amountMinor", "5000");
        recovery.put("payer", "BORROWER");
        recovery.put("payerReferenceId", id + "-customer");
        recovery.set("legalRightsEvidenceIds", harness.json.value(List.of("retained-legal-rights")));
        long income = glBalance("portfolioInterestIncome");
        long recoveryIncome = glBalance("writtenOffRecoveryIncome");
        execute(recovery);
        assertThat(glBalance("portfolioInterestIncome")).isEqualTo(income);
        assertThat(glBalance("writtenOffRecoveryIncome") - recoveryIncome).isEqualTo(-5000);
        JsonNode closed = account(id);
        assertThat(closed.path("position").path("nativeLoanStatus").asText()).isEqualTo("WRITTEN_OFF");
        assertThat(closed.path("closureReason").asText()).isEqualTo("WRITE_OFF");
        assertThat(closed.path("position").path("contractualOutstandingMinor").asText()).isEqualTo("0");
        assertThat(harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/accounts/" + id + "/transactions", null, 200)
                .path("items")).anySatisfy(tx -> {
                    assertThat(tx.path("operationId").asText()).isEqualTo("written-off-recovery");
                    assertThat(tx.path("type").asText()).isEqualTo("RECOVERY");
                    assertThat(tx.path("amountMinor").asText()).isEqualTo("5000");
                    assertThat(tx.path("payer").asText()).isEqualTo("BORROWER");
                    assertThat(tx.path("payerReferenceId").asText()).isEqualTo(id + "-customer");
                });
    }

    private void developerImpairmentAfterDue() throws Exception {
        String id = "developer-impaired-account";
        harness.purchase(id);
        ObjectNode reset = command("RESET_RATE", "developer-impaired-reset", id);
        reset.put("corridorObservationId", "developer-impaired-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("developerAdjustmentDueDate", harness.today.plusDays(45).toString());
        execute(reset);
        harness.moveDate(harness.today.plusDays(46));
        JsonNode lot = null;
        for (JsonNode value : harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/developer-lots", null, 200)
                .path("items")) {
            if (value.path("accountId").asText().equals(id)) {
                lot = value;
            }
        }
        assertThat(lot).isNotNull();
        ObjectNode forecast = forecast(id, "developer-lot-forecast", List.of());
        forecast.put("stage", "STAGE_2");
        ((ObjectNode) forecast.path("scenarios").get(0)).put("defaultDate", harness.today.toString());
        forecast.remove("contentHash");
        forecast.put("contentHash", harness.json.hash(forecast));
        ObjectNode impairment = command("SET_DEVELOPER_IMPAIRMENT", "developer-lot-impairment", id);
        impairment.set("lotId", lot.get("lotId"));
        impairment.put("expectedCarryingMinor", Long.toString(lot.path("outstandingMinor").asLong() + lot.path("settledMinor").asLong()));
        impairment.set("expectedAnnualNominalRate", lot.get("annualNominalRate"));
        impairment.set("forecast", forecast);
        execute(impairment);
        assertThat(harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/accounts/" + id + "/transactions", null, 200)
                .path("items")).anySatisfy(tx -> {
                    assertThat(tx.path("operationId").asText()).isEqualTo("developer-lot-impairment");
                    assertThat(tx.path("type").asText()).isEqualTo("IMPAIRMENT");
                    assertThat(tx.path("amountMinor").asLong()).isPositive();
                });
        controls(id);
    }

    private void funding() throws Exception {
        ObjectNode terms = harness.json.object();
        terms.put("facilityId", "facility");
        terms.put("currency", "EGP");
        terms.put("annualNominalRate", "0.18");
        terms.put("dayCount", "ACTUAL_360_SIMPLE");
        terms.put("capitalizeInterest", false);
        fundingEvent(terms, "DRAW", "draw", "10000000", "0");
        harness.moveDate(harness.today.plusDays(31));
        fundingEvent(terms, "INTEREST_SETTLEMENT", "interest", "155000", "1");
        assertThat(harness
                .queryLong("select cast(principal_minor as decimal(19,0)) from m_mnzl_r_funding_facility where facility_id='facility'"))
                .isEqualTo(10000000);
        assertThat(harness
                .queryLong("select cast(interest_minor as decimal(19,0)) from m_mnzl_r_funding_facility where facility_id='facility'"))
                .isZero();
        assertThat(glBalance("fundingExpense")).isEqualTo(155000);
        fundingEvent(terms, "REPAYMENT", "repay-funding", "10000000", "2");
        assertThat(harness
                .queryLong("select cast(principal_minor as decimal(19,0)) from m_mnzl_r_funding_facility where facility_id='facility'"))
                .isZero();
    }

    private void fundingEvent(ObjectNode terms, String kind, String operation, String amount, String version) throws Exception {
        ObjectNode c = harness.command("RECORD_FUNDING_EVENT", operation, "facility", "FUNDING_FACILITY");
        c.set("scope", harness.scope(false));
        c.put("expectedVersion", version);
        c.set("terms", terms);
        ObjectNode event = harness.json.object();
        event.put("kind", kind);
        event.put(kind.equals("INTEREST_SETTLEMENT") ? "interestMinor" : "principalMinor", amount);
        ObjectNode source = bankSource(operation, amount, kind.equals("DRAW") ? "INCOMING" : "OUTGOING");
        source.put("facilityId", "facility");
        event.set("cashSource", source);
        c.set("event", event);
        execute(c);
    }
}
