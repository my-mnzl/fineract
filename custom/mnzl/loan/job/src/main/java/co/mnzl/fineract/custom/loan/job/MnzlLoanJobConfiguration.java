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

import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.account.jobs.executestandinginstructions.ExecuteStandingInstructionsTasklet;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.jobs.transferfeechargeforloans.TransferFeeChargeForLoansTasklet;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "mnzl.loan.job.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlLoanJobConfiguration {

    @Bean
    @Primary
    public ExecuteStandingInstructionsTasklet mnzlExecuteStandingInstructionsTasklet(
            StandingInstructionReadPlatformService standingInstructionReadPlatformService, JdbcTemplate jdbcTemplate,
            DatabaseSpecificSQLGenerator sqlGenerator, AccountTransfersWritePlatformService accountTransfersWritePlatformService,
            TransactionTemplate transactionTemplate, ScheduledDateGenerator scheduledDateGenerator) {
        return new MnzlExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator,
                accountTransfersWritePlatformService, transactionTemplate, scheduledDateGenerator);
    }

    @Bean
    @Primary
    public TransferFeeChargeForLoansTasklet mnzlTransferFeeChargeForLoansTasklet(
            LoanChargeReadPlatformService loanChargeReadPlatformService,
            AccountAssociationsReadPlatformService accountAssociationsReadPlatformService,
            AccountTransfersWritePlatformService accountTransfersWritePlatformService, TransactionTemplate transactionTemplate) {
        return new MnzlTransferFeeChargeForLoansTasklet(loanChargeReadPlatformService, accountAssociationsReadPlatformService,
                accountTransfersWritePlatformService, transactionTemplate);
    }
}
