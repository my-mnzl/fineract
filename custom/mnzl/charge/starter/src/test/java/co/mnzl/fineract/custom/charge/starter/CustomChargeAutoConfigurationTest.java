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

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class CustomChargeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomChargeAutoConfiguration.class)).withUserConfiguration(TestCollaborators.class);

    @Test
    void mnzlChargeEnabled_default_descriptorBeanPresent() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomChargeAutoConfiguration.class);
            assertThat(ctx).hasBean("mnzlAmountInterestAndPenaltiesChargeCalculationDescriptor");
            assertThat(ctx).hasSingleBean(ChargeCalculationDescriptor.class);
        });
    }

    @Test
    void mnzlChargeEnabled_explicitTrue_descriptorBeanPresent() {
        contextRunner.withPropertyValues("mnzl.charge.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomChargeAutoConfiguration.class);
            assertThat(ctx).hasBean("mnzlAmountInterestAndPenaltiesChargeCalculationDescriptor");
            assertThat(ctx).hasSingleBean(ChargeCalculationDescriptor.class);
        });
    }

    @Test
    void mnzlChargeEnabled_false_descriptorBeanAbsent() {
        contextRunner.withPropertyValues("mnzl.charge.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CustomChargeAutoConfiguration.class);
            assertThat(ctx).doesNotHaveBean("mnzlAmountInterestAndPenaltiesChargeCalculationDescriptor");
            assertThat(ctx).doesNotHaveBean(ChargeCalculationDescriptor.class);
        });
    }

    @Test
    void descriptorBean_hasCorrectCode_andDisplayNameKey() {
        contextRunner.run(ctx -> {
            ChargeCalculationDescriptor descriptor = ctx.getBean(ChargeCalculationDescriptor.class);

            assertThat(descriptor.id()).isEqualTo(6);
            assertThat(descriptor.code()).isEqualTo("chargeCalculationType.percent.of.amount.interest.and.penalties");
            assertThat(descriptor.label()).isEqualTo("% Amount + Interest + Penalties");
            assertThat(descriptor.supportsLoan()).isTrue();
            assertThat(descriptor.supportsSavings()).isFalse();
            assertThat(descriptor.supportsShares()).isFalse();
            assertThat(descriptor.supportsClients()).isFalse();
            assertThat(descriptor.supportsShareAccountActivation()).isFalse();
            assertThat(descriptor.supportsTrancheDisbursement()).isFalse();
        });
    }

    static class TestCollaborators {

        // The @ComponentScan("co.mnzl.fineract.custom.charge") on CustomChargeAutoConfiguration pulls in
        // MnzlAmountInterestAndPenaltiesChargeCalculationValidator, which depends on
        // MnzlLoanProductStrategyReadService.
        // Provide a mock so the context can start.
        @Bean
        MnzlLoanProductStrategyReadService mnzlLoanProductStrategyReadService() {
            return mock(MnzlLoanProductStrategyReadService.class);
        }
    }
}
