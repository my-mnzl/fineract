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
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyWriteService;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L2 Spring slice: verifies that {@code mnzl.loan.instrument.enabled} controls the JDBC strategy service.
 *
 * <p>
 * Scans only the package's services + the JSON validator. The {@code MnzlLoanProductStrategyApiResource} (JAX-RS
 * endpoint) is excluded — it depends on PlatformSecurityContext + ApiRequestParameterHelper which belong to the API
 * layer and are exercised by L3 endpoint tests.
 * </p>
 */
class MnzlLoanProductStrategyConfigurationSpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(InstrumentPackageScan.class,
            InstrumentCollaborators.class);

    @Test
    void mnzlInstrumentEnabled_jdbcServiceRegistered() {
        contextRunner.withPropertyValues("mnzl.loan.instrument.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(JdbcMnzlLoanProductStrategyService.class);
            // Same bean implements both read + write.
            assertThat(ctx.getBean(MnzlLoanProductStrategyReadService.class)).isInstanceOf(JdbcMnzlLoanProductStrategyService.class);
            assertThat(ctx.getBean(MnzlLoanProductStrategyWriteService.class)).isInstanceOf(JdbcMnzlLoanProductStrategyService.class);
            assertThat(ctx).hasSingleBean(MnzlLoanProductStrategyApiJsonValidator.class);
        });
    }

    @Test
    void mnzlInstrumentEnabled_default_jdbcServiceRegistered() {
        // matchIfMissing=true — the @ConditionalOnProperty allows the bean even without the flag set.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JdbcMnzlLoanProductStrategyService.class);
        });
    }

    @Test
    void mnzlInstrumentDisabled_serviceAbsent() {
        contextRunner.withPropertyValues("mnzl.loan.instrument.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(JdbcMnzlLoanProductStrategyService.class);
            assertThat(ctx).doesNotHaveBean(MnzlLoanProductStrategyApiJsonValidator.class);
            assertThat(ctx).doesNotHaveBean(MnzlLoanProductStrategyReadService.class);
            assertThat(ctx).doesNotHaveBean(MnzlLoanProductStrategyWriteService.class);
        });
    }

    @Configuration
    @ComponentScan(basePackages = "co.mnzl.fineract.custom.loan.instrument", excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "co\\.mnzl\\.fineract\\.custom\\.loan\\.instrument\\.MnzlLoanProductStrategyApiResource"))
    static class InstrumentPackageScan {}

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
    }
}
