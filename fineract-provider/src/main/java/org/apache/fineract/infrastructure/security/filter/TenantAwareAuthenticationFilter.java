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

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class TenantAwareAuthenticationFilter extends OncePerRequestFilter {

    private static final String TENANT_CLAIM = "tenant";
    private static final String TENANT_ID_REQUEST_HEADER = "Fineract-Platform-TenantId";
    private static final String TENANT_IDENTIFIER_PARAMETER = "tenantIdentifier";
    private static final String TENANT_ID_PARAMETER = "tenantId";

    private final BearerTokenResolver resolver;
    private final AuthTenantDetailsService tenantDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String tenantId = resolveTenantId(request);
            if (StringUtils.isNotBlank(tenantId)) {
                ThreadLocalContextUtil.setTenant(tenantDetailsService.loadTenantById(tenantId, isReportRequest(request)));
            }
        } catch (Exception e) {
            log.debug("Failed to establish tenant context for OAuth request", e);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            ThreadLocalContextUtil.reset();
        }
    }

    private String resolveTenantId(HttpServletRequest request) throws Exception {
        String tenantId = resolveTenantIdFromToken(request);
        if (StringUtils.isNotBlank(tenantId)) {
            return tenantId;
        }

        tenantId = request.getHeader(TENANT_ID_REQUEST_HEADER);
        if (StringUtils.isNotBlank(tenantId)) {
            return tenantId;
        }

        tenantId = request.getParameter(TENANT_IDENTIFIER_PARAMETER);
        if (StringUtils.isNotBlank(tenantId)) {
            return tenantId;
        }

        return request.getParameter(TENANT_ID_PARAMETER);
    }

    private String resolveTenantIdFromToken(HttpServletRequest request) throws Exception {
        String token = resolver.resolve(request);
        if (StringUtils.isBlank(token)) {
            return null;
        }

        var jwt = JWTParser.parse(token); // not validated here!
        return jwt.getJWTClaimsSet().getStringClaim(TENANT_CLAIM);
    }

    private boolean isReportRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.contains("report");
    }
}
