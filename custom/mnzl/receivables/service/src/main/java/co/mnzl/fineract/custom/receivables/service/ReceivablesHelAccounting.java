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
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Actual posted loan balances and exact recorded product provision attribution, without synthetic loan ECL. */
@Service
@RequiredArgsConstructor
public class ReceivablesHelAccounting {

    private static final Set<Long> ASSETS = Set.of(2L, 7L, 8L, 9L);
    private static final Set<Long> INTEREST = Set.of(3L, 22L, 25L);
    private final ReceivablesStore store;
    private final ReceivablesJson json;

    public record Evidence(JsonNode mapping, List<JsonNode> journals, Map<Long, JsonNode> loanMeasures, BigInteger allowance,
            int provisionJournalCount, JsonNode provisionExclusions, LocalDate provisionHistoryFloor) {
    }

    public JsonNode mapping(long productId) {
        var rows = store.jdbc()
                .queryForList("select gl_account_id,financial_account_type,payment_type,charge_id from acc_product_mapping "
                        + "where product_id=? and product_type=1 order by financial_account_type,gl_account_id,payment_type,charge_id",
                        productId);
        require(!rows.isEmpty(), "FINERACT_CAPABILITY_MISSING");
        var result = json.array();
        for (var row : rows) {
            var item = result.addObject();
            item.put("nativeGlAccountId", Long.toString(number(row, "gl_account_id")));
            item.put("financialAccountType", Long.toString(number(row, "financial_account_type")));
            item.put("paymentTypeId", row.get("payment_type") == null ? null : string(row, "payment_type"));
            item.put("chargeId", row.get("charge_id") == null ? null : string(row, "charge_id"));
        }
        return result;
    }

