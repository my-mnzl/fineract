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
package co.mnzl.fineract.custom.loan.instrument.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.loan.instrument.JdbcMnzlLoanProductStrategyService;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyApiJsonValidator;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyApiResource;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyData;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyWriteService;
import co.mnzl.fineract.custom.platform.starter.CustomPlatformAutoConfiguration;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L2 Spring slice proving the always-on platform starter discovers the strategy API and services.
 */
class MnzlLoanProductStrategyConfigurationSpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomPlatformAutoConfiguration.class))
            .withUserConfiguration(InstrumentCollaborators.class);

    @Test
    void strategyServicesAreRegistered() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JdbcMnzlLoanProductStrategyService.class);
            assertThat(ctx.getBean(MnzlLoanProductStrategyReadService.class)).isInstanceOf(JdbcMnzlLoanProductStrategyService.class);
            assertThat(ctx.getBean(MnzlLoanProductStrategyWriteService.class)).isInstanceOf(JdbcMnzlLoanProductStrategyService.class);
            assertThat(ctx).hasSingleBean(MnzlLoanProductStrategyApiJsonValidator.class);
            assertThat(ctx).hasSingleBean(MnzlLoanProductStrategyApiResource.class);
        });
    }

    @Configuration
    static class InstrumentCollaborators {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        LoanProductRepository loanProductRepository() {
            return mock(LoanProductRepository.class);
        }

        @Bean
        FromJsonHelper fromJsonHelper() {
            return mock(FromJsonHelper.class);
        }

        @Bean
        PlatformSecurityContext platformSecurityContext() {
            return mock(PlatformSecurityContext.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        DefaultToApiJsonSerializer<MnzlLoanProductStrategyData> defaultToApiJsonSerializer() {
            return mock(DefaultToApiJsonSerializer.class);
        }

        @Bean
        ApiRequestParameterHelper apiRequestParameterHelper() {
            return mock(ApiRequestParameterHelper.class);
        }
    }
}
