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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MNZL policy: loan fees/charges must never be auto-swept from a client's linked savings account. The tasklet is
 * disabled via {@code FEE_TRANSFER_FROM_SAVINGS_ENABLED = false} and short-circuits before touching any collaborator.
 * These tests lock that policy in: if someone re-enables the flag, they will fail and force a deliberate review.
 */
@ExtendWith(MockitoExtension.class)
class MnzlTransferFeeChargeForLoansTaskletTest {

    @Mock
    private LoanChargeReadPlatformService loanChargeReadPlatformService;

    @Mock
    private AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;

    @Mock
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    @Test
    void jobIsDisabledAndReportsFinished() throws Exception {
        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));
    }

    @Test
    void jobNeverTouchesSavingsOrChargesWhenDisabled() throws Exception {
        MnzlTransferFeeChargeForLoansTasklet underTest = newTasklet();

        underTest.execute(stepContribution, chunkContext);

        // The guard returns before any charge lookup or account transfer is attempted:
        // no charges are read, no savings account is resolved, no funds are moved, no transaction is opened.
        verifyNoInteractions(loanChargeReadPlatformService);
        verifyNoInteractions(accountAssociationsReadPlatformService);
        verifyNoInteractions(accountTransfersWritePlatformService);
        verifyNoInteractions(transactionTemplate);
    }

    private MnzlTransferFeeChargeForLoansTasklet newTasklet() {
        return new MnzlTransferFeeChargeForLoansTasklet(loanChargeReadPlatformService, accountAssociationsReadPlatformService,
                accountTransfersWritePlatformService, transactionTemplate);
    }
}
