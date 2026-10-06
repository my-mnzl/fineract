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
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Exercises the real migration, HTTP authorization, transaction locks and historical ledger after configuration
 * changes.
 */
final class ReceivablesConfigurationScenarios {

    private static final String BASE = ReceivablesDatabaseIntegrationTest.PREFIX;
    private static final String UPFRONT = co.mnzl.fineract.receivables.math.ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION;
    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesConfigurationScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        ObjectNode legacyBook = legacyHistory();
        String requestBefore = harness.queryText("select request_json from m_mnzl_r_command where operation_id='account-1-book'");
        String eventsBefore = harness.queryText("select event_json from m_mnzl_r_event where operation_key="
                + "(select record_key from m_mnzl_r_command where operation_id='account-1-book')");
        harness.migrateLegacyConfiguration();
        assertThat(harness.queryText("select request_json from m_mnzl_r_command where operation_id='account-1-book'"))
                .isEqualTo(requestBefore);
        assertThat(harness.queryText("select event_json from m_mnzl_r_event where operation_key="
                + "(select record_key from m_mnzl_r_command where operation_id='account-1-book')")).isEqualTo(eventsBefore);
        JsonNode original = harness.request("GET", BASE + "/configuration", null, 200);
        JsonNode active = harness.request("GET", BASE + "/configuration/active", null, 200);
        assertThat(active.path("configuration")).isEqualTo(original);
        assertThat(active.path("contentHash").asText()).isEqualTo(harness.json.hash(original));
        JsonNode runtime = harness.request("GET", BASE + "/configuration/runtime", null, 200);
        assertThat(runtime.path("sourceRevision").asText()).matches("[a-f0-9]{40}");
        assertThat(runtime.path("openApiSha256").asText()).matches("[a-f0-9]{64}");
        String helOperation = harness.queryText("select operation_key from m_mnzl_r_hel_funding limit 1");
        String helLoan = harness.queryText("select loan_id from m_mnzl_r_hel_funding where operation_key=?", helOperation);
        JsonNode helResult = harness.json
                .read(harness.queryText("select result_json from m_mnzl_r_hel_funding where operation_key=?", helOperation));
        var helPathBuilder = new StringBuilder(BASE).append("/hel-journals?loanId=").append(helLoan);
        for (JsonNode id : helResult.path("nativeTransactionIds")) {
            helPathBuilder.append("&transactionIds=").append(id.asText());
        }
        String helPath = helPathBuilder.append("&eventWatermark=").append(harness.queryLong("select max(sequence_id) from m_mnzl_r_event"))
                .toString();
        JsonNode helBefore = harness.request("GET", helPath, null, 200);
        // The legacy set cannot book an upfront admin fee; both purchases are prepared now and booked after migration.
        ObjectNode upfront = harness.preparePurchase("upfront-account", "1", "99999", "acquisition-upfront", UPFRONT);
        assertThat(harness.request("POST", BASE + "/commands", upfront, 409).path("code").asText())
                .isEqualTo("FINERACT_CAPABILITY_MISSING");
        ObjectNode deferred = harness.preparePurchase("deferred-after-migration", "1", "99999", "acquisition-deferred",
                ReceivablesConfiguration.CALCULATION);
        assertThat(harness.request("GET", BASE + "/capabilities", null, 200).path("calculationVersions"))
                .doesNotContain(harness.json.value(UPFRONT));
        ObjectNode changed = original.deepCopy();
        changed.put("calculatorBuild", "build-next");
        assertThat(harness.request("POST", BASE + "/configuration/revisions", changed, 409).path("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        changed.put("accountMappingRevisionId", "mapping-next");
        // The used scope may adopt the admin fee set only by keeping the deferred fee on its account.
        changed.set("accountMap", harness.accountMap(true));
        ObjectNode moved = changed.deepCopy();
        moved.put("accountMappingRevisionId", "forbidden-deferred-fee-move");
        for (JsonNode entry : moved.path("accountMap")) {
            if (entry.path("accountKey").asText().equals("deferredAdminFee")) {
                ((ObjectNode) entry).put("nativeGlAccountId", Long.toString(harness.accounts.get("adminFeeIncome")));
            }
        }
        harness.request("POST", BASE + "/configuration/revisions", moved, 409);
        JsonNode staged = harness.request("POST", BASE + "/configuration/revisions", changed, 200);
        var reversed = new ArrayList<JsonNode>();
        changed.path("accountMap").forEach(reversed::add);
        Collections.reverse(reversed);
        changed.set("accountMap", harness.json.value(reversed));
        assertThat(harness.request("POST", BASE + "/configuration/revisions", changed, 200)).isEqualTo(staged);
        ObjectNode activation = activation("activate-next", "mapping-next", staged, "mapping-1");
        String period = harness.queryText("select record_key from m_mnzl_r_period limit 1");
        String periodStatus = harness.queryText("select status from m_mnzl_r_period where record_key=?", period);
        harness.executeSql("update m_mnzl_r_period set status='PREPARING' where record_key=?", period);
        try {
            assertThat(harness.request("POST", BASE + "/configuration/activations", activation, 409).path("code").asText())
                    .isEqualTo("PERIOD_CLOSED");
        } finally {
            harness.executeSql("update m_mnzl_r_period set status=? where record_key=?", periodStatus, period);
        }
        JsonNode activated = harness.request("POST", BASE + "/configuration/activations", activation, 200);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_account_map where account_key in ('deferredAdminFee','adminFeeIncome') "
                + "and mapping_revision='mapping-next'")).isEqualTo(2);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_account_map where account_key='deferredIntegralFee'")).isZero();
        assertThat(harness.request("GET", BASE + "/capabilities", null, 200).path("calculationVersions"))
                .contains(harness.json.value(UPFRONT));
        assertThat(harness.request("GET", BASE + "/configuration", null, 200)).isEqualTo(original);
        JsonNode migrated = harness.requestMapping("GET", BASE + "/configuration", null, "mapping-next", 200);
        verifyMigratedPurchases(upfront, deferred);
        verifyUpfrontHistoricalPurchase();
        assertThat(harness.request("POST", BASE + "/commands", legacyBook, 200).path("payloadHash").asText())
                .isEqualTo(harness.json.hash(legacyBook));
        var command = harness.json.read(requestBefore);
        JsonNode replay = harness.request("POST", BASE + "/commands", command, 200);
        assertThat(replay.path("payloadHash").asText()).isEqualTo(harness.json.hash(command));
        ObjectNode stale = command.deepCopy();
        stale.put("operationId", "stale-after-activation").put("idempotencyKey", "stale-after-activation");
        assertThat(harness.request("POST", BASE + "/commands", stale, 409).path("code").asText()).isEqualTo("FINERACT_CAPABILITY_MISSING");
        ObjectNode fresh = harness.receiptCommand("cash-new-configuration", "account-1", "1");
        fresh.put("accountMappingRevisionId", "mapping-next");
        JsonNode freshResult = harness.request("POST", BASE + "/commands", fresh, 200);
        String eventId = freshResult.path("financialEventIds").get(0).asText();
        JsonNode freshEvent = harness.json.read(harness.queryText("select event_json from m_mnzl_r_event where record_key=?", eventId));
        assertThat(freshEvent.path("accountMappingRevisionId").asText()).isEqualTo("mapping-next");
        assertThat(freshEvent.path("calculatorBuild").asText()).isEqualTo("build-next");
        assertThat(harness.queryText(
                "select l.native_gl_id from m_mnzl_r_journal_line l join acc_gl_journal_entry j "
                        + "on j.id=l.native_journal_id and j.account_id=l.native_gl_id where l.event_key=? and l.semantic_account='bank'",
                eventId)).isEqualTo(Long.toString(harness.accounts.get("bank")));
        assertThat(harness.request("GET", helPath, null, 200)).isEqualTo(helBefore);
        new ReceivablesPeriodProofScenarios(harness).verify();
        for (String field : new String[] { "bankAccountReference", "officeId", "productId", "helPaymentTypeId", "helProductId" }) {
            ObjectNode forbidden = migrated.deepCopy();
            forbidden.put("accountMappingRevisionId", "forbidden-" + field);
            forbidden.put(field, field.equals("bankAccountReference") ? "changed-bank" : "999999999");
            harness.request("POST", BASE + "/configuration/revisions", forbidden, 409);
        }
        long otherOffice = harness.request("POST", "/offices", harness.json.value(java.util.Map.of("name", "Other Flex office", "parentId",
                1, "openingDate", harness.today.toString(), "dateFormat", "yyyy-MM-dd", "locale", "en")), 200).path("officeId").asLong();
        if (otherOffice == 0) {
            otherOffice = harness.queryLong("select id from m_office where name='Other Flex office'");
        }
        ObjectNode movedOffice = migrated.deepCopy();
        movedOffice.put("accountMappingRevisionId", "forbidden-valid-office").put("officeId", Long.toString(otherOffice));
        harness.request("POST", BASE + "/configuration/revisions", movedOffice, 409);
        long otherPayment = harness.request("POST", "/paymenttypes", harness.json.value(java.util.Map.of("name", "Other noncash route",
                "isCashPayment", false, "position", 99, "description", "Lifecycle freeze test")), 200).path("resourceId").asLong();
        harness.executeSql(
                "insert into acc_product_mapping (gl_account_id,product_id,product_type,financial_account_type,payment_type) "
                        + "select gl_account_id,product_id,product_type,financial_account_type,? from acc_product_mapping "
                        + "where product_id=? and product_type=1 and financial_account_type=1 and payment_type=?",
                otherPayment, Long.parseLong(original.path("helProductId").asText()),
                Long.parseLong(original.path("helPaymentTypeId").asText()));
        ObjectNode rerouted = migrated.deepCopy();
        rerouted.put("accountMappingRevisionId", "forbidden-valid-route").put("helPaymentTypeId", Long.toString(otherPayment));
        harness.request("POST", BASE + "/configuration/revisions", rerouted, 409);
        ObjectNode remapped = migrated.deepCopy();
        remapped.put("accountMappingRevisionId", "forbidden-map");
        ((ObjectNode) remapped.path("accountMap").get(0)).set("nativeGlAccountId",
                remapped.path("accountMap").get(1).get("nativeGlAccountId"));
        harness.request("POST", BASE + "/configuration/revisions", remapped, 409);
        // Once migrated, the legacy set is never routing again.
        ObjectNode reverted = original.deepCopy();
        reverted.put("accountMappingRevisionId", "forbidden-legacy-set");
        harness.request("POST", BASE + "/configuration/revisions", reverted, 409);
        ObjectNode left = migrated.deepCopy();
        left.put("accountMappingRevisionId", "mapping-left");
        ObjectNode right = migrated.deepCopy();
        right.put("accountMappingRevisionId", "mapping-right");
        var leftStage = harness.request("POST", BASE + "/configuration/revisions", left, 200);
        var rightStage = harness.request("POST", BASE + "/configuration/revisions", right, 200);
        var leftRequest = activation("activate-left", "mapping-left", leftStage, "mapping-next");
        var rightRequest = activation("activate-right", "mapping-right", rightStage, "mapping-next");
        var first = CompletableFuture.supplyAsync(() -> activateRace(leftRequest));
        var second = CompletableFuture.supplyAsync(() -> activateRace(rightRequest));
        var results = java.util.List.of(first.get(), second.get());
        assertThat(results.stream().filter(row -> row.has("activationId")).count()).isEqualTo(1);
        assertThat(results.stream().filter(row -> row.path("code").asText().equals("ACCOUNT_VERSION_CHANGED")).count()).isEqualTo(1);
        JsonNode winner = harness.request("GET", BASE + "/configuration/active", null, 200);
        assertThat(harness.request("POST", BASE + "/configuration/activations", activation, 200)).isEqualTo(activated);
        assertThat(harness.request("GET", BASE + "/configuration/active", null, 200)).isEqualTo(winner);
        var retired = activation("reactivate-retired", "mapping-1", active,
                winner.path("configuration").path("accountMappingRevisionId").asText());
        harness.request("POST", BASE + "/configuration/activations", retired, 409);
        // Persisted historical mapping remains authoritative even when the requested read token is a newer revision.
        String current = winner.path("configuration").path("accountMappingRevisionId").asText();
        assertThat(harness.requestMapping("GET", BASE + "/configuration", null, current, 200).path("accountMappingRevisionId").asText())
                .isEqualTo(current);
        String eventPeriod = harness.queryText("select posting_period from m_mnzl_r_event where operation_key="
                + "(select record_key from m_mnzl_r_command where operation_id='account-1-book')");
        long watermark = harness.queryLong("select max(sequence_id) from m_mnzl_r_event");
        String proofPath = BASE + "/period-activity-proof?postingPeriod=" + eventPeriod + "&eventWatermark=" + watermark;
        harness.requestMapping("GET", proofPath, null, current, 200);
        String historicalJson = harness
                .queryText("select config_json from m_mnzl_r_configuration_revision where mapping_revision='mapping-1'");
        ObjectNode damaged = original.deepCopy();
        for (JsonNode entry : damaged.path("accountMap")) {
            if (entry.path("accountKey").asText().equals("contractualReceivable")) {
                ((ObjectNode) entry).put("nativeGlAccountId", Long.toString(harness.accounts.get("bank")));
            }
        }
        harness.executeSql("update m_mnzl_r_configuration_revision set config_json=? where mapping_revision='mapping-1'",
                harness.json.write(damaged));
        try {
            assertThat(harness.requestMapping("GET", proofPath, null, current, 409).path("code").asText()).isEqualTo("JOURNAL_MISMATCH");
        } finally {
            harness.executeSql("update m_mnzl_r_configuration_revision set config_json=? where mapping_revision='mapping-1'",
                    historicalJson);
        }

    }

    /**
     * Rewrites one booked account into what the integral fee vocabulary stored before the rename (command, event,
     * journal registry and terms, resealed as they were sealed then), and every segment snapshot into its legacy field,
     * then runs the admin fee changeset. Returns the legacy command for replay.
     */
    private ObjectNode legacyHistory() throws Exception {
        harness.purchase("legacy-account");
        String operation = harness.queryText("select record_key from m_mnzl_r_command where operation_id='legacy-account-book'");
        ObjectNode request = (ObjectNode) legacy(
                harness.queryText("select request_json from m_mnzl_r_command where record_key=?", operation));
        assertThat(request.path("basis").path("acceptedAccountPrices").get(0).has("integralFeeMinor")).isTrue();
        ObjectNode result = (ObjectNode) harness.json
                .read(harness.queryText("select result_json from m_mnzl_r_command where record_key=?", operation));
        result.put("payloadHash", harness.json.hash(request));
        harness.executeSql("update m_mnzl_r_command set request_json=?, payload_hash=?, result_json=? where record_key=?",
                harness.json.write(request), harness.json.hash(request), harness.json.write(result), operation);
        String event = harness.queryText("select record_key from m_mnzl_r_event where operation_key=?", operation);
        ObjectNode sealed = (ObjectNode) legacy(harness.queryText("select event_json from m_mnzl_r_event where record_key=?", event));
        sealed.remove("contentHash");
        // Sealed events written before positions named their version carry it only at event level.
        sealed.path("positionsAfter").forEach(position -> ((ObjectNode) position).remove("calculationVersion"));
        String hash = harness.json.hash(sealed);
        sealed.put("contentHash", hash);
        assertThat(harness.json.write(sealed)).contains("\"deferredIntegralFee\"", "\"INTEGRAL_FEE\"", "\"deferredIntegralFeeMinor\"");
        harness.executeSql("update m_mnzl_r_event set event_json=?, content_hash=? where record_key=?", harness.json.write(sealed), hash,
                event);
        harness.executeSql("update m_mnzl_r_journal_line set semantic_account='deferredIntegralFee' where event_key=? "
                + "and semantic_account='deferredAdminFee'", event);
        harness.executeSql("update m_mnzl_r_journal_line set component='INTEGRAL_FEE' where event_key=? and component='ADMIN_FEE'", event);
        harness.executeSql("update m_mnzl_r_account set terms_json=? where external_id='legacy-account'", harness.json
                .write(legacy(harness.queryText("select terms_json from m_mnzl_r_account where external_id='legacy-account'"))));
        harness.executeSql("update m_mnzl_r_segment set snapshot_json=replace(snapshot_json, '\"deferredAdminFeeMinor\":', "
                + "'\"deferredIntegralFeeMinor\":')");
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_segment where snapshot_json like '%deferredAdminFeeMinor%'")).isZero();
        harness.migrateAdminFee();
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_segment where snapshot_json like '%deferredIntegralFeeMinor%'"))
                .isZero();
        // Sealed legacy records read under current names, and the deferred fee control still reconciles.
        JsonNode account = harness.request("GET", BASE + "/accounts/legacy-account", null, 200);
        assertThat(account.path("position").has("deferredIntegralFeeMinor")).isFalse();
        assertThat(account.path("position").path("calculationVersion").asText()).isEqualTo(ReceivablesConfiguration.CALCULATION);
        assertThat(account.path("position").path("deferredAdminFeeMinor").asText())
                .isEqualTo(request.path("basis").path("acceptedAccountPrices").get(0).path("integralFeeMinor").asText());
        JsonNode controls = harness.request("GET", BASE + "/controls?accountId=legacy-account", null, 200);
        for (JsonNode balance : controls.path("balances")) {
            assertThat(balance.path("differenceMinor").asText()).isEqualTo("0");
            if (balance.path("accountKey").asText().equals("deferredAdminFee")) {
                assertThat(balance.path("nativeGlMinor").asText())
                        .isEqualTo("-" + account.path("position").path("deferredAdminFeeMinor").asText());
            }
        }
        String journals = BASE + "/journals?operationIds=legacy-account-book&eventWatermark="
                + harness.queryLong("select max(sequence_id) from m_mnzl_r_event");
        assertThat(harness.request("GET", journals, null, 200).path("lines"))
                .anyMatch(line -> line.path("accountKey").asText().equals("deferredAdminFee"));
        // Legacy names replay their sealed command exactly, but cannot start a new one.
        assertThat(harness.request("POST", BASE + "/commands", request, 200).path("payloadHash").asText())
                .isEqualTo(harness.json.hash(request));
        ObjectNode renamed = request.deepCopy();
        renamed.put("operationId", "legacy-names-new-operation").put("idempotencyKey", "legacy-names-new-operation");
        assertThat(harness.request("POST", BASE + "/commands", renamed, 400).path("code").asText()).isEqualTo("INVALID_DATA");
        // The events feed returns the sealed legacy event as stored, and the read contract accepts it.
        String cursor = "";
        do {
            JsonNode page = harness.request("GET", BASE + "/events?limit=50" + (cursor.isEmpty() ? "" : "&cursor=" + cursor), null, 200);
            harness.json.validate("financialEventsPage", page);
            cursor = page.path("nextCursor").asText("");
        } while (!cursor.isEmpty());
        return request;
    }

    private JsonNode legacy(String stored) {
        return harness.json.read(stored.replace("\"deferredAdminFeeMinor\"", "\"deferredIntegralFeeMinor\"")
                .replace("\"adminFeeMinor\"", "\"integralFeeMinor\"").replace("\"deferredAdminFee\"", "\"deferredIntegralFee\"")
                .replace("\"ADMIN_FEE\"", "\"INTEGRAL_FEE\""));
    }

    /** After migration the upfront fee is purchase income and the deferred fee keeps its original account. */
    private void verifyMigratedPurchases(ObjectNode upfront, ObjectNode deferred) throws Exception {
        for (ObjectNode book : java.util.List.of(upfront, deferred)) {
            book.put("accountMappingRevisionId", "mapping-next");
            ((ObjectNode) book.get("basis")).put("calculatorBuild", "build-next");
            book.put("basisHash", harness.json.hash(book.get("basis")));
        }
        JsonNode accepted = upfront.path("basis").path("acceptedAccountPrices").get(0);
        String fee = accepted.path("adminFeeMinor").asText();
        JsonNode event = event(harness.request("POST", BASE + "/commands", upfront, 200));
        assertThat(event.path("calculationVersion").asText()).isEqualTo(UPFRONT);
        assertThat(lines(event)).contains("adminFeeIncome CREDIT " + fee + " ADMIN_FEE")
                .noneMatch(line -> line.startsWith("deferredAdminFee"));
        JsonNode position = event.path("positionsAfter").get(0);
        assertThat(position.path("calculationVersion").asText()).isEqualTo(UPFRONT);
        assertThat(position.path("deferredAdminFeeMinor").asText()).isEqualTo("0");
        assertThat(position.path("amortizedCostMinor")).isEqualTo(position.path("grossPurchaseBasisMinor"));
        assertThat(position.path("amortizedCostMinor")).isEqualTo(accepted.path("grossPurchasePriceMinor"));
        assertThat(harness.queryText("select purchase_fee_minor from m_mnzl_r_account where external_id='upfront-account'")).isEqualTo(fee);
        assertThat(glOf(event, "adminFeeIncome")).isEqualTo(harness.accounts.get("adminFeeIncome"));
        JsonNode deferredEvent = event(harness.request("POST", BASE + "/commands", deferred, 200));
        String deferredFee = deferred.path("basis").path("acceptedAccountPrices").get(0).path("adminFeeMinor").asText();
        assertThat(lines(deferredEvent)).contains("deferredAdminFee CREDIT " + deferredFee + " ADMIN_FEE")
                .noneMatch(line -> line.startsWith("adminFeeIncome"));
        assertThat(glOf(deferredEvent, "deferredAdminFee")).isEqualTo(harness.accounts.get("deferredAdminFee"));
        assertThat(harness.queryText("select purchase_fee_minor from m_mnzl_r_account where external_id='deferred-after-migration'"))
                .isEqualTo(deferredFee);
    }

    /**
     * A DAILY_V2 historical purchase earns the admin fee when it is recorded, before any cash moves; reconciling the
     * net cash later settles acquisition clearing without touching the fee or the measured position.
     */
    private void verifyUpfrontHistoricalPurchase() throws Exception {
        String id = "upfront-historical";
        ObjectNode basis = harness.bookingCommands.get("account-1").path("basis").deepCopy();
        basis.put("calculationVersion", UPFRONT).put("calculatorBuild", "build-next").put("settlementDate", harness.today.toString());
        var flows = new ArrayList<co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow>();
        int index = 0;
        for (JsonNode flow : basis.path("cashflows")) {
            ObjectNode value = (ObjectNode) flow;
            value.put("receivableId", id).put("cashflowId", id + "-" + index).put("installmentId", id + "-" + index).put("dueDate",
                    harness.today.plusDays(++index * 45).toString());
            flows.add(new co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow(value.path("cashflowId").asText(),
                    java.time.LocalDate.parse(value.path("dueDate").asText()),
                    new java.math.BigInteger(value.path("amountMinor").asText())));
        }
        var purchase = co.mnzl.fineract.receivables.math.ReceivablesMath.price(UPFRONT, harness.today, flows,
                new java.math.BigDecimal(basis.path("corridorRate").asText()).add(new java.math.BigDecimal(basis.path("spread").asText())),
                new java.math.BigDecimal(basis.path("feeRate").asText()));
        String gross = purchase.grossPurchasePriceMinor().toString();
        String fee = purchase.adminFeeMinor().toString();
        String net = purchase.netPurchaseCashMinor().toString();
        basis.set("acceptedAccountPrices", harness.json.value(java.util.List.of(
                java.util.Map.of("accountId", id, "grossPurchasePriceMinor", gross, "adminFeeMinor", fee, "netPurchaseCashMinor", net))));
        ObjectNode book = next(harness.command("BOOK_HISTORICAL_PURCHASE", id + "-book", id, "RECEIVABLE"));
        book.put("accountId", id).put("dealId", "deal").put("customerReferenceId", id + "-customer");
        book.set("acquisition", harness.json.value(java.util.Map.of("id", id + "-acquisition", "developerReferenceId", "developer",
                "sourceReferenceId", id + "-record", "effectiveDate", harness.today.toString())));
        book.set("basis", basis);
        book.put("basisHash", harness.json.hash(basis));
        book.set("approverIds", harness.json.value(java.util.List.of()));
        book.set("approvalEvidenceIds", harness.json.value(java.util.List.of()));
        book.set("acknowledgment",
                harness.json
                        .value(java.util.Map.of("recordId", id + "-record", "sourceHash", basis.path("sourceHash").asText(), "basisHash",
                                harness.json.hash(basis), "actorId", "operator", "reason", "Recorded completed upfront fee acquisition")));
        JsonNode booked = event(harness.request("POST", BASE + "/commands", book, 200));
        assertThat(booked.path("calculationVersion").asText()).isEqualTo(UPFRONT);
        assertThat(lines(booked)).containsExactlyInAnyOrder("contractualReceivable DEBIT " + purchase.contractualFaceMinor() + " FACE",
                "deferredDiscount CREDIT " + purchase.contractualFaceMinor().subtract(purchase.grossPurchasePriceMinor()) + " DISCOUNT",
                "acquisitionClearing CREDIT " + net + " CASH", "adminFeeIncome CREDIT " + fee + " ADMIN_FEE");
        JsonNode position = booked.path("positionsAfter").get(0);
        assertThat(position.path("unreconciledPurchaseMinor").asText()).isEqualTo(net);
        assertThat(position.path("deferredAdminFeeMinor").asText()).isEqualTo("0");
        assertThat(position.path("amortizedCostMinor").asText()).isEqualTo(gross);
        assertThat(harness.queryText("select purchase_fee_minor from m_mnzl_r_account where external_id=?", id)).isEqualTo(fee);

        ObjectNode cash = next(harness.command("RECORD_CASH_MOVEMENT", id + "-payment", "deal", "DEAL"));
        cash.set("source",
                harness.json.value(java.util.Map.of("bankSourceId", id + "-payment", "bankAccountReference", "test-bank",
                        "verificationEvidenceId", "verified-later", "valueDate", harness.today.toString(), "currency", "EGP", "amountMinor",
                        net, "direction", "OUTGOING", "allocations",
                        java.util.List.of(java.util.Map.of("allocationId", id + "-payment-allocation", "kind", "ACQUISITION_ADVANCE",
                                "accountId", id, "dealId", "deal", "beneficiaryReferenceId", "developer", "amountMinor", net)))));
        harness.request("POST", BASE + "/commands", cash, 200);
        ObjectNode reconcile = next(harness.command("RECONCILE_HISTORICAL_PURCHASE", id + "-reconcile", id, "RECEIVABLE"));
        reconcile.put("expectedVersion", "1").put("amountMinor", net);
        reconcile.set("acquisitionClearingAllocationIds", harness.json.value(java.util.List.of(id + "-payment-allocation")));
        JsonNode reconciled = event(harness.request("POST", BASE + "/commands", reconcile, 200));
        assertThat(lines(reconciled)).noneMatch(line -> line.startsWith("adminFeeIncome") || line.startsWith("deferredAdminFee"));
        JsonNode account = harness.request("GET", BASE + "/accounts/" + id, null, 200).path("position");
        assertThat(account.path("unreconciledPurchaseMinor").asText()).isEqualTo("0");
        assertThat(account.path("calculationVersion").asText()).isEqualTo(UPFRONT);
        assertThat(account.path("amortizedCostMinor").asText()).isEqualTo(gross);
        assertThat(account.path("deferredAdminFeeMinor").asText()).isEqualTo("0");
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_journal_line l join m_mnzl_r_account a on a.record_key=l.account_key "
                + "where a.external_id='" + id + "' and l.semantic_account='adminFeeIncome' and l.side='CREDIT' and l.amount_minor='" + fee
                + "'")).isEqualTo(1);
        JsonNode controls = harness.request("GET", BASE + "/controls?accountId=" + id, null, 200);
        for (JsonNode balance : controls.path("balances")) {
            assertThat(balance.path("differenceMinor").asText()).describedAs(balance.path("accountKey").asText()).isEqualTo("0");
            if (balance.path("accountKey").asText().equals("adminFeeIncome")) {
                assertThat(balance.path("nativeGlMinor").asText()).isEqualTo("-" + fee);
            }
        }
        assertThat(controls.path("unreconciledPurchaseMinor").asText()).isEqualTo("0");
    }

    private static ObjectNode next(ObjectNode command) {
        return command.put("accountMappingRevisionId", "mapping-next");
    }

    private JsonNode event(JsonNode result) throws Exception {
        return harness.json.read(harness.queryText("select event_json from m_mnzl_r_event where record_key=?",
                result.path("financialEventIds").get(0).asText()));
    }

    private static java.util.List<String> lines(JsonNode event) {
        var lines = new ArrayList<String>();
        java.math.BigInteger balance = java.math.BigInteger.ZERO;
        for (JsonNode line : event.path("journalLines")) {
            var amount = new java.math.BigInteger(line.path("amountMinor").asText());
            balance = line.path("side").asText().equals("DEBIT") ? balance.add(amount) : balance.subtract(amount);
            lines.add(line.path("accountKey").asText() + " " + line.path("side").asText() + " " + amount + " "
                    + line.path("component").asText());
        }
        assertThat(balance).isZero();
        return lines;
    }

    private long glOf(JsonNode event, String semantic) throws Exception {
        return harness.queryLong("select max(native_gl_id) from m_mnzl_r_journal_line where event_key='" + event.path("eventId").asText()
                + "' and semantic_account='" + semantic + "'");
    }

    private ObjectNode activation(String id, String target, JsonNode stage, String previous) {
        return harness.json.object().put("activationId", id).put("accountMappingRevisionId", target)
                .put("contentHash", stage.path("contentHash").asText()).put("expectedActiveAccountMappingRevisionId", previous);
    }

    private JsonNode activateRace(ObjectNode request) {
        try {
            return harness.request("POST", BASE + "/configuration/activations", request, 200, 409);
        } catch (Exception exception) {
            throw new java.util.concurrent.CompletionException(exception);
        }
    }
}
