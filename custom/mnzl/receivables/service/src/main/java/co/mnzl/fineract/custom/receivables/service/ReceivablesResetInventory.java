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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

/** A fixed deletion surface. Unknown dependent rows fail closed, including ordinary HEL artifacts. */
@Component
@RequiredArgsConstructor
public class ReceivablesResetInventory {

    static final List<String> SCOPED = List.of("journal_line", "collection", "lot_allocation", "segment_leg", "leg", "segment",
            "developer_lot", "risk_forecast", "state_snapshot", "cash_allocation", "cash_source", "funding_event", "funding_facility",
            "hel_snapshot", "hel_funding", "event", "command", "period", "authorization", "account");
    static final List<String> NATIVE = List.of("m_loan_transaction_repayment_schedule_mapping", "m_loan_arrears_aging",
            "m_loan_paid_in_advance", "acc_gl_journal_entry", "m_loan_transaction", "m_loan_repayment_schedule", "m_loan");
    private final ReceivablesStore store;
    private final ReceivablesJson json;

    record Rows(String table, String primaryKey, List<Map<String, Object>> values) {

        Set<String> ids() {
            Set<String> result = new TreeSet<>();
            values.forEach(row -> result.add(string(row, primaryKey)));
            return result;
        }
    }

    List<Rows> collect(String scope, Set<String> loans, Set<String> journals) {
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        NATIVE.forEach(table -> selected.put(table, new TreeSet<>()));
        selected.get("m_loan").addAll(loans);
        selected.get("acc_gl_journal_entry").addAll(journals);
        store.jdbc().execute((ConnectionCallback<Void>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            boolean changed;
            do {
                changed = false;
                for (String parent : NATIVE) {
                    if (selected.get(parent).isEmpty()) continue;
                    try (var keys = metadata.getExportedKeys(catalog, schema, parent)) {
                        while (keys.next()) {
                            String child = identifier(keys.getString("FKTABLE_NAME"));
                            String column = identifier(keys.getString("FKCOLUMN_NAME"));
                            require(primaryKey(parent).equals(keys.getString("PKCOLUMN_NAME")) && keys.getShort("KEY_SEQ") == 1,
                                    "OWNERSHIP_CONFLICT");
                            List<Map<String, Object>> children = matching(child, column, selected.get(parent));
                            if (children.isEmpty()) continue;
                            require(NATIVE.contains(child), "OWNERSHIP_CONFLICT");
                            Set<String> ids = new Rows(child, primaryKey(child), children).ids();
                            if (child.equals("m_loan") || child.equals("acc_gl_journal_entry")) {
                                require(selected.get(child).containsAll(ids), "OWNERSHIP_CONFLICT");
                            } else {
                                changed |= selected.get(child).addAll(ids);
                            }
                        }
                    }
                }
            } while (changed);
            for (String table : NATIVE) {
                if (selected.get(table).isEmpty()) continue;
                try (var keys = metadata.getImportedKeys(catalog, schema, table)) {
                    while (keys.next()) {
                        String parent = keys.getString("PKTABLE_NAME");
                        if (!NATIVE.contains(parent)) continue;
                        String column = identifier(keys.getString("FKCOLUMN_NAME"));
                        for (var row : matching(table, primaryKey(table), selected.get(table))) {
                            if (row.get(column) != null) require(selected.get(parent).contains(string(row, column)), "OWNERSHIP_CONFLICT");
                        }
                    }
                }
            }
            return null;
        });
        List<Rows> rows = new ArrayList<>();
        for (String table : SCOPED)
            rows.add(new Rows("m_mnzl_r_" + table, "record_key", store.scoped(table, scope)));
        for (String table : NATIVE)
            rows.add(new Rows(table, primaryKey(table), matching(table, primaryKey(table), selected.get(table))));
        return rows;
    }

    ArrayNode manifest(List<Rows> tables) {
        ArrayNode result = json.object().putArray("rows");
        for (Rows rows : tables) {
            var item = result.addObject();
            item.put("table", rows.table());
            item.set("ids", json.value(rows.ids()));
            // Preserve exact DECIMAL/date values when hashing JDBC snapshots, without publishing row contents.
            List<Map<String, String>> content = rows.values().stream().map(row -> {
                Map<String, String> normalized = new TreeMap<>();
                row.forEach((key, value) -> normalized.put(key, value == null ? null : value.toString()));
                return normalized;
            }).toList();
            item.put("contentHash", json.hash(json.value(content)));
        }
        return result;
    }

    ArrayNode remove(List<Rows> tables) {
        ArrayNode counts = json.object().putArray("counts");
        for (Rows rows : tables) {
            int count = 0;
            for (String id : rows.ids())
                count += store.jdbc().update("delete from " + rows.table() + " where " + rows.primaryKey() + "=?", id);
            require(count == rows.values().size(), "SOURCE_CHANGED");
            counts.addObject().put("table", rows.table()).put("count", count);
        }
        return counts;
    }

    private List<Map<String, Object>> matching(String table, String column, Set<String> ids) {
        if (ids.isEmpty()) return List.of();
        // Bounded parameters avoid backend-specific array syntax and unbounded SQL parameter lists.
        Map<String, Map<String, Object>> found = new TreeMap<>();
        for (String id : ids) {
            for (var row : store.jdbc().queryForList("select * from " + identifier(table) + " where " + identifier(column) + "=?", id)) {
                found.put(row.toString(), row);
            }
        }
        return new ArrayList<>(found.values());
    }

    private static String primaryKey(String table) {
        return table.equals("m_loan_arrears_aging") || table.equals("m_loan_paid_in_advance") ? "loan_id" : "id";
    }

    private static String identifier(String name) {
        require(name != null && name.matches("[a-z_]+"), "OWNERSHIP_CONFLICT");
        return name;
    }
}
