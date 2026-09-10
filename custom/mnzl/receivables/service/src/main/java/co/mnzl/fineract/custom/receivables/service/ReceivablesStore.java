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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReceivablesStore {

    private final JdbcTemplate jdbc;
    private static final Set<String> TABLES = Set.of("configuration", "account_map", "account", "leg", "segment", "segment_leg", "command",
            "event", "journal_line", "cash_source", "cash_allocation", "collection", "developer_lot", "lot_allocation", "funding_facility",
            "funding_event", "risk_forecast", "period", "authorization", "hel_funding", "state_snapshot");

    private static String table(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Unknown receivables table");
        }
        return "m_mnzl_r_" + table;
    }

    private static void columns(Set<String> columns) {
        for (String col : columns) {
            if (!col.matches("[a-z_]+")) {
                throw new IllegalArgumentException("Invalid column");
            }
        }
    }

    public static String key(String scope, String kind, String id) {
        return ReceivablesJson.hashText(scope + "\n" + kind + "\n" + id);
    }

    public void insert(String table, String key, Map<String, ?> fields) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("record_key", key);
        all.putAll(fields);
        columns(all.keySet());
        jdbc.update("insert into " + table(table) + " (" + String.join(",", all.keySet()) + ") values ("
                + all.keySet().stream().map(k -> "?").collect(Collectors.joining(",")) + ")", all.values().toArray());
    }

    public void update(String table, String key, Map<String, ?> fields) {
        columns(fields.keySet());
        List<Object> values = new ArrayList<>(fields.values());
        values.add(key);
        int changed = jdbc.update("update " + table(table) + " set "
                + fields.keySet().stream().map(k -> k + "=?").collect(Collectors.joining(",")) + " where record_key=?", values.toArray());
        ReceivablesException.require(changed == 1, "ACCOUNT_VERSION_CHANGED");
    }

    public Map<String, Object> find(String table, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table(table) + " where record_key=?", key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> require(String table, String key) {
        Map<String, Object> row = find(table, key);
        ReceivablesException.require(row != null, "RECOVERY_REQUIRED");
        return row;
    }

    public Map<String, Object> lockConfiguration(String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from m_mnzl_r_configuration where record_key=? for update", scope);
        ReceivablesException.require(rows.size() == 1, "FINERACT_CAPABILITY_MISSING");
        return rows.getFirst();
    }

    public List<Map<String, Object>> scoped(String table, String scope) {
        return jdbc.queryForList("select * from " + table(table) + " where scope_key=? order by record_key", scope);
    }

    public List<Map<String, Object>> children(String table, String parentColumn, String key) {
        columns(Set.of(parentColumn));
        return jdbc.queryForList("select * from " + table(table) + " where " + parentColumn + "=? order by record_key", key);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static String string(Map<String, Object> row, String col) {
        return String.valueOf(row.get(col));
    }

    public static long number(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }
}
