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
 * F.14 — Balloon loan lifecycle: disburse → regular installments → larger balloon final installment → close.
 *
 * The balloon strategy is currently a placeholder per A.2 design —
 * {@link org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder#balloon30_360()} returns the same shape
 * as the standard declining-balance variant, and the {@code MNZL_BALLOON_LOAN} instrument code does not yet alter
 * schedule generation. Until the balloon-specific schedule shaping is implemented at the product level, this scenario
 * is disabled.
 */
@Slf4j
public class MnzlBalloonLoanLifecycleIT extends BaseLoanIntegrationTest {

    @Test
    @Disabled("Balloon strategy currently produces the same schedule as standard MNZL_DECLINING_BALANCE; behavior pending"
            + " product-level differentiation. The MNZL_BALLOON_LOAN instrument code is wired (verified at L2 in"
            + " MnzlProductStrategyControllerSliceTest) but no balloon-shaped amortization is generated yet.")
    public void balloonLoan_finalInstallmentLargerThanRegular() {
        // Once balloon-shaped amortization lands, the test plan is:
        // 1) build a product with builder.balloon30_360() and pin via MnzlProductStrategyHelper.setMnzlBalloon
        // 2) preview a 12-term schedule via MnzlSimulationDriver
        // 3) assert installment 12's principalDue is materially larger than the average of installments 1-11
        // 4) drive DISBURSE + PAY×11 (regular EMI) + PAY (final balloon) and assert the simulation completes
    }
}
