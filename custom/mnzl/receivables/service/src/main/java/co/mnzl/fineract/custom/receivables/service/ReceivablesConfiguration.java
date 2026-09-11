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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReceivablesConfiguration {

    public static final String CALCULATION = "EG_RECEIVABLES_ACT360_DAILY_V1";
    public static final String POLICY = "EG_RECEIVABLES_V1";
    public static final Set<String> ACCOUNTS = Set.of("contractualReceivable", "installmentDues", "deferredDiscount", "deferredIntegralFee",
            "lossAllowance", "developerReceivable", "developerReceivableAllowance", "developerPayable", "acquisitionClearing",
            "developerSettlementAdvance", "cashUnapplied", "helSettlementClearing", "bank", "portfolioInterestIncome",
            "developerAdjustmentIncome", "developerAdjustmentExpense", "impairmentExpense", "modificationGainLoss",
            "writtenOffRecoveryIncome", "fundingPrincipal", "fundingInterestPayable", "fundingExpense");
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final PlatformSecurityContext security;
    private final org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository products;
    private final co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService strategies;

    private final co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge nativeBridge;

    @Transactional
    public JsonNode resolveBorrower(String request) {
        security.authenticatedUser().validateHasPermissionTo("EXECUTE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("nativeBorrowerRequest", request);
        Map<String, Object> config = authorize(input.get("scope"), true);
        require(text(input, "accountMappingRevisionId").equals(string(config, "mapping_revision")), "FINERACT_CAPABILITY_MISSING");
        String customer = text(input, "customerReferenceId");
        long clientId = nativeBridge
                .createClient(new co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.ClientIdentity(
                        "R" + ReceivablesStore.key(scopeKey(input.get("scope")), "customer", customer), customer,
                        number(config, "office_id")));
        ObjectNode result = json.object();
        result.set("scope", input.get("scope"));
        result.put("customerReferenceId", customer);
        result.put("nativeClientId", Long.toString(clientId));
        return result;
    }

    public String scopeKey(JsonNode scope) {
        ObjectNode financier = json.object();
        for (String field : new String[] { "platformId", "financierOrganizationId", "environment", "ledgerEpoch" }) {
            financier.put(field, text(scope, field));
        }
        return json.hash(financier);
    }

    public String tenantId() {
        return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
    }

    /** Resolve context only within the authenticated tenant and integration user's configuration. */
    @Transactional(readOnly = true)
    public JsonNode discoverContext(String platform, String financier, String environment) {
        var user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        for (String value : new String[] { platform, financier, environment }) {
            require(value != null && !value.isBlank(), "INVALID_DATA");
        }
        require(Set.of("local", "test", "staging", "production").contains(environment), "INVALID_DATA");
        var candidates = store.jdbc()
                .queryForList("select * from m_mnzl_r_configuration where integration_user_id=? and financier_id=?", user.getId(),
                        financier)
                .stream().filter(row -> !Boolean.TRUE.equals(row.get("retired")) && !"1".equals(string(row, "retired"))).filter(row -> {
                    var scope = json.read(string(row, "scope_json"));
                    return platform.equals(text(scope, "platformId")) && financier.equals(text(scope, "financierOrganizationId"))
                            && environment.equals(text(scope, "environment"));
                }).toList();
        require(candidates.size() == 1, "FINERACT_CAPABILITY_MISSING");
        var config = candidates.getFirst();
        var scope = json.read(string(config, "scope_json"));
        require(!text(scope, "ledgerEpoch").isBlank() && string(config, "epoch").equals(text(scope, "ledgerEpoch"))
                && scopeKey(scope).equals(string(config, "scope_key")), "FINERACT_CAPABILITY_MISSING");
        var result = json.object();
        result.put("tenantId", tenantId());
        result.set("scope", scope);
        result.put("accountMappingRevisionId", string(config, "mapping_revision"));
        return result;
    }

    public Map<String, Object> authorize(JsonNode scope, boolean lock) {
        String key = scopeKey(scope);
        Map<String, Object> config = lock ? store.lockConfiguration(key) : store.require("configuration", key);
        require(number(config, "integration_user_id") == security.authenticatedUser().getId(), "FINERACT_CAPABILITY_MISSING");
        require(string(config, "epoch").equals(text(scope, "ledgerEpoch")), "FINERACT_CAPABILITY_MISSING");
        return config;
    }

    @Transactional
    public JsonNode configure(String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("nativeConfiguration", request);
        String scope = scopeKey(input.get("scope"));
        validateProduct(Long.parseLong(text(input, "productId")));
        Set<String> keys = new HashSet<>();
        for (JsonNode entry : input.get("accountMap")) {
            require(keys.add(text(entry, "accountKey")), "FINERACT_CAPABILITY_MISSING");
            var rows = store.jdbc().queryForList("select disabled, account_usage from acc_gl_account where id=?",
                    Long.parseLong(text(entry, "nativeGlAccountId")));
            require(rows.size() == 1 && !Boolean.parseBoolean(String.valueOf(rows.getFirst().get("disabled")))
                    && ((Number) rows.getFirst().get("account_usage")).intValue() == 1, "FINERACT_CAPABILITY_MISSING");
        }
        require(keys.equals(ACCOUNTS), "FINERACT_CAPABILITY_MISSING");
        Map<String, Object> existing = store.find("configuration", scope);
        if (existing != null) {
            require(json.hash(json.read(string(existing, "config_json"))).equals(json.hash(input)), "FINERACT_CAPABILITY_MISSING");
            return capabilities(input.get("scope"), false);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope_key", scope);
        fields.put("scope_json", json.write(input.get("scope")));
        fields.put("financier_id", text(input.get("scope"), "financierOrganizationId"));
        fields.put("epoch", text(input.get("scope"), "ledgerEpoch"));
        fields.put("integration_user_id", Long.parseLong(text(input, "integrationUserId")));
        fields.put("product_id", Long.parseLong(text(input, "productId")));
        fields.put("office_id", Long.parseLong(text(input, "officeId")));
        fields.put("mapping_revision", text(input, "accountMappingRevisionId"));
        fields.put("policy_revision", text(input, "policyRevisionId"));
        fields.put("calculator_build", text(input, "calculatorBuild"));
        fields.put("hel_payment_type_id", input.get("helPaymentTypeId").isNull() ? null : Long.parseLong(text(input, "helPaymentTypeId")));
        fields.put("hel_product_id", input.get("helProductId").isNull() ? null : Long.parseLong(text(input, "helProductId")));
        fields.put("config_json", json.write(input));
        fields.put("released", false);
        fields.put("version", 0L);
        store.insert("configuration", scope, fields);
        for (JsonNode entry : input.get("accountMap")) {
            store.insert("account_map", ReceivablesStore.key(scope, "map", text(entry, "accountKey")),
                    Map.of("scope_key", scope, "account_key", text(entry, "accountKey"), "native_gl_id",
                            Long.parseLong(text(entry, "nativeGlAccountId")), "mapping_revision", text(input, "accountMappingRevisionId")));
        }
        return capabilities(input.get("scope"), false);
    }

    @Transactional(readOnly = true)
    public JsonNode readConfiguration(JsonNode scope, String mappingRevision) {
        var config = store.require("configuration", scopeKey(scope));
        if (number(config, "integration_user_id") == security.authenticatedUser().getId()) {
            security.authenticatedUser().validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        } else {
            security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        }
        require(string(config, "mapping_revision").equals(mappingRevision), "FINERACT_CAPABILITY_MISSING");
        validateProduct(number(config, "product_id"));
        ObjectNode result = (ObjectNode) json.read(string(config, "config_json"));
        result.put("productId", Long.toString(number(config, "product_id")));
        result.put("officeId", Long.toString(number(config, "office_id")));
        result.put("integrationUserId", Long.toString(number(config, "integration_user_id")));
        result.put("accountMappingRevisionId", string(config, "mapping_revision"));
        result.put("policyRevisionId", string(config, "policy_revision"));
        result.put("calculatorBuild", string(config, "calculator_build"));
        if (config.get("hel_product_id") == null) {
            result.putNull("helProductId");
        } else {
            result.put("helProductId", Long.toString(number(config, "hel_product_id")));
        }
        if (config.get("hel_payment_type_id") == null) {
            result.putNull("helPaymentTypeId");
        } else {
            result.put("helPaymentTypeId", Long.toString(number(config, "hel_payment_type_id")));
        }
        var mappings = result.putArray("accountMap");
        for (var mapping : store.scoped("account_map", scopeKey(scope))) {
            var actual = store.jdbc().queryForMap("select disabled,account_usage from acc_gl_account where id=?",
                    number(mapping, "native_gl_id"));
            require(!Boolean.parseBoolean(string(actual, "disabled")) && !"1".equals(string(actual, "disabled"))
                    && number(actual, "account_usage") == 1, "FINERACT_CAPABILITY_MISSING");
            require(mappingRevision.equals(string(mapping, "mapping_revision")), "FINERACT_CAPABILITY_MISSING");
            mappings.addObject().put("accountKey", string(mapping, "account_key")).put("nativeGlAccountId",
                    Long.toString(number(mapping, "native_gl_id")));
        }
        require(mappings.size() == ACCOUNTS.size(), "FINERACT_CAPABILITY_MISSING");
        return result;
    }

    public void validateProduct(long id) {
        var product = products.findById(id).orElseThrow(() -> new ReceivablesException("FINERACT_CAPABILITY_MISSING"));
        var strategy = strategies.findOne(id);
        try {
            require("MNZL_PURCHASED_RECEIVABLE".equals(strategy.getInstrumentCode()), "FINERACT_CAPABILITY_MISSING");
            co.mnzl.fineract.custom.loan.instrument.PurchasedReceivableProductValidator.validateStrategies(strategy.getInstrumentCode(),
                    strategy.getScheduleStrategyCode(), strategy.getChargeStrategyCode(), strategy.getCobStrategyCode());
            co.mnzl.fineract.custom.loan.instrument.PurchasedReceivableProductValidator.validate(product);
        } catch (IllegalArgumentException e) {
            throw new ReceivablesException("FINERACT_CAPABILITY_MISSING", e);
        }
    }

    public JsonNode capabilities(JsonNode scope, boolean checkUser) {
        Map<String, Object> config = checkUser ? authorize(scope, false) : store.require("configuration", scopeKey(scope));
        boolean ready;
        try {
            validateProduct(number(config, "product_id"));
            ready = true;
        } catch (ReceivablesException e) {
            ready = false;
        }
        ObjectNode result = json.object();
        result.put("calculationVersion", CALCULATION);
        result.put("productPolicyCode", POLICY);
        result.put("schemaVersion", "1");
        result.put("policyRevisionId", string(config, "policy_revision"));
        result.put("calculatorBuild", string(config, "calculator_build"));
        result.put("accountMappingRevisionId", string(config, "mapping_revision"));
        result.put("eventVersion", "flex.receivables.financial-event.v1");
        result.put("productCode", "MNZL_PURCHASED_RECEIVABLE");
        result.put("scheduleCode", "MNZL_FIXED_RECEIVABLE");
        result.put("chargeStrategyCode", "MNZL_NO_BORROWER_CHARGES");
        result.put("accountingMode", "NONE");
        result.put("productConfigurationReady", ready);
        result.put("accountConfigurationReady", store.scoped("account_map", scopeKey(scope)).size() == ACCOUNTS.size());
        Long watermark = store.jdbc().queryForObject("select coalesce(max(sequence_id),0) from m_mnzl_r_event where scope_key=?",
                Long.class, scopeKey(scope));
        result.put("currentEventWatermark", Long.toString(watermark));
        if (config.get("hel_product_id") == null) {
            result.putNull("helProductId");
        } else {
            result.put("helProductId", Long.toString(number(config, "hel_product_id")));
        }
        if (config.get("hel_payment_type_id") == null) {
            result.putNull("helPaymentTypeId");
        } else {
            result.put("helPaymentTypeId", Long.toString(number(config, "hel_payment_type_id")));
        }
        result.put("periodState", "OPEN");
        result.put("businessDate", DateUtils.getBusinessLocalDate().toString());
        result.set("blockers", json.value(ready ? java.util.List.of() : java.util.List.of("FINERACT_CAPABILITY_MISSING")));
        return result;
    }

    public void validateExecution(JsonNode command, Map<String, Object> config) {
        String scope = scopeKey(command.get("scope"));
        require(string(config, "mapping_revision").equals(text(command, "accountMappingRevisionId")), "FINERACT_CAPABILITY_MISSING");
        validateProduct(number(config, "product_id"));
        LocalDate date = LocalDate.parse(text(command, "businessDate"));
        require(store.jdbc().queryForObject(
                "select count(*) from m_mnzl_r_period where scope_key=? and status in ('CLOSED','PREPARING') and boundary_date>?",
                Long.class, scope, date) == 0, "PERIOD_CLOSED");
        String mode = text(command, "executionMode");
        if ("CURRENT".equals(mode)) {
            require(date.equals(DateUtils.getBusinessLocalDate()) && command.get("executionAuthorization").isNull(),
                    "APPROVAL_SCOPE_CHANGED");
        } else {
            security.authenticatedUser()
                    .validateHasPermissionTo(("RECONSTRUCTION".equals(mode) ? "RECONSTRUCT" : "CORRECT") + "_MNZL_RECEIVABLES");
            JsonNode authorization = command.get("executionAuthorization");
            require(authorization != null && !authorization.isNull(), "APPROVAL_SCOPE_CHANGED");
            Map<String, Object> recorded = store.require("authorization",
                    ReceivablesStore.key(scope, "authorization", text(authorization, "authorizationId")));
            require(!Boolean.parseBoolean(string(recorded, "revoked")) && mode.equals(string(recorded, "mode"))
                    && text(command, "executionScopeHash").equals(string(recorded, "scope_hash"))
                    && text(authorization, "scopeHash").equals(string(recorded, "scope_hash"))
                    && text(authorization, "approvedBy").equals(string(recorded, "approved_by"))
                    && !date.isBefore(LocalDate.parse(string(recorded, "effective_from")))
                    && !date.isAfter(LocalDate.parse(string(recorded, "effective_through"))), "APPROVAL_SCOPE_CHANGED");
        }
        boolean prepare = text(command, "commandType").equals("CLOSE_PERIOD") && text(command, "phase").equals("PREPARE");
        require(!text(command, "actorId").isBlank() && (prepare || command.get("approverIds").size() > 0), "APPROVAL_SCOPE_CHANGED");
        for (JsonNode approver : command.get("approverIds")) {
            require(!approver.asText().equals(text(command, "actorId")), "APPROVAL_SCOPE_CHANGED");
        }
    }

    @Transactional
    public JsonNode authorizeHistory(String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("nativeHistoricalAuthorization", request);
        String scope = scopeKey(input.get("scope"));
        store.lockConfiguration(scope);
        require(text(input, "approvedBy").equals(security.authenticatedUser().getId().toString()), "APPROVAL_SCOPE_CHANGED");
        LocalDate from = LocalDate.parse(text(input, "effectiveFrom"));
        LocalDate through = LocalDate.parse(text(input, "effectiveThrough"));
        require(!through.isBefore(from), "INVALID_DATA");
        String key = ReceivablesStore.key(scope, "authorization", text(input, "authorizationId"));
        var fields = Map.<String, Object>of("scope_key", scope, "authorization_id", text(input, "authorizationId"), "scope_hash",
                text(input, "scopeHash"), "approved_by", text(input, "approvedBy"), "effective_from", from, "effective_through", through,
                "mode", text(input, "mode"), "revoked", false);
        var old = store.find("authorization", key);
        if (old == null) {
            store.insert("authorization", key, fields);
        } else {
            require(string(old, "scope_hash").equals(text(input, "scopeHash")), "IDEMPOTENCY_CONFLICT");
        }
        return input;
    }

    @Transactional
    public JsonNode authorizeWorkout(String request) {
        security.authenticatedUser().validateHasPermissionTo("CONFIGURE_MNZL_RECEIVABLES");
        JsonNode input = json.validate("nativeWorkoutAuthorization", request);
        String scope = scopeKey(input.get("scope"));
        var config = store.lockConfiguration(scope);
        require(text(input, "approvedBy").equals(security.authenticatedUser().getId().toString())
                && number(config, "integration_user_id") != security.authenticatedUser().getId(), "APPROVAL_SCOPE_CHANGED");
        var account = store.require("account", ReceivablesStore.key(scope, "account", text(input, "accountId")));
        require(json.read(string(account, "scope_json")).equals(input.get("scope")), "OWNERSHIP_CONFLICT");
        String key = ReceivablesStore.key(scope, "authorization", text(input, "approvedWorkoutCaseId"));
        var previous = store.find("authorization", key);
        String hash = json.hash(input);
        if (previous != null) {
            require(hash.equals(string(previous, "scope_hash")), "IDEMPOTENCY_CONFLICT");
            return input;
        }
        LocalDate date = LocalDate.parse(text(input, "businessDate"));
        store.insert("authorization", key,
                Map.of("scope_key", scope, "authorization_id", text(input, "approvedWorkoutCaseId"), "scope_hash", hash, "approved_by",
                        text(input, "approvedBy"), "effective_from", date, "effective_through", date, "mode", "WORKOUT", "revoked", false,
                        "payload_json", json.write(input)));
        return input;
    }

    public void requireWorkout(ReceivablesExecution e, String classification, java.math.BigInteger consideration) {
        var authorization = store.require("authorization",
                ReceivablesStore.key(e.scope, "authorization", text(e.command, "approvedWorkoutCaseId")));
        require(string(authorization, "mode").equals("WORKOUT") && !Boolean.parseBoolean(string(authorization, "revoked")),
                "APPROVAL_SCOPE_CHANGED");
        JsonNode approved = json.read(string(authorization, "payload_json"));
        require(approved.get("scope").equals(e.command.get("scope")) && text(approved, "accountId").equals(e.subjectId())
                && text(approved, "classification").equals(classification) && text(approved, "businessDate").equals(e.date.toString())
                && ReceivablesJson.minor(approved, "approvedConsiderationMinor").equals(consideration), "APPROVAL_SCOPE_CHANGED");
        Set<String> evidence = new HashSet<>();
        e.command.get("legalEvidenceIds").forEach(v -> evidence.add(v.asText()));
        Set<String> recorded = new HashSet<>();
        approved.get("legalEvidenceIds").forEach(v -> recorded.add(v.asText()));
        require(evidence.equals(recorded), "APPROVAL_SCOPE_CHANGED");
        if (classification.equals("EXCHANGE")) {
            JsonNode outcome = e.command.get("legalOutcome");
            require(approved.get("replacementAccountId").equals(outcome.get("replacementAccountId"))
                    && approved.get("replacementSourceHash").equals(outcome.get("replacementSourceHash")), "SOURCE_CHANGED");
            var basis = json.object();
            basis.set("cashflows", e.command.get("modifiedCashflows"));
            basis.set("riskForecast", e.command.get("riskForecast"));
            basis.put("approvedConsiderationMinor", consideration.toString());
            require(json.hash(basis).equals(text(outcome, "replacementSourceHash")), "SOURCE_CHANGED");
        }
    }

}
