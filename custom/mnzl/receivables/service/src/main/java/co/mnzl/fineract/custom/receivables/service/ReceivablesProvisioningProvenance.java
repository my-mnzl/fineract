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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.ProvisioningJournalEntryObserver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Freezes original native provision attribution before mutable component history can be rebuilt. */
@Service
@RequiredArgsConstructor
public class ReceivablesProvisioningProvenance implements ProvisioningJournalEntryObserver {

    private final ReceivablesStore store;
    private final ReceivablesJson json;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void posted(long journalId, LocalDate postingDate, long historyId, long officeId, String currency, long accountId, boolean debit,
            BigDecimal amount, List<Component> components) {
        var proof = json.object();
        proof.put("nativeJournalId", Long.toString(journalId));
        proof.put("postingDate", postingDate.toString());
        proof.put("nativeProvisionHistoryId", Long.toString(historyId));
        proof.put("officeId", Long.toString(officeId));
        proof.put("currency", currency);
        proof.put("nativeGlAccountId", Long.toString(accountId));
        proof.put("side", debit ? "DEBIT" : "CREDIT");
        proof.put("amount", amount.toPlainString());
        var sources = proof.putArray("components");
        BigDecimal total = BigDecimal.ZERO;
        for (Component component : components) {
            require(component.historyId() == historyId && component.officeId() == officeId && component.currency().equals(currency)
                    && (debit ? component.expenseAccountId() : component.liabilityAccountId()) == accountId, "JOURNAL_MISMATCH");
            sources.add(json.value(component));
            total = total.add(component.reservedAmount());
        }
        require(!components.isEmpty() && total.compareTo(amount) == 0, "JOURNAL_MISMATCH");
        store.jdbc().update("insert into m_mnzl_r_provision_source (native_journal_id,source_json,source_hash) values (?,?,?)", journalId,
                json.write(proof), json.hash(proof));
    }
}
