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
package co.mnzl.fineract.custom.platform.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CustomPlatformAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomPlatformAutoConfiguration.class));

    @Test
    void mnzlPlatformEnabled_default_configurationLoaded() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomPlatformAutoConfiguration.class);
        });
    }

    @Test
    void mnzlPlatformEnabled_explicitTrue_configurationLoaded() {
        contextRunner.withPropertyValues("mnzl.platform.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomPlatformAutoConfiguration.class);
        });
    }

    @Test
    void mnzlPlatformEnabled_false_configurationNotLoaded() {
        contextRunner.withPropertyValues("mnzl.platform.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CustomPlatformAutoConfiguration.class);
        });
    }
}
