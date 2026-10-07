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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one read-side alias from the integral fee vocabulary to the admin fee vocabulary. Hash-sealed records (events,
 * journal lines, commands, account terms, configuration revisions, period snapshots) keep their stored bytes and are
 * only ever verified against them; callers alias what they parse, never what they hash.
 */
public final class ReceivablesLegacy {

    /** The deferred fee account key used by configuration revisions approved before the admin fee. */
    public static final String DEFERRED_FEE_ACCOUNT = "deferredIntegralFee";
    private static final Map<String, String> ACCOUNT_KEYS = Map.of(DEFERRED_FEE_ACCOUNT, "deferredAdminFee");
    private static final Map<String, String> COMPONENTS = Map.of("INTEGRAL_FEE", "ADMIN_FEE");
    private static final Map<String, String> FIELDS = Map.of("integralFeeMinor", "adminFeeMinor", "deferredIntegralFeeMinor",
            "deferredAdminFeeMinor", "integralFeeIncomeMinor", "adminFeeIncomeMinor", "originalIntegralFeeMinor", "originalAdminFeeMinor");

    private ReceivablesLegacy() {}

    public static String accountKey(String key) {
        return ACCOUNT_KEYS.getOrDefault(key, key);
    }

    /** The same node when nothing is legacy, otherwise an aliased copy; the input is never modified. */
    public static JsonNode current(JsonNode node) {
        return legacy(node) ? alias(node.deepCopy()) : node;
    }

    static boolean legacy(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (legacy(item)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (FIELDS.containsKey(field.getKey()) || legacyValue(field.getKey(), field.getValue()) || legacy(field.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean legacyValue(String name, JsonNode value) {
        return value.isTextual() && ((name.equals("accountKey") && ACCOUNT_KEYS.containsKey(value.textValue()))
                || (name.equals("component") && COMPONENTS.containsKey(value.textValue())));
    }

    private static JsonNode alias(JsonNode node) {
        if (node.isArray()) {
            node.forEach(ReceivablesLegacy::alias);
        } else if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            object.fields().forEachRemaining(field -> entries.add(Map.entry(field.getKey(), field.getValue())));
            object.removeAll();
            for (var entry : entries) {
                String name = entry.getKey();
                JsonNode value = alias(entry.getValue());
                if (legacyValue(name, value)) {
                    value = object
                            .textNode(name.equals("accountKey") ? ACCOUNT_KEYS.get(value.textValue()) : COMPONENTS.get(value.textValue()));
                }
                object.set(FIELDS.getOrDefault(name, name), value);
            }
        }
        return node;
    }
}
