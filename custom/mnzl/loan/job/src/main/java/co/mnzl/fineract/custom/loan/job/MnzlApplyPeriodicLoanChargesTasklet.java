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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.jobs.exception.TruncatedJobExecutionException;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.lang.NonNull;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@RequiredArgsConstructor
public class MnzlApplyPeriodicLoanChargesTasklet implements Tasklet {

    private final MnzlPeriodicLoanChargeCandidateReadService candidateReadService;
    private final LoanChargeWritePlatformService loanChargeWritePlatformService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final Collection<Long> candidateLoanIds = candidateReadService
                .retrieveLoanIdsWithDuePeriodicCharges(DateUtils.getBusinessLocalDate());
        final Set<Long> uniqueLoanIds = new LinkedHashSet<>(candidateLoanIds);
        final List<Throwable> errors = new ArrayList<>();

        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (final Long loanId : uniqueLoanIds) {
            try {
                transactionTemplate.execute(new TransactionCallbackWithoutResult() {

                    @Override
                    protected void doInTransactionWithoutResult(@NonNull final TransactionStatus status) {
                        loanChargeWritePlatformService.applyPeriodicChargesForLoan(loanId);
                    }
                });
            } catch (final RuntimeException e) {
                log.error("Apply periodic loan charges failed for account {}", loanId, e);
                errors.add(e);
            }
        }

        if (!errors.isEmpty()) {
            throw new TruncatedJobExecutionException(errors);
        }
        return RepeatStatus.FINISHED;
    }
}
