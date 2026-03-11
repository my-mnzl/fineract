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
package co.mnzl.fineract.custom.loan.instrument;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mnzl.loan.instrument.enabled", havingValue = "true", matchIfMissing = true)
@Path("/v1/mnzl/loan-products/{loanProductId}/strategies")
@RequiredArgsConstructor
public class MnzlLoanProductStrategyApiResource {

    private static final String PERMISSION_RESOURCE = "LOANPRODUCT";
    private static final Set<String> RESPONSE_PARAMETERS = Set.of("loanProductId", "instrumentCode", "scheduleStrategyCode",
            "chargeStrategyCode", "cobStrategyCode");

    private final PlatformSecurityContext context;
    private final MnzlLoanProductStrategyReadService readService;
    private final MnzlLoanProductStrategyWriteService writeService;
    private final MnzlLoanProductStrategyApiJsonValidator validator;
    private final DefaultToApiJsonSerializer<MnzlLoanProductStrategyData> toApiJsonSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String retrieve(@PathParam("loanProductId") Long loanProductId, @Context UriInfo uriInfo) {
        context.authenticatedUser().validateHasReadPermission(PERMISSION_RESOURCE);
        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return toApiJsonSerializer.serialize(settings, readService.findOne(loanProductId), RESPONSE_PARAMETERS);
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String update(@PathParam("loanProductId") Long loanProductId, String apiRequestBodyAsJson) {
        context.authenticatedUser().validateHasPermissionTo("UPDATE_" + PERMISSION_RESOURCE);
        validator.validateForUpdate(apiRequestBodyAsJson);
        return toApiJsonSerializer.serialize(writeService.update(loanProductId, apiRequestBodyAsJson));
    }
}
