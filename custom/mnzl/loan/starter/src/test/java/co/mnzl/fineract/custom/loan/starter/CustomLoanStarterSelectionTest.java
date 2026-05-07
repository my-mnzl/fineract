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
package co.mnzl.fineract.custom.loan.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import java.util.List;
import org.apache.fineract.cob.COBBusinessStepService;
import org.apache.fineract.cob.COBBusinessStepServiceImpl;
import org.apache.fineract.cob.data.BusinessStepNameAndOrder;
import org.apache.fineract.cob.domain.BatchBusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.cob.loan.CheckDueInstallmentsBusinessStep;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.cob.service.ReloaderService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CustomLoanStarterSelectionTest {

    @Test
    void customLoanStarterProvidesPrimaryCheckDueInstallmentsStep() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CustomLoanAutoConfiguration.class))
                .withPropertyValues("mnzl.loan.enabled=true", "mnzl.loan.job.enabled=false", "mnzl.loan.schedule.enabled=false",
                        "mnzl.loan.instrument.enabled=false", "mnzl.loan.simulator.enabled=false",
                        "mnzl.loan.grace.workingDays.enabled=false")
                .withUserConfiguration(TestConfiguration.class).run(ctx -> {
                    COBBusinessStepService businessStepService = ctx.getBean(COBBusinessStepService.class);
                    var result = businessStepService.getCOBBusinessSteps(LoanCOBBusinessStep.class, "JOB");

                    assertThat(ctx).hasBean("customCheckDueInstallmentsBusinessStep");
                    assertThat(result).extracting(BusinessStepNameAndOrder::getStepName).contains("customCheckDueInstallmentsBusinessStep");
                });
    }

    @EnableConfigurationProperties({ FineractProperties.class })
    static class TestConfiguration {

        @Bean
        BatchBusinessStepRepository batchBusinessStepRepository() {
            BatchBusinessStepRepository repository = mock(BatchBusinessStepRepository.class);
            BatchBusinessStep step = new BatchBusinessStep();
            step.setStepName("CHECK_DUE_INSTALLMENTS");
            step.setStepOrder(1L);
            org.mockito.Mockito.when(repository.findAllByJobName("JOB")).thenReturn(List.of(step));
            return repository;
        }

        @Bean
        BusinessEventNotifierService businessEventNotifierService() {
            return mock(BusinessEventNotifierService.class);
        }

        @Bean
        CheckDueInstallmentsBusinessStep checkDueInstallmentsBusinessStep() {
            CheckDueInstallmentsBusinessStep step = mock(CheckDueInstallmentsBusinessStep.class);
            org.mockito.Mockito.when(step.getEnumStyledName()).thenReturn("CHECK_DUE_INSTALLMENTS");
            org.mockito.Mockito.when(step.getHumanReadableName()).thenReturn("Check Due Installments");
            return step;
        }

        @Bean
        MnzlLoanProductStrategyReadService mnzlLoanProductStrategyReadService() {
            return mock(MnzlLoanProductStrategyReadService.class);
        }

        @Bean
        COBBusinessStepService cobBusinessStepService(BatchBusinessStepRepository batchBusinessStepRepository, ApplicationContext context,
                ListableBeanFactory beanFactory, BusinessEventNotifierService businessEventNotifierService,
                ConfigurationDomainService configurationDomainService, ReloaderService reloaderService) {
            return new COBBusinessStepServiceImpl(batchBusinessStepRepository, context, beanFactory, businessEventNotifierService,
                    configurationDomainService, reloaderService);
        }

        @Bean
        LoanAccountDomainService loanAccountDomainService() {
            return mock(LoanAccountDomainService.class);
        }

        @Bean
        StandingInstructionReadPlatformService standingInstructionReadPlatformService() {
            return mock(StandingInstructionReadPlatformService.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        DatabaseSpecificSQLGenerator databaseSpecificSQLGenerator() {
            return mock(DatabaseSpecificSQLGenerator.class);
        }

        @Bean
        AccountTransfersWritePlatformService accountTransfersWritePlatformService() {
            return mock(AccountTransfersWritePlatformService.class);
        }

        @Bean
        TransactionTemplate transactionTemplate() {
            return mock(TransactionTemplate.class);
        }

        @Bean
        ScheduledDateGenerator scheduledDateGenerator() {
            return mock(ScheduledDateGenerator.class);
        }

        @Bean
        LoanChargeReadPlatformService loanChargeReadPlatformService() {
            return mock(LoanChargeReadPlatformService.class);
        }

        @Bean
        AccountAssociationsReadPlatformService accountAssociationsReadPlatformService() {
            return mock(AccountAssociationsReadPlatformService.class);
        }

        @Bean
        ConfigurationDomainService configurationDomainService() {
            return mock(ConfigurationDomainService.class);
        }

        @Bean
        ReloaderService reloaderService() {
            return mock(ReloaderService.class);
        }
    }
}
