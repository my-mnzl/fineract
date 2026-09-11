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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceivablesPricingApiTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesCommandService commands = mock(ReceivablesCommandService.class);
    private final ReceivablesConfiguration configuration = mock(ReceivablesConfiguration.class);
    private final PlatformSecurityContext security = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final HttpHeaders headers = mock(HttpHeaders.class);
    private final ReceivablesWriteApiResource api = new ReceivablesWriteApiResource(commands, mock(ReceivablesMaintenance.class),
            new ReceivablesCalculationService(json, new ReceivablesMeasurement(null, json, null)), configuration, json, security);

    ReceivablesPricingApiTest() throws Exception {}

    @BeforeEach
    void configureTrustedOwner() {
        when(security.authenticatedUser()).thenReturn(user);
        when(configuration.authorize(any(), eq(false))).thenReturn(Map.of("policy_revision", "mnzl-policy", "calculator_build", "build-1"));
        when(headers.getHeaderString("X-MNZL-Platform")).thenReturn("platform");
        when(headers.getHeaderString("X-MNZL-Financier")).thenReturn("mnzl");
        when(headers.getHeaderString("X-MNZL-Environment")).thenReturn("test");
        when(headers.getHeaderString("X-MNZL-Ledger-Epoch")).thenReturn("epoch");
    }

    private ObjectNode request(String type) {
        ObjectNode input = (ObjectNode) json.read("""
                {"calculationVersion":"EG_RECEIVABLES_ACT360_DAILY_V1","productPolicyCode":"EG_RECEIVABLES_V1",
                "schemaVersion":"1","policyRevisionId":"external-authentic-policy","calculatorBuild":"build-1"}
                """);
        ObjectNode basis = input.deepCopy();
        input.put("calculationType", type);
        if (type.equals("PRICE")) {
            basis.put("settlementDate", "2026-09-11");
            basis.put("corridorRate", "0");
            basis.put("spread", "0");
            basis.put("feeRate", "0");
            basis.put("corridorObservationId", "external-rate");
            basis.put("sourceVersion", "1");
            basis.put("sourceHash", "0".repeat(64));
            basis.set("acceptedAccountPrices", json.read("[]"));
            basis.set("cashflows", json.read("""
                    [{"cashflowId":"flow","installmentId":"installment","receivableId":"external-receivable",
                    "currency":"EGP","scheduleVersionId":"schedule","dueDate":"2026-10-11","amountMinor":"100"}]
                    """));
            input.set("basis", basis);
        } else {
            input.set("terms", json.read("""
                    {"facilityId":"external-assumptions","currency":"EGP","annualNominalRate":"0",
                    "dayCount":"ACTUAL_360_SIMPLE","capitalizeInterest":false}
                    """));
            input.put("fromDate", "2026-09-11");
            input.put("throughDate", "2026-09-12");
            input.put("openingPrincipalMinor", "100");
            input.set("events", json.read("[]"));
        }
        input.put("basisHash", json.hash(type.equals("PRICE") ? basis : input));
        return input;
    }

    @Test
    void preservesExternalPolicyAndPureMathWithoutCommands() {
        for (String type : new String[] { "PRICE", "FUNDING" }) {
            ObjectNode input = request(type);
            var result = json.read(api.calculatePricing(headers, json.write(input)));
            assertThat(result.path("policyRevisionId").asText()).isEqualTo("external-authentic-policy");
            assertThat(result.path("basisHash")).isEqualTo(input.path("basisHash"));
            assertThat(result.path("calculationType").asText()).isEqualTo(type);
        }
        verifyNoInteractions(commands);
        verify(user, org.mockito.Mockito.times(2)).validateHasPermissionTo("CALCULATE_MNZL_RECEIVABLES");
    }

    @Test
    void retainsLedgerPolicyCheckOnOriginalCalculationRoute() {
        assertThatThrownBy(() -> api.calculate(headers, json.write(request("PRICE")))).isInstanceOf(ReceivablesException.class);
        verifyNoInteractions(commands);
    }

    @Test
    void refusesUnsupportedTypesVersionsBuildsAndChangedBasis() {
        for (String field : new String[] { "calculationType", "calculatorBuild", "schemaVersion", "basisHash" }) {
            ObjectNode input = request("PRICE");
            input.put(field, field.equals("calculationType") ? "SCHEDULE" : "invalid");
            assertThatThrownBy(() -> api.calculatePricing(headers, json.write(input))).isInstanceOf(ReceivablesException.class);
        }
        ObjectNode input = request("PRICE");
        ((ObjectNode) input.get("basis")).put("policyRevisionId", "changed");
        assertThatThrownBy(() -> api.calculatePricing(headers, json.write(input))).isInstanceOf(ReceivablesException.class);
        verifyNoInteractions(commands);
    }

    @Test
    void requiresTheConfiguredIntegrationUserBeforeCalculation() {
        when(configuration.authorize(any(), eq(false))).thenThrow(new ReceivablesException("FINERACT_CAPABILITY_MISSING"));
        assertThatThrownBy(() -> api.calculatePricing(headers, json.write(request("PRICE")))).isInstanceOf(ReceivablesException.class);
        verifyNoInteractions(commands);
    }
}
