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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/**
 * Removes MNZL's platform, financier and environment from the stored copies of request scopes. Only a developer remains
 * in a scope, and a scope left empty is removed. A body sealed with a hash (events, frozen period controls) keeps its
 * bytes, so its hash still verifies. Record keys, event ids and native journal references derive from the book key and
 * are not touched.
 */
public class ReceivablesNativeScopeMigration implements liquibase.change.custom.CustomTaskChange {

    static final List<String> NATIVE_FIELDS = List.of("platformId", "financierOrganizationId", "environment");
    private static final Map<String, List<String>> COLUMNS = Map.of("m_mnzl_r_account", List.of("scope_json"), "m_mnzl_r_command",
            List.of("request_json", "result_json"), "m_mnzl_r_authorization", List.of("payload_json"), "m_mnzl_r_hel_funding",
            List.of("result_json"), "m_mnzl_r_configuration", List.of("config_json"), "m_mnzl_r_configuration_revision",
            List.of("config_json"));
    private final ObjectMapper mapper = new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Override
    public void execute(Database database) throws CustomChangeException {
        var connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
        try {
            for (var table : COLUMNS.entrySet()) {
                for (String column : table.getValue()) {
                    try (var select = connection.prepareStatement("select record_key, " + column + " from " + table.getKey() + " where "
                            + column + " like '%\"financierOrganizationId\"%'");
                            var update = connection
                                    .prepareStatement("update " + table.getKey() + " set " + column + "=? where record_key=?");
                            var rows = select.executeQuery()) {
                        while (rows.next()) {
                            JsonNode value = mapper.readTree(rows.getString(2));
                            if (column.equals("scope_json")) {
                                ((ObjectNode) value).remove(NATIVE_FIELDS);
                            } else {
                                strip(value);
                            }
                            update.setString(1, mapper.writeValueAsString(value));
                            update.setString(2, rows.getString(1));
                            update.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException | IOException failure) {
            throw new CustomChangeException(failure);
        }
    }

    /** Strips every scope in an unsealed body, leaving sealed bodies within it as they are. */
    static void strip(JsonNode node) {
        if (node.isArray()) {
            node.forEach(ReceivablesNativeScopeMigration::strip);
        }
        if (!node.isObject() || node.has("contentHash") || node.has("proofHash") || node.has("snapshotHash")) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        if (object.get("scope") instanceof ObjectNode scope) {
            scope.remove(NATIVE_FIELDS);
            if (scope.isEmpty()) {
                object.remove("scope");
            }
        }
        object.forEach(ReceivablesNativeScopeMigration::strip);
    }

    @Override
    public String getConfirmationMessage() {
        return "Removed the native scope from MNZL receivables request copies";
    }

    @Override
    public void setUp() {}

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
