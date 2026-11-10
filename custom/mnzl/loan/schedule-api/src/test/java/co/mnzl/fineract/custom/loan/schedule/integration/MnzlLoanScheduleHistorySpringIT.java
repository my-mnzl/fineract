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
package co.mnzl.fineract.custom.loan.schedule.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.schedule.JdbcMnzlLoanScheduleHistoryService;
import co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleHistoryApiResource;
import co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleHistoryReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * L2 Spring slice for the schedule history endpoint: the package scan wires the JAX-RS resource and the JDBC read
 * service over an embedded H2 database seeded with the history, reschedule request and term variation tables, so the
 * real SQL (joins, office scoping, column mapping) runs end to end.
 *
 * <p>
 * {@code mnzl.loan.schedule.enabled=false} keeps {@code MnzlLoanScheduleApiConfiguration} (30+ collaborators, see
 * {@link MnzlLoanReadPlatformServiceSpringIT}) out of the slice, which also proves the endpoint does not depend on it.
 * </p>
 */
class MnzlLoanScheduleHistorySpringIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulePackageScan.class, HistoryCollaborators.class)
            .withPropertyValues("mnzl.loan.schedule.enabled=false");

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "Africa/Cairo", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void returnsArchivedVersionsWithTheirRescheduleRequest() {
        contextRunner.run(ctx -> {
            JsonNode json = JSON.readTree(ctx.getBean(MnzlLoanScheduleHistoryApiResource.class).retrieve(100L));

            assertThat(json.get("loanId").asLong()).isEqualTo(100L);
            assertThat(json.get("versions")).hasSize(3);

            JsonNode first = json.get("versions").get(0);
            assertThat(first.get("version").asInt()).isEqualTo(1);
            // No reschedule request: the installment stamp, here created_date only, read in Africa/Cairo (UTC+2 in
            // February).
            assertThat(first.get("archivedAt").asText()).isEqualTo("2026-02-17T11:20:26Z");
            assertThat(first.get("rescheduleRequest").isNull()).isTrue();
            assertThat(first.get("periods")).extracting(period -> period.get("installment").asInt()).containsExactly(1, 2);
            assertThat(first.get("periods").get(0).get("installment").asInt()).isEqualTo(1);
            assertThat(first.get("periods").get(0).get("fromDate").asText()).isEqualTo("2025-12-08");
            assertThat(first.get("periods").get(0).get("dueDate").asText()).isEqualTo("2026-01-01");
            assertThat(first.get("periods").get(1).get("interest").decimalValue()).isEqualByComparingTo("300.00");
            assertThat(first.get("periods").get(1).get("penalties").decimalValue()).isEqualByComparingTo("0");

            JsonNode second = json.get("versions").get(1);
            // The processed APPROVE command, not the installment stamp (2026-07-08) the archive rows copied.
            assertThat(second.get("archivedAt").asText()).isEqualTo("2026-11-08T14:05:03Z");
            assertThat(second.get("periods").get(0).get("principal").decimalValue()).isEqualByComparingTo("1000.00");
            JsonNode request = second.get("rescheduleRequest");
            assertThat(request.get("id").asLong()).isEqualTo(1L);
            assertThat(request.get("status").asText()).isEqualTo("Approved");
            assertThat(request.get("rescheduleFromDate").asText()).isEqualTo("2026-12-01");
            assertThat(request.get("rescheduleFromInstallment").asInt()).isEqualTo(2);
            assertThat(request.get("reason").asText()).isEqualTo("CBE rate change");
            assertThat(request.get("comment").isNull()).isTrue();
            assertThat(request.get("submittedOnDate").asText()).isEqualTo("2026-11-12");
            assertThat(request.get("submittedByUsername").asText()).isEqualTo("mifos");
            assertThat(request.get("approvedOnDate").asText()).isEqualTo("2026-11-12");
            assertThat(request.get("approvedByUsername").asText()).isEqualTo("approver");
            assertThat(request.get("termVariations")).hasSize(1);
            JsonNode variation = request.get("termVariations").get(0);
            assertThat(variation.get("type").asText()).isEqualTo("interestRateFromInstallment");
            assertThat(variation.get("applicableFrom").asText()).isEqualTo("2026-12-01");
            assertThat(variation.get("decimalValue").decimalValue()).isEqualByComparingTo("26");
            assertThat(variation.get("dateValue").isNull()).isTrue();

            JsonNode third = json.get("versions").get(2);
            assertThat(third.get("rescheduleRequest").get("id").asLong()).isEqualTo(2L);
            // No approval command recorded: start of the approval business day (3 Dec) in tenant time.
            assertThat(third.get("archivedAt").asText()).isEqualTo("2026-12-02T22:00:00Z");
            assertThat(third.get("rescheduleRequest").get("termVariations")).isEmpty();
        });
    }

    @Test
    void archivedAtDoesNotDependOnTheJvmDefaultZone() {
        TimeZone jvmZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            contextRunner.run(ctx -> {
                JsonNode versions = JSON.readTree(ctx.getBean(MnzlLoanScheduleHistoryApiResource.class).retrieve(100L)).get("versions");

                assertThat(versions).extracting(version -> version.get("archivedAt").asText()).containsExactly("2026-02-17T11:20:26Z",
                        "2026-11-08T14:05:03Z", "2026-12-02T22:00:00Z");
            });
        } finally {
            TimeZone.setDefault(jvmZone);
        }
    }

    @Test
    void loanWithoutArchiveHasEmptyVersions() {
        contextRunner.run(ctx -> {
            JsonNode json = JSON.readTree(ctx.getBean(MnzlLoanScheduleHistoryApiResource.class).retrieve(101L));

            assertThat(json.get("loanId").asLong()).isEqualTo(101L);
            assertThat(json.get("versions").isArray()).isTrue();
            assertThat(json.get("versions")).isEmpty();
        });
    }

    @Test
    void unknownLoanAndLoanOutsideTheCallersOfficeAreNotFound() {
        contextRunner.run(ctx -> {
            MnzlLoanScheduleHistoryReadService service = ctx.getBean(MnzlLoanScheduleHistoryReadService.class);

            assertThatThrownBy(() -> service.retrieveScheduleHistory(999L)).isInstanceOf(LoanNotFoundException.class);
            assertThatThrownBy(() -> service.retrieveScheduleHistory(102L)).isInstanceOf(LoanNotFoundException.class);
        });
    }

    @Test
    void scheduleHistoryDisabledRemovesTheEndpoint() {
        contextRunner.withPropertyValues("mnzl.loan.scheduleHistory.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MnzlLoanScheduleHistoryApiResource.class);
            assertThat(ctx).doesNotHaveBean(JdbcMnzlLoanScheduleHistoryService.class);
        });
    }

    @Configuration
    @ComponentScan(basePackages = "co.mnzl.fineract.custom.loan.schedule", excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX, pattern = "co\\.mnzl\\.fineract\\.custom\\.loan\\.schedule\\.integration\\..*"))
    static class SchedulePackageScan {}

    @Configuration
    static class HistoryCollaborators {

        @Bean(destroyMethod = "shutdown")
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:co/mnzl/fineract/custom/loan/schedule/integration/schedule-history.sql").build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformSecurityContext platformSecurityContext() {
            Office office = mock(Office.class);
            when(office.getHierarchy()).thenReturn(".2.");
            AppUser user = mock(AppUser.class);
            when(user.getOffice()).thenReturn(office);
            PlatformSecurityContext context = mock(PlatformSecurityContext.class);
            when(context.authenticatedUser()).thenReturn(user);
            return context;
        }
    }
}
