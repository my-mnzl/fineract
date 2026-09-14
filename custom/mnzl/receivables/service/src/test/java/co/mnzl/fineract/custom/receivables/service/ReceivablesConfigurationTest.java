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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesConfigurationTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final ReceivablesConfiguration configuration = new ReceivablesConfiguration(store, json, security, null, null, null);

    ReceivablesConfigurationTest() throws Exception {}

    @BeforeEach
    void setup() {
        when(store.jdbc()).thenReturn(jdbc);
        when(security.authenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(7L);
    }

    @Test
    void canonicalConfigurationHashIgnoresMappingOrderButNotBuildOrPhysicalMapping() {
        var first = json.read(
                "{\"calculatorBuild\":\"build-1\",\"accountMap\":[{\"accountKey\":\"bank\",\"nativeGlAccountId\":\"1\"},{\"accountKey\":\"acquisitionClearing\",\"nativeGlAccountId\":\"2\"}]}");
        var second = json.read(
                "{\"accountMap\":[{\"nativeGlAccountId\":\"2\",\"accountKey\":\"acquisitionClearing\"},{\"nativeGlAccountId\":\"1\",\"accountKey\":\"bank\"}],\"calculatorBuild\":\"build-1\"}");
        assertThat(json.hash(configuration.normalizeConfiguration(first)))
                .isEqualTo(json.hash(configuration.normalizeConfiguration(second)));
        var changed = configuration.normalizeConfiguration(first);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("calculatorBuild", "build-2");
        assertThat(json.hash(changed)).isNotEqualTo(json.hash(configuration.normalizeConfiguration(first)));
    }

    @Test
    void exactActivationReplayDoesNotRevalidateOrRevertTheCurrentRevision() {
        var scope = json.object().put("platformId", "mnzl").put("financierOrganizationId", "financier").put("environment", "test");
        String key = configuration.scopeKey(scope);
        var request = json.object().put("activationId", "deployment-1").put("accountMappingRevisionId", "second")
                .put("contentHash", "a".repeat(64)).put("expectedActiveAccountMappingRevisionId", "first");
        when(store.lockConfiguration(key)).thenReturn(Map.of("mapping_revision", "third"));
        var result = json.object().put("accountMappingRevisionId", "second");
        when(store.find(eq("configuration_activation"), anyString()))
                .thenReturn(Map.of("request_hash", json.hash(request), "result_json", json.write(result)));
        assertThat(configuration.activate(scope, json.write(request))).isEqualTo(result);
        verify(store, never()).update(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap());
        request.put("accountMappingRevisionId", "fourth");
        assertThatThrownBy(() -> configuration.activate(scope, json.write(request))).isInstanceOf(ReceivablesException.class)
                .hasMessage("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void historicalReadAuthorizesCurrentPrincipalAndReturnsImmutableSnapshot() {
        var scope = json.object().put("platformId", "mnzl").put("financierOrganizationId", "financier").put("environment", "test");
        String key = configuration.scopeKey(scope);
        when(store.require("configuration", key)).thenReturn(Map.of("integration_user_id", 7L, "mapping_revision", "current"));
        var historical = json.object().put("integrationUserId", "4").put("accountMappingRevisionId", "previous");
        historical.putArray("accountMap");
        when(jdbc.queryForList(anyString(), eq(key), eq("previous"))).thenReturn(List.of(Map.of("config_json", json.write(historical))));
        assertThat(configuration.readConfiguration(scope, "previous")).isEqualTo(historical);
        verify(user).validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        when(user.getId()).thenReturn(4L);
        configuration.readConfiguration(scope, "previous");
        verify(user).validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
    }

    @Test
    void historicalAcknowledgmentIsPermissionProtectedAndBoundToOperatorAndSource() {
        var command = json.object().put("actorId", "employee").put("basisHash", "basis");
        command.putObject("basis").put("sourceHash", "source");
        command.putObject("acknowledgment").put("reason", "Recorded source facts").put("actorId", "employee").put("basisHash", "basis")
                .put("sourceHash", "source");
        configuration.validateHistoricalOperator(command);
        verify(user).validateHasPermissionTo("RECORD_HISTORICAL_MNZL_RECEIVABLES");
        command.putObject("acknowledgment").put("reason", "Recorded source facts").put("actorId", "other-employee")
                .put("basisHash", "basis").put("sourceHash", "source");
        assertThatThrownBy(() -> configuration.validateHistoricalOperator(command)).isInstanceOf(ReceivablesException.class)
                .hasMessage("APPROVAL_SCOPE_CHANGED");
    }

}
