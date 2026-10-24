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
package co.mnzl.fineract.custom.loan.grace;

import java.util.Map;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.delinquency.helper.DelinquencyEffectivePauseHelper;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainService;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainServiceImpl;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.jobs.updateloanarrearsageing.LoanArrearsAgeingUpdateHandler;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.service.LoanArrearsAgingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionReadService;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "mnzl.loan.grace.workingDays.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlGraceConfiguration {

    @Bean
    public MnzlWorkingDayCalculator mnzlWorkingDayCalculator(WorkingDaysRepositoryWrapper workingDaysRepository,
            HolidayRepositoryWrapper holidayRepository) {
        return new MnzlWorkingDayCalculator(workingDaysRepository, holidayRepository);
    }

    @Bean
    @Primary
    public LoanDelinquencyDomainService mnzlLoanDelinquencyDomainService(DelinquencyEffectivePauseHelper delinquencyEffectivePauseHelper,
            LoanTransactionReadService loanTransactionReadService, MnzlWorkingDayCalculator workingDayCalculator) {
        // Upstream DelinquencyConfiguration declares its loanDelinquencyDomainService bean as
        // @ConditionalOnMissingBean(LoanDelinquencyDomainService.class) — registering our bean disables theirs,
        // so we have to construct the delegate directly. If the upstream constructor signature ever changes,
        // this call breaks at compile time.
        LoanDelinquencyDomainService delegate = new LoanDelinquencyDomainServiceImpl(delinquencyEffectivePauseHelper,
                loanTransactionReadService);
        return new MnzlLoanDelinquencyDomainService(delegate, workingDayCalculator);
    }

    @Bean
    @Primary
    public LoanCOBBusinessStep mnzlCheckLoanRepaymentOverdueBusinessStep(ConfigurationDomainService configurationDomainService,
            BusinessEventNotifierService businessEventNotifierService, MnzlWorkingDayCalculator workingDayCalculator) {
        return new MnzlCheckLoanRepaymentOverdueBusinessStep(configurationDomainService, businessEventNotifierService,
                workingDayCalculator);
    }

    @Bean
    @Primary
    public LoanCOBBusinessStep mnzlApplyChargeToOverdueLoansBusinessStep(ConfigurationDomainService configurationDomainService,
            LoanChargeWritePlatformService loanChargeWritePlatformService, MnzlWorkingDayCalculator workingDayCalculator) {
        return new MnzlApplyChargeToOverdueLoansBusinessStep(configurationDomainService, loanChargeWritePlatformService,
                workingDayCalculator);
    }

    /**
     * Enforces working-day grace at the shared write chokepoint so the standalone "Apply penalty to overdue loans"
     * scheduled job (which bypasses the COB step) also honours working days. Spring's AspectJ auto-proxy picks up the
     * {@code @Aspect} on this {@code @Bean}-registered class.
     */
    @Bean
    public MnzlOverdueChargeGraceAspect mnzlOverdueChargeGraceAspect(MnzlWorkingDayCalculator workingDayCalculator,
            ConfigurationDomainService configurationDomainService, LoanRepositoryWrapper loanRepositoryWrapper,
            ChargeRepositoryWrapper chargeRepository, LoanChargeReadPlatformService loanChargeReadPlatformService,
            ScheduledDateGenerator scheduledDateGenerator) {
        return new MnzlOverdueChargeGraceAspect(workingDayCalculator, configurationDomainService, loanRepositoryWrapper, chargeRepository,
                loanChargeReadPlatformService, scheduledDateGenerator);
    }

    /**
     * Named like upstream's bean so it replaces it (Fineract runs with bean definition overriding on, and this
     * auto-configured module registers after upstream's {@code LoanAccountConfiguration}). The instance registers the
     * arrears event listeners, so it must be the only one: see {@link #mnzlSingleLoanArrearsAgingServiceCheck}.
     */
    @Bean(name = "loanArrearsAgingService")
    @Primary
    public LoanArrearsAgingService mnzlLoanArrearsAgingService(JdbcTemplate jdbcTemplate,
            BusinessEventNotifierService businessEventNotifierService, DatabaseSpecificSQLGenerator sqlGenerator,
            MnzlWorkingDayCalculator workingDayCalculator) {
        return new MnzlLoanArrearsAgingService(jdbcTemplate, businessEventNotifierService, sqlGenerator, workingDayCalculator);
    }

    /** Named like upstream's {@code @Component} so it replaces it for the arrears job and COB step. */
    @Bean(name = "loanArrearsAgeingUpdateHandler")
    @Primary
    public LoanArrearsAgeingUpdateHandler mnzlLoanArrearsAgeingUpdateHandler(JdbcTemplate jdbcTemplate,
            DatabaseSpecificSQLGenerator sqlGenerator, LoanArrearsAgingService loanArrearsAgingService,
            MnzlWorkingDayCalculator workingDayCalculator) {
        return new MnzlLoanArrearsAgeingUpdateHandler(jdbcTemplate, sqlGenerator, loanArrearsAgingService, workingDayCalculator);
    }

    /**
     * Fails startup if upstream's arrears service survived next to ours: both would register the arrears event
     * listeners and overwrite each other's {@code m_loan_arrears_aging} rows on every repayment.
     */
    @Bean
    public SmartInitializingSingleton mnzlSingleLoanArrearsAgingServiceCheck(ListableBeanFactory beanFactory) {
        return () -> {
            Map<String, LoanArrearsAgingService> services = beanFactory.getBeansOfType(LoanArrearsAgingService.class);
            if (services.size() != 1 || !(services.values().iterator().next() instanceof MnzlLoanArrearsAgingService)) {
                throw new IllegalStateException("Expected MnzlLoanArrearsAgingService to be the only LoanArrearsAgingService, found "
                        + services.keySet() + " — check the bean name still matches upstream's LoanAccountConfiguration");
            }
        };
    }
}
