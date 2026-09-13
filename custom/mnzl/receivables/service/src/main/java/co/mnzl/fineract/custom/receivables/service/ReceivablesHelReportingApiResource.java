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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/mnzl/receivables/hel-reporting")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class ReceivablesHelReportingApiResource {

    private final ReceivablesHelReporting reporting;
    private final ReceivablesHelReportingReadService reportingReads;
    private final ReceivablesReadService reads;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesJson json;

    private JsonNode scope(HttpHeaders headers) {
        return reads.authorize(headers.getHeaderString("X-MNZL-Platform"), headers.getHeaderString("X-MNZL-Financier"),
                headers.getHeaderString("X-MNZL-Environment"), headers.getHeaderString("X-MNZL-Account-Mapping"));
    }

    private void scope(HttpHeaders headers, String request) {
        require(configuration.scopeKey(json.read(request).get("scope")).equals(configuration.scopeKey(scope(headers))),
                "OWNERSHIP_CONFLICT");
    }

    @POST
    @Path("/registrations")
    public String register(@Context HttpHeaders headers, String request) {
        scope(headers, request);
        return json.write(reporting.register(request));
    }

    @POST
    @Path("/snapshots")
    public String capture(@Context HttpHeaders headers, String request) {
        scope(headers, request);
        return json.write(reporting.capture(request));
    }

    @GET
    @Path("/snapshots/{snapshotId}")
    public String snapshot(@Context HttpHeaders headers, @PathParam("snapshotId") String snapshotId) {
        return json.write(reporting.snapshot(scope(headers), snapshotId));
    }

    @GET
    @Path("/snapshots/{snapshotId}/members")
    public String members(@Context HttpHeaders headers, @PathParam("snapshotId") String snapshotId, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return json.write(reportingReads.members(scope(headers), snapshotId, cursor, limit));
    }

    @GET
    @Path("/snapshots/{snapshotId}/journals")
    public String journals(@Context HttpHeaders headers, @PathParam("snapshotId") String snapshotId,
            @QueryParam("periodStartDate") String start, @QueryParam("throughDate") String through, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return json.write(reportingReads.journals(scope(headers), snapshotId, date(start), date(through), cursor, limit));
    }

    @SuppressWarnings("AvoidHidingCauseException")
    private LocalDate date(String value) {
        require(value != null, "INVALID_DATA");
        try {
            return LocalDate.parse(value);
        } catch (java.time.DateTimeException e) {
            var failure = new ReceivablesException("INVALID_DATA");
            failure.initCause(e);
            throw failure;
        }
    }
}
