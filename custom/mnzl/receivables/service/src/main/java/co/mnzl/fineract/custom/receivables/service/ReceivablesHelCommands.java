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

import static co.mnzl.fineract.custom.receivables.service.ReceivablesException.require;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.minor;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;

import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeHelBridge;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceivablesHelCommands {

    private final NativeHelBridge bridge;
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesCashCommands cash;

    public void fund(ReceivablesExecution e) {
        require(e.configuration.get("hel_payment_type_id") != null && e.configuration.get("hel_product_id") != null,
                "FINERACT_CAPABILITY_MISSING");
        require(text(e.command, "subjectId").equals(text(e.command, "loanExternalId")), "SOURCE_CHANGED");
        var terms = bridge.readTerms(text(e.command, "loanExternalId"));
        require(terms.clientId() == Long.parseLong(text(e.command, "expectedNativeClientId")), "OWNERSHIP_CONFLICT");
        var loan = store.jdbc().queryForMap("select product_id from m_loan where id=?", terms.nativeLoanId());
        require(number(loan, "product_id") == number(e.configuration, "hel_product_id"), "FINERACT_CAPABILITY_MISSING");
        var feeIds = new ArrayList<Long>();
        e.command.get("financedFeeIds").forEach(id -> feeIds.add(Long.parseLong(id.asText())));
        long clearing = number(store.require("account_map", ReceivablesStore.key(e.scope, "map", "helSettlementClearing")), "native_gl_id");
        var funded = bridge.fund(text(e.command, "loanExternalId"), e.date, minor(e.command, "expectedPrincipalMinor"),
                minor(e.command, "expectedFinancedFeesMinor"), feeIds, number(e.configuration, "hel_payment_type_id"), clearing,
                "R" + e.operationKey);
        e.helFunding = funded;
        funded.nativeTransactionIds().forEach(id -> e.nativeTransactions.add(id.toString()));
    }

    public void allocateAdvance(ReceivablesExecution e) {
        String deal = text(e.command, "dealId");
        var amount = minor(e.command, "payoffMinor");
        cash.consumeHelClearing(e, text(e.command, "helFundingOperationId"), amount, deal);
        cash.consumeAllocations(e, e.command.get("verifiedAdvanceAllocationIds"), amount, "DEVELOPER_SETTLEMENT_ADVANCE", deal);
        ReceivablesLedger.pair(e.lines, "helSettlementClearing", "developerSettlementAdvance", amount, null, deal, "SETTLEMENT");
    }

    public JsonNode finish(ReceivablesExecution e, JsonNode operation) {
        if (e.helFunding == null) {
            return operation;
        }
        var funded = e.helFunding;
        var result = json.object();
        result.set("operation", operation);
        result.put("nativeLoanId", Long.toString(funded.nativeLoanId()));
        result.put("nativeLoanStatus", "ACTIVE");
        result.put("clearingAmountMinor", funded.clearingAmountMinor().toString());
        result.set("nativeTransactionIds", json.value(e.nativeTransactions));
        result.set("journalIds", json.value(funded.journals().stream().map(j -> Long.toString(j.journalId())).toList()));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("scope_key", e.scope);
        fields.put("operation_key", e.operationKey);
        fields.put("loan_id", funded.nativeLoanId());
        fields.put("loan_external_id", text(e.command, "loanExternalId"));
        fields.put("deal_id", text(e.command, "dealId"));
        fields.put("principal_minor", text(e.command, "expectedPrincipalMinor"));
        fields.put("fees_minor", text(e.command, "expectedFinancedFeesMinor"));
        fields.put("clearing_minor", funded.clearingAmountMinor().toString());
        fields.put("result_json", json.write(result));
        store.insert("hel_funding", ReceivablesStore.key(e.scope, "hel-funding", text(e.command, "operationId")), fields);
        return result;
    }
}
