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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.receivables.math.ReceivablesMath;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import co.mnzl.fineract.receivables.math.ReceivablesMath.MeasurementState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** A period close over accounts pinned to different versions posts one event whose positions name their versions. */
class ReceivablesMixedVersionEventTest {

    private static final LocalDate START = LocalDate.of(2030, 1, 1);
    private static final LocalDate BOUNDARY = LocalDate.of(2030, 2, 1);
    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReceivablesConfiguration configuration = mock(ReceivablesConfiguration.class);
    private final ReceivablesCloseCommands close = mock(ReceivablesCloseCommands.class);
    private final ReceivablesHelCommands hel = mock(ReceivablesHelCommands.class);
    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final ReceivablesCommandService commands = new ReceivablesCommandService(store, json, configuration,
            new ReceivablesMeasurement(store, json, configuration), mock(ReceivablesLedger.class), mock(ReceivablesAccountCommands.class),
            mock(ReceivablesCashCommands.class), close, hel, security);

    ReceivablesMixedVersionEventTest() throws Exception {}

    @Test
    void closeOverDailyV1AndV2AccountsSucceedsWithPerPositionVersionsAndNoEventVersion() {
        JsonNode event = close(ReceivablesMath.CALCULATION_VERSION, ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION);
        assertThat(event.has("calculationVersion")).isFalse();
        assertThat(event.path("positionsAfter")).extracting(p -> p.path("accountId").asText() + ":" + p.path("calculationVersion").asText())
                .containsExactly("account-0:" + ReceivablesMath.CALCULATION_VERSION,
                        "account-1:" + ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION);
    }

    @Test
    void singleVersionEventKeepsTheEventLevelVersion() {
        JsonNode event = close(ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION, ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION);
        assertThat(event.path("calculationVersion").asText()).isEqualTo(ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION);
        assertThat(event.path("positionsAfter"))
                .allMatch(p -> p.path("calculationVersion").asText().equals(ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION));
    }

    @SuppressWarnings("unchecked")
    private JsonNode close(String... versions) {
        when(store.jdbc()).thenReturn(jdbc);
        when(store.find(anyString(), anyString())).thenReturn(null);
        when(jdbc.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        AppUser user = mock(AppUser.class);
        when(security.authenticatedUser()).thenReturn(user);
        when(configuration.scopeKey(any())).thenReturn("scope");
        when(configuration.tenantId()).thenReturn("default");
        Map<String, Object> config = Map.of("office_id", 1L, "mapping_revision", "map", "policy_revision", "policy", "calculator_build",
                "build");
        when(configuration.authorize(any(), eq(true))).thenReturn(config);
        ObjectNode scope = json.object().put("platformId", "mnzl").put("financierOrganizationId", "financier").put("environment", "test");
        for (int i = 0; i < versions.length; i++) {
            String key = ReceivablesStore.key("scope", "account", "account-" + i);
            var segment = ReceivablesMath.price(versions[i], START,
                    List.of(new Cashflow("cf-" + i, LocalDate.of(2030, 6, 1), BigInteger.valueOf(1_000_000))), new BigDecimal("0.24"),
                    new BigDecimal("0.01")).segment();
            Map<String, BigInteger> outstanding = new LinkedHashMap<>(Map.of("cf-" + i, BigInteger.valueOf(1_000_000)));
            var state = new MeasurementState(segment, ReceivablesMath.position(segment, START, outstanding), outstanding);
            when(store.require("segment", "segment-" + i)).thenReturn(Map.of("snapshot_json", json.write(state)));
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("record_key", key);
            account.put("external_id", "account-" + i);
            account.put("deal_id", "deal");
            account.put("scope_json", json.write(scope.deepCopy().put("developerOrganizationId", "developer")));
            account.put("native_loan_id", 10L + i);
            account.put("status", "ACTIVE");
            account.put("version", 1L);
            account.put("stage", "STAGE_1");
            account.put("risk_assessment_status", "ASSESSED");
            account.put("unreconciled_purchase_minor", "0");
            account.put("allowance_minor", "0");
            account.put("active_segment_key", "segment-" + i);
            account.put("last_event_key", null);
            when(store.require("account", key)).thenReturn(account);
        }
        when(store.require(eq("event"), anyString())).thenReturn(Map.of("sequence_id", 9L));
        when(hel.finish(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        doAnswer(invocation -> {
            ReceivablesExecution e = invocation.getArgument(0);
            for (int i = 0; i < versions.length; i++) {
                e.accountKeys.add(e.accountKey("account-" + i));
            }
            e.date = BOUNDARY;
            e.postingDate = BOUNDARY.minusDays(1);
            e.boundarySide = "BEFORE_EVENTS";
            return null;
        }).when(close).close(any());
        ObjectNode command = json.object().put("commandType", "CLOSE_PERIOD").put("executionMode", "CURRENT").putNull("executionAuthorization")
                .put("accountMappingRevisionId", "map").put("operationId", "close").put("idempotencyKey", "close").put("expectedVersion", "0")
                .put("businessDate", BOUNDARY.toString()).put("basisHash", "0".repeat(64)).put("subjectId", "2030-01")
                .put("subjectKind", "PERIOD").put("executionScopeHash", "0".repeat(64)).put("actorId", "operator").put("phase", "PREPARE")
                .put("periodId", "2030-01").put("boundaryDate", BOUNDARY.toString()).put("eventWatermark", "8")
                .put("sourceCutoffHash", "0".repeat(64));
        command.set("scope", scope);
        command.putArray("affectedAccountVersions");
        command.putArray("approverIds");
        command.putArray("approvalEvidenceIds").add("approved");
        command.putArray("forecastIds");
        commands.execute(json.write(command));
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(store).insert(eq("event"), anyString(), fields.capture());
        verify(store).insert(eq("command"), anyString(), anyMap());
        JsonNode event = json.read((String) fields.getValue().get("event_json"));
        json.validate("financialEvent", event);
        return event;
    }
}
