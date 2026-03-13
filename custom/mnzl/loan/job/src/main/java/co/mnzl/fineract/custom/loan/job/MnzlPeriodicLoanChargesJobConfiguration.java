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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mnzl.loan.job.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlPeriodicLoanChargesJobConfiguration {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Bean
    public MnzlPeriodicLoanChargeCandidateReadService mnzlPeriodicLoanChargeCandidateReadService(final JdbcTemplate jdbcTemplate) {
        return new MnzlPeriodicLoanChargeCandidateReadService(jdbcTemplate);
    }

    @Bean
    public MnzlApplyPeriodicLoanChargesTasklet mnzlApplyPeriodicLoanChargesTasklet(
            final MnzlPeriodicLoanChargeCandidateReadService candidateReadService,
            final LoanChargeWritePlatformService loanChargeWritePlatformService, final TransactionTemplate transactionTemplate) {
        return new MnzlApplyPeriodicLoanChargesTasklet(candidateReadService, loanChargeWritePlatformService, transactionTemplate);
    }

    @Bean
    protected Step mnzlApplyPeriodicLoanChargesStep(final MnzlApplyPeriodicLoanChargesTasklet tasklet) {
        return new StepBuilder(MnzlJobName.APPLY_PERIODIC_LOAN_CHARGES.name(), jobRepository).tasklet(tasklet, transactionManager).build();
    }

    @Bean
    public Job mnzlApplyPeriodicLoanChargesJob(final MnzlApplyPeriodicLoanChargesTasklet tasklet) {
        return new JobBuilder(MnzlJobName.APPLY_PERIODIC_LOAN_CHARGES.name(), jobRepository)
                .start(mnzlApplyPeriodicLoanChargesStep(tasklet)).incrementer(new RunIdIncrementer()).build();
    }
}
