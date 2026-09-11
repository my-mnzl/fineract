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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

@Component
public final class ReceivablesJson {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonNode specification;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    public ReceivablesJson() throws IOException {
        var amounts = new com.fasterxml.jackson.databind.module.SimpleModule();
        amounts.addSerializer(java.math.BigDecimal.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
        amounts.addSerializer(java.math.BigInteger.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
        mapper.registerModule(amounts);
        try (var stream = getClass().getResourceAsStream("/receivables-v1.json")) {
            if (stream == null) {
                throw new IOException("Missing receivables OpenAPI");
            }
            specification = mapper.readTree(stream);
        }
    }

    public ObjectNode normalizedBasis(JsonNode basis) {
        ObjectNode normalized = basis.deepCopy();
        for (String field : java.util.List.of("cashflows", "acceptedAccountPrices")) {
            java.util.List<JsonNode> rows = new java.util.ArrayList<>();
            basis.path(field).forEach(rows::add);
            String id = field.equals("cashflows") ? "cashflowId" : "accountId";
            rows.sort(java.util.Comparator.comparing(row -> text(row, id)));
            ReceivablesException.require(rows.stream().map(row -> text(row, id)).distinct().count() == rows.size(), "INVALID_DATA");
            normalized.set(field, value(rows));
        }
        return normalized;
    }

    public JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (IOException e) {
            throw new ReceivablesException("INVALID_DATA", e);
        }
    }

    public JsonNode validate(String schema, String value) {
        JsonNode node = read(value);
        JsonSchema validator = schemas.computeIfAbsent(schema, name -> {
            ObjectNode root = mapper.createObjectNode();
            root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            root.put("$ref", "#/components/schemas/" + name);
            root.set("components", specification.get("components"));
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        });
        ReceivablesException.require(validator.validate(node).isEmpty(), "INVALID_DATA");
        return node;
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public JsonNode value(Object value) {
        return mapper.valueToTree(value);
    }

    public <T> T convert(JsonNode value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hash(JsonNode value) {
        try {
            return sha256(new JsonCanonicalizer(write(value)).getEncodedUTF8());
        } catch (IOException e) {
            throw new ReceivablesException("INVALID_DATA", e);
        }
    }

    public static String hashText(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(JsonNode value, String name) {
        JsonNode field = value.get(name);
        ReceivablesException.require(field != null && field.isTextual(), "INVALID_DATA");
        return field.textValue();
    }

    public static BigInteger minor(JsonNode value, String name) {
        return new BigInteger(text(value, name));
    }
}
