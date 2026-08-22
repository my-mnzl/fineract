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
package co.mnzl.fineract.custom.charge.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.charge.calculation.CustomAmountInterestPenaltiesChargeCalculator;
import co.mnzl.fineract.custom.charge.calculation.MnzlAmountInterestAndPenaltiesChargeCalculationValidator;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class CustomChargeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomChargeAutoConfiguration.class)).withUserConfiguration(TestCollaborators.class);

    @Test
    void registersAlwaysActiveCalculatorAndValidator() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CustomChargeAutoConfiguration.class);
            assertThat(context).hasSingleBean(CustomAmountInterestPenaltiesChargeCalculator.class);
            assertThat(context).hasSingleBean(MnzlAmountInterestAndPenaltiesChargeCalculationValidator.class);
        });
    }

    static class TestCollaborators {

        @Bean
        MnzlLoanProductStrategyReadService mnzlLoanProductStrategyReadService() {
            return mock(MnzlLoanProductStrategyReadService.class);
        }
    }
}
