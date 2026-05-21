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

import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.delinquency.helper.DelinquencyEffectivePauseHelper;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainService;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainServiceImpl;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
            ConfigurationDomainService configurationDomainService, LoanRepositoryWrapper loanRepositoryWrapper) {
        return new MnzlOverdueChargeGraceAspect(workingDayCalculator, configurationDomainService, loanRepositoryWrapper);
    }
}
