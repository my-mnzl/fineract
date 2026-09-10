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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesReadServiceTest {

    @Test
    void snapshotsSelectLatestVersionAtCutoffBeforePaging() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(ReceivablesStore.class);
        var config = mock(ReceivablesConfiguration.class);
        var json = new ReceivablesJson();
        var scope = json.object();
        when(store.jdbc()).thenReturn(jdbc);
        when(config.scopeKey(scope)).thenReturn("scope");
        var date = LocalDate.of(2026, 9, 1);
        when(jdbc.queryForList(anyString(), eq("scope"), eq("LOT"), eq(7L), eq(date))).thenReturn(List.of(
                Map.of("subject_key", "a", "record_key", "a1", "created_at", "2026-08-01T00:00:00Z", "snapshot_json",
                        "{\"lotId\":\"a\",\"settledMinor\":\"0\"}"),
                Map.of("subject_key", "b", "record_key", "b1", "created_at", "2026-08-02T00:00:00Z", "snapshot_json", "{\"lotId\":\"b\"}"),
                Map.of("subject_key", "a", "record_key", "a2", "created_at", "2026-08-03T00:00:00Z", "snapshot_json",
                        "{\"lotId\":\"a\",\"settledMinor\":\"12\"}")));
        var reads = new ReceivablesReadService(store, json, config, null, null, null, null);
        var boundary = new ReceivablesReadService.Boundary(date, "BEFORE_EVENTS", 7);
        var first = reads.snapshots(scope, boundary, "LOT", null, 1);
        assertThat(first.path("items").get(0).path("lotId").asText()).isEqualTo("b");
        var second = reads.snapshots(scope, boundary, "LOT", first.path("nextCursor").asText(), 1);
        assertThat(second.path("items").get(0).path("settledMinor").asText()).isEqualTo("12");
        assertThat(second.path("nextCursor").isNull()).isTrue();
        verify(jdbc, times(2)).queryForList(contains("s.business_date<?"), eq("scope"), eq("LOT"), eq(7L), eq(date));
        assertThatThrownBy(() -> reads.snapshots(scope, boundary, "LOT", "!", 1)).isInstanceOf(ReceivablesException.class);
    }

    @Test
    void journalsUseActualLedgerAmountsAndAccountIds() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(ReceivablesStore.class);
        var config = mock(ReceivablesConfiguration.class);
        var json = new ReceivablesJson();
        var scope = json.object();
        when(store.jdbc()).thenReturn(jdbc);
        when(config.scopeKey(scope)).thenReturn("scope");
        var row = new java.util.HashMap<String, Object>();
        row.put("actual_id", 91L);
        row.put("actual_gl", 42L);
        row.put("actual_side", 2);
        row.put("actual_amount", "1.23");
        row.put("amount_minor", "999");
        row.put("source_line_id", "source");
        row.put("semantic_account", "cash");
        row.put("currency_code", "EGP");
        row.put("operation_id", "op");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        var reads = new ReceivablesReadService(store, json, config, null, null, null, null);
        var line = reads.journals(scope, List.of("op"), 7).path("lines").get(0);
        assertThat(line.path("amountMinor").asText()).isEqualTo("123");
        assertThat(line.path("nativeGlAccountId").asText()).isEqualTo("42");
        assertThat(line.path("side").asText()).isEqualTo("DEBIT");
    }
}
