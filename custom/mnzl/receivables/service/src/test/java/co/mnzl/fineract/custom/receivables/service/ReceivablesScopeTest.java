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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesScopeTest {

    private static final String QUERY = "select scope_json from m_mnzl_r_configuration";

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReceivablesConfiguration configuration = new ReceivablesConfiguration(store, json, null, null, null, null);
    private final ObjectNode configured = json.object().put("platformId", "mnzl").put("financierOrganizationId", "mnzl-financier")
            .put("environment", "production");

    ReceivablesScopeTest() throws Exception {}

    @BeforeEach
    void setup() {
        when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(QUERY)).thenReturn(List.of(Map.of("scope_json", json.write(configured))));
    }

    @Test
    void omittedScopeResolvesToTheOnlyConfiguration() {
        assertThat(configuration.resolveScope(null)).isEqualTo(configured);
        assertThat(configuration.resolveScope(json.object())).isEqualTo(configured);
        assertThat(configuration.scopeKey(configuration.resolveScope(json.object()))).isEqualTo(configuration.scopeKey(configured));
    }

    @Test
    void partialScopeMustMatchTheConfiguration() {
        assertThat(configuration.resolveScope(json.object().put("environment", "production"))).isEqualTo(configured);
        assertThatThrownBy(() -> configuration.resolveScope(json.object().put("financierOrganizationId", "other")))
                .isInstanceOf(ReceivablesException.class).hasMessageContaining("FINERACT_CAPABILITY_MISSING");
    }

    @Test
    void fullScopeKeepsTheOriginalBehaviourWithoutReadingConfiguration() {
        var other = json.object().put("platformId", "mnzl").put("financierOrganizationId", "other").put("environment", "test");
        assertThat(configuration.resolveScope(other)).isEqualTo(other);
        verifyNoInteractions(jdbc);
    }

    @Test
    void omittedScopeNeedsExactlyOneConfiguration() {
        for (var rows : List.of(List.<Map<String, Object>>of(),
                List.<Map<String, Object>>of(Map.of("scope_json", json.write(configured)), Map.of("scope_json", json.write(configured))))) {
            when(jdbc.queryForList(QUERY)).thenReturn(rows);
            assertThatThrownBy(() -> configuration.resolveScope(json.object())).isInstanceOf(ReceivablesException.class)
                    .hasMessageContaining("FINERACT_CAPABILITY_MISSING");
        }
    }

    @Test
    void requestScopeIsCompletedAndKeepsTheDeveloper() {
        var command = json.object().put("operationId", "op-1");
        command.set("scope", json.object().put("developerOrganizationId", "developer"));
        var replacement = json.object().put("operationId", "op-1:replacement");
        command.set("replacementCommand", replacement);
        var completed = configuration.withScope(command);
        assertThat(completed.get("scope")).isEqualTo(configured.deepCopy().put("developerOrganizationId", "developer"));
        assertThat(completed.get("replacementCommand").get("scope")).isEqualTo(configured);
        assertThat(command.get("scope").size()).isEqualTo(1);
        assertThat(command.get("replacementCommand").has("scope")).isFalse();
        var unscoped = json.object().put("operationId", "op-2");
        assertThat(configuration.withScope(unscoped).get("scope")).isEqualTo(configured);
        var invalid = json.object().put("scope", "mnzl");
        assertThat(configuration.withScope(invalid)).isEqualTo(invalid);
    }

    @Test
    void sentScopeIsUnchangedByCompletion() {
        var command = json.object().put("operationId", "op-1");
        command.set("scope", configured.deepCopy().put("developerOrganizationId", "developer"));
        assertThat(json.hash(configuration.withScope(command))).isEqualTo(json.hash(command));
    }
}
