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
            int provisionJournalCount) {
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
        var provisionRows = store.jdbc()
                .queryForList("select j.* from acc_gl_journal_entry j where j.entity_type_enum=3 "
                        + "and j.entry_date<=? and exists (select 1 from m_loanproduct_provisioning_entry p where p.history_id=j.entity_id "
                        + "and p.product_id=? and p.office_id=j.office_id and p.currency_code=j.currency_code "
                        + "and (p.liability_account=j.account_id or p.expense_account=j.account_id)) order by j.id", date, productId);
        BigInteger allowance = BigInteger.ZERO;
        Map<Long, List<Map<String, Object>>> histories = new HashMap<>();
        for (var row : provisionRows) {
            long historyId = number(row, "entity_id");
            var components = histories.computeIfAbsent(historyId, ignored -> store.jdbc()
                    .queryForList("select * from m_loanproduct_provisioning_entry where history_id=? order by id", historyId));
            var aggregate = json.array();
            var target = json.array();
            BigInteger aggregateAmount = BigInteger.ZERO;
            BigInteger targetAmount = BigInteger.ZERO;
            String accountRole = null;
            for (var component : components) {
                if (number(component, "office_id") != number(row, "office_id") || !string(component, "currency_code").equals(currency)) {
                    continue;
                }
                boolean liability = number(component, "liability_account") == number(row, "account_id");
                boolean expense = number(component, "expense_account") == number(row, "account_id");
                if (!liability && !expense) {
                    continue;
                }
                require(liability != expense, "SOURCE_CHANGED");
                String role = liability ? "PROVISION_ALLOWANCE" : "PROVISION_EXPENSE";
                require(accountRole == null || accountRole.equals(role), "SOURCE_CHANGED");
                accountRole = role;
                var source = json.object();
                source.put("nativeProvisionComponentId", Long.toString(number(component, "id")));
                source.put("nativeProvisionHistoryId", Long.toString(historyId));
                source.put("productId", Long.toString(number(component, "product_id")));
                source.put("officeId", Long.toString(number(component, "office_id")));
                source.put("categoryId", Long.toString(number(component, "category_id")));
                source.put("criteriaId", Long.toString(number(component, "criteria_id")));
                source.put("currency", currency);
                source.put("nativeGlAccountId", Long.toString(number(row, "account_id")));
                BigInteger amount = minor(component, "reseve_amount");
                require(amount.signum() >= 0, "SOURCE_CHANGED");
                source.put("reservedAmountMinor", amount.toString());
                source.put("contentHash", json.hash(source));
                aggregate.add(source);
                aggregateAmount = aggregateAmount.add(amount);
                if (number(component, "product_id") == productId) {
                    target.add(source);
                    targetAmount = targetAmount.add(amount);
                }
            }
            require(accountRole != null && aggregateAmount.equals(minor(row, "amount")) && !target.isEmpty(), "JOURNAL_MISMATCH");
            ObjectNode journal = journal(row, currency);
            journal.put("sourceKind", "PROVISION_POOL");
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
            if (accountRole.equals("PROVISION_ALLOWANCE")) {
                allowance = allowance.add(number(row, "type_enum") == 2 ? targetAmount.negate() : targetAmount);
            }
        }
        require(allowance.signum() >= 0, "JOURNAL_MISMATCH");
        journals.sort(Comparator.comparingLong(j -> Long.parseLong(text(j, "nativeJournalId"))));
        Map<Long, JsonNode> measures = new TreeMap<>();
        loanJournals.forEach((loan, rows) -> measures.put(loan, measures(rows)));
        return new Evidence(mapping, journals, measures, allowance, provisionRows.size());
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
