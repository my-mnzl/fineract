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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReceivablesHistoricalPurchaseTest {

    @Test
    void pendingAssessmentBlocksCloseForExposureButNotFullyCollectedAccounts() throws Exception {
        var store = mock(ReceivablesStore.class);
        var json = new ReceivablesJson();
        var accounts = spy(new ReceivablesAccountCommands(store, json, null, null, null, null, null));
        var e = new ReceivablesExecution(json.object().put("operationId", "close").put("businessDate", "2026-09-14"), "scope", Map.of());
        Map<String, Object> pending = Map.of("record_key", "account", "face_minor", "100", "risk_assessment_status", "PENDING");
        when(store.require("account", "account")).thenReturn(pending);
        doNothing().when(accounts).accrue(e, pending);
        assertThatThrownBy(() -> accounts.closeAccrue(e, pending, Set.of())).isInstanceOf(ReceivablesException.class)
                .hasMessage("RISK_ASSESSMENT_PENDING");
        when(store.require("account", "account"))
                .thenReturn(Map.of("record_key", "account", "face_minor", "0", "risk_assessment_status", "PENDING"));
        accounts.closeAccrue(e, pending, Set.of());
        assertThat(e.lines).isEmpty();
    }

    @Test
    void historicalCommandRequiresExplicitSingleOperatorAcknowledgmentWithoutSyntheticEvidence() throws Exception {
        var json = new ReceivablesJson();
        var command = json
                .read("""
                        {"commandType":"BOOK_HISTORICAL_PURCHASE","executionMode":"CURRENT","executionAuthorization":null,
                        "accountMappingRevisionId":"map","operationId":"book","idempotencyKey":"book","expectedVersion":"0",
                        "businessDate":"2026-09-06","basisHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "scope":{"platformId":"mnzl","financierOrganizationId":"mnzl-financier","developerOrganizationId":"developer","environment":"test"},
                        "subjectId":"account","subjectKind":"RECEIVABLE","affectedAccountVersions":[],
                        "executionScopeHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "actorId":"employee","approverIds":[],"approvalEvidenceIds":[],"accountId":"account","dealId":"deal","customerReferenceId":"customer",
                        "acquisition":{"id":"acquisition","developerReferenceId":"developer","sourceReferenceId":"record","effectiveDate":"2026-09-06"},
                        "basis":{"calculationVersion":"EG_RECEIVABLES_ACT360_DAILY_V1","productPolicyCode":"EG_RECEIVABLES_V1","schemaVersion":"1","policyRevisionId":"policy","calculatorBuild":"build",
                        "settlementDate":"2026-09-06","corridorObservationId":"historical-rate","corridorRate":"0.2","spread":"0.021","feeRate":"0.01","sourceVersion":"1",
                        "sourceHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","acceptedAccountPrices":[],
                        "cashflows":[{"cashflowId":"flow","receivableId":"account","installmentId":"installment","dueDate":"2027-01-01","amountMinor":"10000","currency":"EGP","scheduleVersionId":"schedule"}]},
                        "acknowledgment":{"recordId":"record","sourceHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "basisHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","actorId":"employee","reason":"Completed acquisition recorded from source files"}}
                        """);
        assertThat(json.validate("financialCommand", json.write(command))).isEqualTo(command);
        var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) command.deepCopy();
        invalid.putArray("approverIds").add("invented-checker");
        assertThatThrownBy(() -> json.validate("financialCommand", json.write(invalid))).isInstanceOf(ReceivablesException.class);
        invalid.putArray("approverIds");
        invalid.putObject("riskForecast");
        assertThatThrownBy(() -> json.validate("financialCommand", json.write(invalid))).isInstanceOf(ReceivablesException.class);
    }
}
