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
package co.mnzl.fineract.custom.loan.simulator.api;

import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import co.mnzl.fineract.custom.loan.simulator.service.MnzlSimulationReadService;
import co.mnzl.fineract.custom.loan.simulator.service.MnzlSimulationWriteService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mnzl.loan.simulator.enabled", havingValue = "true", matchIfMissing = true)
@Path("/v1/mnzl/simulations")
@RequiredArgsConstructor
public class MnzlSimulationApiResource {

    private static final String PERMISSION_RESOURCE = "MNZL_SIMULATION";
    private static final Set<String> RESPONSE_PARAMETERS = Set.of("uuid", "name", "status", "errorMessage", "snapshots");

    private final PlatformSecurityContext context;
    private final MnzlSimulationReadService readService;
    private final MnzlSimulationWriteService writeService;
    private final DefaultToApiJsonSerializer<SimulationResult> serializer;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String runSimulation(final String apiRequestBodyAsJson) {
        context.authenticatedUser().validateHasPermissionTo("CREATE_" + PERMISSION_RESOURCE);
        SimulationResult result = writeService.runSimulation(apiRequestBodyAsJson);
        return serializer.serialize(result);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String listSimulations(@QueryParam("offset") @DefaultValue("0") final int offset,
            @QueryParam("limit") @DefaultValue("50") final int limit) {
        context.authenticatedUser().validateHasReadPermission(PERMISSION_RESOURCE);
        List<SimulationResult> results = readService.findAll(offset, limit);
        return serializer.serialize(results);
    }

    @GET
    @Path("/{uuid}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getSimulation(@PathParam("uuid") final String uuid) {
        context.authenticatedUser().validateHasReadPermission(PERMISSION_RESOURCE);
        SimulationResult result = readService.findByUuid(uuid);
        return serializer.serialize(result);
    }

    @POST
    @Path("/{uuid}/rerun")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String rerunSimulation(@PathParam("uuid") final String uuid) {
        context.authenticatedUser().validateHasPermissionTo("CREATE_" + PERMISSION_RESOURCE);
        SimulationResult result = writeService.rerunSimulation(uuid);
        return serializer.serialize(result);
    }

    @DELETE
    @Path("/{uuid}")
    @Produces(MediaType.APPLICATION_JSON)
    public String deleteSimulation(@PathParam("uuid") final String uuid) {
        context.authenticatedUser().validateHasPermissionTo("DELETE_" + PERMISSION_RESOURCE);
        writeService.deleteSimulation(uuid);
        return "{}";
    }
}
