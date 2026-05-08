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
package co.mnzl.fineract.custom.loan.starter.integration;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Shared Spring slice base for mnzl module integration tests.
 *
 * <p>
 * Subclasses build an {@link ApplicationContextRunner} with the module's auto-configuration plus the minimum mock
 * collaborators required to verify {@code @Primary} bean wiring and {@code @ConditionalOnProperty} flag toggles.
 * </p>
 *
 * <p>
 * These are NOT full Spring Boot tests — they are lightweight slices that just verify wiring behavior.
 * </p>
 */
public abstract class AbstractMnzlModuleSpringTest {

    protected ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner();
    }

    protected ApplicationContextRunner withProperties(String... pairs) {
        return contextRunner().withPropertyValues(pairs);
    }
}
