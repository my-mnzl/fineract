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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesContextTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final ReceivablesConfiguration configuration = spy(new ReceivablesConfiguration(store, json, security, null, null, null));

    ReceivablesContextTest() throws Exception {}

    @BeforeEach
    void authenticate() {
        when(store.jdbc()).thenReturn(jdbc);
        when(security.authenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(7L);
        doReturn("authenticated-tenant").when(configuration).tenantId();
    }

    private Map<String, Object> row(String platform, String epoch, boolean retired) {
        var scope = json.object().put("platformId", platform).put("financierOrganizationId", "mnzl").put("environment", "test")
                .put("ledgerEpoch", epoch);
        var row = new HashMap<String, Object>();
        row.put("scope_json", json.write(scope));
        row.put("scope_key", configuration.scopeKey(scope));
        row.put("epoch", epoch);
        row.put("mapping_revision", "map-1");
        if (retired) {
            row.put("retired", true);
        }
        return row;
    }

    private void rows(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(anyString(), eq(7L), eq("mnzl"))).thenReturn(rows);
    }

    @Test
    void discoversOnlyTheAuthenticatedUsersActiveMatchingContext() {
        rows(List.of(row("other-platform", "other", false), row("platform", "retired", true), row("platform", "active", false)));
        var result = configuration.discoverContext("platform", "mnzl", "test");
        assertThat(result.path("tenantId").asText()).isEqualTo("authenticated-tenant");
        assertThat(result.path("scope").path("ledgerEpoch").asText()).isEqualTo("active");
        assertThat(result.path("accountMappingRevisionId").asText()).isEqualTo("map-1");
        verify(user).validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        verify(jdbc).queryForList("select * from m_mnzl_r_configuration where integration_user_id=? and financier_id=?", 7L, "mnzl");
    }

    @Test
    void rejectsMissingAmbiguousAndRetiredConfigurations() {
        for (var candidates : List.of(List.<Map<String, Object>>of(), List.of(row("platform", "retired", true)),
                List.of(row("platform", "one", false), row("platform", "two", false)))) {
            rows(candidates);
            assertThatThrownBy(() -> configuration.discoverContext("platform", "mnzl", "test")).isInstanceOf(ReceivablesException.class);
        }
    }

    @Test
    void deniedPermissionCannotDiscoverConfiguration() {
        doThrow(new ReceivablesException("FINERACT_CAPABILITY_MISSING")).when(user).validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        assertThatThrownBy(() -> configuration.discoverContext("platform", "mnzl", "test")).isInstanceOf(ReceivablesException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsIncompleteIdentityAndInconsistentNativeEpoch() {
        assertThatThrownBy(() -> configuration.discoverContext(null, "mnzl", "test")).isInstanceOf(ReceivablesException.class);
        var row = row("platform", "active", false);
        row.put("epoch", "different");
        rows(List.of(row));
        assertThatThrownBy(() -> configuration.discoverContext("platform", "mnzl", "test")).isInstanceOf(ReceivablesException.class);
    }
}
