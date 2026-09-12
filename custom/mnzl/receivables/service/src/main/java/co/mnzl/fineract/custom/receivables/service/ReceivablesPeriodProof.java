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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A complete event population and independently observed custom journal proof in one database snapshot. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReceivablesPeriodProof {

    private static final int MAX_ROWS = 100000;
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesConfiguration configuration;

    public JsonNode read(JsonNode scope, String mapping, String period, String watermark) {
        require(period != null && period.matches("[0-9]{4}-(0[1-9]|1[0-2])") && watermark != null
                && watermark.matches("0|[1-9][0-9]{0,18}"), "INVALID_DATA");
        long maximum;
        try {
            YearMonth.parse(period);
            maximum = Long.parseLong(watermark);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new ReceivablesException("INVALID_DATA", failure);
        }
        String scopeKey = configuration.scopeKey(scope);
        var config = configuration.authorize(scope, false);
        require(mapping.equals(string(config, "mapping_revision")), "FINERACT_CAPABILITY_MISSING");
        String tenant = configuration.tenantId();
        Long latest = store.jdbc().queryForObject("select max(sequence_id) from m_mnzl_r_event where scope_key=?", Long.class, scopeKey);
        require(maximum <= (latest == null ? 0 : latest), "INVALID_DATA");
        ObjectNode selection = json.object();
        selection.put("schemaVersion", "1");
        selection.put("tenantId", tenant);
        selection.set("scope", scope);
        selection.put("postingPeriod", period);
        selection.put("eventWatermark", watermark);
        selection.put("readAccountMappingRevisionId", mapping);
        selection.put("coverage", "SCOPED_EVENTS_CUSTOM_R_JOURNALS_V1");
        selection.set("excludedJournalPopulations", json.value(List.of("ORDINARY_HEL_LOAN_JOURNALS")));

        var events = bounded("select e.*,c.command_type,c.request_json,c.operation_id from m_mnzl_r_event e "
                + "left join m_mnzl_r_command c on c.record_key=e.operation_key where e.scope_key=? and e.sequence_id<=? limit 100001",
                scopeKey, maximum);
        Map<String, JsonNode> selected = new HashMap<>();
        Map<String, LocalDate> postingDates = new HashMap<>();
        List<JsonNode> members = new ArrayList<>();
        Map<String, Expected> expected = new HashMap<>();
        for (var row : events) {
            require(row.get("request_json") != null && row.get("command_type") != null, "JOURNAL_MISMATCH");
            ObjectNode event = (ObjectNode) json.read(string(row, "event_json"));
            String id = text(event, "eventId");
            ObjectNode unhashed = event.deepCopy();
            unhashed.remove("contentHash");
            require(id.equals(string(row, "record_key")) && id.equals(string(row, "event_id"))
                    && text(event, "contentHash").equals(string(row, "content_hash"))
                    && text(event, "contentHash").equals(json.hash(unhashed))
                    && text(event, "postingPeriod").equals(string(row, "posting_period"))
                    && text(event, "businessDate").equals(string(row, "business_date"))
                    && text(event, "operationId").equals(string(row, "operation_id")) && tenant.equals(text(event, "tenantId"))
                    && configuration.scopeKey(event.get("scope")).equals(scopeKey), "JOURNAL_MISMATCH");
            LocalDate date = LocalDate.parse(text(event, "businessDate"));
            JsonNode command = json.read(string(row, "request_json"));
            if (string(row, "command_type").equals("CLOSE_PERIOD") && text(command, "phase").equals("PREPARE")) {
                date = LocalDate.parse(text(command, "boundaryDate")).minusDays(1);
            }
            require(YearMonth.from(date).toString().equals(text(event, "postingPeriod")), "JOURNAL_MISMATCH");
            if (!period.equals(text(event, "postingPeriod"))) {
                continue;
            }
            selected.put(id, event);
            postingDates.put(id, date);
            members.add(json.value(Map.of("eventId", id, "contentHash", text(event, "contentHash"))));
            for (JsonNode line : event.get("journalLines")) {
                require(expected.put(text(line, "sourceLineId"), new Expected(event, line)) == null, "JOURNAL_MISMATCH");
                require(expected.size() <= MAX_ROWS, "INVALID_DATA");
            }
        }
        members.sort(Comparator.comparing(row -> text(row, "eventId")));
        var registry = bounded("select l.* from m_mnzl_r_journal_line l join m_mnzl_r_event e on e.record_key=l.event_key "
                + "where e.scope_key=? and e.sequence_id<=? limit 100001", scopeKey, maximum);
        var mappings = store.scoped("account_map", scopeKey);
        Map<String, Long> approvedGl = new HashMap<>();
        for (var row : mappings) {
            require(mapping.equals(string(row, "mapping_revision"))
                    && approvedGl.put(string(row, "account_key"), number(row, "native_gl_id")) == null, "JOURNAL_MISMATCH");
        }
        require(approvedGl.keySet().equals(ReceivablesConfiguration.ACCOUNTS), "JOURNAL_MISMATCH");
        Map<Long, Expected> byJournal = new HashMap<>();
        for (var row : registry) {
            if (!selected.containsKey(string(row, "event_key"))) {
                continue;
            }
            Expected match = expected.get(string(row, "source_line_id"));
            require(match != null && scopeKey.equals(string(row, "scope_key"))
                    && text(match.event(), "eventId").equals(string(row, "event_key"))
                    && text(match.line(), "journalId").equals(Long.toString(number(row, "native_journal_id")))
                    && text(match.line(), "accountKey").equals(string(row, "semantic_account"))
                    && text(match.line(), "component").equals(string(row, "component"))
                    && text(match.line(), "amountMinor").equals(string(row, "amount_minor"))
                    && text(match.line(), "side").equals(string(row, "side")), "JOURNAL_MISMATCH");
            if (mapping.equals(text(match.event(), "accountMappingRevisionId"))) {
                require(Long.valueOf(number(row, "native_gl_id")).equals(approvedGl.get(string(row, "semantic_account"))),
                        "JOURNAL_MISMATCH");
            }
            match.registry = row;
            require(byJournal.put(number(row, "native_journal_id"), match) == null, "JOURNAL_MISMATCH");
        }
        require(byJournal.size() == expected.size(), "JOURNAL_MISMATCH");
        Map<String, ObjectNode> accounts = new LinkedHashMap<>();
        for (var row : mappings) {
            account(accounts, mapping, string(row, "account_key"), Long.toString(number(row, "native_gl_id")));
        }
        // Membership comes from the native transaction identity, not the journal registry. Keep rows outside the
        // requested
        // month until checking the expected event date; also catch actual-period rows belonging to differently dated
        // events.
        var actual = bounded("select j.*,e.record_key event_key from acc_gl_journal_entry j join m_mnzl_r_event e "
                + "on j.transaction_id=concat('R',substring(e.record_key,1,40)) "
                + "where e.scope_key=? and e.sequence_id<=? order by j.id limit 100001", scopeKey, maximum);
        List<JsonNode> committedRows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var row : actual) {
            String eventId = string(row, "event_key");
            LocalDate date = LocalDate.parse(string(row, "entry_date"));
            if (!selected.containsKey(eventId) && !YearMonth.from(date).toString().equals(period)) {
                continue;
            }
            long id = number(row, "id");
            Expected match = byJournal.get(id);
            require(match != null && selected.containsKey(eventId) && seen.add(id) && text(match.event(), "eventId").equals(eventId)
                    && date.equals(postingDates.get(eventId)), "JOURNAL_MISMATCH");
            String side = number(row, "type_enum") == 2 ? "DEBIT" : "CREDIT";
            BigInteger amount = new BigDecimal(string(row, "amount")).movePointRight(2).toBigIntegerExact();
            String gl = Long.toString(number(row, "account_id"));
            boolean reversed = "1".equals(string(row, "reversed")) || Boolean.parseBoolean(string(row, "reversed"));
            require(!reversed && Set.of(1L, 2L).contains(number(row, "type_enum")) && amount.signum() > 0
                    && side.equals(text(match.line(), "side")) && amount.toString().equals(text(match.line(), "amountMinor"))
                    && gl.equals(Long.toString(number(match.registry, "native_gl_id"))) && "EGP".equals(string(row, "currency_code"))
                    && text(match.line(), "sourceLineId").equals(string(row, "ref_num")), "JOURNAL_MISMATCH");
            String revision = text(match.event(), "accountMappingRevisionId");
            String semantic = text(match.line(), "accountKey");
            ObjectNode observed = json.object();
            observed.put("journalId", Long.toString(id));
            observed.put("eventId", eventId);
            observed.put("transactionId", string(row, "transaction_id"));
            observed.put("entryDate", date.toString());
            observed.put("nativeGlAccountId", gl);
            observed.put("currency", "EGP");
            observed.put("side", side);
            observed.put("amountMinor", amount.toString());
            observed.put("reversed", reversed);
            observed.put("originalMappingRevisionId", revision);
            observed.put("accountKey", semantic);
            observed.put("sourceLineId", text(match.line(), "sourceLineId"));
            committedRows.add(observed);
            ObjectNode balance = account(accounts, revision, semantic, gl);
            String field = side.equals("DEBIT") ? "debitMinor" : "creditMinor";
            balance.put(field, new BigInteger(text(balance, field)).add(amount).toString());
        }
        require(seen.size() == expected.size(), "JOURNAL_MISMATCH");
        List<ObjectNode> balances = new ArrayList<>(accounts.values());
        balances.sort(Comparator.comparing((ObjectNode row) -> text(row, "originalMappingRevisionId"))
                .thenComparing(row -> text(row, "accountKey")).thenComparing(row -> text(row, "nativeGlAccountId"))
                .thenComparing(row -> text(row, "currency")));
        for (var balance : balances) {
            balance.put("netMinor",
                    new BigInteger(text(balance, "debitMinor")).subtract(new BigInteger(text(balance, "creditMinor"))).toString());
        }
        ObjectNode manifest = json.object();
        manifest.put("ordering", "EVENT_ID_ASC");
        manifest.put("count", Integer.toString(members.size()));
        manifest.put("sha256", json.hash(json.value(Map.of("selection", selection, "events", members))));
        ObjectNode observed = json.object();
        observed.put("semantics", "CURRENT_AT_REPEATABLE_READ");
        observed.put("observedAt", Instant.now().toString());
        observed.put("count", Integer.toString(committedRows.size()));
        observed.put("sha256", json.hash(json.value(Map.of("selection", selection, "rows", committedRows))));
        observed.put("zeroAccountPopulation", "CURRENT_APPROVED_MAPPING");
        observed.set("accounts", json.value(balances));
        ObjectNode result = selection.deepCopy();
        result.set("eventManifest", manifest);
        result.set("observedGl", observed);
        result.put("proofHash", json.hash(result));
        return json.validate("periodActivityProof", json.write(result));
    }

    private List<Map<String, Object>> bounded(String sql, Object... args) {
        var rows = store.jdbc().queryForList(sql, args);
        require(rows.size() <= MAX_ROWS, "INVALID_DATA");
        return rows;
    }

    private ObjectNode account(Map<String, ObjectNode> accounts, String revision, String semantic, String gl) {
        String key = revision + "\n" + semantic + "\n" + gl + "\nEGP";
        return accounts.computeIfAbsent(key, ignored -> {
            ObjectNode row = json.object();
            row.put("originalMappingRevisionId", revision);
            row.put("accountKey", semantic);
            row.put("nativeGlAccountId", gl);
            row.put("currency", "EGP");
            row.put("debitMinor", "0");
            row.put("creditMinor", "0");
            return row;
        });
    }

    private static final class Expected {

        private final JsonNode event;
        private final JsonNode line;
        private Map<String, Object> registry;

        private Expected(JsonNode event, JsonNode line) {
            this.event = event;
            this.line = line;
        }

        private JsonNode event() {
            return event;
        }

        private JsonNode line() {
            return line;
        }
    }
}
