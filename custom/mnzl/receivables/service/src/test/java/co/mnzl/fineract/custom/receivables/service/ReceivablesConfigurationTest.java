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

    private com.fasterxml.jackson.databind.node.ObjectNode configurationOf(java.util.Set<String> keys, long deferredFeeGl) {
        var config = json.object().put("accountMappingRevisionId", "map");
        var map = config.putArray("accountMap");
        long gl = 100;
        for (String key : keys.stream().sorted().toList()) {
            map.addObject().put("accountKey", key).put("nativeGlAccountId",
                    Long.toString(key.equals("deferredAdminFee") || key.equals("deferredIntegralFee") ? deferredFeeGl : gl++));
        }
        return config;
    }

    @Test
    void revisionsAreValidUnderExactlyOneAccountSetAndReadUnderCurrentKeys() {
        var legacy = ReceivablesConfiguration.accountMap(configurationOf(ReceivablesConfiguration.LEGACY_ACCOUNTS, 7));
        var current = ReceivablesConfiguration.current(legacy);
        assertThat(current).containsEntry("deferredAdminFee", 7L).doesNotContainKey("deferredIntegralFee")
                .doesNotContainKey(ReceivablesConfiguration.ADMIN_FEE_INCOME).hasSize(22);
        assertThat(ReceivablesConfiguration.calculations(current)).containsExactly(
                co.mnzl.fineract.receivables.math.ReceivablesMath.CALCULATION_VERSION,
                co.mnzl.fineract.receivables.math.ReceivablesMath.SIMPLE_CALCULATION_VERSION);
        var adminFee = ReceivablesConfiguration.accountMap(configurationOf(ReceivablesConfiguration.ACCOUNTS, 7));
        assertThat(ReceivablesConfiguration.current(adminFee)).isEqualTo(adminFee).hasSize(23);
        assertThat(ReceivablesConfiguration.calculations(adminFee)).isEqualTo(ReceivablesConfiguration.CALCULATIONS);
        var mixed = new java.util.HashSet<>(ReceivablesConfiguration.LEGACY_ACCOUNTS);
        mixed.add(ReceivablesConfiguration.ADMIN_FEE_INCOME);
        var partial = new java.util.HashSet<>(ReceivablesConfiguration.ACCOUNTS);
        partial.remove(ReceivablesConfiguration.ADMIN_FEE_INCOME);
        for (var keys : List.of(mixed, partial)) {
            assertThatThrownBy(() -> ReceivablesConfiguration.accountMap(configurationOf(keys, 7))).isInstanceOf(ReceivablesException.class)
                    .hasMessage("FINERACT_CAPABILITY_MISSING");
        }
    }

    @Test
    void aUsedScopeMayOnlyAdoptTheAdminFeeSetOnTheSameDeferredFeeAccount() {
        var legacy = configurationOf(ReceivablesConfiguration.LEGACY_ACCOUNTS, 7);
        var adminFee = configurationOf(ReceivablesConfiguration.ACCOUNTS, 7);
        // Sorted assignment shifts by one key; align every other route with the legacy one before comparing.
        var routes = ReceivablesConfiguration.current(ReceivablesConfiguration.accountMap(legacy));
        for (var entry : adminFee.path("accountMap")) {
            String key = entry.path("accountKey").asText();
            ((com.fasterxml.jackson.databind.node.ObjectNode) entry).put("nativeGlAccountId",
                    Long.toString(routes.getOrDefault(key, 999L)));
        }
        assertThat(ReceivablesConfiguration.adoptsAdminFee(legacy, adminFee)).isTrue();
        assertThat(ReceivablesConfiguration.adoptsAdminFee(adminFee, legacy)).isFalse();
        assertThat(ReceivablesConfiguration.adoptsAdminFee(adminFee, adminFee)).isFalse();
        var moved = adminFee.deepCopy();
        for (var entry : moved.path("accountMap")) {
            if (entry.path("accountKey").asText().equals("deferredAdminFee")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) entry).put("nativeGlAccountId", "8");
            }
        }
        assertThat(ReceivablesConfiguration.adoptsAdminFee(legacy, moved)).isFalse();
        var rerouted = adminFee.deepCopy();
        for (var entry : rerouted.path("accountMap")) {
            if (entry.path("accountKey").asText().equals("bank")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) entry).put("nativeGlAccountId", "9");
            }
        }
        assertThat(ReceivablesConfiguration.adoptsAdminFee(legacy, rerouted)).isFalse();
    }

    @Test
    void upfrontAdminFeeBookingRequiresTheAdminFeeSet() {
        var e = new ReceivablesExecution(json.object().put("operationId", "book").put("businessDate", "2030-01-01"), "scope",
                Map.of("mapping_revision", "map"));
        String upfront = co.mnzl.fineract.receivables.math.ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION;
        when(jdbc.queryForList(anyString(), eq("scope"), eq("map")))
                .thenReturn(List.of(Map.of("config_json", json.write(configurationOf(ReceivablesConfiguration.LEGACY_ACCOUNTS, 7)))));
        configuration.requireCalculation(e, ReceivablesConfiguration.CALCULATION);
        assertThatThrownBy(() -> configuration.requireCalculation(e, upfront)).isInstanceOf(ReceivablesException.class)
                .hasMessage("FINERACT_CAPABILITY_MISSING");
        when(jdbc.queryForList(anyString(), eq("scope"), eq("map")))
                .thenReturn(List.of(Map.of("config_json", json.write(configurationOf(ReceivablesConfiguration.ACCOUNTS, 7)))));
        configuration.requireCalculation(e, upfront);
        configuration.requireCalculation(e, ReceivablesConfiguration.CALCULATION);
    }

    @Test
    void legacyAndAdminFeeConfigurationsBothMatchTheInputSchema() {
        for (var keys : List.of(ReceivablesConfiguration.LEGACY_ACCOUNTS, ReceivablesConfiguration.ACCOUNTS)) {
            var input = configurationOf(keys, 7).put("calculationVersion", "EG_RECEIVABLES_ACT360_DAILY_V1")
                    .put("productPolicyCode", "EG_RECEIVABLES_V1").put("schemaVersion", "1").put("policyRevisionId", "policy")
                    .put("calculatorBuild", "build").put("productId", "1").put("officeId", "1").put("integrationUserId", "1")
                    .put("bankAccountReference", "bank").putNull("helPaymentTypeId").putNull("helProductId");
            input.putObject("scope").put("platformId", "mnzl").put("financierOrganizationId", "financier").put("environment", "test");
            json.validate("nativeConfiguration", input);
        }
    }
}
