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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scoped, capability-gated cutover tooling. Never used as financial correction/recovery. */
@Service
@RequiredArgsConstructor
public class ReceivablesMaintenance {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;
    private final ReceivablesResetInventory inventory;
    private final PlatformSecurityContext security;
    @Value("${mnzl.receivables.maintenance.enabled:false}")
    private boolean enabled;

    @Transactional
    public JsonNode begin(String request) {
        JsonNode input = validate(request);
        var config = store.lockConfiguration(configuration.scopeKey(input.get("scope")));
        require(!retired(config), "FINERACT_CAPABILITY_MISSING");
        plan(input, config);
        String frozen = json.hash(input);
        require(config.get("maintenance_request_hash") == null || frozen.equals(string(config, "maintenance_request_hash")),
                "SOURCE_CHANGED");
        // All native mutations use this same configuration lock: reaching here drains the in-flight native command.
        store.update("configuration", string(config, "record_key"), Map.of("maintenance_request_hash", frozen));
        return input;
    }

    @Transactional(readOnly = true)
    public JsonNode plan(String request) {
        JsonNode input = validate(request);
        var config = store.require("configuration", configuration.scopeKey(input.get("scope")));
        requireWindow(input, config);
        return plan(input, config);
    }

    @Transactional
    public JsonNode apply(String request) {
        JsonNode apply = json.validate("nativeResetApplyRequest", request);
        JsonNode input = validate(json.write(apply.get("request")));
        String scope = configuration.scopeKey(input.get("scope"));
        var config = store.lockConfiguration(scope);
        requireWindow(input, config);
        if (retired(config)) {
            require(text(apply, "planHash").equals(string(config, "reset_plan_hash")), "SOURCE_CHANGED");
            return json.read(string(config, "reset_result_json"));
        }
        for (String id : ids(input, "nativeLoanIds"))
            store.jdbc().queryForList("select id from m_loan where id=? for update", id);
        for (String id : ids(input, "journalIds"))
            store.jdbc().queryForList("select id from acc_gl_journal_entry where id=? for update", id);
        ObjectNode planned = plan(input, config);
        require(text(apply, "planHash").equals(text(planned, "planHash")), "SOURCE_CHANGED");
        var rows = inventory.collect(scope, ids(input, "nativeLoanIds"), ids(input, "journalIds"));
        require(inventory.manifest(rows).equals(planned.get("rows")), "SOURCE_CHANGED");
        var result = json.object();
        for (String field : List.of("scope", "tenantId", "nextLedgerEpoch", "maintenanceWindowId"))
            result.set(field, input.get(field));
        result.put("planHash", text(planned, "planHash"));
        result.set("removedCounts", inventory.remove(rows));
        result.put("retired", true);
        // Keep the old scope/configuration as a permanent epoch tombstone, including the idempotent reset receipt.
        store.update("configuration", scope,
                Map.of("retired", true, "reset_plan_hash", text(planned, "planHash"), "reset_result_json", json.write(result)));
        for (String table : ReceivablesResetInventory.SCOPED)
            require(store.scoped(table, scope).isEmpty(), "RECOVERY_REQUIRED");
        return result;
    }

    private JsonNode validate(String request) {
        security.authenticatedUser().validateHasPermissionTo("MAINTAIN_MNZL_RECEIVABLES");
        require(enabled, "FINERACT_CAPABILITY_MISSING");
        JsonNode input = json.validate("nativeResetRequest", request);
        require(configuration.tenantId().equals(text(input, "tenantId")), "OWNERSHIP_CONFLICT");
        require(!text(input, "nextLedgerEpoch").equals(text(input.get("scope"), "ledgerEpoch")), "INVALID_DATA");
        for (String field : List.of("developerOrganizationIds", "accountIds", "nativeLoanIds", "productIds", "sourceIds", "journalIds"))
            ids(input, field);
        require(!ids(input, "developerOrganizationIds").isEmpty() && !ids(input, "productIds").isEmpty(), "INVALID_DATA");
        return input;
    }

    private void requireWindow(JsonNode input, Map<String, Object> config) {
        require(json.hash(input).equals(string(config, "maintenance_request_hash")), "APPROVAL_SCOPE_CHANGED");
    }

