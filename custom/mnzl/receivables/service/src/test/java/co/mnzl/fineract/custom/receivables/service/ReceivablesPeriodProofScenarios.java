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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Real database proof checks include omissions that would disappear behind a registry inner join or period filter. */
final class ReceivablesPeriodProofScenarios {

    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesPeriodProofScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify() throws Exception {
        long watermark = harness.queryLong("select max(sequence_id) from m_mnzl_r_event");
        List<JsonNode> events = new ArrayList<>();
        String cursor = "";
        do {
            JsonNode page = harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/events?eventWatermark=" + watermark
                    + "&limit=7" + (cursor.isEmpty() ? "" : "&cursor=" + cursor), null, 200);
            page.path("items").forEach(events::add);
            cursor = page.path("nextCursor").asText("");
        } while (!cursor.isEmpty());
        assertThat(events).anyMatch(event -> !event.path("reversalOfEventId").isNull());
        assertThat(events).anyMatch(event -> event.path("sourceKind").asText().equals("HEL") && event.path("journalLines").isEmpty());
        for (String period : events.stream().map(event -> event.path("postingPeriod").asText()).distinct().toList()) {
            JsonNode proof = proof(period, watermark, 200);
            ObjectNode selection = ((ObjectNode) proof).deepCopy();
            selection.remove(List.of("eventManifest", "observedGl", "proofHash"));
            var members = events.stream().filter(event -> event.path("postingPeriod").asText().equals(period))
                    .sorted(Comparator.comparing(event -> event.path("eventId").asText()))
                    .map(event -> Map.of("eventId", event.path("eventId").asText(), "contentHash", event.path("contentHash").asText()))
                    .toList();
            assertThat(proof.path("eventManifest").path("count").asInt()).isEqualTo(members.size());
            assertThat(proof.path("eventManifest").path("sha256").asText())
                    .isEqualTo(harness.json.hash(harness.json.value(Map.of("selection", selection, "events", members))));
            ObjectNode unsigned = ((ObjectNode) proof).deepCopy();
            unsigned.remove("proofHash");
            assertThat(proof.path("proofHash").asText()).isEqualTo(harness.json.hash(unsigned));
            long lineCount = events.stream().filter(event -> event.path("postingPeriod").asText().equals(period))
                    .mapToLong(event -> event.path("journalLines").size()).sum();
            assertThat(proof.path("observedGl").path("count").asLong()).isEqualTo(lineCount);
            BigInteger debits = BigInteger.ZERO;
            BigInteger credits = BigInteger.ZERO;
            for (JsonNode account : proof.path("observedGl").path("accounts")) {
                BigInteger debit = new BigInteger(account.path("debitMinor").asText());
                BigInteger credit = new BigInteger(account.path("creditMinor").asText());
                assertThat(account.path("netMinor").asText()).isEqualTo(debit.subtract(credit).toString());
                debits = debits.add(debit);
                credits = credits.add(credit);
            }
            assertThat(debits).isEqualTo(credits);
            if (lineCount > 0) {
                assertThat(debits).isPositive();
            }
        }
        JsonNode empty = proof("1900-01", watermark, 200);
        assertThat(empty.path("eventManifest").path("count").asText()).isEqualTo("0");
        assertThat(empty.path("observedGl").path("count").asText()).isEqualTo("0");
        assertThat(empty.path("observedGl").path("accounts").size()).isEqualTo(harness.accounts.size());
        empty.path("observedGl").path("accounts").forEach(account -> {
            assertThat(account.path("debitMinor").asText()).isEqualTo("0");
            assertThat(account.path("creditMinor").asText()).isEqualTo("0");
        });
        assertThat(proof(harness.today.toString().substring(0, 7), watermark + 1, 400).path("code").asText()).isEqualTo("INVALID_DATA");
        verifyDamage(watermark);
    }

    private void verifyDamage(long watermark) throws Exception {
        String eventId = harness.queryText("select e.record_key from m_mnzl_r_event e join m_mnzl_r_command c "
                + "on c.record_key=e.operation_key where c.operation_id='office-native-first'");
        String period = harness.queryText("select posting_period from m_mnzl_r_event where record_key='" + eventId + "'");
        final String journalCondition = "id in (select native_journal_id from m_mnzl_r_journal_line where event_key=?)";
        JsonNode original = proof(period, watermark, 200);
        harness.executeSql("create table flex_proof_journal_backup as select * from acc_gl_journal_entry where " + journalCondition,
                eventId);
        try {
            assertThat(harness.queryLong("select count(*) from flex_proof_journal_backup")).isEqualTo(2);
            // A missing debit/credit pair nets to zero; population correspondence must still reject it.
            harness.executeSql("delete from acc_gl_journal_entry where " + journalCondition, eventId);
            rejected(period, watermark);
            restoreJournals();
            harness.executeSql("update acc_gl_journal_entry set entry_date='1900-01-01' where " + journalCondition, eventId);
            rejected(period, watermark);
            rejected("1900-01", watermark); // An event outside the period has actual native rows inside it.
            restoreJournals();
            harness.executeSql("update acc_gl_journal_entry set reversed=true where " + journalCondition, eventId);
            rejected(period, watermark);
            restoreJournals();
            long extra = harness.queryLong("select max(id) from acc_gl_journal_entry") + 1000;
            harness.executeSql("update flex_proof_journal_backup set id=id+?", extra);
            harness.executeSql("insert into acc_gl_journal_entry select * from flex_proof_journal_backup");
            try {
                rejected(period, watermark); // Actual transaction rows absent from the registry are visible.
            } finally {
                harness.executeSql("delete from acc_gl_journal_entry where id in (select id from flex_proof_journal_backup)");
                harness.executeSql("update flex_proof_journal_backup set id=id-?", extra);
            }
            final String condition = " where record_key=?";
            harness.executeSql("update m_mnzl_r_event set posting_period='1900-01'" + condition, eventId);
            try {
                rejected(period, watermark);
            } finally {
                harness.executeSql("update m_mnzl_r_event set posting_period=?" + condition, period, eventId);
            }
            String hash = harness.queryText("select content_hash from m_mnzl_r_event where record_key='" + eventId + "'");
            harness.executeSql("update m_mnzl_r_event set content_hash=?" + condition, "0".repeat(64), eventId);
            try {
                rejected(period, watermark);
            } finally {
                harness.executeSql("update m_mnzl_r_event set content_hash=?" + condition, hash, eventId);
            }
            assertThat(proof(period, watermark, 200).path("observedGl").path("sha256"))
                    .isEqualTo(original.path("observedGl").path("sha256"));
        } finally {
            restoreJournals();
            harness.executeSql("drop table flex_proof_journal_backup");
        }
    }

    private void restoreJournals() throws Exception {
        harness.executeSql("delete from acc_gl_journal_entry where id in (select id from flex_proof_journal_backup)");
        harness.executeSql("insert into acc_gl_journal_entry select * from flex_proof_journal_backup");
    }

    private void rejected(String period, long watermark) throws Exception {
        assertThat(proof(period, watermark, 409).path("code").asText()).isEqualTo("JOURNAL_MISMATCH");
    }

    private JsonNode proof(String period, long watermark, int status) throws Exception {
        JsonNode response = harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/period-activity-proof?postingPeriod="
                + period + "&eventWatermark=" + watermark, null, status);
        if (status == 200) {
            harness.json.validate("periodActivityProof", harness.json.write(response));
        }
        return response;
    }
}
