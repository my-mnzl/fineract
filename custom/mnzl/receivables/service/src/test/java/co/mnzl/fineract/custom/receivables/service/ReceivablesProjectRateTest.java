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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivablesProjectRateTest {

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesMeasurement measurement = new ReceivablesMeasurement(null, json, null);
    private final ReceivablesCalculationService service = new ReceivablesCalculationService(json, measurement);

    ReceivablesProjectRateTest() throws Exception {}

    private ObjectNode basis() {
        ObjectNode basis = (ObjectNode) json.read("""
                {"calculationVersion":"EG_RECEIVABLES_ACT360_DAILY_V1","productPolicyCode":"EG_RECEIVABLES_V1",
                "schemaVersion":"1","policyRevisionId":"policy-1","calculatorBuild":"build-1",
                "settlementDate":"2027-04-14","feeRate":"0.01","sourceVersion":"1","acceptedAccountPrices":[],
                "cashflows":[
                  {"cashflowId":"a","receivableId":"account","installmentId":"a","dueDate":"2027-07-01",
                   "amountMinor":"4000000","currency":"EGP","scheduleVersionId":"schedule"},
                  {"cashflowId":"b","receivableId":"account","installmentId":"b","dueDate":"2028-01-01",
                   "amountMinor":"6000000","currency":"EGP","scheduleVersionId":"schedule"}],
                "acquisitionRateBasis":{"kind":"PROJECT_RATE","projectId":"project-1","projectRateRevisionId":"revision-1",
                  "effectiveDate":"2027-03-01","pricingDate":"2027-02-28","rateLookupDate":"2027-03-01",
                  "annualNominalRate":"0.18"}}
                """);
        basis.put("sourceHash", "0".repeat(64));
        ((ObjectNode) basis.get("acquisitionRateBasis")).put("projectRateContentHash", "a".repeat(64));
        return basis;
    }

    private ObjectNode request(ObjectNode basis, boolean estimate) {
        ObjectNode request = basis.deepCopy().retain("calculationVersion", "productPolicyCode", "schemaVersion", "policyRevisionId",
                "calculatorBuild");
        request.put("calculationType", "PRICE");
        request.put("includeProjections", !estimate);
        request.set("basis", basis);
        request.put("basisHash", json.hash(json.normalizedBasis(basis)));
        return request;
    }

    private JsonNode calculate(ObjectNode basis, boolean estimate) {
        return service.calculate(json.write(request(basis, estimate))).path("pricing");
    }

    @Test
    void projectRateMatchesEstablishedPricesAndEstimatesForBothAccretionRules() {
        for (String version : ReceivablesConfiguration.CALCULATIONS) {
            for (String rate : List.of("0", "0.10", "0.15", "0.18", "0.221")) {
                ObjectNode project = basis();
                project.put("calculationVersion", version);
                ((ObjectNode) project.get("acquisitionRateBasis")).put("annualNominalRate", rate);
                ObjectNode legacy = project.deepCopy();
                legacy.remove("acquisitionRateBasis");
                legacy.put("corridorObservationId", "observed-rate");
                legacy.put("corridorRate", rate.equals("0.18") ? "0.16" : rate);
                legacy.put("spread", rate.equals("0.18") ? "0.02" : "0");
                JsonNode original = calculate(legacy, false);
                JsonNode full = calculate(project, false);
                JsonNode estimate = calculate(project, true);
                assertThat(full.path("totals")).isEqualTo(original.path("totals"));
                assertThat(full.path("accounts")).isEqualTo(original.path("accounts"));
                assertThat(estimate.path("totals")).isEqualTo(full.path("totals"));
                assertThat(full.path("basis")).isEqualTo(project);
                assertThat(estimate.path("basisHash")).isEqualTo(full.path("basisHash"));
                assertThat(measurement.purchase(project, "account").segment().calculationVersion()).isEqualTo(version);
            }
        }
    }

    @Test
    void acceptedPurchaseIsRecalculatedFromItsPinnedRateAndRejectsChangedAmounts() {
        ObjectNode project = basis();
        ObjectNode accepted = calculate(project, false).path("accounts").get(0).deepCopy();
        accepted.retain("accountId", "grossPurchasePriceMinor", "integralFeeMinor", "netPurchaseCashMinor");
        project.set("acceptedAccountPrices", json.value(List.of(accepted)));
        assertThat(measurement.purchase(project, "account").netPurchaseCashMinor().toString())
                .isEqualTo(accepted.path("netPurchaseCashMinor").asText());
        ((ObjectNode) project.get("acquisitionRateBasis")).put("annualNominalRate", "0.21");
        assertThatThrownBy(() -> calculate(project, false)).isInstanceOf(ReceivablesException.class).hasMessage("SOURCE_CHANGED");
    }

    @Test
    void hashBindsProjectRateAndDateProvenance() {
        ObjectNode request = request(basis(), false);
        ObjectNode rate = (ObjectNode) request.path("basis").path("acquisitionRateBasis");
        for (String field : List.of("projectId", "projectRateRevisionId", "projectRateContentHash", "annualNominalRate",
                "rateLookupDate")) {
            String original = rate.path(field).asText();
            rate.put(field, field.equals("annualNominalRate") ? "0.21"
                    : field.equals("rateLookupDate") ? "2027-03-02" : field.equals("projectRateContentHash") ? "b".repeat(64) : "changed");
            assertThatThrownBy(() -> service.calculate(json.write(request))).isInstanceOf(ReceivablesException.class)
                    .hasMessage("SOURCE_CHANGED");
            rate.put(field, original);
        }
    }

    @Test
    void rejectsMixedIncompleteInvalidAndInconsistentRateBases() {
        for (boolean estimate : List.of(false, true)) {
            for (String field : List.of("corridorObservationId", "corridorRate", "spread")) {
                ObjectNode project = basis();
                project.put(field, field.equals("corridorObservationId") ? "unrelated" : "0.20");
                assertThatThrownBy(() -> calculate(project, estimate)).isInstanceOf(ReceivablesException.class).hasMessage("INVALID_DATA");
            }
            for (String rate : List.of("-0.01", "NaN", "1e-2", "0." + "1".repeat(31), "1".repeat(36))) {
                ObjectNode project = basis();
                ((ObjectNode) project.get("acquisitionRateBasis")).put("annualNominalRate", rate);
                assertThatThrownBy(() -> calculate(project, estimate)).isInstanceOf(ReceivablesException.class).hasMessage("INVALID_DATA");
            }
            for (String field : List.of("effectiveDate", "rateLookupDate")) {
                ObjectNode project = basis();
                ((ObjectNode) project.get("acquisitionRateBasis")).put(field, "2027-03-02");
                assertThatThrownBy(() -> calculate(project, estimate)).isInstanceOf(ReceivablesException.class).hasMessage("INVALID_DATA");
            }
            ObjectNode incomplete = basis();
            ((ObjectNode) incomplete.get("acquisitionRateBasis")).remove("projectRateRevisionId");
            assertThatThrownBy(() -> calculate(incomplete, estimate)).isInstanceOf(ReceivablesException.class).hasMessage("INVALID_DATA");
        }
    }

    @Test
    void canonicalHashesMatchTheTypeScriptWireVectorsWithoutChangingLegacyShapes() {
        JsonNode vectors = json.read("""
                [
                  {
                    "basis": {
                      "calculationVersion": "EG_RECEIVABLES_ACT360_DAILY_V1",
                      "productPolicyCode": "EG_RECEIVABLES_V1",
                      "schemaVersion": "1",
                      "policyRevisionId": "policy-1",
                      "calculatorBuild": "build-1",
                      "settlementDate": "2027-04-14",
                      "feeRate": "0",
                      "acceptedAccountPrices": [],
                      "sourceVersion": "1",
                      "sourceHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "cashflows": [
                        {
                          "cashflowId": "cashflow-1",
                          "receivableId": "receivable-1",
                          "installmentId": "installment-1",
                          "dueDate": "2028-01-01",
                          "amountMinor": "1000000",
                          "currency": "EGP",
                          "scheduleVersionId": "schedule-1"
                        }
                      ],
                      "corridorObservationId": "corridor-1",
                      "corridorRate": "0.16",
                      "spread": "0.02"
                    },
                    "hash": "1f5bf9eea5dec211f88c38729f3de00874ef9258a29fde2ddc63f4a8aca997f3"
                  },
                  {
                    "basis": {
                      "calculationVersion": "EG_RECEIVABLES_ACT360_DAILY_V1",
                      "productPolicyCode": "EG_RECEIVABLES_V1",
                      "schemaVersion": "1",
                      "policyRevisionId": "policy-1",
                      "calculatorBuild": "build-1",
                      "settlementDate": "2027-04-14",
                      "feeRate": "0",
                      "acceptedAccountPrices": [],
                      "sourceVersion": "1",
                      "sourceHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "cashflows": [
                        {
                          "cashflowId": "cashflow-1",
                          "receivableId": "receivable-1",
                          "installmentId": "installment-1",
                          "dueDate": "2028-01-01",
                          "amountMinor": "1000000",
                          "currency": "EGP",
                          "scheduleVersionId": "schedule-1"
                        }
                      ],
                      "acquisitionRateBasis": {
                        "kind": "PROJECT_RATE",
                        "projectId": "project-1",
                        "projectRateRevisionId": "rate-1",
                        "projectRateContentHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "effectiveDate": "2027-03-01",
                        "pricingDate": "2027-02-28",
                        "rateLookupDate": "2027-03-01",
                        "annualNominalRate": "0.18"
                      }
                    },
                    "hash": "a6dab404729d1204f7e09c463473029c75e6cc165677d8ce98b1fd2d75f76b62"
                  }
                ]
                """);
        for (JsonNode vector : vectors) {
            JsonNode basis = vector.path("basis");
            assertThat(json.validate("calculationBasis", json.write(basis))).isEqualTo(basis);
            assertThat(json.hash(json.normalizedBasis(basis))).isEqualTo(vector.path("hash").asText());
        }
    }

    @Test
    void fixedRateResetIsRejectedBeforeAccrualOrCorrectionEffects() {
        var store = mock(ReceivablesStore.class);
        var measurement = mock(ReceivablesMeasurement.class);
        var accounts = new ReceivablesAccountCommands(store, json, measurement, null, null, null, null);
        for (String mode : List.of("CURRENT", "CORRECTION")) {
            var command = json.object().put("operationId", "reset-" + mode).put("businessDate", "2027-05-01").put("subjectId", "account")
                    .put("executionMode", mode).put("originalResetOperationId", "old-reset");
            var execution = new ReceivablesExecution(command, "scope", Map.of());
            when(store.require("account", execution.accountKey("account"))).thenReturn(Map.of("terms_json", json.write(basis())));
            assertThatThrownBy(() -> accounts.reset(execution)).isInstanceOf(ReceivablesException.class).hasMessage("INVALID_DATA");
            assertThat(execution.lines).isEmpty();
            assertThat(execution.nativeTransactions).isEmpty();
        }
        verify(store, org.mockito.Mockito.times(2)).require("account", ReceivablesStore.key("scope", "account", "account"));
        verifyNoMoreInteractions(store);
        verifyNoInteractions(measurement);
    }
}
