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
package org.apache.fineract.integrationtests.mnzl.regression;

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Regression test pinning behavior locked by commit 7d11e0453 (Fix tenant resolution for JWT auth).
 *
 * The original bug: requests using OAuth2 JWT bearer tokens against tenant-aware endpoints could fail to resolve the
 * tenant context because {@code TenantAwareAuthenticationFilter} ran before the JWT filter chain populated the security
 * context. After the fix, JWT-authenticated requests correctly resolve the tenant identifier from the standard query
 * parameter and reach mnzl-namespaced controllers.
 *
 * The default {@code :integration-tests} module is built around basic-auth requests. Exercising JWT auth end-to-end
 * requires an OAuth2 issuer and the {@code fineract-provider} configured with
 * {@code spring.security.oauth2.resourceserver.jwt} — a setup that lives in {@code :oauth2-tests}. To avoid pulling
 * that whole infrastructure into this module, we keep the suite as a compile-clean placeholder and point at the manual
 * smoke coverage in {@code oauth2-tests/}.
 *
 * When L3 wants to actually run this end-to-end, the right move is to add a sibling test class under
 * {@code oauth2-tests/src/test/java/org/apache/fineract/oauth2tests/mnzl/} that reuses the OAuth2 fixture from
 * {@code OAuth2AuthenticationTest} and hits {@code /v1/mnzl/simulations}. The unit-level coverage already lives in
 * {@code TenantAwareAuthenticationFilterTest} (added in 7d11e0453).
 */
@Slf4j
public class TenantResolutionJwtAuthIT extends BaseLoanIntegrationTest {

    /**
     * JWT bearer + mnzl endpoint must resolve the correct tenant. Disabled here because executing this test requires an
     * OAuth2 authorization server stood up alongside fineract-provider — see the {@code oauth2-tests} module for the
     * harness that provides this. The post-fix invariant is also unit-tested in
     * {@code TenantAwareAuthenticationFilterTest}.
     */
    @Test
    @Disabled("Requires OAuth2 issuer; covered by manual smoke test in oauth2-tests module and unit-tested in TenantAwareAuthenticationFilterTest")
    public void jwtAuthRequest_resolvesDefaultTenant_andHitsMnzlSimulationsEndpoint() {
        // Intentionally empty. See class-level Javadoc — to enable, port this to the :oauth2-tests module
        // where an OAuth2 issuer fixture is available.
    }

    /**
     * Control case: a request bearing an obviously-invalid JWT must produce a 401. Disabled for the same reason as
     * above — the resource server's JWT validation only runs when an OAuth2 issuer is configured.
     */
    @Test
    @Disabled("Requires OAuth2 issuer; covered by manual smoke test in oauth2-tests module")
    public void jwtAuthRequest_withInvalidToken_returns401() {
        // Intentionally empty. See class-level Javadoc.
    }
}
