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
package org.apache.fineract.infrastructure.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

class TenantAwareAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void shouldSetTenantContextFromJwtClaimBeforeFilterChain() throws Exception {
        BearerTokenResolver resolver = mock(BearerTokenResolver.class);
        AuthTenantDetailsService tenantDetailsService = mock(AuthTenantDetailsService.class);
        FineractPlatformTenant tenant = tenant("default");
        when(resolver.resolve(any())).thenReturn(jwtWithTenantClaim("default"));
        when(tenantDetailsService.loadTenantById("default", false)).thenReturn(tenant);

        TenantAwareAuthenticationFilter filter = new TenantAwareAuthenticationFilter(resolver, tenantDetailsService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fineract-provider/api/v1/clients");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<FineractPlatformTenant> tenantInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> tenantInsideChain.set(ThreadLocalContextUtil.getTenant()));

        assertThat(tenantInsideChain.get()).isEqualTo(tenant);
        assertThat(ThreadLocalContextUtil.getTenant()).isNull();
        verify(tenantDetailsService).loadTenantById("default", false);
    }

    @Test
    void shouldFallBackToTenantHeaderWhenJwtClaimIsMissing() throws Exception {
        BearerTokenResolver resolver = mock(BearerTokenResolver.class);
        AuthTenantDetailsService tenantDetailsService = mock(AuthTenantDetailsService.class);
        FineractPlatformTenant tenant = tenant("default");
        when(resolver.resolve(any())).thenReturn(new PlainJWT(new JWTClaimsSet.Builder().subject("custom-user").build()).serialize());
        when(tenantDetailsService.loadTenantById("default", false)).thenReturn(tenant);

        TenantAwareAuthenticationFilter filter = new TenantAwareAuthenticationFilter(resolver, tenantDetailsService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fineract-provider/api/v1/clients");
        request.addHeader("Fineract-Platform-TenantId", "default");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<FineractPlatformTenant> tenantInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> tenantInsideChain.set(ThreadLocalContextUtil.getTenant()));

        assertThat(tenantInsideChain.get()).isEqualTo(tenant);
        verify(tenantDetailsService).loadTenantById("default", false);
    }

    @Test
    void shouldFallBackToTenantIdRequestParameterWhenJwtClaimAndHeaderAreMissing() throws Exception {
        BearerTokenResolver resolver = mock(BearerTokenResolver.class);
        AuthTenantDetailsService tenantDetailsService = mock(AuthTenantDetailsService.class);
        FineractPlatformTenant tenant = tenant("default");
        when(resolver.resolve(any())).thenReturn(null);
        when(tenantDetailsService.loadTenantById("default", false)).thenReturn(tenant);

        TenantAwareAuthenticationFilter filter = new TenantAwareAuthenticationFilter(resolver, tenantDetailsService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fineract-provider/login");
        request.addParameter("tenantId", "default");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<FineractPlatformTenant> tenantInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> tenantInsideChain.set(ThreadLocalContextUtil.getTenant()));

        assertThat(tenantInsideChain.get()).isEqualTo(tenant);
        verify(tenantDetailsService).loadTenantById("default", false);
    }

    @Test
    void shouldContinueFilterChainOnceWhenTenantResolutionFails() throws Exception {
        BearerTokenResolver resolver = mock(BearerTokenResolver.class);
        AuthTenantDetailsService tenantDetailsService = mock(AuthTenantDetailsService.class);
        when(resolver.resolve(any())).thenReturn("not-a-jwt");

        TenantAwareAuthenticationFilter filter = new TenantAwareAuthenticationFilter(resolver, tenantDetailsService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fineract-provider/api/v1/clients");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger invocations = new AtomicInteger();

        filter.doFilter(request, response, (req, res) -> invocations.incrementAndGet());

        assertThat(invocations).hasValue(1);
        verify(tenantDetailsService, org.mockito.Mockito.never()).loadTenantById(eq("default"), eq(false));
    }

    private static FineractPlatformTenant tenant(String tenantIdentifier) {
        return FineractPlatformTenant.builder().id(1L).tenantIdentifier(tenantIdentifier).name("Default").timezoneId("UTC").build();
    }

    private static String jwtWithTenantClaim(String tenantIdentifier) {
        return new PlainJWT(new JWTClaimsSet.Builder().claim("tenant", tenantIdentifier).build()).serialize();
    }
}
