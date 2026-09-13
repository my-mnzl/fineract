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

import co.mnzl.fineract.receivables.math.ReceivableEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Account-scoped immutable developer statements with SQL-bounded, selection-bound pages. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReceivablesDeveloperReadService {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;

    public JsonNode page(JsonNode scope, String accountId, ReceivablesReadService.Boundary boundary, String cursor, int limit,
            boolean allocations) {
        require(limit >= 1 && limit <= 200, "INVALID_DATA");
        String scopeKey = configuration.scopeKey(scope);
        String accountKey = ReceivablesStore.key(scopeKey, "account", accountId);
        var account = store.require("account", accountKey);
        require(scopeKey.equals(string(account, "scope_key")), "OWNERSHIP_CONFLICT");
        ObjectNode selection = json.object();
        selection.set("scope", scope);
        selection.put("accountId", accountId);
        selection.put("businessDate", boundary.date().toString());
        selection.put("boundarySide", boundary.side());
        selection.put("eventWatermark", Long.toString(boundary.watermark()));
        selection.put("resource", allocations ? "DEVELOPER_LOT_ALLOCATIONS" : "DEVELOPER_LOTS");
        String selectionHash = json.hash(selection);
        String after = "";
        if (cursor != null) {
            JsonNode decoded = decode(cursor);
            require(selectionHash.equals(text(decoded, "selectionHash")), "SOURCE_CHANGED");
            after = text(decoded, "after");
        }
        List<Object> args = new ArrayList<>(
                List.of(scopeKey, accountKey, boundary.watermark(), boundary.date(), boundary.date(), after, limit + 1));
        String query;
        if (allocations) {
            query = "select a.*,l.lot_id,c.operation_id,e.event_id,e.sequence_id,e.business_date from m_mnzl_r_lot_allocation a "
                    + "join m_mnzl_r_developer_lot l on l.record_key=a.lot_key join m_mnzl_r_command c on c.record_key=a.operation_key "
                    + "join m_mnzl_r_event e on e.operation_key=a.operation_key where a.scope_key=? and a.account_key=? "
                    + "and e.sequence_id<=? and " + boundary.eventCutoff() + " and a.record_key>? order by a.record_key limit ?";
        } else {
            query = "select * from (select l.record_key,l.lot_id,l.snapshot_json original_snapshot,s.snapshot_json,s.account_key, "
                    + "row_number() over (partition by s.subject_key order by e.sequence_id desc) latest_rank "
                    + "from m_mnzl_r_state_snapshot s join m_mnzl_r_developer_lot l on l.record_key=s.subject_key "
                    + "join m_mnzl_r_event e on e.record_key=s.event_key where s.scope_key=? and exists (select 1 from m_mnzl_r_state_snapshot own "
                    + "where own.subject_key=s.subject_key and own.account_key=?) " + "and s.subject_kind='LOT' and e.sequence_id<=? and "
                    + boundary.eventCutoff()
                    + ") latest where latest_rank=1 and account_key=? and record_key>? order by record_key limit ?";
        }
        if (!allocations) {
            args.add(5, accountKey);
        }
        var rows = store.jdbc().queryForList(query, args.toArray());
        var items = new ArrayList<JsonNode>();
        for (var row : rows.stream().limit(limit).toList()) {
            ObjectNode value;
            if (allocations) {
                value = json.object();
                value.put("allocationId", string(row, "record_key"));
                value.put("lotId", string(row, "lot_id"));
                value.put("accountId", accountId);
                value.put("operationId", string(row, "operation_id"));
                value.put("eventId", string(row, "event_id"));
                value.put("eventSequence", Long.toString(number(row, "sequence_id")));
                value.put("businessDate", string(row, "business_date"));
                value.put("amountMinor", string(row, "amount_minor"));
                value.put("method", string(row, "method"));
                value.put("evidenceRef", string(row, "evidence_ref"));
                value.put("cashMovementId", string(row, "method").equals("CASH") ? string(row, "evidence_ref") : null);
                value.put("contentHash", json.hash(value));
            } else {
                value = (ObjectNode) json.read(string(row, "snapshot_json"));
                var lot = json.convert(json.read(string(row, "original_snapshot")), ReceivableEvents.AdjustmentLot.class);
                BigInteger carrying = lot.balanceMinor(boundary.date());
                value.put("outstandingMinor", carrying.subtract(new BigInteger(text(value, "settledMinor"))).toString());
                value.put("accruedUnwindMinor", carrying.subtract(new BigInteger(text(value, "originalAmountMinor"))).toString());
            }
            items.add(value);
        }
        ObjectNode result = selection.deepCopy();
        result.remove("resource");
        result.put("selectionHash", selectionHash);
        result.set("items", json.value(items));
        if (rows.size() > limit) {
            var next = json.value(Map.of("selectionHash", selectionHash, "after", string(rows.get(limit - 1), "record_key")));
            result.put("nextCursor",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(json.write(next).getBytes(StandardCharsets.UTF_8)));
        } else {
            result.putNull("nextCursor");
        }
        return result;
    }

    @SuppressWarnings("AvoidHidingCauseException")
    private JsonNode decode(String cursor) {
        try {
            return json.read(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            var failure = new ReceivablesException("INVALID_DATA");
            failure.initCause(e);
            throw failure;
        }
    }
}
