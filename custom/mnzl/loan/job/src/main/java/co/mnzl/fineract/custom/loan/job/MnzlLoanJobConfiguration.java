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
package co.mnzl.fineract.custom.loan.job;

import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformServiceImpl;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculatorRegistry;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

@Configuration
public class MnzlLoanJobConfiguration {

    @Bean
    public MnzlPeriodicChargeProjectionService mnzlPeriodicChargeProjectionService(@Lazy final LoanChargeAssembler loanChargeAssembler,
            final LoanChargeService loanChargeService, final ScheduledDateGenerator scheduledDateGenerator) {
        return new MnzlPeriodicChargeProjectionService(loanChargeAssembler, loanChargeService, scheduledDateGenerator);
    }

    @Bean
    public MnzlPeriodicChargeProjectionListener mnzlPeriodicChargeProjectionListener(
            final BusinessEventNotifierService businessEventNotifierService,
            final MnzlPeriodicChargeProjectionService mnzlPeriodicChargeProjectionService) {
        return new MnzlPeriodicChargeProjectionListener(businessEventNotifierService, mnzlPeriodicChargeProjectionService);
    }

    @Bean
    @Primary
    public LoanScheduleCalculationPlatformService mnzlLoanScheduleCalculationPlatformService(
            final LoanScheduleCalculationPlatformServiceImpl delegate, final LoanProductRepository loanProductRepository,
            final MnzlPeriodicChargeProjectionService projectionService) {
        return new MnzlPeriodicChargeCalculatorDecorator(delegate, loanProductRepository, projectionService);
    }

    @Bean
    @Primary
    public LoanChargeAssembler mnzlLoanChargeAssembler(final FromJsonHelper fromApiJsonHelper,
            final ChargeRepositoryWrapper chargeRepository, final LoanChargeRepository loanChargeRepository,
            final LoanProductRepository loanProductRepository, final ExternalIdFactory externalIdFactory,
            final LoanChargeService loanChargeService, final ChargeAmountCalculatorRegistry chargeAmountCalculatorRegistry,
            final MnzlPeriodicChargeProjectionService projectionService) {
        return new MnzlLoanChargeAssembler(fromApiJsonHelper, chargeRepository, loanChargeRepository, loanProductRepository,
                externalIdFactory, loanChargeService, chargeAmountCalculatorRegistry, projectionService);
    }
}
