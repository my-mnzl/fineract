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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/mnzl/receivables")
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class ReceivablesReadApiResource {

    private final ReceivablesReadService reads;
    private final ReceivablesJson json;

    private JsonNode scope(HttpHeaders headers) {
        return reads.authorize(headers.getHeaderString("X-MNZL-Platform"), headers.getHeaderString("X-MNZL-Financier"),
                headers.getHeaderString("X-MNZL-Environment"), headers.getHeaderString("X-MNZL-Ledger-Epoch"),
                headers.getHeaderString("X-MNZL-Account-Mapping"));
    }

    @SuppressWarnings("AvoidHidingCauseException") // Cause retained through initCause.
    private ReceivablesReadService.Boundary boundary(JsonNode scope, String date, String side, String watermark) {
        try {
            return new ReceivablesReadService.Boundary(date == null ? DateUtils.getBusinessLocalDate() : LocalDate.parse(date),
                    side == null ? "AFTER_EVENTS" : side, watermark == null ? reads.watermark(scope) : Long.parseLong(watermark));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            var failure = new ReceivablesException("INVALID_DATA");
            failure.initCause(e);
            throw failure;
        }
    }

    @GET
    @Path("/capabilities")
    public String capabilities(@Context HttpHeaders headers) {
        return json.write(reads.capabilities(scope(headers)));
    }

    @GET
    @Path("/operations/{id}")
    public String operation(@Context HttpHeaders headers, @PathParam("id") String id) {
        return json.write(reads.operation(scope(headers), id, false));
    }

    @GET
    @Path("/hel-funding/operations/{id}")
    public String helOperation(@Context HttpHeaders headers, @PathParam("id") String id) {
        return json.write(reads.operation(scope(headers), id, true));
    }

    @GET
    @Path("/hel-funding/allocations/{id}")
    public String helAllocation(@Context HttpHeaders headers, @PathParam("id") String id) {
        return json.write(reads.operation(scope(headers), id, false));
    }

    @GET
    @Path("/accounts/{id}")
    public String account(@Context HttpHeaders headers, @PathParam("id") String id) {
        return json.write(reads.account(scope(headers), id));
    }

    @GET
    @Path("/accounts/{id}/position")
    public String position(@Context HttpHeaders headers, @PathParam("id") String id, @QueryParam("businessDate") String date,
            @QueryParam("boundarySide") String side, @QueryParam("eventWatermark") String maximum) {
        JsonNode scope = scope(headers);
        return json.write(reads.position(scope, id, boundary(scope, date, side, maximum)));
    }

    @GET
    @Path("/accounts/{id}/measurement")
    public String measurement(@Context HttpHeaders headers, @PathParam("id") String id, @QueryParam("businessDate") String date,
            @QueryParam("boundarySide") String side, @QueryParam("eventWatermark") String maximum) {
        JsonNode scope = scope(headers);
        return json.write(reads.measurement(scope, id, boundary(scope, date, side, maximum)));
    }

    @GET
    @Path("/accounts/{id}/schedule")
    public String schedule(@Context HttpHeaders headers, @PathParam("id") String id, @QueryParam("businessDate") String date,
            @QueryParam("boundarySide") String side, @QueryParam("eventWatermark") String maximum) {
        JsonNode scope = scope(headers);
        return json.write(reads.schedule(scope, id, boundary(scope, date, side, maximum)));
    }

    @GET
    @Path("/accounts/{id}/transactions")
    public String transactions(@Context HttpHeaders headers, @PathParam("id") String id, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return json.write(reads.transactions(scope(headers), id, cursor, limit));
    }

    @GET
    @Path("/events")
    public String events(@Context HttpHeaders headers, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit, @QueryParam("eventWatermark") String maximum) {
        JsonNode scope = scope(headers);
        return json.write(reads.events(scope, cursor, limit, boundary(scope, null, null, maximum).watermark()));
    }

    @GET
    @Path("/accounts")
    public String accounts(@Context HttpHeaders headers, @QueryParam("asOfDate") String date, @QueryParam("periodStartDate") String start,
            @QueryParam("eventWatermark") String maximum, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        JsonNode scope = scope(headers);
        var boundary = boundary(scope, date, null, maximum);
        return json.write(
                reads.accounts(scope, boundary.date(), boundary(scope, start, null, maximum).date(), boundary.watermark(), cursor, limit));
    }

    @GET
    @Path("/developer-lots")
    public String lots(@Context HttpHeaders headers, @QueryParam("businessDate") String date, @QueryParam("boundarySide") String side,
            @QueryParam("eventWatermark") String maximum, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        JsonNode scope = scope(headers);
        return json.write(reads.snapshots(scope, boundary(scope, date, side, maximum), "LOT", cursor, limit));
    }

    @GET
    @Path("/funding-facilities")
    public String facilities(@Context HttpHeaders headers, @QueryParam("businessDate") String date, @QueryParam("boundarySide") String side,
            @QueryParam("eventWatermark") String maximum, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        JsonNode scope = scope(headers);
        return json.write(reads.snapshots(scope, boundary(scope, date, side, maximum), "FUNDING", cursor, limit));
    }

    @GET
    @Path("/controls")
    public String controls(@Context HttpHeaders headers, @QueryParam("businessDate") String date, @QueryParam("boundarySide") String side,
            @QueryParam("eventWatermark") String maximum, @QueryParam("dealId") String deal, @QueryParam("accountId") String account) {
        JsonNode scope = scope(headers);
        return json.write(reads.controls(scope, boundary(scope, date, side, maximum), deal, account));
    }

    @GET
    @Path("/journals")
    public String journals(@Context HttpHeaders headers, @QueryParam("operationIds") List<String> ids,
            @QueryParam("eventWatermark") String maximum) {
        JsonNode scope = scope(headers);
        return json.write(reads.journals(scope, ids, boundary(scope, null, null, maximum).watermark()));
    }

    @GET
    @Path("/hel-terms/{id}")
    public String helTerms(@Context HttpHeaders headers, @PathParam("id") String id) {
        return json.write(reads.helTerms(scope(headers), id));
    }

    @GET
    @Path("/hel-journals")
    public String helJournals(@Context HttpHeaders headers, @QueryParam("loanId") long loanId, @QueryParam("transactionIds") List<Long> ids,
            @QueryParam("eventWatermark") String maximum) {
        JsonNode scope = scope(headers);
        return json.write(reads.helJournals(scope, loanId, ids, boundary(scope, null, null, maximum).watermark()));
    }
}
