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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesResetInventoryTest {

    @Test
    void unknownDependentRowsFailClosedInsteadOfFollowingCascade() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(ReceivablesStore.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var keys = mock(ResultSet.class);
        when(store.jdbc()).thenReturn(jdbc);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getExportedKeys(null, null, "m_loan")).thenReturn(keys);
        when(keys.next()).thenReturn(true, false);
        when(keys.getString("FKTABLE_NAME")).thenReturn("m_note");
        when(keys.getString("FKCOLUMN_NAME")).thenReturn("loan_id");
        when(keys.getString("PKCOLUMN_NAME")).thenReturn("id");
        when(keys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(jdbc.queryForList("select * from m_note where loan_id=?", "7")).thenReturn(List.of(Map.of("id", 9L, "loan_id", 7L)));
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Void>>any())).thenAnswer(call -> {
            ConnectionCallback<Void> callback = call.getArgument(0);
            return callback.doInConnection(connection);
        });
        var inventory = new ReceivablesResetInventory(store, new ReceivablesJson());
        assertThatThrownBy(() -> inventory.collect("scope", Set.of("7"), Set.of())).hasMessage("OWNERSHIP_CONFLICT");
    }

    @Test
    void sameRowIdsWithChangedAmountsProduceDifferentPlanEvidence() throws Exception {
        var inventory = new ReceivablesResetInventory(mock(ReceivablesStore.class), new ReceivablesJson());
        var original = new ReceivablesResetInventory.Rows("m_loan", "id", List.of(Map.of("id", 1L, "amount", "100.000001")));
        var changed = new ReceivablesResetInventory.Rows("m_loan", "id", List.of(Map.of("id", 1L, "amount", "100.000002")));
        var before = inventory.manifest(List.of(original)).get(0);
        var after = inventory.manifest(List.of(changed)).get(0);
        assertThat(before.path("ids")).isEqualTo(after.path("ids"));
        assertThat(before.path("contentHash")).isNotEqualTo(after.path("contentHash"));
    }
}
