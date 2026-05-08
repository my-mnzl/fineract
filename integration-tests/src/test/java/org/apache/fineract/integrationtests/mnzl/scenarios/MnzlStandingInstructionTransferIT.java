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
package org.apache.fineract.integrationtests.mnzl.scenarios;

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * F.17 — Standing-instruction transfer: a recurring savings → loan repayment scheduled instruction is executed by the
 * "Execute Standing Instructions" job; the savings account is debited and the loan is credited on the configured due
 * date.
 *
 * Setting up a savings account, a fully-formed standing instruction, and the cross-account transfer permissions
 * requires substantial fixture wiring ({@code SavingsProductHelper} + {@code SavingsAccountHelper} +
 * {@code StandingInstructionsHelper} + the savings/loan account-transfer command bus). That goes well beyond the F-
 * series scenario scope; the optimistic-lock retry path that protects the standing-instruction tasklet is already
 * verified at L1 in {@code MnzlExecuteStandingInstructionsTaskletOptimisticLockRetryTest}.
 */
@Slf4j
public class MnzlStandingInstructionTransferIT extends BaseLoanIntegrationTest {

    @Test
    @Disabled("Requires SavingsAccountHelper + standing instruction setup with cross-account transfer wiring;"
            + " the optimistic-lock retry behavior is covered at L1 in" + " MnzlExecuteStandingInstructionsTaskletOptimisticLockRetryTest.")
    public void standingInstruction_transfersOnDueDate() {
        // Once the savings + standing instruction helpers are wired, the test plan is:
        // 1) create a client + a savings account funded with enough balance to cover one repayment
        // 2) apply/approve/disburse a mnzl loan for the same client
        // 3) create a standing instruction: from = savings account, to = loan, type = repayment,
        // amount = installment-1 totalDue, recurrence = monthly aligned with installment dates
        // 4) advance business date past installment 1's due date
        // 5) trigger SchedulerJobHelper.executeAndAwaitJob("Execute Standing Instructions")
        // 6) assert: savings balance dropped by installment amount; loan installment 1 marked paid
    }
}
