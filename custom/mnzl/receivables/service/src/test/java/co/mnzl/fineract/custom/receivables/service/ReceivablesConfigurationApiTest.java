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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionSystemException;

class ReceivablesConfigurationApiTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesConfiguration configuration = mock(ReceivablesConfiguration.class);
    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final HttpHeaders headers = mock(HttpHeaders.class);
    private final ReceivablesWriteApiResource api = new ReceivablesWriteApiResource(null, null, configuration, json, security);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ReceivablesWriteApiResource.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    ReceivablesConfigurationApiTest() throws Exception {}

    @BeforeEach
    void authenticateAndCaptureLogs() {
        when(security.authenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(16L);
        when(configuration.tenantId()).thenReturn("PRIVATE_AUTHENTICATED_TENANT");
        when(headers.getHeaderString("X-MNZL-Platform")).thenReturn("private-platform");
        when(headers.getHeaderString("X-MNZL-Financier")).thenReturn("private-financier");
        when(headers.getHeaderString("X-MNZL-Environment")).thenReturn("test");
        when(headers.getHeaderString("X-MNZL-Account-Mapping")).thenReturn("revision-2");
        when(configuration.scopeKey(any())).thenAnswer(invocation -> json.hash(invocation.getArgument(0)));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private ObjectNode request() {
        var input = json.object().put("activationId", "activation-1").put("accountMappingRevisionId", "revision-2")
                .put("expectedActiveAccountMappingRevisionId", "revision-1").put("bankAccountReference", "PRIVATE_BANK_REFERENCE")
                .put("contentHash", "a".repeat(64));
        input.set("scope", json.object().put("platformId", "private-platform").put("financierOrganizationId", "private-financier")
                .put("environment", "test"));
        return input;
    }

    private JsonNode event() {
        assertThat(logs.list).hasSize(1);
        var message = logs.list.getFirst().getFormattedMessage();
        assertThat(message).startsWith("MNZL receivables configuration ").doesNotContain("PRIVATE_BANK_REFERENCE", "private-platform",
                "private-financier");
        return json.read(message.substring(message.indexOf('{')));
    }

    @Test
    void recordsCommittedActivationAndExactRetryWithoutLoggingConfiguration() {
        var response = json.object().put("activationId", "activation-1").put("previousAccountMappingRevisionId", "revision-1")
                .put("accountMappingRevisionId", "revision-2").put("activatedAt", "2026-09-13T12:00:00Z");
        when(configuration.activate(any(), anyString())).thenReturn(response);
        for (int attempt = 0; attempt < 2; attempt++) {
            logs.list.clear();
            assertThat(json.read(api.activate(headers, json.write(request())))).isEqualTo(response);
            var event = event();
            assertThat(event.path("action").asText()).isEqualTo("ACTIVATE");
            assertThat(event.path("outcome").asText()).isEqualTo("SUCCEEDED");
            assertThat(event.path("rejectionCode").isNull()).isTrue();
            assertThat(event.path("actorId").asLong()).isEqualTo(16);
            assertThat(event.path("tenantHash").asText()).isEqualTo(ReceivablesJson.hashText("PRIVATE_AUTHENTICATED_TENANT"));
            assertThat(event.path("scopeHash").asText()).isEqualTo(json.hash(request().path("scope")));
            assertThat(event.path("activationId").asText()).isEqualTo("activation-1");
            assertThat(event.path("sourceRevision").asText()).isEqualTo("revision-1");
            assertThat(event.path("expectedActiveRevision").asText()).isEqualTo("revision-1");
            assertThat(event.path("targetRevision").asText()).isEqualTo("revision-2");
            assertThat(event.path("activatedAt").asText()).isEqualTo("2026-09-13T12:00:00Z");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "ACCOUNT_VERSION_CHANGED", "PERIOD_CLOSED", "IDEMPOTENCY_CONFLICT", "FINERACT_CAPABILITY_MISSING" })
    void recordsActivationRejectionsWithoutReplacingTheNativeError(String code) {
        var failure = new ReceivablesException(code);
        when(configuration.activate(any(), anyString())).thenThrow(failure);
        assertThatThrownBy(() -> api.activate(headers, json.write(request()))).isSameAs(failure);
        var event = event();
        assertThat(event.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(event.path("rejectionCode").asText()).isEqualTo(code);
        assertThat(event.path("expectedActiveRevision").asText()).isEqualTo("revision-1");
        assertThat(event.path("sourceRevision").isNull()).isTrue();
    }

    @Test
    void distinguishesAuthenticatedTenantsWithTheSameScopeAndActor() {
        when(configuration.stage(anyString())).thenReturn(json.object().put("state", "STAGED"));
        when(headers.getHeaderString("Fineract-Platform-TenantId")).thenReturn("untrusted-header");
        api.stage(headers, json.write(request()));
        var first = event();
        logs.list.clear();
        when(configuration.tenantId()).thenReturn("other-authenticated-tenant");
        api.stage(headers, json.write(request()));
        var second = event();
        assertThat(second.path("tenantHash").asText()).isEqualTo(ReceivablesJson.hashText("other-authenticated-tenant"))
                .isNotEqualTo(first.path("tenantHash").asText());
        assertThat(second.path("scopeHash")).isEqualTo(first.path("scopeHash"));
        assertThat(second.path("actorId")).isEqualTo(first.path("actorId"));
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("other-authenticated-tenant", "untrusted-header");
    }

    @Test
    void recordsNativePermissionDenialWithoutLoggingTheExceptionMessage() {
        var failure = new NoAuthorizationException("PRIVATE_PERMISSION_CONTENT");
        when(configuration.stage(anyString())).thenThrow(failure);
        assertThatThrownBy(() -> api.stage(headers, json.write(request()))).isSameAs(failure);
        var event = event();
        assertThat(event.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(event.path("rejectionCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("PRIVATE_PERMISSION_CONTENT");
    }

    @Test
    void logsCommitFailureAsFailedRatherThanSuccessfulActivation() {
        var failure = new TransactionSystemException("PRIVATE_DATABASE_CONTENT");
        when(configuration.activate(any(), anyString())).thenThrow(failure);
        assertThatThrownBy(() -> api.activate(headers, json.write(request()))).isSameAs(failure);
        var event = event();
        assertThat(event.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(event.path("rejectionCode").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("PRIVATE_DATABASE_CONTENT");
    }

    @Test
    void recordsStagingSuccessAndConflict() {
        when(configuration.stage(anyString())).thenReturn(json.object().put("state", "STAGED"));
        api.stage(headers, json.write(request()));
        assertThat(event().path("revisionState").asText()).isEqualTo("STAGED");
        logs.list.clear();
        when(configuration.stage(anyString())).thenThrow(new ReceivablesException("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> api.stage(headers, json.write(request()))).isInstanceOf(ReceivablesException.class);
        assertThat(event().path("action").asText()).isEqualTo("STAGE");
        assertThat(event().path("rejectionCode").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void logsMalformedRequestsAndHeaderRejectionsWithoutRawInputOrLogInjection() {
        assertThatThrownBy(() -> api.activate(headers, "PRIVATE_BANK_REFERENCE")).isInstanceOf(ReceivablesException.class);
        assertThat(event().path("rejectionCode").asText()).isEqualTo("INVALID_DATA");
        logs.list.clear();
        var input = request().put("activationId", "PRIVATE_BANK_REFERENCE\nforged-entry");
        when(headers.getHeaderString("X-MNZL-Account-Mapping")).thenReturn("wrong-revision");
        assertThatThrownBy(() -> api.activate(headers, json.write(input))).isInstanceOf(ReceivablesException.class);
        var event = event();
        assertThat(event.path("activationId").asText()).startsWith("sha256:");
        assertThat(event.path("rejectionCode").asText()).isEqualTo("FINERACT_CAPABILITY_MISSING");
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("forged-entry");
    }
}
