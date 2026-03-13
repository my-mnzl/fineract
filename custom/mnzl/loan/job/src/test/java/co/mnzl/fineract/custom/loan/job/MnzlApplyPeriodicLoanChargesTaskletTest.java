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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.jobs.exception.TruncatedJobExecutionException;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MnzlApplyPeriodicLoanChargesTaskletTest {

    @Mock
    private MnzlPeriodicLoanChargeCandidateReadService candidateReadService;

    @Mock
    private LoanChargeWritePlatformService loanChargeWritePlatformService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private TransactionStatus transactionStatus;

    @Test
    void executeProcessesEachCandidateLoanOnce() throws Exception {
        when(candidateReadService.retrieveLoanIdsWithDuePeriodicCharges(any(LocalDate.class))).thenReturn(List.of(11L, 11L, 17L));
        when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        MnzlApplyPeriodicLoanChargesTasklet underTest = new MnzlApplyPeriodicLoanChargesTasklet(candidateReadService,
                loanChargeWritePlatformService, transactionTemplate);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(LocalDate.of(2026, 3, 12));

            RepeatStatus result = underTest.execute(stepContribution, chunkContext);

            assertEquals(RepeatStatus.FINISHED, result);
        }

        verify(transactionTemplate, times(1)).setPropagationBehavior(anyInt());
        verify(loanChargeWritePlatformService, times(1)).applyPeriodicChargesForLoan(11L);
        verify(loanChargeWritePlatformService, times(1)).applyPeriodicChargesForLoan(17L);
    }

    @Test
    void executeContinuesAfterLoanFailureAndThrowsTruncatedExecutionException() {
        when(candidateReadService.retrieveLoanIdsWithDuePeriodicCharges(any(LocalDate.class))).thenReturn(List.of(11L, 13L, 17L));
        when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(transactionStatus));
        doThrow(new IllegalStateException("boom")).when(loanChargeWritePlatformService).applyPeriodicChargesForLoan(13L);
        MnzlApplyPeriodicLoanChargesTasklet underTest = new MnzlApplyPeriodicLoanChargesTasklet(candidateReadService,
                loanChargeWritePlatformService, transactionTemplate);

        try (MockedStatic<DateUtils> mockedDateUtils = org.mockito.Mockito.mockStatic(DateUtils.class)) {
            mockedDateUtils.when(DateUtils::getBusinessLocalDate).thenReturn(LocalDate.of(2026, 3, 12));

            assertThatThrownBy(() -> underTest.execute(stepContribution, chunkContext))
                    .isInstanceOf(TruncatedJobExecutionException.class);
        }

        verify(loanChargeWritePlatformService, times(1)).applyPeriodicChargesForLoan(11L);
        verify(loanChargeWritePlatformService, times(1)).applyPeriodicChargesForLoan(13L);
        verify(loanChargeWritePlatformService, times(1)).applyPeriodicChargesForLoan(17L);
    }
}
