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
package co.mnzl.fineract.custom.loan.schedule.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import co.mnzl.fineract.custom.loan.schedule.CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator;
import co.mnzl.fineract.custom.loan.schedule.CustomLoanScheduleGeneratorFactory;
import org.apache.fineract.organisation.monetary.mapper.CurrencyMapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.CumulativeFlatInterestLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultLoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.PaymentPeriodsInOneYearCalculator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ProgressiveLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** L2 Spring slice: verifies that toggling {@code mnzl.loan.schedule.enabled} controls the custom factory. */
class CustomLoanScheduleGeneratorFactorySpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(SchedulePackageScan.class,
            SchedulePackageCollaborators.class);

    @Test
    void mnzlScheduleEnabled_customFactoryRegisteredAsPrimary() {
        contextRunner.withPropertyValues("mnzl.loan.schedule.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomLoanScheduleGeneratorFactory.class);
            // Custom factory is @Primary — when injection asks for the interface, the custom one must win.
            LoanScheduleGeneratorFactory selected = ctx.getBean(LoanScheduleGeneratorFactory.class);
            assertThat(selected).isInstanceOf(CustomLoanScheduleGeneratorFactory.class);
        });
    }

    @Test
    void mnzlScheduleEnabled_default_customFactoryRegistered() {
        // matchIfMissing=true — without setting the flag, the @Component still registers.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomLoanScheduleGeneratorFactory.class);
        });
    }

    @Test
    void mnzlScheduleEnabled_false_upstreamFactoryActive() {
        contextRunner.withPropertyValues("mnzl.loan.schedule.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CustomLoanScheduleGeneratorFactory.class);
            assertThat(ctx).doesNotHaveBean(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
            // Upstream DefaultLoanScheduleGeneratorFactory remains, because we provide it as a mock collaborator.
            assertThat(ctx).hasSingleBean(DefaultLoanScheduleGeneratorFactory.class);
        });
    }

    @Configuration
    @ComponentScan("co.mnzl.fineract.custom.loan.schedule")
    static class SchedulePackageScan {}

    @Configuration
    static class SchedulePackageCollaborators {

        @Bean
        ProgressiveLoanScheduleGenerator progressiveLoanScheduleGenerator() {
            return mock(ProgressiveLoanScheduleGenerator.class);
        }

        @Bean
        CumulativeFlatInterestLoanScheduleGenerator cumulativeFlatInterestLoanScheduleGenerator() {
            return mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        }

        @Bean
        DefaultLoanScheduleGeneratorFactory defaultLoanScheduleGeneratorFactory() {
            return mock(DefaultLoanScheduleGeneratorFactory.class);
        }

        @Bean
        MnzlLoanProductStrategyReadService mnzlLoanProductStrategyReadService() {
            return mock(MnzlLoanProductStrategyReadService.class);
        }

        @Bean
        ScheduledDateGenerator scheduledDateGenerator() {
            return mock(ScheduledDateGenerator.class);
        }

        @Bean
        PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator() {
            return mock(PaymentPeriodsInOneYearCalculator.class);
        }

        @Bean
        LoanTransactionRepository loanTransactionRepository() {
            return mock(LoanTransactionRepository.class);
        }

        @Bean
        CurrencyMapper currencyMapper() {
            return mock(CurrencyMapper.class);
        }
    }
}