    public Evidence capture(long productId, LocalDate date, String currency) {
        JsonNode mapping = mapping(productId);
        Map<Long, Set<Long>> roles = new HashMap<>();
        for (JsonNode item : mapping) {
            roles.computeIfAbsent(Long.parseLong(text(item, "nativeGlAccountId")), ignored -> new java.util.TreeSet<>())
                    .add(Long.parseLong(text(item, "financialAccountType")));
        }
        require(roles.values().stream().anyMatch(values -> values.contains(2L)), "FINERACT_CAPABILITY_MISSING");
        for (Set<Long> values : roles.values()) {
            long groups = values.stream().map(this::roleGroup).distinct().count();
            require(groups == 1, "SOURCE_CHANGED");
        }
        var journals = new ArrayList<JsonNode>();
        Map<Long, List<JsonNode>> loanJournals = new TreeMap<>();
        var loanRows = store.jdbc().queryForList("select j.* from acc_gl_journal_entry j join m_loan l on l.id=j.entity_id "
                + "where j.entity_type_enum=1 and l.product_id=? and j.entry_date<=? order by j.id", productId, date);
        for (var row : loanRows) {
            Set<Long> accountRoles = roles.get(number(row, "account_id"));
            require(accountRoles != null, "SOURCE_CHANGED");
            ObjectNode journal = journal(row, currency);
            journal.put("sourceKind", "LOAN");
            journal.putNull("originalProvisionJournalId");
            journal.putNull("originalPostingProofHash");
            journal.put("loanId", Long.toString(number(row, "entity_id")));
            journal.put("accountRole", roleGroup(accountRoles.iterator().next()));
            journal.set("financialAccountTypes", json.value(accountRoles.stream().map(Object::toString).toList()));
            journal.set("attributedAmountMinor", journal.get("amountMinor"));
            journal.putNull("aggregateSourceHash");
            journal.put("aggregateSourceCount", "0");
            journal.putNull("aggregateSourceAmountMinor");
            journal.set("targetProvisionComponents", json.array());
            journal.put("contentHash", json.hash(journal));
            journals.add(journal);
            loanJournals.computeIfAbsent(number(row, "entity_id"), ignored -> new ArrayList<>()).add(journal);
        }
        // Enumerate journals independently: native provisioning components can be deleted/reassigned by recreate.
        var provisionRows = store.jdbc().queryForList("select j.* from acc_gl_journal_entry j where j.entity_type_enum=3 "
                + "and j.entry_date<=? and j.currency_code=? order by j.id", date, currency);
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        Map<Long, Map<String, Object>> originals = new HashMap<>();
        for (var row : provisionRows) {
            byId.put(number(row, "id"), row);
            if (row.get("reversal_id") != null) {
                require(originals.put(number(row, "reversal_id"), row) == null, "JOURNAL_MISMATCH");
            }
        }
        BigInteger allowance = BigInteger.ZERO;
        var exclusions = json.array();
        LocalDate historyFloor = null;
        int attributedProvisionCount = 0;
        for (var row : provisionRows) {
            long journalId = number(row, "id");
            var original = originals.get(journalId);
            var sourceRow = original == null ? row : original;
            if (original != null) {
                requireExactReversal(original, row);
            }
            var saved = store.jdbc().queryForList("select source_json,source_hash from m_mnzl_r_provision_source where native_journal_id=?",
                    number(sourceRow, "id"));
            if (saved.isEmpty()) {
                // Unknown historical gross attribution is never reconstructed from mutable component rows.
                var reversal = sourceRow.get("reversal_id") == null ? null : byId.get(number(sourceRow, "reversal_id"));
                require(reversal != null, "RECOVERY_REQUIRED");
                requireExactReversal(sourceRow, reversal);
                LocalDate floor = LocalDate.parse(string(sourceRow, "entry_date")).isAfter(LocalDate.parse(string(reversal, "entry_date")))
                        ? LocalDate.parse(string(sourceRow, "entry_date")).plusDays(1)
                        : LocalDate.parse(string(reversal, "entry_date")).plusDays(1);
                historyFloor = historyFloor == null || floor.isAfter(historyFloor) ? floor : historyFloor;
                require(!date.isBefore(historyFloor), "RECOVERY_REQUIRED");
                if (original == null) {
                    var exclusion = exclusions.addObject();
                    exclusion.put("reason", "UNKNOWN_PRODUCT_ATTRIBUTION_EXACTLY_CANCELLED");
                    exclusion.set("originalJournal", journal(sourceRow, currency));
                    exclusion.set("reversalJournal", journal(reversal, currency));
                    exclusion.put("availableFromDate", floor.toString());
                    exclusion.put("contentHash", json.hash(exclusion));
                }
                continue;
            }
            JsonNode proof = json.read(string(saved.getFirst(), "source_json"));
            require(json.hash(proof).equals(string(saved.getFirst(), "source_hash")), "SOURCE_CHANGED");
            require(text(proof, "nativeJournalId").equals(Long.toString(number(sourceRow, "id")))
                    && text(proof, "nativeProvisionHistoryId").equals(Long.toString(number(sourceRow, "entity_id")))
                    && text(proof, "officeId").equals(Long.toString(number(sourceRow, "office_id")))
                    && text(proof, "currency").equals(currency)
                    && text(proof, "nativeGlAccountId").equals(Long.toString(number(sourceRow, "account_id")))
                    && text(proof, "postingDate").equals(string(sourceRow, "entry_date"))
                    && text(proof, "side").equals(number(sourceRow, "type_enum") == 2 ? "DEBIT" : "CREDIT")
                    && new BigDecimal(text(proof, "amount")).compareTo(new BigDecimal(string(sourceRow, "amount"))) == 0,
                    "JOURNAL_MISMATCH");
            var aggregate = json.array();
            var target = json.array();
            BigInteger aggregateAmount = BigInteger.ZERO;
            BigInteger targetAmount = BigInteger.ZERO;
            String accountRole = number(sourceRow, "type_enum") == 2 ? "PROVISION_EXPENSE" : "PROVISION_ALLOWANCE";
            for (JsonNode component : proof.path("components")) {
                var source = json.object();
                source.put("nativeProvisionComponentId", component.path("id").asText());
                source.put("nativeProvisionHistoryId", component.path("historyId").asText());
                source.put("productId", component.path("productId").asText());
                source.put("officeId", component.path("officeId").asText());
                source.put("categoryId", component.path("categoryId").asText());
                source.put("criteriaId", component.path("criteriaId").asText());
                source.put("currency", currency);
                source.put("nativeGlAccountId", Long.toString(number(row, "account_id")));
                BigDecimal reserved = new BigDecimal(component.path("reservedAmount").asText());
                require(reserved.stripTrailingZeros().scale() <= 2 && reserved.signum() >= 0, "SOURCE_CHANGED");
                BigInteger amount = reserved.movePointRight(2).toBigIntegerExact();
                source.put("reservedAmountMinor", amount.toString());
                source.put("contentHash", json.hash(source));
                aggregate.add(source);
                aggregateAmount = aggregateAmount.add(amount);
                if (component.path("productId").asLong() == productId) {
                    target.add(source);
                    targetAmount = targetAmount.add(amount);
                }
            }
            require(aggregateAmount.equals(minor(row, "amount")), "JOURNAL_MISMATCH");
            if (target.isEmpty()) {
                continue;
            }
            ObjectNode journal = journal(row, currency);
            journal.put("sourceKind", "PROVISION_POOL");
            journal.put("originalProvisionJournalId", Long.toString(number(sourceRow, "id")));
            journal.put("originalPostingProofHash", string(saved.getFirst(), "source_hash"));
            journal.putNull("loanId");
            journal.put("accountRole", accountRole);
            journal.set("financialAccountTypes", json.array());
            journal.put("attributedAmountMinor", targetAmount.toString());
            journal.put("aggregateSourceHash", json.hash(aggregate));
            journal.put("aggregateSourceCount", Integer.toString(aggregate.size()));
            journal.put("aggregateSourceAmountMinor", aggregateAmount.toString());
            journal.set("targetProvisionComponents", target);
            journal.put("contentHash", json.hash(journal));
            journals.add(journal);
            attributedProvisionCount++;
            if (accountRole.equals("PROVISION_ALLOWANCE")) {
                allowance = allowance.add(number(row, "type_enum") == 2 ? targetAmount.negate() : targetAmount);
            }
        }
        require(allowance.signum() >= 0, "JOURNAL_MISMATCH");
        journals.sort(Comparator.comparingLong(j -> Long.parseLong(text(j, "nativeJournalId"))));
        Map<Long, JsonNode> measures = new TreeMap<>();
        loanJournals.forEach((loan, rows) -> measures.put(loan, measures(rows)));
        return new Evidence(mapping, journals, measures, allowance, attributedProvisionCount, exclusions, historyFloor);
    }

