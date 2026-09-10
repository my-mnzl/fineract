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
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReceivablesLedger {
    public record Line(String accountKey, String side, BigInteger amountMinor, String accountId, String dealId, String component) {}
    public static void pair(List<Line> lines, String debit, String credit, BigInteger signed, String account, String deal, String component) {
        if (signed.signum() == 0) return;
        lines.add(new Line(signed.signum() > 0 ? debit : credit, "DEBIT", signed.abs(), account, deal, component));
        lines.add(new Line(signed.signum() > 0 ? credit : debit, "CREDIT", signed.abs(), account, deal, component));
    }
    public static void line(List<Line> lines, String accountKey, String side, BigInteger signed, String account, String deal, String component) {
        if (signed.signum() == 0) return;
        lines.add(new Line(accountKey, signed.signum() > 0 ? side : side.equals("DEBIT") ? "CREDIT" : "DEBIT", signed.abs(), account, deal, component));
    }
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final GLAccountRepository accounts;
    private final OfficeRepository offices;
    private final JournalEntryRepository journals;
    @Transactional(propagation = Propagation.MANDATORY)
    public List<JsonNode> post(String scope, String eventKey, LocalDate date, long officeId, List<Line> lines) {
        BigInteger balance = lines.stream().map(l -> l.side().equals("DEBIT") ? l.amountMinor() : l.amountMinor().negate()).reduce(BigInteger.ZERO, BigInteger::add);
        require(balance.signum() == 0, "JOURNAL_MISMATCH");
        List<JsonNode> output = new ArrayList<>();
        int sequence = 0;
        for (Line line : lines) {
            require(ReceivablesConfiguration.ACCOUNTS.contains(line.accountKey()) && line.amountMinor().signum() > 0, "JOURNAL_MISMATCH");
            var mapping = store.require("account_map", ReceivablesStore.key(scope, "map", line.accountKey()));
            long glId = ReceivablesStore.number(mapping, "native_gl_id");
            var account = accounts.findById(glId).orElseThrow();
            require(!account.isDisabled() && account.isDetailAccount(), "FINERACT_CAPABILITY_MISSING");
            String sourceId = eventKey + ":" + sequence++;
            JournalEntry entry = JournalEntry.createNew(offices.findById(officeId).orElseThrow(), null, account, "EGP",
                    "R" + eventKey.substring(0, 40), false, date, line.side().equals("DEBIT") ? JournalEntryType.DEBIT : JournalEntryType.CREDIT,
                    new BigDecimal(line.amountMinor(), 2), "Purchased receivables " + line.component(), null, null, sourceId, null, null, null, null);
            long journalId = journals.saveAndFlush(entry).getId();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("scope_key", scope); fields.put("event_key", eventKey); fields.put("source_line_id", sourceId); fields.put("native_journal_id", journalId);
            fields.put("native_gl_id", glId); fields.put("account_key", line.accountId() == null ? null : ReceivablesStore.key(scope, "account", line.accountId()));
            fields.put("deal_id", line.dealId()); fields.put("semantic_account", line.accountKey()); fields.put("side", line.side());
            fields.put("amount_minor", line.amountMinor().toString()); fields.put("component", line.component());
            store.insert("journal_line", ReceivablesStore.key(scope, "line", sourceId), fields);
            var wire = json.object(); wire.put("sourceLineId", sourceId); wire.put("accountKey", line.accountKey()); wire.put("side", line.side());
            wire.put("amountMinor", line.amountMinor().toString()); wire.put("currency", "EGP"); if (line.accountId() != null) wire.put("accountId", line.accountId());
            wire.put("dealId", line.dealId()); wire.put("component", line.component()); output.add(wire);
        }
        return output;
    }
}
