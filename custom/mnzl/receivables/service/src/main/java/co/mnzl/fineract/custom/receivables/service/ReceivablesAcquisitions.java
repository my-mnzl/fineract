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
import jakarta.ws.rs.NotFoundException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReceivablesAcquisitions {

    private final ReceivablesStore store;
    private final ReceivablesJson json;

    // Called under the command transaction's scope lock; inserting the group and loan succeeds or rolls back together.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, isolation = Isolation.DEFAULT)
    public String register(ReceivablesExecution e) {
        JsonNode acquisition = e.command.get("acquisition");
        require(acquisition != null && acquisition.isObject(), "INVALID_DATA");
        String id = text(acquisition, "id");
        String developer = text(acquisition, "developerReferenceId");
        String source = text(acquisition, "sourceReferenceId");
        String deal = text(e.command, "dealId");
        LocalDate date = LocalDate.parse(text(acquisition, "effectiveDate"));
        require(date.equals(e.date), "SOURCE_CHANGED");
        require(developer.equals(text(e.command.get("scope"), "developerOrganizationId")), "OWNERSHIP_CONFLICT");
        String key = ReceivablesStore.key(e.scope, "acquisition", id);
        var existing = store.find("acquisition", key);
        if (existing != null) {
            require(deal.equals(string(existing, "deal_id")) && developer.equals(string(existing, "developer_ref"))
                    && source.equals(string(existing, "source_ref")) && date.toString().equals(string(existing, "effective_date")),
                    "SOURCE_CHANGED");
        } else {
            store.insert("acquisition", key, Map.of("scope_key", e.scope, "external_id", id, "deal_id", deal, "developer_ref", developer,
                    "source_ref", source, "effective_date", date, "created_at", java.sql.Timestamp.from(java.time.Instant.now())));
        }
        return key;
    }

    public Map<String, Object> requireAcquisition(String scope, String id) {
        var row = store.find("acquisition", ReceivablesStore.key(scope, "acquisition", id));
        if (row == null) {
            throw new NotFoundException();
        }
        return row;
    }

    public JsonNode acquisition(String scope, String id) {
        var acquisition = requireAcquisition(scope, id);
        return summary(acquisition, totals(List.of(acquisition)).get(string(acquisition, "record_key")));
    }

    public JsonNode acquisitions(String scope, String deal, String developer, String cursor, int limit) {
        validatePage(cursor, limit);
        var args = new ArrayList<Object>(List.of(scope));
        String filter = "";
        if (deal != null) {
            filter += " and deal_id=?";
            args.add(deal);
        }
        if (developer != null) {
            filter += " and developer_ref=?";
            args.add(developer);
        }
        if (cursor != null) {
            filter += " and record_key>?";
            args.add(cursor);
        }
        args.add(limit + 1);
        var rows = store.jdbc().queryForList(
                "select * from m_mnzl_r_acquisition where scope_key=?" + filter + " order by record_key limit ?", args.toArray());
        var totals = totals(rows.stream().limit(limit).toList());
        return page(rows, limit, row -> summary(row, totals.get(string(row, "record_key"))));
    }

    public JsonNode accounts(String scope, String id, String cursor, int limit) {
        validatePage(cursor, limit);
        String key = string(requireAcquisition(scope, id), "record_key");
        var args = new ArrayList<Object>(List.of(key));
        String filter = "";
        if (cursor != null) {
            filter = " and a.record_key>?";
            args.add(cursor);
        }
        args.add(limit + 1);
        var rows = store.jdbc()
                .queryForList("select a.*,p.external_id predecessor_id from m_mnzl_r_account a "
                        + "left join m_mnzl_r_account p on p.record_key=a.replaces_account_key where a.acquisition_key=?" + filter
                        + " order by a.record_key limit ?", args.toArray());
        return page(rows, limit, row -> member(row, id));
    }

    private static final int SUMMARY_BATCH_SIZE = 512;
    private static final Map<String, String> ORIGINAL_AMOUNTS = Map.of("originalFaceMinor", "purchase_face_minor",
            "originalGrossPurchasePriceMinor", "purchase_gross_minor", "originalIntegralFeeMinor", "purchase_fee_minor",
            "originalPurchaseCashMinor", "purchase_cash_minor");

    private Map<String, ObjectNode> totals(List<Map<String, Object>> acquisitions) {
        Map<String, ObjectNode> totals = new LinkedHashMap<>();
        for (var acquisition : acquisitions) {
            ObjectNode total = json.object().put("originalAccountCount", 0L).put("replacementAccountCount", 0L)
                    .put("activeAccountCount", 0L).put("currentContractualOutstandingMinor", "0");
            ORIGINAL_AMOUNTS.keySet().forEach(field -> total.put(field, "0"));
            totals.put(string(acquisition, "record_key"), total);
        }
        if (totals.isEmpty()) {
            return totals;
        }
        String after = "";
        while (true) {
            var args = new ArrayList<Object>(totals.keySet());
            args.add(after);
            args.add(SUMMARY_BATCH_SIZE);
            // A bounded cross-acquisition scan keeps VARCHAR(100) money exact without database DECIMAL limits.
            var members = store.jdbc().queryForList("select record_key,acquisition_key,replaces_account_key,status,purchase_face_minor,"
                    + "purchase_gross_minor,purchase_fee_minor,purchase_cash_minor,face_minor from m_mnzl_r_account where acquisition_key in ("
                    + String.join(",", Collections.nCopies(totals.size(), "?")) + ") and record_key>? order by record_key limit ?",
                    args.toArray());
            for (var member : members) {
                ObjectNode total = totals.get(string(member, "acquisition_key"));
                boolean original = member.get("replaces_account_key") == null;
                String count = original ? "originalAccountCount" : "replacementAccountCount";
                total.put(count, total.path(count).asLong() + 1);
                if ("ACTIVE".equals(string(member, "status"))) {
                    total.put("activeAccountCount", total.path("activeAccountCount").asLong() + 1);
                }
                if (original) {
                    ORIGINAL_AMOUNTS.forEach((field, column) -> add(total, field, string(member, column)));
                }
                add(total, "currentContractualOutstandingMinor", string(member, "face_minor"));
            }
            if (members.size() < SUMMARY_BATCH_SIZE) {
                return totals;
            }
            after = string(members.getLast(), "record_key");
        }
    }

    private static void add(ObjectNode total, String field, String value) {
        total.put(field, new BigInteger(total.path(field).asText()).add(new BigInteger(value)).toString());
    }

    private JsonNode summary(Map<String, Object> acquisition, ObjectNode total) {
        ObjectNode node = json.object();
        node.put("id", string(acquisition, "external_id"));
        node.put("dealId", string(acquisition, "deal_id"));
        node.put("developerReferenceId", string(acquisition, "developer_ref"));
        node.put("sourceReferenceId", string(acquisition, "source_ref"));
        node.put("effectiveDate", string(acquisition, "effective_date"));
        node.setAll(total);
        return node;
    }

    private JsonNode member(Map<String, Object> row, String acquisitionId) {
        ObjectNode node = json.object();
        node.put("accountId", string(row, "external_id"));
        node.put("nativeLoanId", Long.toString(number(row, "native_loan_id")));
        node.put("nativeClientId", Long.toString(number(row, "native_client_id")));
        node.put("status", string(row, "status"));
        node.put("acquisitionId", acquisitionId);
        node.put("replacesAccountId", (String) row.get("predecessor_id"));
        node.put("originalAccountId", string(row, "original_account_id"));
        node.put("activationDate", string(row, "activation_date"));
        node.put("contractualOutstandingMinor", string(row, "face_minor"));
        return node;
    }

    private static void validatePage(String cursor, int limit) {
        require(limit >= 1 && limit <= 200 && (cursor == null || cursor.matches("[0-9a-f]{64}")), "INVALID_DATA");
    }

    private JsonNode page(List<Map<String, Object>> rows, int limit, java.util.function.Function<Map<String, Object>, JsonNode> mapper) {
        ObjectNode result = json.object();
        result.set("items", json.value(rows.stream().limit(limit).map(mapper).toList()));
        result.put("nextCursor", rows.size() > limit ? string(rows.get(limit - 1), "record_key") : null);
        return result;
    }
}
