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
package co.mnzl.fineract.custom.charge.calculation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.charge.calculation.CustomAmountInterestPenaltiesChargeCalculator;
import co.mnzl.fineract.custom.charge.starter.CustomChargeAutoConfiguration;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import org.apache.fineract.portfolio.loanaccount.service.AmountInterestPenaltiesChargeCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/** L2 Spring slice: verifies the mnzl charge calculator wires as @Primary and toggles via property. */
class CustomAmountInterestPenaltiesChargeCalculatorSpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomChargeAutoConfiguration.class)).withUserConfiguration(TestCollaborators.class);

    @Test
    void mnzlChargeEnabled_customCalculatorRegisteredAsPrimary() {
        contextRunner.withPropertyValues("mnzl.charge.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomAmountInterestPenaltiesChargeCalculator.class);
            // The custom calculator extends DefaultAmountInterestPenaltiesChargeCalculator and is the only
            // implementation of AmountInterestPenaltiesChargeCalculator on the slice classpath.
            AmountInterestPenaltiesChargeCalculator bean = ctx.getBean(AmountInterestPenaltiesChargeCalculator.class);
            assertThat(bean).isInstanceOf(CustomAmountInterestPenaltiesChargeCalculator.class);
        });
    }

    @Test
    void mnzlChargeEnabled_default_customCalculatorRegistered() {
        // matchIfMissing=true — without setting the flag, the auto-config still loads.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomAmountInterestPenaltiesChargeCalculator.class);
        });
    }

    @Test
    void mnzlChargeDisabled_customCalculatorAbsent() {
        contextRunner.withPropertyValues("mnzl.charge.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CustomChargeAutoConfiguration.class);
            assertThat(ctx).doesNotHaveBean(CustomAmountInterestPenaltiesChargeCalculator.class);
        });
    }

    static class TestCollaborators {

        // The @ComponentScan("co.mnzl.fineract.custom.charge") on CustomChargeAutoConfiguration pulls in
        // MnzlAmountInterestAndPenaltiesChargeCalculationValidator (depends on MnzlLoanProductStrategyReadService).
        @Bean
        MnzlLoanProductStrategyReadService mnzlLoanProductStrategyReadService() {
            return mock(MnzlLoanProductStrategyReadService.class);
        }
    }
}
