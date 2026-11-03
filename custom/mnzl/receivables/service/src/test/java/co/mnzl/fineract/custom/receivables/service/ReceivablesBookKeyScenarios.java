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

/**
 * MNZL is the only lender: the tenant's book key keys every record, scopes name only a developer, and migration 0009
 * removes the platform, financier and environment from stored request copies while sealed events keep their bytes.
 */
final class ReceivablesBookKeyScenarios {

    private static final String PREFIX = ReceivablesDatabaseIntegrationTest.PREFIX;
    private final ReceivablesDatabaseIntegrationTest harness;

    ReceivablesBookKeyScenarios(ReceivablesDatabaseIntegrationTest harness) {
        this.harness = harness;
    }

    void verify(JsonNode booked) throws Exception {
        String book = harness.queryText("select scope_key from m_mnzl_r_configuration");
        assertThat(book).isEqualTo(ReceivablesJson.hashText("book\ndefault"));
        assertThat(harness.queryText("select record_key from m_mnzl_r_account where external_id='account-1'"))
                .isEqualTo(ReceivablesStore.key(book, "account", "account-1"));
        assertThat(booked.path("financialEventIds").get(0).asText())
                .isEqualTo(ReceivablesStore.key(book, "event", ReceivablesStore.key(book, "operation", "account-1-book")));

        JsonNode context = harness.request("GET", PREFIX + "/context", null, 200);
        assertThat(context.has("scope")).isFalse();
        JsonNode account = harness.request("GET", PREFIX + "/accounts/account-1", null, 200);
        assertThat(account.path("position").path("scope")).isEqualTo(harness.scope());
        JsonNode operation = harness.request("GET", PREFIX + "/operations/account-1-book", null, 200);
        assertThat(operation.path("scope")).isEqualTo(harness.scope());
        JsonNode active = harness.request("GET", PREFIX + "/configuration/active", null, 200);
        assertThat(active.path("configuration").has("scope")).isFalse();

        // A request that still names the platform, financier or environment is refused.
        ObjectNode nativeScope = harness.bookingCommands.get("account-1").deepCopy();
        ((ObjectNode) nativeScope.get("scope")).put("platformId", "mnzl");
        assertThat(harness.request("POST", PREFIX + "/commands", nativeScope, 400).path("code").asText()).isEqualTo("INVALID_DATA");

        // Stored copies recorded with the native scope lose it; the sealed event keeps its bytes.
        var before = harness.counts();
        String event = harness.queryText("select event_json from m_mnzl_r_event where record_key=?",
                booked.path("financialEventIds").get(0).asText());
        withNativeScope("m_mnzl_r_account", "scope_json", "external_id='account-1'", null);
        withNativeScope("m_mnzl_r_command", "request_json", "operation_id='account-1-book'", "scope");
        withNativeScope("m_mnzl_r_command", "result_json", "operation_id='account-1-book'", "scope");
        withNativeScope("m_mnzl_r_configuration", "config_json", "1=1", "scope");
        withNativeScope("m_mnzl_r_configuration_revision", "config_json", "mapping_revision='mapping-1'", "scope");
        harness.migrateNativeScope();
        assertThat(harness.request("GET", PREFIX + "/accounts/account-1", null, 200)).isEqualTo(account);
        assertThat(harness.request("GET", PREFIX + "/operations/account-1-book", null, 200)).isEqualTo(operation);
        assertThat(harness.request("GET", PREFIX + "/configuration/active", null, 200)).isEqualTo(active);
        assertThat(harness.queryText("select scope_json from m_mnzl_r_account where external_id='account-1'"))
                .isEqualTo(harness.json.write(harness.scope()));
        assertThat(harness.queryText("select event_json from m_mnzl_r_event where record_key=?",
                booked.path("financialEventIds").get(0).asText())).isEqualTo(event);
        assertThat(harness.counts()).isEqualTo(before);
    }

    /** Adds the former native scope to the stored JSON, at its root or in its {@code field} object. */
    private void withNativeScope(String table, String column, String where, String field) throws Exception {
        ObjectNode stored = (ObjectNode) harness.json.read(harness.queryText("select " + column + " from " + table + " where " + where));
        ObjectNode scope = field == null ? stored : stored.has(field) ? (ObjectNode) stored.get(field) : stored.putObject(field);
        scope.put("platformId", "mnzl").put("financierOrganizationId", "financier").put("environment", "test");
        harness.executeSql("update " + table + " set " + column + "=? where " + where, harness.json.write(stored));
    }
}
