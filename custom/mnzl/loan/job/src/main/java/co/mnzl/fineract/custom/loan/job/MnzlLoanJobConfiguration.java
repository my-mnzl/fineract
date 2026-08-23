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
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.account.jobs.executestandinginstructions.ExecuteStandingInstructionsTasklet;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.jobs.transferfeechargeforloans.TransferFeeChargeForLoansTasklet;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformServiceImpl;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculatorRegistry;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class MnzlLoanJobConfiguration {

    @Bean
    @Primary
    public ExecuteStandingInstructionsTasklet mnzlExecuteStandingInstructionsTasklet(
            final StandingInstructionReadPlatformService standingInstructionReadPlatformService, final JdbcTemplate jdbcTemplate,
            final DatabaseSpecificSQLGenerator sqlGenerator,
            final AccountTransfersWritePlatformService accountTransfersWritePlatformService,
            @Qualifier("requiresNewTransactionTemplate") final TransactionTemplate requiresNewTransactionTemplate,
            final ScheduledDateGenerator scheduledDateGenerator) {
        return new MnzlExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator,
                accountTransfersWritePlatformService, requiresNewTransactionTemplate, scheduledDateGenerator);
    }

    @Bean
    @Primary
    public TransferFeeChargeForLoansTasklet mnzlTransferFeeChargeForLoansTasklet(
            final LoanChargeReadPlatformService loanChargeReadPlatformService,
            final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService,
            final AccountTransfersWritePlatformService accountTransfersWritePlatformService,
            @Qualifier("requiresNewTransactionTemplate") final TransactionTemplate requiresNewTransactionTemplate) {
        return new MnzlTransferFeeChargeForLoansTasklet(loanChargeReadPlatformService, accountAssociationsReadPlatformService,
                accountTransfersWritePlatformService, requiresNewTransactionTemplate);
    }

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