    private ObjectNode plan(JsonNode input, Map<String, Object> config) {
        require(!retired(config), "FINERACT_CAPABILITY_MISSING");
        String scope = configuration.scopeKey(input.get("scope"));
        ObjectNode nextScope = ((ObjectNode) input.get("scope")).deepCopy().put("ledgerEpoch", text(input, "nextLedgerEpoch"));
        require(store.find("configuration", configuration.scopeKey(nextScope)) == null, "OWNERSHIP_CONFLICT");
        configuration.validateProduct(number(config, "product_id"));
        require(ids(input, "productIds").equals(Set.of(Long.toString(number(config, "product_id")))), "OWNERSHIP_CONFLICT");
        // An ordinary HEL loan is not a purchased receivable. Never infer authority to erase it from a financier
        // configuration.
        require(store.scoped("hel_funding", scope).isEmpty() && store.scoped("hel_snapshot", scope).isEmpty(), "OWNERSHIP_CONFLICT");
        Set<String> accounts = new TreeSet<>();
        Set<String> loans = new TreeSet<>();
        Set<String> developers = ids(input, "developerOrganizationIds");
        for (var account : store.scoped("account", scope)) {
            JsonNode owner = json.read(string(account, "scope_json"));
            require(configuration.scopeKey(owner).equals(scope) && developers.contains(text(owner, "developerOrganizationId")),
                    "OWNERSHIP_CONFLICT");
            accounts.add(string(account, "external_id"));
            String loanId = string(account, "native_loan_id");
            require(loans.add(loanId), "OWNERSHIP_CONFLICT");
            var loan = store.jdbc().queryForMap("select * from m_loan where id=?", loanId);
            require(number(loan, "product_id") == number(config, "product_id")
                    && string(loan, "external_id").equals("R" + string(account, "record_key"))
                    && number(loan, "client_id") == number(account, "native_client_id"), "OWNERSHIP_CONFLICT");
            require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_account where native_loan_id=?", Long.class, loanId) == 1,
                    "OWNERSHIP_CONFLICT");
        }
        require(accounts.equals(ids(input, "accountIds")) && loans.equals(ids(input, "nativeLoanIds")), "OWNERSHIP_CONFLICT");
        for (var command : store.scoped("command", scope)) {
            require(command.get("result_json") != null, "RECOVERY_REQUIRED");
            JsonNode owner = json.read(string(command, "request_json")).get("scope");
            require(configuration.scopeKey(owner).equals(scope) && developers.contains(text(owner, "developerOrganizationId")),
                    "OWNERSHIP_CONFLICT");
        }
        Set<String> sources = new TreeSet<>();
        Set<String> eventKeys = new TreeSet<>();
        for (var event : store.scoped("event", scope)) {
            sources.add(string(event, "event_id"));
            eventKeys.add(string(event, "record_key"));
        }
        require(sources.equals(ids(input, "sourceIds")), "OWNERSHIP_CONFLICT");
        Set<String> journals = new TreeSet<>();
        for (var line : store.scoped("journal_line", scope)) {
            String journalId = string(line, "native_journal_id");
            require(journals.add(journalId) && eventKeys.contains(string(line, "event_key")), "OWNERSHIP_CONFLICT");
            var journal = store.jdbc().queryForMap("select * from acc_gl_journal_entry where id=?", journalId);
            require(string(journal, "ref_num").equals(string(line, "source_line_id"))
                    && string(journal, "transaction_id").equals("R" + string(line, "event_key").substring(0, 40))
                    && number(journal, "account_id") == number(line, "native_gl_id")
                    && new BigDecimal(string(journal, "amount")).movePointRight(2).toBigIntegerExact().toString()
                            .equals(string(line, "amount_minor"))
                    && (number(journal, "type_enum") == 2) == string(line, "side").equals("DEBIT") && journal.get("entity_id") == null
                    && journal.get("client_transaction_id") == null && journal.get("share_transaction_id") == null
                    && journal.get("loan_transaction_id") == null && journal.get("savings_transaction_id") == null, "OWNERSHIP_CONFLICT");
            require(store.jdbc().queryForObject("select count(*) from m_mnzl_r_journal_line where native_journal_id=?", Long.class,
                    journalId) == 1, "OWNERSHIP_CONFLICT");
        }
        require(journals.equals(ids(input, "journalIds")), "OWNERSHIP_CONFLICT");
        Set<String> sourceJournals = new TreeSet<>();
        for (String eventKey : eventKeys) {
            for (var row : store.jdbc().queryForList("select id from acc_gl_journal_entry where transaction_id=?",
                    "R" + eventKey.substring(0, 40))) {
                sourceJournals.add(string(row, "id"));
            }
        }
        require(sourceJournals.equals(journals), "OWNERSHIP_CONFLICT");
        ObjectNode result = ((ObjectNode) input).deepCopy();
        result.set("rows", inventory.manifest(inventory.collect(scope, loans, journals)));
        result.put("planHash", json.hash(result));
        return result;
    }

    static Set<String> ids(JsonNode input, String field) {
        Set<String> values = new TreeSet<>();
        for (JsonNode value : input.get(field))
            require(values.add(value.asText()), "INVALID_DATA");
        return values;
    }

    static boolean retired(Map<String, Object> config) {
        return Boolean.TRUE.equals(config.get("retired")) || "1".equals(string(config, "retired"));
    }
}
