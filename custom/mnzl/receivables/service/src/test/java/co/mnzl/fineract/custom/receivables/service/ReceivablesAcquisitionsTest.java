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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.NotFoundException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesAcquisitionsTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final ReceivablesAcquisitions acquisitions = new ReceivablesAcquisitions(store, json);
    private final Map<String, Map<String, Object>> records = new HashMap<>();

    ReceivablesAcquisitionsTest() throws Exception {
        when(store.find(eq("acquisition"), anyString())).thenAnswer(call -> records.get(call.getArgument(1)));
        doAnswer(call -> {
            Map<String, Object> row = new LinkedHashMap<>(call.getArgument(2));
            row.put("record_key", call.getArgument(1));
            records.put(call.getArgument(1), row);
            return null;
        }).when(store).insert(eq("acquisition"), anyString(), anyMap());
    }

    private ReceivablesExecution execution(String scope, String id) {
        ObjectNode command = json.object().put("operationId", "book").put("dealId", "deal").put("businessDate", "2026-09-14");
        command.putObject("scope").put("developerOrganizationId", "developer");
        command.putObject("acquisition").put("id", id).put("developerReferenceId", "developer").put("sourceReferenceId", "offer")
                .put("effectiveDate", "2026-09-14");
        return new ReceivablesExecution(command, scope, Map.of());
    }

    @Test
    void membersShareIdentityOnlyWhenTheClosingAndScopeMatch() {
        String first = acquisitions.register(execution("scope", "first"));
        assertThat(acquisitions.register(execution("scope", "first"))).isEqualTo(first);
        assertThat(acquisitions.register(execution("scope", "second"))).isNotEqualTo(first);
        assertThat(acquisitions.register(execution("other-scope", "first"))).isNotEqualTo(first);
        assertThat(records).hasSize(3);
        assertThat(records.get(first)).containsEntry("effective_date", LocalDate.parse("2026-09-14"));
        assertThatThrownBy(() -> acquisitions.requireAcquisition("unrelated-scope", "first")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void existingClosingCannotChangeItsSourceDeveloperDealOrDate() {
        acquisitions.register(execution("scope", "first"));
        for (String field : new String[] { "sourceReferenceId", "developerReferenceId", "effectiveDate" }) {
            var execution = execution("scope", "first");
            ((ObjectNode) execution.command.get("acquisition")).put(field, field.equals("effectiveDate") ? "2026-09-15" : "different");
            assertThatThrownBy(() -> acquisitions.register(execution)).isInstanceOf(ReceivablesException.class);
        }
        var otherDeal = execution("scope", "first");
        ((ObjectNode) otherDeal.command).put("dealId", "other-deal");
        assertThatThrownBy(() -> acquisitions.register(otherDeal)).isInstanceOf(ReceivablesException.class).hasMessage("SOURCE_CHANGED");
        assertThat(records).hasSize(1);
        assertThat(records.values().iterator().next()).containsEntry("deal_id", "deal").containsEntry("source_ref", "offer");
    }

    @Test
    void pagedMemberBatchesAccumulateAllGroupsWithoutDecimalPrecisionLoss() {
        String first = acquisitions.register(execution("scope", "first"));
        String second = acquisitions.register(execution("scope", "second"));
        var jdbc = mock(JdbcTemplate.class);
        when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(contains("from m_mnzl_r_acquisition"), any(Object[].class)))
                .thenReturn(List.of(records.get(first), records.get(second)));
        String large = "9".repeat(90);
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int index = 0; index < 512; index++) {
            Map<String, Object> member = new HashMap<>();
            member.put("record_key", String.format("%064x", index + 1));
            member.put("acquisition_key", index % 2 == 0 ? first : second);
            member.put("status", "ACTIVE");
            for (String amount : List.of("purchase_face_minor", "purchase_gross_minor", "purchase_fee_minor", "purchase_cash_minor",
                    "face_minor")) {
                member.put(amount, large);
            }
            batch.add(member);
        }
        var replacement = new HashMap<String, Object>(batch.getFirst());
        replacement.put("record_key", String.format("%064x", 513));
        replacement.put("replaces_account_key", batch.getFirst().get("record_key"));
        when(jdbc.queryForList(contains("from m_mnzl_r_account"), any(Object[].class))).thenReturn(batch, List.of(replacement));
        JsonNode result = acquisitions.acquisitions("scope", null, null, null, 2);
        String originals = new BigInteger(large).multiply(BigInteger.valueOf(256)).toString();
        for (JsonNode item : result.path("items")) {
            assertThat(item.path("originalAccountCount").asInt()).isEqualTo(256);
            assertThat(item.path("originalPurchaseCashMinor").asText()).isEqualTo(originals);
            boolean replaced = item.path("id").asText().equals("first");
            assertThat(item.path("replacementAccountCount").asInt()).isEqualTo(replaced ? 1 : 0);
            assertThat(item.path("currentContractualOutstandingMinor").asText())
                    .isEqualTo(new BigInteger(large).multiply(BigInteger.valueOf(replaced ? 257 : 256)).toString());
        }
        verify(jdbc, times(2)).queryForList(contains("from m_mnzl_r_account"), any(Object[].class));
    }

    @Test
    void malformedPageAndUnknownAcquisitionCannotLeakMembers() {
        assertThatThrownBy(() -> acquisitions.accounts("scope", "missing", null, 100)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> acquisitions.acquisitions("scope", null, null, "malformed", 100)).isInstanceOf(ReceivablesException.class)
                .hasMessage("INVALID_DATA");
        assertThatThrownBy(() -> acquisitions.acquisitions("scope", null, null, null, 201)).isInstanceOf(ReceivablesException.class)
                .hasMessage("INVALID_DATA");
    }
}
