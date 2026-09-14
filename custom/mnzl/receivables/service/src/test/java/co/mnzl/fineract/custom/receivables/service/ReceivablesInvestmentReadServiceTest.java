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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class ReceivablesInvestmentReadServiceTest {

    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final LoanReadPlatformService loans = mock(LoanReadPlatformService.class);
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReceivablesReadService reads = mock(ReceivablesReadService.class);
    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesInvestmentReadService service = new ReceivablesInvestmentReadService(security, loans, store, json, reads);

    ReceivablesInvestmentReadServiceTest() throws Exception {
        when(security.authenticatedUser()).thenReturn(user);
        when(store.jdbc()).thenReturn(jdbc);
    }

    @Test
    void authorizedStaffResolveScopeFromTheVisibleLoanOnly() {
        var scope = json.object().put("platformId", "platform").put("financierOrganizationId", "financier");
        when(jdbc.queryForList(anyString(), eq(42L)))
                .thenReturn(List.of(Map.of("scope_json", json.write(scope), "external_id", "receivable")));
        var result = json.object().put("applicable", true);
        when(reads.investment(scope, "receivable", true)).thenReturn(result);
        assertThat(service.read(42, true)).isSameAs(result);
        var order = inOrder(user, loans, jdbc, reads);
        order.verify(user).validateHasReadPermission("LOAN");
        order.verify(user).validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        order.verify(loans).retrieveOne(42L);
        order.verify(jdbc).queryForList(anyString(), eq(42L));
        order.verify(reads).investment(scope, "receivable", true);
    }

    @Test
    void inaccessibleLoansAndMissingPermissionsDoNotQueryTheNativeBook() {
        when(loans.retrieveOne(42L)).thenThrow(new AccessDeniedException("outside office"));
        assertThatThrownBy(() -> service.read(42, false)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, reads);
        doThrow(new AccessDeniedException("permission")).when(user).validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        assertThatThrownBy(() -> service.read(43, true)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, reads);
    }

    @Test
    void ordinaryLoanIsExplicitlyInapplicableAndAmbiguousBindingFails() {
        when(jdbc.queryForList(anyString(), eq(42L))).thenReturn(List.of());
        assertThat(service.read(42, false).path("applicable").asBoolean()).isFalse();
        when(jdbc.queryForList(anyString(), eq(42L))).thenReturn(List.of(Map.of(), Map.of()));
        assertThatThrownBy(() -> service.read(42, false)).isInstanceOf(ReceivablesException.class).hasMessage("RECOVERY_REQUIRED");
        verifyNoInteractions(reads);
    }
}
