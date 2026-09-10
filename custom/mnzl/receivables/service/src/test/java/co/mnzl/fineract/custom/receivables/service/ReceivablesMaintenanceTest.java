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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReceivablesMaintenanceTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final ReceivablesConfiguration configuration = mock(ReceivablesConfiguration.class);
    private final ReceivablesResetInventory inventory = mock(ReceivablesResetInventory.class);
    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final ReceivablesMaintenance service = new ReceivablesMaintenance(store, json, configuration, inventory, security);
    private final Map<String, Object> config = new HashMap<>(Map.of("record_key", "scope", "product_id", 7L, "retired", false));

    ReceivablesMaintenanceTest() throws Exception {
        when(security.authenticatedUser()).thenReturn(user);
        when(configuration.tenantId()).thenReturn("local-tenant");
        when(configuration.scopeKey(any())).thenReturn("scope");
        when(store.lockConfiguration("scope")).thenReturn(config);
        when(store.find("configuration", "scope")).thenReturn(null);
        when(store.require("configuration", "scope")).thenReturn(config);
        when(inventory.collect(anyString(), any(), any())).thenReturn(List.of());
        when(inventory.manifest(any())).thenReturn(json.object().putArray("rows"));
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    private ObjectNode request() {
        return (ObjectNode) json.read("""
                {"scope":{"platformId":"p","financierOrganizationId":"f","environment":"local","ledgerEpoch":"old"},
                "tenantId":"local-tenant","nextLedgerEpoch":"new","maintenanceWindowId":"window",
                "developerOrganizationIds":["d"],"accountIds":[],"nativeLoanIds":[],"productIds":["7"],"sourceIds":[],"journalIds":[]}
                """);
    }

    @Test
    void maintenanceRequiresCapabilityTenantWindowAndExactAllowlist() {
        var request = request();
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThatThrownBy(() -> service.begin(json.write(request))).hasMessage("FINERACT_CAPABILITY_MISSING");
        ReflectionTestUtils.setField(service, "enabled", true);
        assertThatThrownBy(() -> service.begin(json.write(request.deepCopy().put("tenantId", "other")))).hasMessage("OWNERSHIP_CONFLICT");
        assertThatThrownBy(() -> service.plan(json.write(request))).hasMessage("APPROVAL_SCOPE_CHANGED");
        var wrong = request.deepCopy();
        wrong.putArray("accountIds").add("unrelated");
        assertThatThrownBy(() -> service.begin(json.write(wrong))).hasMessage("OWNERSHIP_CONFLICT");
        verify(store, never()).update(anyString(), anyString(), any());
        assertThat(service.begin(json.write(request))).isEqualTo(request);
        verify(user, org.mockito.Mockito.atLeastOnce()).validateHasPermissionTo("MAINTAIN_MNZL_RECEIVABLES");
        verify(store).update(eq("configuration"), eq("scope"), eq(Map.of("maintenance_request_hash", json.hash(request))));
    }

    @Test
    void changedPlanCannotDeleteAndRetiredEpochCannotBeReused() {
        var request = request();
        config.put("maintenance_request_hash", json.hash(request));
        var plan = service.plan(json.write(request));
        assertThat(plan.path("planHash").asText()).hasSize(64);
        var apply = json.object();
        apply.set("request", request);
        apply.put("planHash", "0".repeat(64));
        assertThatThrownBy(() -> service.apply(json.write(apply))).hasMessage("SOURCE_CHANGED");
        verify(inventory, never()).remove(any());
        assertThatThrownBy(() -> ReceivablesConfiguration.requireMutable(config)).hasMessage("FINERACT_CAPABILITY_MISSING");
        config.remove("maintenance_request_hash");
        config.put("retired", true);
        assertThatThrownBy(() -> ReceivablesConfiguration.requireMutable(config)).hasMessage("FINERACT_CAPABILITY_MISSING");
        assertThatThrownBy(() -> service.begin(json.write(request))).hasMessage("FINERACT_CAPABILITY_MISSING");
    }

    @Test
    void helArtifactsAndDuplicateIdsFailBeforeFreezingScope() {
        var request = request();
        var duplicate = request.deepCopy();
        duplicate.putArray("developerOrganizationIds").add("d").add("d");
        assertThatThrownBy(() -> service.begin(json.write(duplicate))).hasMessage("INVALID_DATA");
        when(store.scoped("hel_funding", "scope")).thenReturn(List.of(Map.of("loan_id", 42L)));
        assertThatThrownBy(() -> service.begin(json.write(request))).hasMessage("OWNERSHIP_CONFLICT");
        verify(store, never()).update(anyString(), anyString(), any());
    }
}
