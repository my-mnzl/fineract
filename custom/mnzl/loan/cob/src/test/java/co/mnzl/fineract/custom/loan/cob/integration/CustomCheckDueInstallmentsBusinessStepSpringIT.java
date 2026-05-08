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
package co.mnzl.fineract.custom.loan.cob.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.loan.cob.CustomCheckDueInstallmentsBusinessStep;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import org.apache.fineract.cob.loan.CheckDueInstallmentsBusinessStep;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * L2 Spring slice: verifies the custom COB step is registered as the {@code @Primary} {@link LoanCOBBusinessStep}.
 *
 * <p>
 * {@link CustomCheckDueInstallmentsBusinessStep} is a {@code @Component} (no {@code @ConditionalOnProperty}). It is
 * enabled/disabled via the parent module's {@code mnzl.loan.enabled} flag at the loan-starter level, not here. So this
 * slice only verifies @Primary wiring + collaborator injection.
 * </p>
 */
class CustomCheckDueInstallmentsBusinessStepSpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(CobPackageScan.class,
            CobCollaborators.class);

    @Test
    void mnzlCobLoaded_customStepRegisteredAsPrimary() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomCheckDueInstallmentsBusinessStep.class);
            // @Primary: when injection asks for the interface, the custom one must win even though the upstream
            // CheckDueInstallmentsBusinessStep is also present as a collaborator.
            LoanCOBBusinessStep selected = ctx.getBean(LoanCOBBusinessStep.class);
            assertThat(selected).isInstanceOf(CustomCheckDueInstallmentsBusinessStep.class);
        });
    }

    @Configuration
    @ComponentScan("co.mnzl.fineract.custom.loan.cob")
    static class CobPackageScan {}

    @Configuration
    static class CobCollaborators {

        @Bean
        BusinessEventNotifierService businessEventNotifierService() {
            return mock(BusinessEventNotifierService.class);
        }

        @Bean
        CheckDueInstallmentsBusinessStep defaultCheckDueInstallmentsBusinessStep() {
            return mock(CheckDueInstallmentsBusinessStep.class);
        }

        @Bean
        MnzlLoanProductStrategyReadService mnzlLoanProductStrategyReadService() {
            return mock(MnzlLoanProductStrategyReadService.class);
        }
    }
}
