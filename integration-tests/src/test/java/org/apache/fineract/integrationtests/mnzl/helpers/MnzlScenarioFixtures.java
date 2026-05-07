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
package org.apache.fineract.integrationtests.mnzl.helpers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** Loads prod-config JSON fixtures into @MethodSource arguments for parameterized scenarios. */
public final class MnzlScenarioFixtures {

    private static final Gson GSON = new Gson();

    private MnzlScenarioFixtures() {}

    /** @MethodSource for prod product configs. Returns: (configName, productJson) per row. */
    public static Stream<Arguments> prodProductConfigs() {
        List<Map<String, Object>> products = loadJsonList("/mnzl/prod-configs/products.json");
        return products.stream().map(p -> Arguments.of(p.get("configName"), p));
    }

    /** @MethodSource for prod periodic charges. */
    public static Stream<Arguments> prodPeriodicCharges() {
        List<Map<String, Object>> charges = loadJsonList("/mnzl/prod-configs/charges.json");
        return charges.stream().filter(c -> "LOAN_PERIODIC".equals(c.get("chargeTimeType"))).map(c -> Arguments.of(c.get("name"), c));
    }

    /** @MethodSource for prod holidays per office. */
    public static Stream<Arguments> prodHolidays() {
        List<Map<String, Object>> holidays = loadJsonList("/mnzl/prod-configs/holidays.json");
        return holidays.stream().map(h -> Arguments.of(h.get("officeId"), h));
    }

    private static List<Map<String, Object>> loadJsonList(String resourcePath) {
        try (InputStream in = MnzlScenarioFixtures.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing fixture: " + resourcePath + " — run `./gradlew :integration-tests:refreshMnzlProdConfigs` first");
            }
            Type t = new TypeToken<List<Map<String, Object>>>() {}.getType();
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), t);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resourcePath, e);
        }
    }
}
