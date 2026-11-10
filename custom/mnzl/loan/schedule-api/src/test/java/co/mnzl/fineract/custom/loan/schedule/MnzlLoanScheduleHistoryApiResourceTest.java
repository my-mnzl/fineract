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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MnzlLoanScheduleHistoryApiResourceTest {

    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final MnzlLoanScheduleHistoryReadService readService = mock(MnzlLoanScheduleHistoryReadService.class);
    private final MnzlLoanScheduleHistoryApiResource resource = new MnzlLoanScheduleHistoryApiResource(context, readService);

    @BeforeEach
    void setUp() {
        when(context.authenticatedUser()).thenReturn(user);
    }

    @Test
    void checksLoanReadPermissionBeforeReading() throws Exception {
        when(readService.retrieveScheduleHistory(7L)).thenReturn(new MnzlLoanScheduleHistoryData(7L, List.of()));

        JsonNode json = MnzlLoanScheduleHistoryApiResource.JSON.readTree(resource.retrieve(7L));

        verify(user).validateHasReadPermission("LOAN");
        assertThat(json.get("loanId").asLong()).isEqualTo(7L);
        assertThat(json.get("versions").isArray()).isTrue();
        assertThat(json.get("versions")).isEmpty();
    }

    @Test
    void deniedPermissionNeverReads() {
        doThrow(new NoAuthorizationException("no")).when(user).validateHasReadPermission("LOAN");

        assertThatThrownBy(() -> resource.retrieve(7L)).isInstanceOf(NoAuthorizationException.class);
        verify(readService, never()).retrieveScheduleHistory(anyLong());
    }

    @Test
    void writesIsoDatesPlainAmountsAndExplicitNulls() throws Exception {
        MnzlLoanScheduleHistoryData data = new MnzlLoanScheduleHistoryData(23297L, List.of(
                new MnzlLoanScheduleHistoryData.Version(1, Instant.parse("2026-11-13T10:02:11Z"),
                        new MnzlLoanScheduleHistoryData.RescheduleRequest(12L, "Approved", LocalDate.of(2026, 11, 15), 5, "CBE rate change",
                                null, LocalDate.of(2026, 11, 13), "mifos", LocalDate.of(2026, 11, 13), "mifos",
                                List.of(new MnzlLoanScheduleHistoryData.TermVariation("interestRate", LocalDate.of(2026, 11, 15),
                                        new BigDecimal("26"), null))),
                        List.of(new MnzlLoanScheduleHistoryData.Period(1, LocalDate.of(2026, 6, 8), LocalDate.of(2026, 7, 15),
                                new BigDecimal("9999.99"), new BigDecimal("26982.95"), new BigDecimal("20.83"), new BigDecimal("0.00")))),
                new MnzlLoanScheduleHistoryData.Version(2, null, null, List.of())));

        JsonNode json = MnzlLoanScheduleHistoryApiResource.JSON.readTree(MnzlLoanScheduleHistoryApiResource.write(data));

        JsonNode first = json.get("versions").get(0);
        assertThat(first.get("archivedAt").asText()).isEqualTo("2026-11-13T10:02:11Z");
        JsonNode request = first.get("rescheduleRequest");
        assertThat(request.get("rescheduleFromDate").asText()).isEqualTo("2026-11-15");
        assertThat(request.has("comment")).isTrue();
        assertThat(request.get("comment").isNull()).isTrue();
        JsonNode variation = request.get("termVariations").get(0);
        assertThat(variation.get("decimalValue").isNumber()).isTrue();
        assertThat(variation.get("decimalValue").decimalValue()).isEqualByComparingTo("26");
        assertThat(variation.get("dateValue").isNull()).isTrue();
        JsonNode period = first.get("periods").get(0);
        assertThat(period.get("dueDate").asText()).isEqualTo("2026-07-15");
        assertThat(period.get("principal").decimalValue()).isEqualByComparingTo("9999.99");
        assertThat(period.get("penalties").decimalValue()).isEqualByComparingTo("0");
        JsonNode second = json.get("versions").get(1);
        assertThat(second.get("rescheduleRequest").isNull()).isTrue();
        assertThat(second.get("archivedAt").isNull()).isTrue();
    }
}
