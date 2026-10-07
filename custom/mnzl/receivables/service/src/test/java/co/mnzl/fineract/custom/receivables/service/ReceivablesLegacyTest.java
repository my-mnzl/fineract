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

import org.junit.jupiter.api.Test;

/** Hash-sealed records are aliased when parsed and never when hashed. */
class ReceivablesLegacyTest {

    private final ReceivablesJson json = new ReceivablesJson();

    ReceivablesLegacyTest() throws Exception {}

    @Test
    void storedEventsReadUnderCurrentNamesWithoutTouchingTheSealedBytes() {
        String stored = """
                {"eventId":"e","journalLines":[{"accountKey":"deferredIntegralFee","side":"CREDIT","amountMinor":"5","component":"INTEGRAL_FEE"},
                {"accountKey":"bank","side":"DEBIT","amountMinor":"5","component":"CASH"}],
                "positionsAfter":[{"accountId":"a","deferredDiscountMinor":"1","deferredIntegralFeeMinor":"2","amortizedCostMinor":"3"}]}""";
        var event = json.read(stored);
        String sealed = json.hash(event);
        var current = ReceivablesLegacy.current(event);
        assertThat(json.hash(event)).isEqualTo(sealed);
        assertThat(json.hash(json.read(stored))).isEqualTo(sealed);
        assertThat(current.path("journalLines").get(0).path("accountKey").asText()).isEqualTo("deferredAdminFee");
        assertThat(current.path("journalLines").get(0).path("component").asText()).isEqualTo("ADMIN_FEE");
        assertThat(current.path("journalLines").get(1)).isEqualTo(event.path("journalLines").get(1));
        var position = current.path("positionsAfter").get(0);
        assertThat(position.has("deferredIntegralFeeMinor")).isFalse();
        assertThat(position.path("deferredAdminFeeMinor").asText()).isEqualTo("2");
        assertThat(position.fieldNames()).toIterable().containsExactly("accountId", "deferredDiscountMinor", "deferredAdminFeeMinor",
                "amortizedCostMinor");
        assertThat(ReceivablesLegacy.accountKey("deferredIntegralFee")).isEqualTo("deferredAdminFee");
        assertThat(ReceivablesLegacy.accountKey("deferredAdminFee")).isEqualTo("deferredAdminFee");
    }

    @Test
    void currentRecordsAreReturnedAsIs() {
        var current = json.read("{\"acceptedAccountPrices\":[{\"accountId\":\"a\",\"adminFeeMinor\":\"1\"}],\"accountKey\":\"bank\"}");
        assertThat(ReceivablesLegacy.current(current)).isSameAs(current);
        var unrelated = json.read("{\"note\":\"deferredIntegralFee\",\"items\":[\"INTEGRAL_FEE\"]}");
        assertThat(ReceivablesLegacy.current(unrelated)).isSameAs(unrelated);
    }

    /** A replayed command is hashed as sent; only its parse is aliased so it validates against the current schema. */
    @Test
    void legacyCommandPayloadValidatesOnlyOnceAliased() {
        var legacy = json
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
                        "sourceHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "acceptedAccountPrices":[{"accountId":"account","grossPurchasePriceMinor":"9500","integralFeeMinor":"95","netPurchaseCashMinor":"9405"}],
                        "cashflows":[{"cashflowId":"flow","receivableId":"account","installmentId":"installment","dueDate":"2027-01-01","amountMinor":"10000","currency":"EGP","scheduleVersionId":"schedule"}]},
                        "acknowledgment":{"recordId":"record","sourceHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "basisHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","actorId":"employee","reason":"Completed acquisition recorded from source files"}}
                        """);
        String storedHash = json.hash(legacy);
        assertThatThrownBy(() -> json.validate("financialCommand", legacy)).isInstanceOf(ReceivablesException.class)
                .hasMessage("INVALID_DATA");
        var current = ReceivablesLegacy.current(legacy);
        assertThat(json.validate("financialCommand", current)).isSameAs(current);
        assertThat(current.path("basis").path("acceptedAccountPrices").get(0).path("adminFeeMinor").asText()).isEqualTo("95");
        assertThat(json.hash(legacy)).isEqualTo(storedHash);
        assertThat(json.hash(current)).isNotEqualTo(storedHash);
        ((com.fasterxml.jackson.databind.node.ObjectNode) current.path("basis")).put("calculationVersion",
                "EG_RECEIVABLES_ACT360_DAILY_V2");
        assertThat(json.validate("financialCommand", current)).isSameAs(current);
    }
}
