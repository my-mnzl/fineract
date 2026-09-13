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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReceivablesHelReportingReadService {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesHelReporting reporting;

    public JsonNode members(JsonNode scope, String snapshotId, String cursor, int limit) {
        JsonNode snapshot = reporting.snapshot(scope, snapshotId);
        var selection = selection(snapshot, "MEMBERS");
        String hash = json.hash(selection);
        long after = after(cursor, hash);
        require(limit >= 1 && limit <= 200, "INVALID_DATA");
        String key = ReceivablesStore.key(configuration.scopeKey(scope), "hel-reporting-capture", snapshotId);
        var rows = store.jdbc().queryForList("select loan_id,member_json from m_mnzl_r_hel_reporting_member "
                + "where capture_key=? and loan_id>? order by loan_id limit ?", key, after, limit + 1);
        ObjectNode result = page(snapshot, selection, hash, rows, "loan_id", "member_json", limit);
        result.set("itemCount", snapshot.get("memberCount"));
        result.set("itemManifestHash", snapshot.get("memberManifestHash"));
        json.validate("nativeHelReportingMembersPage", json.write(result));
        return result;
    }

    public JsonNode journals(JsonNode scope, String snapshotId, LocalDate start, LocalDate through, String cursor, int limit) {
        JsonNode snapshot = reporting.snapshot(scope, snapshotId);
        require(start != null && through != null && !through.isBefore(start)
                && !start.isBefore(LocalDate.parse(text(snapshot, "journalHistoryAvailableFromDate")))
                && !through.isAfter(LocalDate.parse(text(snapshot, "snapshotBusinessDate"))), "RECOVERY_REQUIRED");
        require(limit >= 1 && limit <= 200, "INVALID_DATA");
        String key = ReceivablesStore.key(configuration.scopeKey(scope), "hel-reporting-capture", snapshotId);
        var selection = selection(snapshot, "PERIOD_JOURNALS");
        selection.put("periodStartDate", start.toString());
        selection.put("throughDate", through.toString());
        String hash = json.hash(selection);
        long after = after(cursor, hash);
        var commitments = store.jdbc().queryForList("select native_journal_id,content_hash,attributed_amount_minor,side,account_role "
                + "from m_mnzl_r_hel_reporting_journal where capture_key=? and posting_date>=? and posting_date<=? order by native_journal_id",
                key, start, through);
        var manifestRows = json.array();
        BigInteger debit = BigInteger.ZERO;
        BigInteger credit = BigInteger.ZERO;
        BigInteger interest = BigInteger.ZERO;
        BigInteger provisionExpense = BigInteger.ZERO;
        for (var row : commitments) {
            manifestRows.add(json.value(Map.of("nativeJournalId", Long.toString(number(row, "native_journal_id")), "contentHash",
                    string(row, "content_hash"))));
            BigInteger amount = new BigInteger(string(row, "attributed_amount_minor"));
            boolean isDebit = string(row, "side").equals("DEBIT");
            debit = debit.add(isDebit ? amount : BigInteger.ZERO);
            credit = credit.add(isDebit ? BigInteger.ZERO : amount);
            if (string(row, "account_role").equals("INTEREST_INCOME")) {
                interest = interest.add(isDebit ? amount.negate() : amount);
            }
            if (string(row, "account_role").equals("PROVISION_EXPENSE")) {
                provisionExpense = provisionExpense.add(isDebit ? amount : amount.negate());
            }
        }
        require(debit.equals(credit), "JOURNAL_MISMATCH");
        var rows = store.jdbc().queryForList("select native_journal_id,journal_json from m_mnzl_r_hel_reporting_journal "
                + "where capture_key=? and posting_date>=? and posting_date<=? and native_journal_id>? order by native_journal_id limit ?",
                key, start, through, after, limit + 1);
        ObjectNode result = page(snapshot, selection, hash, rows, "native_journal_id", "journal_json", limit);
        result.put("itemCount", Integer.toString(commitments.size()));
        result.put("itemManifestHash", json.hash(manifestRows));
        result.put("grossAttributedDebitMinor", debit.toString());
        result.put("grossAttributedCreditMinor", credit.toString());
        result.put("ordinaryHelInterestIncomeMinor", interest.toString());
        result.put("ordinaryHelProvisionExpenseMinor", provisionExpense.toString());
        json.validate("nativeHelReportingJournalsPage", json.write(result));
        return result;
    }

    private ObjectNode selection(JsonNode snapshot, String resource) {
        var result = json.object();
        result.set("scope", snapshot.get("scope"));
        result.set("tenantId", snapshot.get("tenantId"));
        result.set("snapshotId", snapshot.get("snapshotId"));
        result.set("snapshotContentHash", snapshot.get("contentHash"));
        result.set("registrationRevisionHash", snapshot.get("registrationRevisionHash"));
        result.set("accountingBasis", snapshot.get("accountingBasis"));
        result.set("cut", snapshot.get("cut"));
        result.put("resource", resource);
        return result;
    }

    private ObjectNode page(JsonNode snapshot, ObjectNode selection, String hash, List<Map<String, Object>> rows, String id, String payload,
            int limit) {
        var result = json.object();
        result.put("schemaVersion", "1");
        result.set("selection", selection);
        result.put("selectionHash", hash);
        result.set("snapshot", snapshot);
        result.set("items", json.value(rows.stream().limit(limit).map(row -> json.read(string(row, payload))).toList()));
        if (rows.size() > limit) {
            var next = json.value(Map.of("selectionHash", hash, "after", Long.toString(number(rows.get(limit - 1), id))));
            result.put("nextCursor",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(json.write(next).getBytes(StandardCharsets.UTF_8)));
        } else {
            result.putNull("nextCursor");
        }
        return result;
    }

    @SuppressWarnings("AvoidHidingCauseException")
    private long after(String cursor, String selectionHash) {
        if (cursor == null) {
            return 0;
        }
        try {
            JsonNode value = json.read(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            require(selectionHash.equals(text(value, "selectionHash")), "SOURCE_CHANGED");
            long after = Long.parseLong(text(value, "after"));
            require(after >= 0, "INVALID_DATA");
            return after;
        } catch (IllegalArgumentException e) {
            var failure = new ReceivablesException("INVALID_DATA");
            failure.initCause(e);
            throw failure;
        }
    }
}
