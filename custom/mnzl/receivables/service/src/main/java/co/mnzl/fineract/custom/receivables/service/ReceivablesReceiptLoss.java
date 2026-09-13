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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit full receipt return with approved absence of a surviving enforceable recovery right. */
@Service
@RequiredArgsConstructor
public class ReceivablesReceiptLoss {

    public static final String ACCOUNT = "receiptReturnLoss";
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesCashCommands cash;
    private final PlatformSecurityContext security;

    @Transactional
    public JsonNode configure(String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("receiptLossMappingRequest", request);
        var config = configuration.authorize(input.get("scope"), true);
        String scope = configuration.scopeKey(input.get("scope"));
        require(text(input, "accountMappingRevisionId").equals(string(config, "mapping_revision")), "FINERACT_CAPABILITY_MISSING");
        require(text(input, "approvedBy").equals(security.authenticatedUser().getId().toString()), "APPROVAL_SCOPE_CHANGED");
        var existing = store.find("receipt_loss_extension", scope);
        if (existing != null) {
            require(input.equals(json.read(string(existing, "request_json"))), "IDEMPOTENCY_CONFLICT");
            return json.read(string(existing, "extension_json"));
        }
        long gl = Long.parseLong(text(input, "nativeLossGlAccountId"));
        var rows = store.jdbc().queryForList("select disabled,account_usage,classification_enum from acc_gl_account where id=?", gl);
        require(rows.size() == 1 && !Boolean.parseBoolean(string(rows.getFirst(), "disabled"))
                && !"1".equals(string(rows.getFirst(), "disabled")) && number(rows.getFirst(), "account_usage") == 1
                && number(rows.getFirst(), "classification_enum") == 5, "FINERACT_CAPABILITY_MISSING");
        require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_account_map where scope_key=? and native_gl_id=?", Long.class,
                scope, gl) == 0, "FINERACT_CAPABILITY_MISSING");
        ObjectNode extension = input.deepCopy();
        extension.put("accountKey", ACCOUNT);
        extension.put("contentHash", json.hash(extension));
        store.insert("receipt_loss_extension", scope,
                Map.of("scope_key", scope, "extension_id", text(input, "extensionId"), "native_gl_id", gl, "request_json",
                        json.write(input), "extension_json", json.write(extension), "content_hash", text(extension, "contentHash")));
        return extension;
    }

    @Transactional(readOnly = true)
    public JsonNode configuration(JsonNode scope) {
        security.authenticatedUser().validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        configuration.authorize(scope, false);
        return extension(configuration.scopeKey(scope));
    }

    public JsonNode extension(String scope) {
        return json.read(string(store.require("receipt_loss_extension", scope), "extension_json"));
    }

    public long glAccount(String scope, JsonNode evidence) {
        JsonNode extension = extension(scope);
        require(extension.equals(evidence), "JOURNAL_MISMATCH");
        ObjectNode unhashed = extension.deepCopy();
        unhashed.remove("contentHash");
        require(json.hash(unhashed).equals(text(extension, "contentHash")), "JOURNAL_MISMATCH");
        return Long.parseLong(text(extension, "nativeLossGlAccountId"));
    }

    public void execute(ReceivablesExecution e) {
        require(text(e.command, "executionMode").equals("CORRECTION") && e.date.equals(DateUtils.getBusinessLocalDate()),
                "APPROVAL_SCOPE_CHANGED");
        JsonNode approval = e.command.get("lossApproval");
        require(text(approval, "legalDetermination").equals("NO_SURVIVING_ENFORCEABLE_RIGHT"), "APPROVAL_SCOPE_CHANGED");
        require(ReceivablesMeasurement.date(approval, "approvalDate").equals(e.date), "APPROVAL_SCOPE_CHANGED");
        var identities = new HashSet<String>();
        identities.add(text(e.command, "actorId"));
        var approvers = new HashSet<String>();
        e.command.get("approverIds").forEach(id -> approvers.add(id.asText()));
        for (String role : List.of("legalApproverId", "financeApproverId", "creditApproverId")) {
            require(identities.add(text(approval, role)) && approvers.contains(text(approval, role)), "APPROVAL_SCOPE_CHANGED");
        }
        JsonNode extension = extension(e.scope);
        require(text(extension, "extensionId").equals(text(e.command, "lossMappingExtensionId"))
                && text(extension, "contentHash").equals(text(e.command, "lossMappingExtensionHash")), "SOURCE_CHANGED");
        var account = store.require("account", e.accountKey(e.subjectId()));
        require(json.read(string(account, "scope_json")).equals(e.command.get("scope"))
                && string(account, "deal_id").equals(text(e.command, "dealId")), "OWNERSHIP_CONFLICT");
        String originalKey = ReceivablesStore.key(e.scope, "operation", text(e.command, "originalCollectionOperationId"));
        var original = store.require("command", originalKey);
        JsonNode originalCommand = json.read(string(original, "request_json"));
        require(text(originalCommand, "commandType").equals("COLLECT") && original.get("result_json") != null
                && e.subjectId().equals(text(originalCommand, "subjectId")) && originalCommand.get("scope").equals(e.command.get("scope"))
                && string(original, "payload_hash").equals(text(e.command, "originalCollectionCommandHash")), "SOURCE_CHANGED");
        var originalEvent = event(original, text(e.command, "originalCollectionEventHash"));
        var sources = store.jdbc().queryForList("select * from m_mnzl_r_cash_source where scope_key=? and bank_source_id=?", e.scope,
                text(e.command, "originalBankSourceId"));
        require(sources.size() == 1, "BANK_PROOF_MISMATCH");
        var source = sources.getFirst();
        String sourceKey = string(source, "record_key");
        require(string(source, "direction").equals("INCOMING")
                && json.hash(json.read(string(source, "source_json"))).equals(text(e.command, "originalBankSourceHash")),
                "BANK_PROOF_MISMATCH");
        require(store.find("receipt_loss", sourceKey) == null
                && store.jdbc().queryForObject("select count(*) from m_mnzl_r_receipt_loss where scope_key=? and disposition_id=?",
                        Long.class, e.scope, text(e.command, "dispositionId")) == 0,
                "IDEMPOTENCY_CONFLICT");
        var allocations = store.children("collection", "cash_source_key", sourceKey);
        require(!allocations.isEmpty(), "BANK_PROOF_MISMATCH");
        BigInteger collected = BigInteger.ZERO;
        for (var allocation : allocations) {
            require(originalKey.equals(string(allocation, "operation_key"))
                    && e.accountKey(e.subjectId()).equals(string(allocation, "account_key")) && allocation.get("reversed_by") == null,
                    "RECOVERY_REQUIRED");
            var nativeRows = store.jdbc().queryForList("select is_reversed from m_loan_transaction where id=? and loan_id=?",
                    number(allocation, "native_transaction_id"), number(account, "native_loan_id"));
            require(nativeRows.size() == 1 && !Boolean.parseBoolean(string(nativeRows.getFirst(), "is_reversed"))
                    && !"1".equals(string(nativeRows.getFirst(), "is_reversed")), "RECOVERY_REQUIRED");
            collected = collected.add(ReceivablesMeasurement.amount(allocation, "amount_minor"));
        }
        // This branch supports a full, fully collected original receipt only. Partial returns need a separate
        // disposition.
        JsonNode debit = e.command.get("bankDebit");
        require(collected.signum() > 0 && collected.equals(ReceivablesMeasurement.amount(source, "amount_minor"))
                && collected.equals(ReceivablesJson.minor(debit, "amountMinor")), "BANK_PROOF_MISMATCH");
        LocalDate originalDate = LocalDate.parse(string(source, "value_date"));
        LocalDate returnDate = ReceivablesMeasurement.date(debit, "valueDate");
        require(!returnDate.isBefore(originalDate) && !returnDate.isAfter(e.date) && text(debit, "direction").equals("OUTGOING"),
                "BANK_PROOF_MISMATCH");
        var downstream = store.require("command", ReceivablesStore.key(e.scope, "operation", text(e.command, "downstreamOperationId")));
        JsonNode downstreamCommand = json.read(string(downstream, "request_json"));
        require(downstream.get("result_json") != null && e.subjectId().equals(text(downstreamCommand, "subjectId"))
                && downstreamCommand.get("scope").equals(e.command.get("scope"))
                && string(downstream, "payload_hash").equals(text(e.command, "downstreamCommandHash")), "SOURCE_CHANGED");
        var downstreamEvent = event(downstream, text(e.command, "downstreamEventHash"));
        require(number(downstreamEvent, "sequence_id") > number(originalEvent, "sequence_id"), "SOURCE_CHANGED");
        String kind = text(downstreamCommand, "commandType");
        JsonNode helEvent = null;
        if (kind.equals("SUBSTITUTE_RECEIVABLE")) {
            require(string(account, "status").equals("ASSIGNED_OUT") && string(account, "closure_reason").equals("ASSIGNED_OUT"),
                    "RECOVERY_REQUIRED");
        } else {
            require(kind.equals("SETTLE_RECEIVABLE") && string(account, "status").equals("CLOSED")
                    && downstreamCommand.path("settlementSource").path("kind").asText().equals("HEL_CLEARING"), "RECOVERY_REQUIRED");
            String fundingId = text(downstreamCommand.get("settlementSource"), "helFundingOperationId");
            var funding = store.require("command", ReceivablesStore.key(e.scope, "operation", fundingId));
            require(string(funding, "command_type").equals("FUND_HEL_TO_SETTLEMENT_CLEARING") && funding.get("result_json") != null,
                    "SOURCE_CHANGED");
            var fundingEvent = store.require("event", ReceivablesStore.key(e.scope, "event", string(funding, "record_key")));
            var link = json.object();
            link.put("fundingOperationId", fundingId);
            link.put("fundingCommandHash", string(funding, "payload_hash"));
            link.put("fundingEventId", string(fundingEvent, "record_key"));
            link.put("fundingEventHash", string(fundingEvent, "content_hash"));
            link.set("nativeLoanId", json.read(string(funding, "result_json")).get("nativeLoanId"));
            helEvent = link;
        }
        cash.recordReceiptReturnSource(e, debit);
        ReceivablesLedger.bankPair(e.lines, ACCOUNT, "bank", collected, null, string(account, "deal_id"), "CASH",
                text(e.command, "operationId"));
        var outcome = json.object();
        for (String field : List.of("dispositionId", "originalCollectionOperationId", "originalCollectionCommandHash",
                "originalCollectionEventHash", "originalBankSourceId", "originalBankSourceHash", "downstreamOperationId",
                "downstreamCommandHash", "downstreamEventHash")) {
            outcome.set(field, e.command.get(field));
        }
        outcome.put("outcome", "APPROVED_NO_RIGHT_LOSS");
        outcome.put("sourceAccountId", e.subjectId());
        outcome.put("originalCollectionEventId", string(originalEvent, "record_key"));
        outcome.put("downstreamEventId", string(downstreamEvent, "record_key"));
        outcome.put("originalValueDate", originalDate.toString());
        outcome.put("returnValueDate", returnDate.toString());
        outcome.put("returnBankSourceId", text(debit, "bankSourceId"));
        outcome.put("returnAmountMinor", collected.toString());
        outcome.set("lossMappingExtension", extension);
        outcome.set("lossApproval", approval);
        outcome.set("helFundingEvidence", helEvent == null ? json.value(null) : helEvent);
        outcome.put("contentHash", json.hash(outcome));
        e.receiptLossDisposition = outcome;
        e.originalValueDate = originalDate;
        e.correctionOf = string(originalEvent, "record_key");
        store.insert("receipt_loss", sourceKey, Map.of("scope_key", e.scope, "operation_key", e.operationKey, "disposition_id",
                text(e.command, "dispositionId"), "disposition_json", json.write(outcome), "content_hash", text(outcome, "contentHash")));
    }

    private Map<String, Object> event(Map<String, Object> command, String hash) {
        String key = ReceivablesStore.key(string(command, "scope_key"), "event", string(command, "record_key"));
        var event = store.require("event", key);
        require(string(event, "content_hash").equals(hash), "SOURCE_CHANGED");
        return event;
    }
}
