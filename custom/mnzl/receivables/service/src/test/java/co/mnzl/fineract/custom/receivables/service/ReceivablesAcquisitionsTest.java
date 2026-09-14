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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
    void malformedPageAndUnknownAcquisitionCannotLeakMembers() {
        assertThatThrownBy(() -> acquisitions.accounts("scope", "missing", null, 100)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> acquisitions.acquisitions("scope", null, null, "malformed", 100)).isInstanceOf(ReceivablesException.class)
                .hasMessage("INVALID_DATA");
        assertThatThrownBy(() -> acquisitions.acquisitions("scope", null, null, null, 201)).isInstanceOf(ReceivablesException.class)
                .hasMessage("INVALID_DATA");
    }
}
