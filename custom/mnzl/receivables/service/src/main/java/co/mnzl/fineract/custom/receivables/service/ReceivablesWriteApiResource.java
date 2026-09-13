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

import static co.mnzl.fineract.custom.receivables.service.ReceivablesException.require;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/mnzl/receivables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
@Slf4j
public class ReceivablesWriteApiResource {

    private final ReceivablesCommandService commands;
    private final ReceivablesCalculationService calculations;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesJson json;
    private final PlatformSecurityContext security;

    private JsonNode scope(HttpHeaders headers) {
        var scope = json.object();
        String[] fields = { "platformId", "financierOrganizationId", "environment" };
        String[] names = { "X-MNZL-Platform", "X-MNZL-Financier", "X-MNZL-Environment" };
        for (int i = 0; i < fields.length; i++) {
            String value = headers.getHeaderString(names[i]);
            require(value != null && !value.isBlank(), "INVALID_DATA");
            scope.put(fields[i], value);
        }
        return scope;
    }

    private void authorizeRequest(HttpHeaders headers, String request) {
        JsonNode input = json.read(request);
        JsonNode requested = scope(headers);
        require(configuration.scopeKey(input.get("scope")).equals(configuration.scopeKey(requested)), "OWNERSHIP_CONFLICT");
        require(text(input, "accountMappingRevisionId").equals(headers.getHeaderString("X-MNZL-Account-Mapping")),
                "FINERACT_CAPABILITY_MISSING");
    }

    @POST
    @Path("/commands")
    public String execute(@Context HttpHeaders headers, String request) {
        long started = System.nanoTime();
        JsonNode input = json.read(request);
        String result = "FAILED";
        try {
            authorizeRequest(headers, request);
            String response = json.write(commands.execute(request));
            result = "COMPLETED";
            return response;
        } catch (ReceivablesException exception) {
            result = exception.code();
            throw exception;
        } finally {
            log.info("MNZL receivables command type={} operationId={} scope={} subject={} result={} latencyMs={}",
                    input.path("commandType").asText(), input.path("operationId").asText(),
                    ReceivablesJson.hashText(input.path("scope").toString()), ReceivablesJson.hashText(input.path("subjectId").asText()),
                    result, (System.nanoTime() - started) / 1_000_000);
        }
    }

    @POST
    @Path("/hel-funding/commands")
    public String fund(@Context HttpHeaders headers, String request) {
        require(text(json.read(request), "commandType").equals("FUND_HEL_TO_SETTLEMENT_CLEARING"), "INVALID_DATA");
        return execute(headers, request);
    }

    @POST
    @Path("/hel-funding/allocations")
    public String allocate(@Context HttpHeaders headers, String request) {
        require(text(json.read(request), "commandType").equals("ALLOCATE_HEL_DEVELOPER_ADVANCE"), "INVALID_DATA");
        return execute(headers, request);
    }

    @POST
    @Path("/calculate")
    public String calculate(@Context HttpHeaders headers, String request) {
        security.authenticatedUser().validateHasPermissionTo("CALCULATE_MNZL_RECEIVABLES");
        var config = configuration.authorize(scope(headers), false);
        var input = json.read(request);
        JsonNode versions = input.has("basis") ? input.get("basis") : input;
        require(text(versions, "policyRevisionId").equals(string(config, "policy_revision"))
                && text(versions, "calculatorBuild").equals(string(config, "calculator_build")), "UNSUPPORTED_VERSION");
        return json.write(calculations.calculate(request));
    }

    /**
     * Stateless product pricing preserves the caller's policy provenance, independently of the managed ledger's policy.
     */
    @POST
    @Path("/pricing-calculations")
    public String calculatePricing(@Context HttpHeaders headers, String request) {
        security.authenticatedUser().validateHasPermissionTo("CALCULATE_MNZL_RECEIVABLES");
        var config = configuration.authorize(scope(headers), false);
        var input = json.validate("pricingCalculationRequest", request);
        require(text(input, "calculatorBuild").equals(string(config, "calculator_build")), "UNSUPPORTED_VERSION");
        return json.write(calculations.calculate(request));
    }

    @POST
    @Path("/borrowers/resolve")
    public String resolveBorrower(@Context HttpHeaders headers, String request) {
        authorizeRequest(headers, request);
        return json.write(configuration.resolveBorrower(request));
    }

    @GET
    @Path("/configuration")
    public String readConfiguration(@Context HttpHeaders headers) {
        return json.write(configuration.readConfiguration(scope(headers), headers.getHeaderString("X-MNZL-Account-Mapping")));
    }

    @POST
    @Path("/configuration")
    public String configure(@Context HttpHeaders headers, String request) {
        authorizeRequest(headers, request);
        return json.write(configuration.configure(request));
    }

    @GET
    @Path("/configuration/active")
    public String activeConfiguration(@Context HttpHeaders headers) {
        return json.write(configuration.activeConfiguration(scope(headers)));
    }

    @POST
    @Path("/configuration/revisions")
    public String stage(@Context HttpHeaders headers, String request) {
        authorizeRequest(headers, request);
        return json.write(configuration.stage(request));
    }

    @POST
    @Path("/configuration/activations")
    public String activate(@Context HttpHeaders headers, String request) {
        require(text(json.read(request), "accountMappingRevisionId").equals(headers.getHeaderString("X-MNZL-Account-Mapping")),
                "FINERACT_CAPABILITY_MISSING");
        return json.write(configuration.activate(scope(headers), request));
    }

    @GET
    @Path("/configuration/runtime")
    public String runtime() {
        security.authenticatedUser().validateHasPermissionTo("READ_MNZL_RECEIVABLES",
                java.util.List.of("READ_MNZL_RECEIVABLES", "CONFIGURE_MNZL_RECEIVABLES"));
        var properties = new java.util.Properties();
        try (var stream = getClass().getResourceAsStream("/receivables-runtime.properties")) {
            require(stream != null, "FINERACT_CAPABILITY_MISSING");
            properties.load(stream);
        } catch (java.io.IOException exception) {
            throw new ReceivablesException("FINERACT_CAPABILITY_MISSING", exception);
        }
        return json.write(json.object().put("configurationLifecycleVersion", "1")
                .put("calculationVersion", ReceivablesConfiguration.CALCULATION).put("productPolicyCode", ReceivablesConfiguration.POLICY)
                .put("sourceRevision", properties.getProperty("sourceRevision"))
                .put("openApiSha256", properties.getProperty("openApiSha256")));
    }

    @POST
    @Path("/authorizations")
    public String authorizeLegacy(@Context HttpHeaders headers, String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        throw new ReceivablesException("UNSUPPORTED_VERSION");
    }

    @POST
    @Path("/authorizations/v2")
    public String authorize(@Context HttpHeaders headers, String request) {
        require(configuration.scopeKey(json.read(request).get("scope")).equals(configuration.scopeKey(scope(headers))),
                "OWNERSHIP_CONFLICT");
        return json.write(configuration.authorizeHistory(request));
    }

    @POST
    @Path("/workout-authorizations")
    public String authorizeWorkout(@Context HttpHeaders headers, String request) {
        require(configuration.scopeKey(json.read(request).get("scope")).equals(configuration.scopeKey(scope(headers))),
                "OWNERSHIP_CONFLICT");
        return json.write(configuration.authorizeWorkout(request));
    }

}
