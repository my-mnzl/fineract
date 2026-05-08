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
package co.mnzl.fineract.custom.loan.simulator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import co.mnzl.fineract.custom.loan.simulator.MnzlSimulationConfiguration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;

/**
 * L2 Spring slice for the simulator module.
 *
 * <p>
 * The simulator's {@code MnzlSimulationConfiguration} only defines a {@code TaskExecutor} bean — that piece is easy to
 * verify directly. The bulk of simulator wiring lives in {@code @Component}-annotated classes
 * ({@code JdbcMnzlSimulationService}, {@code MnzlLoanSimulationRunner}, {@code MnzlSimulationApiResource},
 * {@code MnzlSimulationApiJsonValidator}) which collectively pull in 15+ collaborators (FromJsonHelper,
 * PlatformSecurityContext, PortfolioCommandSourceWritePlatformService, LoanRepositoryWrapper, InlineExecutorService,
 * LoanProductRepository, LoanScheduleCalculationPlatformService, JdbcTemplate, ...). Wiring those at the slice tier
 * provides no incremental signal — covered by L3 (Phase F).
 * </p>
 */
class MnzlLoanSimulatorConfigurationSpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MnzlSimulationConfiguration.class);

    @Test
    void mnzlSimulatorEnabled_taskExecutorRegistered() {
        contextRunner.withPropertyValues("mnzl.loan.simulator.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(MnzlSimulationConfiguration.class);
            assertThat(ctx).hasSingleBean(TaskExecutor.class);
        });
    }

    @Test
    void mnzlSimulatorEnabled_default_taskExecutorRegistered() {
        // matchIfMissing=true — without setting the flag, the @Configuration still loads.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MnzlSimulationConfiguration.class);
            assertThat(ctx).hasSingleBean(TaskExecutor.class);
        });
    }

    @Test
    void mnzlSimulatorDisabled_taskExecutorAbsent() {
        contextRunner.withPropertyValues("mnzl.loan.simulator.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MnzlSimulationConfiguration.class);
            assertThat(ctx).doesNotHaveBean(TaskExecutor.class);
        });
    }

    @Test
    @Disabled("L2 slice deferred: simulator @Component classes (JdbcMnzlSimulationService, "
            + "MnzlLoanSimulationRunner, MnzlSimulationApiResource, MnzlSimulationApiJsonValidator) require "
            + "15+ collaborator mocks (FromJsonHelper, PlatformSecurityContext, "
            + "PortfolioCommandSourceWritePlatformService, LoanRepositoryWrapper, InlineExecutorService, "
            + "LoanProductRepository, LoanScheduleCalculationPlatformService, JdbcTemplate, ...). Covered at " + "L3 (Phase F).")
    void mnzlSimulatorEnabled_servicesRegistered() {
        // intentionally empty — see @Disabled reason
    }
}
