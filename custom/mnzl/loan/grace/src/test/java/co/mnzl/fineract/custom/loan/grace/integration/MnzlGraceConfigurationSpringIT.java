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
package co.mnzl.fineract.custom.loan.grace.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.loan.grace.MnzlGraceConfiguration;
import co.mnzl.fineract.custom.loan.grace.MnzlWorkingDayCalculator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.delinquency.helper.DelinquencyEffectivePauseHelper;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionReadService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * L2 Spring slice: verifies that {@code mnzl.loan.grace.workingDays.enabled} controls the grace beans.
 *
 * <p>
 * Each {@code @Bean} method on {@link MnzlGraceConfiguration} has a small collaborator chain (6 distinct types total),
 * so wiring is feasible at the slice tier.
 * </p>
 */
class MnzlGraceConfigurationSpringIT {

    // withBean(...) is used (instead of a @Configuration class providing @Bean methods) for collaborators
    // whose real implementation has @PersistenceContext fields or other Spring-managed annotations that
    // Spring would still try to honor on the mock subclass. Using withBean registers a pre-built instance and
    // bypasses much (but not all) of that processing — in practice Spring still post-processes the instance,
    // but we provide every needed mock collaborator so injection succeeds.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MnzlGraceConfiguration.class)
            .withBean(WorkingDaysRepositoryWrapper.class, () -> mock(WorkingDaysRepositoryWrapper.class))
            .withBean(HolidayRepositoryWrapper.class, () -> mock(HolidayRepositoryWrapper.class))
            .withBean(DelinquencyEffectivePauseHelper.class, () -> mock(DelinquencyEffectivePauseHelper.class))
            .withBean(LoanTransactionReadService.class, () -> mock(LoanTransactionReadService.class))
            .withBean(ConfigurationDomainService.class, () -> mock(ConfigurationDomainService.class))
            .withBean(BusinessEventNotifierService.class, () -> mock(BusinessEventNotifierService.class))
            .withBean(LoanChargeWritePlatformService.class, () -> mock(LoanChargeWritePlatformService.class))
            // The mock subclass of LoanTransactionReadService inherits its @PersistenceContext field, so
            // Spring's PersistenceAnnotationBeanPostProcessor still tries to find an EntityManagerFactory on
            // the slice. Provide a mock so post-processing succeeds.
            .withBean(EntityManagerFactory.class, () -> {
                EntityManagerFactory emf = mock(EntityManagerFactory.class);
                org.mockito.Mockito.when(emf.createEntityManager()).thenReturn(mock(EntityManager.class));
                return emf;
            });

    @Test
    void mnzlGraceWorkingDaysEnabled_customBeansRegistered() {
        contextRunner.withPropertyValues("mnzl.loan.grace.workingDays.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(MnzlGraceConfiguration.class);
            assertThat(ctx).hasSingleBean(MnzlWorkingDayCalculator.class);
            assertThat(ctx).hasSingleBean(LoanDelinquencyDomainService.class);
            // Two LoanCOBBusinessStep @Primary beans are exposed by the config.
            assertThat(ctx).getBeans(LoanCOBBusinessStep.class).hasSize(2);
        });
    }

    @Test
    void mnzlGraceWorkingDaysEnabled_default_customBeansRegistered() {
        // matchIfMissing=true — without setting the flag, the @Configuration still loads.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MnzlGraceConfiguration.class);
            assertThat(ctx).hasSingleBean(MnzlWorkingDayCalculator.class);
        });
    }

    @Test
    void mnzlGraceWorkingDaysDisabled_customBeansAbsent() {
        contextRunner.withPropertyValues("mnzl.loan.grace.workingDays.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MnzlGraceConfiguration.class);
            assertThat(ctx).doesNotHaveBean(MnzlWorkingDayCalculator.class);
            assertThat(ctx).doesNotHaveBean(LoanDelinquencyDomainService.class);
            assertThat(ctx).doesNotHaveBean(LoanCOBBusinessStep.class);
        });
    }

}
