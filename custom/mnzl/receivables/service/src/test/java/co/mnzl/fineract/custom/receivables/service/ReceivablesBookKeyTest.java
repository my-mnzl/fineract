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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceivablesBookKeyTest {

    private static final String QUERY = "select * from m_mnzl_r_configuration";

    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReceivablesConfiguration configuration = new ReceivablesConfiguration(store, json, null, null, null, null);

    ReceivablesBookKeyTest() throws Exception {}

    @Test
    void bookKeyIsTheOnlyConfigurationsScopeKey() {
        when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(QUERY)).thenReturn(List.of(Map.of("scope_key", "book")));
        assertThat(configuration.bookKey()).isEqualTo("book");
        for (var rows : List.of(List.<Map<String, Object>>of(),
                List.<Map<String, Object>>of(Map.of("scope_key", "book"), Map.of("scope_key", "other")))) {
            when(jdbc.queryForList(QUERY)).thenReturn(rows);
            assertThatThrownBy(configuration::bookKey).isInstanceOf(ReceivablesException.class)
                    .hasMessageContaining("FINERACT_CAPABILITY_MISSING");
        }
    }

    @Test
    void migrationStripsNativeScopesAndKeepsSealedBodies() {
        var event = "{\"eventId\":\"e\",\"scope\":{\"platformId\":\"p\",\"financierOrganizationId\":\"f\",\"environment\":\"test\"},"
                + "\"contentHash\":\"h\"}";
        var stored = json.read("{\"scope\":{\"platformId\":\"p\",\"financierOrganizationId\":\"f\",\"environment\":\"test\","
                + "\"developerOrganizationId\":\"d\"},\"replacementCommand\":{\"scope\":{\"platformId\":\"p\","
                + "\"financierOrganizationId\":\"f\",\"environment\":\"test\"}},\"events\":[" + event + "]}");
        ReceivablesNativeScopeMigration.strip(stored);
        assertThat(stored).isEqualTo(
                json.read("{\"scope\":{\"developerOrganizationId\":\"d\"},\"replacementCommand\":{}," + "\"events\":[" + event + "]}"));
    }
}