    private void requireExactReversal(Map<String, Object> original, Map<String, Object> reversal) {
        require(original.get("reversal_id") != null && number(original, "reversal_id") == number(reversal, "id")
                && number(original, "id") != number(reversal, "id") && reversal.get("reversal_id") == null
                && number(original, "entity_type_enum") == 3 && number(reversal, "entity_type_enum") == 3
                && number(original, "entity_id") == number(reversal, "entity_id")
                && number(original, "office_id") == number(reversal, "office_id")
                && number(original, "account_id") == number(reversal, "account_id")
                && string(original, "currency_code").equals(string(reversal, "currency_code"))
                && Set.of(1L, 2L).contains(number(original, "type_enum"))
                && number(original, "type_enum") + number(reversal, "type_enum") == 3
                && minor(original, "amount").equals(minor(reversal, "amount")), "RECOVERY_REQUIRED");
    }

    public JsonNode measures(List<JsonNode> journals) {
        Map<String, List<JsonNode>> groups = new TreeMap<>();
        for (JsonNode journal : journals) {
            groups.computeIfAbsent(text(journal, "officeId") + ":" + text(journal, "nativeGlAccountId"), ignored -> new ArrayList<>())
                    .add(journal);
        }
        var components = json.array();
        BigInteger gross = BigInteger.ZERO;
        for (List<JsonNode> group : groups.values()) {
            JsonNode first = group.getFirst();
            var component = json.object();
            for (String field : List.of("officeId", "nativeGlAccountId", "currency", "accountRole", "financialAccountTypes")) {
                component.set(field, first.get(field));
            }
            BigInteger balance = BigInteger.ZERO;
            for (JsonNode journal : group) {
                BigInteger amount = ReceivablesJson.minor(journal, "attributedAmountMinor");
                balance = balance.add(text(journal, "side").equals("DEBIT") ? amount : amount.negate());
            }
            if (Set.of("EARNING_RECEIVABLE", "DEFERRED_INCOME").contains(text(first, "accountRole"))) {
                gross = gross.add(balance);
            }
            component.put("signedBalanceMinor", balance.toString());
            component.put("sourceJournalCount", Integer.toString(group.size()));
            component.put("sourceJournalHash", json.hash(json.value(group)));
            component.put("contentHash", json.hash(component));
            components.add(component);
        }
        var result = json.object();
        result.put("grossEarningCarryingMinor", gross.toString());
        result.set("components", components);
        result.put("contentHash", json.hash(result));
        return result;
    }

    private String roleGroup(long role) {
        return ASSETS.contains(role) ? "EARNING_RECEIVABLE"
                : role == 23 ? "DEFERRED_INCOME" : INTEREST.contains(role) ? "INTEREST_INCOME" : "OTHER";
    }

    private ObjectNode journal(Map<String, Object> row, String currency) {
        require(currency.equals(string(row, "currency_code")) && Set.of(1L, 2L).contains(number(row, "type_enum")), "SOURCE_CHANGED");
        var result = json.object();
        result.put("nativeJournalId", Long.toString(number(row, "id")));
        result.put("nativeGlAccountId", Long.toString(number(row, "account_id")));
        result.put("officeId", Long.toString(number(row, "office_id")));
        result.put("currency", currency);
        result.put("postingDate", string(row, "entry_date"));
        result.put("side", number(row, "type_enum") == 2 ? "DEBIT" : "CREDIT");
        result.put("amountMinor", minor(row, "amount").toString());
        result.put("nativeEntityId", Long.toString(number(row, "entity_id")));
        result.put("nativeTransactionId", row.get("loan_transaction_id") == null ? null : string(row, "loan_transaction_id"));
        result.put("reversalJournalId", row.get("reversal_id") == null ? null : string(row, "reversal_id"));
        return result;
    }

    private static BigInteger minor(Map<String, Object> row, String field) {
        BigDecimal amount = new BigDecimal(string(row, field));
        require(amount.stripTrailingZeros().scale() <= 2, "SOURCE_CHANGED");
        return amount.movePointRight(2).toBigIntegerExact();
    }
}
