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
package co.mnzl.fineract.custom.loan.schedule;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Archived repayment schedules of a loan, versions ascending. Each version's {@code rescheduleRequest} is the
 * reschedule that replaced that version: its term variations describe the next version. See
 * {@link MnzlLoanScheduleHistoryData}.
 */
@Component
@ConditionalOnProperty(name = "mnzl.loan.scheduleHistory.enabled", havingValue = "true", matchIfMissing = true)
@Path("/v1/mnzl/loans/{loanId}/schedule-history")
@RequiredArgsConstructor
public class MnzlLoanScheduleHistoryApiResource {

    // Same permission as GET /loans/{loanId} (LoansApiResource): READ_LOAN.
    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "LOAN";

    // Fineract's Gson drops nulls and writes dates as arrays; the contract wants explicit nulls and ISO dates.
    static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).build();

    private final PlatformSecurityContext context;
    private final MnzlLoanScheduleHistoryReadService readService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String retrieve(@PathParam("loanId") Long loanId) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        return write(readService.retrieveScheduleHistory(loanId));
    }

    static String write(MnzlLoanScheduleHistoryData data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
