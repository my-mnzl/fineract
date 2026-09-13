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

import static co.mnzl.fineract.custom.receivables.service.ReceivablesException.require;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in whole-product authority and immutable actual-current captures, never invented historical balances. */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ReceivablesHelReporting {

    public static final String BASIS = "POSTED_NATIVE_RECEIVABLES_NET_OF_RECORDED_PRODUCT_ALLOWANCE_V1";
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesHelHistory history;
    private final ReceivablesHelAccounting accounting;
    private final PlatformSecurityContext security;
    private final EntityManager entityManager;

    public JsonNode register(String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("nativeHelReportingRegistrationRequest", request);
        require(!input.get("scope").has("developerOrganizationId"), "INVALID_DATA");
        var config = configuration.authorize(input.get("scope"), true);
        String scope = configuration.scopeKey(input.get("scope"));
        long product = Long.parseLong(text(input, "helProductId"));
        require(product == number(config, "hel_product_id"), "OWNERSHIP_CONFLICT");
        store.jdbc().queryForList("select id from m_product_loan where id=? for update", product);
        String key = ReceivablesStore.key(scope, "hel-reporting-registration", text(input, "registrationId"));
        var old = store.find("hel_reporting_registration", key);
        if (old != null) {
            require(input.equals(json.read(string(old, "input_json"))), "IDEMPOTENCY_CONFLICT");
            return registrationResult(json.read(string(old, "registration_json")));
        }
        require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_hel_reporting_registration where product_id=? or scope_key=?",
                Long.class, product, scope) == 0, "OWNERSHIP_CONFLICT");
        require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_configuration where hel_product_id=? and scope_key<>?",
                Long.class, product, scope) == 0, "OWNERSHIP_CONFLICT");
        require(store.jdbc().queryForObject(
                "select count(*) from m_mnzl_r_hel_funding f join m_loan l on l.id=f.loan_id " + "where l.product_id=? and f.scope_key<>?",
                Long.class, product, scope) == 0, "OWNERSHIP_CONFLICT");
        ObjectNode registration = json.object();
        registration.put("schemaVersion", "1");
        registration.put("tenantId", configuration.tenantId());
        registration.set("scope", input.get("scope"));
        for (String field : List.of("registrationId", "helProductId", "coverageMode", "initialSnapshotId")) {
            registration.set(field, input.get(field));
        }
        registration.put("registeredBy", security.authenticatedUser().getId().toString());
        registration.put("registeredAt", Instant.now().toString());
        registration.put("registeredBusinessDate", DateUtils.getBusinessLocalDate().toString());
        registration.put("accountingBasis", BASIS);
        registration.put("nativeMappingHash", json.hash(accounting.mapping(product)));
        registration.put("registrationRevisionHash", json.hash(registration));
        store.insert("hel_reporting_registration", key, Map.of("scope_key", scope, "product_id", product, "registration_id",
                text(input, "registrationId"), "registered_business_date", DateUtils.getBusinessLocalDate(), "mapping_hash",
                text(registration, "nativeMappingHash"), "input_json", json.write(input), "registration_json", json.write(registration)));
        var initial = json.object();
        initial.set("scope", input.get("scope"));
        initial.set("registrationId", input.get("registrationId"));
        initial.set("snapshotId", input.get("initialSnapshotId"));
        capture(json.write(initial));
        return registrationResult(registration);
    }

    private JsonNode registrationResult(JsonNode registration) {
        var result = json.object();
        result.set("registration", registration);
        result.set("initialSnapshot", snapshot(registration.get("scope"), text(registration, "initialSnapshotId")));
        return result;
    }

    public JsonNode capture(String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("nativeHelReportingCaptureRequest", request);
        var config = configuration.authorize(input.get("scope"), true);
        String scope = configuration.scopeKey(input.get("scope"));
        String captureKey = ReceivablesStore.key(scope, "hel-reporting-capture", text(input, "snapshotId"));
        var old = store.find("hel_reporting_capture", captureKey);
        if (old != null) {
            require(json.hash(input).equals(string(old, "request_hash")), "IDEMPOTENCY_CONFLICT");
            return json.read(string(old, "capture_json"));
        }
        String registrationKey = ReceivablesStore.key(scope, "hel-reporting-registration", text(input, "registrationId"));
        var registrationRow = store.require("hel_reporting_registration", registrationKey);
        JsonNode registration = json.read(string(registrationRow, "registration_json"));
        require(registration.get("scope").equals(input.get("scope")), "OWNERSHIP_CONFLICT");
        long product = number(registrationRow, "product_id");
        require(product == number(config, "hel_product_id"), "OWNERSHIP_CONFLICT");
        LocalDate date = DateUtils.getBusinessLocalDate();
        require(!date.isBefore(LocalDate.parse(text(registration, "registeredBusinessDate"))), "RECOVERY_REQUIRED");
        String currency = store.jdbc().queryForObject("select currency_code from m_product_loan where id=?", String.class, product);
        require("EGP".equals(currency), "FINERACT_CAPABILITY_MISSING");
        var loanIds = store.jdbc().queryForList("select id from m_loan where product_id=? order by id", Long.class, product);
        var known = store.jdbc().queryForList(
                "select distinct m.loan_id from m_mnzl_r_hel_reporting_member m "
                        + "join m_mnzl_r_hel_reporting_capture c on c.record_key=m.capture_key where c.registration_key=?",
                Long.class, registrationKey);
        require(new HashSet<>(loanIds).containsAll(known), "SOURCE_CHANGED");
        var evidence = accounting.capture(product, date, currency);
        require(json.hash(evidence.mapping()).equals(string(registrationRow, "mapping_hash")), "SOURCE_CHANGED");
        var members = new ArrayList<JsonNode>();
        BigInteger gross = BigInteger.ZERO;
        long helSequence = store.jdbc().queryForObject("select coalesce(max(sequence_id),0) from m_mnzl_r_hel_snapshot where scope_key=?",
                Long.class, scope);
        for (long loanId : loanIds) {
            Loan loan = entityManager.find(Loan.class, loanId);
            require(loan != null && !loan.isPurchasedReceivable() && loan.productId() == product, "OWNERSHIP_CONFLICT");
            ObjectNode state = history.snapshot(loan);
            String stateId = UUID.randomUUID().toString();
            store.insert("hel_snapshot", stateId,
                    Map.of("scope_key", scope, "loan_id", loanId, "business_date", date, "snapshot_json", json.write(state)));
            long sequence = number(store.require("hel_snapshot", stateId), "sequence_id");
            helSequence = Math.max(helSequence, sequence);
            JsonNode measures = evidence.loanMeasures().getOrDefault(loanId, accounting.measures(List.of()));
            var member = json.object();
            member.put("nativeLoanId", Long.toString(loanId));
            member.put("helSnapshotId", stateId);
            member.put("helSnapshotSequence", Long.toString(sequence));
            member.put("nativeStatusId", String.valueOf(loan.getStatus().getValue()));
            member.put("nativeStatusCode", loan.getStatus().name());
            member.put("disbursementState", loan.getActualDisbursementDate() == null ? "UNDISBURSED" : "DISBURSED");
            member.set("loan", state);
            member.set("earningCarrying", measures);
            member.put("contentHash", json.hash(member));
            json.validate("nativeHelReportingMember", json.write(member));
            members.add(member);
            gross = gross.add(new BigInteger(text(measures, "grossEarningCarryingMinor")));
            store.insert("hel_reporting_member", ReceivablesStore.key(scope, "hel-reporting-member", captureKey + ":" + loanId),
                    Map.of("scope_key", scope, "capture_key", captureKey, "loan_id", loanId, "member_json", json.write(member),
                            "content_hash", text(member, "contentHash")));
        }
        for (JsonNode journal : evidence.journals()) {
            String id = text(journal, "nativeJournalId");
            var fields = new LinkedHashMap<String, Object>();
            fields.put("scope_key", scope);
            fields.put("capture_key", captureKey);
            fields.put("native_journal_id", Long.parseLong(id));
            fields.put("posting_date", LocalDate.parse(text(journal, "postingDate")));
            fields.put("journal_json", json.write(journal));
            fields.put("content_hash", text(journal, "contentHash"));
            fields.put("attributed_amount_minor", text(journal, "attributedAmountMinor"));
            fields.put("side", text(journal, "side"));
            fields.put("account_role", text(journal, "accountRole"));
            store.insert("hel_reporting_journal", ReceivablesStore.key(scope, "hel-reporting-journal", captureKey + ":" + id), fields);
        }
        var pool = json.object();
        pool.put("currency", currency);
        pool.put("grossEarningCarryingMinor", gross.toString());
        pool.put("recordedProductAllowanceMinor", evidence.allowance().toString());
        pool.put("netEarningCarryingMinor", gross.subtract(evidence.allowance()).toString());
        pool.put("allowanceBasis",
                evidence.provisionJournalCount() == 0 ? "NO_POSTED_PROVISIONING" : "RECONCILED_NATIVE_PRODUCT_COMPONENTS");
        pool.put("contentHash", json.hash(pool));
        var manifest = json.object();
        manifest.put("schemaVersion", "1");
        manifest.put("tenantId", configuration.tenantId());
        manifest.set("scope", input.get("scope"));
        manifest.set("snapshotId", input.get("snapshotId"));
        for (String field : List.of("registrationId", "registrationRevisionHash", "helProductId", "coverageMode", "accountingBasis",
                "nativeMappingHash", "registeredBusinessDate")) {
            manifest.set(field, registration.get(field));
        }
        manifest.put("observedAt", Instant.now().toString());
        manifest.put("snapshotBusinessDate", date.toString());
        manifest.put("asOfDate", date.toString());
        manifest.put("boundarySide", "AFTER_EVENTS");
        var cut = manifest.putObject("cut");
        cut.put("receivablesEventWatermark", Long.toString(store.jdbc()
                .queryForObject("select coalesce(max(sequence_id),0) from m_mnzl_r_event where scope_key=?", Long.class, scope)));
        cut.put("helSnapshotSequence", Long.toString(helSequence));
        manifest.put("memberCount", Integer.toString(members.size()));
        manifest.put("membershipHash", json.hash(json.value(loanIds.stream().map(Object::toString).toList())));
        manifest.put("memberManifestHash", rowManifest(members, "nativeLoanId"));
        manifest.put("journalCount", Integer.toString(evidence.journals().size()));
        manifest.put("journalManifestHash", rowManifest(evidence.journals(), "nativeJournalId"));
        manifest.set("nativeAccountMapping", evidence.mapping());
        manifest.set("productPool", pool);
        manifest.set("provisionExclusions", evidence.provisionExclusions());
        LocalDate provisionFloor = evidence.provisionHistoryFloor();
        LocalDate registeredDate = LocalDate.parse(text(registration, "registeredBusinessDate"));
        manifest.put("journalHistoryAvailableFromDate",
                provisionFloor != null && provisionFloor.isAfter(registeredDate) ? provisionFloor.toString() : registeredDate.toString());
        manifest.put("contentHash", json.hash(manifest));
        json.validate("nativeHelReportingSnapshot", json.write(manifest));
        store.insert("hel_reporting_capture", captureKey, Map.of("scope_key", scope, "registration_key", registrationKey, "snapshot_id",
                text(input, "snapshotId"), "request_hash", json.hash(input), "capture_json", json.write(manifest)));
        return manifest;
    }

    public String rowManifest(List<JsonNode> rows, String identity) {
        return json.hash(json
                .value(rows.stream().map(row -> Map.of(identity, text(row, identity), "contentHash", text(row, "contentHash"))).toList()));
    }

    @Transactional(readOnly = true)
    public JsonNode snapshot(JsonNode scope, String snapshotId) {
        var row = store.require("hel_reporting_capture",
                ReceivablesStore.key(configuration.scopeKey(scope), "hel-reporting-capture", snapshotId));
        return json.read(string(row, "capture_json"));
    }
}
