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

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Staff-only loan read; scope and measurement are resolved by the server. */
@Component
@Path("/v1/loans/{loanId}/mnzl-investment")
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class ReceivablesInvestmentApiResource {

    private final ReceivablesInvestmentReadService reads;
    private final ReceivablesJson json;

    @GET
    public String read(@PathParam("loanId") long loanId,
            @QueryParam("includeProjections") @DefaultValue("false") boolean includeProjections) {
        return json.write(reads.read(loanId, includeProjections));
    }
}
