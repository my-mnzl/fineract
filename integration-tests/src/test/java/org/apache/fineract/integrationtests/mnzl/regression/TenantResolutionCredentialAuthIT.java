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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.Utils;
import org.junit.jupiter.api.Test;

/**
 * Regression test pinning behavior locked by commit f2073115d (Fix tenant resolution for credential auth).
 *
 * The original bug: requests using HTTP basic auth against tenant-aware mnzl endpoints could fail to resolve the tenant
 * context because {@code AuthorizationServerConfig} routed credential auth through a path that did not apply the tenant
 * filter. After the fix, basic-auth requests correctly resolve the tenant identifier from the standard query parameter
 * and reach mnzl-namespaced controllers.
 *
 * This IT exercises a basic-auth GET against {@code /v1/mnzl/simulations} (a mnzl-namespaced endpoint added by the loan
 * simulator module) and asserts the request succeeds. Pre-fix, the request would either 401 with a tenant resolution
 * error or 500 with a context-not-set error.
 */
@Slf4j
public class TenantResolutionCredentialAuthIT extends BaseLoanIntegrationTest {

    private static final String SIMULATIONS_URL = "/fineract-provider/api/v1/mnzl/simulations?" + Utils.TENANT_IDENTIFIER;

    /**
     * Basic-auth request against a mnzl-namespaced endpoint must resolve the default tenant and return 200. Pre-fix,
     * the tenant filter was bypassed for credential auth, leaving the request without a tenant and causing a downstream
     * failure.
     */
    @Test
    public void basicAuthRequest_resolvesDefaultTenant_andHitsMnzlSimulationsEndpoint() {
        // requestSpec is constructed in BaseLoanIntegrationTest using basic auth.
        String body = Utils.performServerGet(requestSpec, responseSpec, SIMULATIONS_URL, null);
        // Listing endpoint returns a JSON array (possibly empty). Either way it must not be a tenant-resolution
        // error page, and the listing serializer always emits a JSON array literal.
        assertThat(body).as("simulator listing response").isNotNull();
        assertThat(body.trim()).as("simulator listing must be a JSON array").startsWith("[");
    }

    /**
     * Control case: invalid credentials must produce a 401 Unauthorized — the tenant filter must not silently accept
     * requests that fail authentication. (Some Fineract deployments return 403 instead; we accept either to keep the
     * test robust to upstream auth-failure mapping.)
     */
    @Test
    public void basicAuthRequest_withInvalidCredentials_returns401() {
        RequestSpecification badAuth = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        badAuth.header("Authorization",
                "Basic " + java.util.Base64.getEncoder().encodeToString("nope:nope".getBytes(StandardCharsets.UTF_8)));

        ResponseSpecification expectUnauthorized = new ResponseSpecBuilder().expectStatusCode(oneOf(401, 403)).build();

        Response resp = given().spec(badAuth).expect().spec(expectUnauthorized).log().ifError().when().get(SIMULATIONS_URL).andReturn();
        assertThat(resp.getStatusCode()).as("invalid credentials status code")
                .is(new org.assertj.core.api.Condition<>(code -> code == 401 || code == 403, "401 or 403"));
        // Sanity: matcher above already asserted, but we keep an explicit check for clarity in failure output.
        org.hamcrest.MatcherAssert.assertThat(resp.getStatusCode(), is(oneOf(401, 403)));
    }
}
