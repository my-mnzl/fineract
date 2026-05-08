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
 * F.15 — Floating-rate loan: rate-sheet changes flow through {@code MnzlLoanTermVariationsEnricher} so future
 * installments use the new floating rate while past installments are unchanged.
 *
 * Setting up a floating-rate product end-to-end requires creating an {@code m_floating_rates} parent row plus one or
 * more {@code m_floating_rates_periods} rows and binding them to a loan product with
 * {@code isLinkedToFloatingInterestRates=true}. There is no {@code FloatingRateAdminHelper} in the integration-test
 * helpers yet, and the prod {@code /v1/floatingrates} REST surface needs non-trivial scaffolding for the test office +
 * permissions. Building that helper is out of scope for the F-series scenarios — it will land in a follow-up.
 *
 * The enricher logic itself is covered at L2 in {@code MnzlLoanTermVariationsEnricherTest}, and the recalculation flow
 * for a deployed floating-rate loan with rate changes is covered in regression by
 * {@code OutstandingFloatingRateRecalcAllChangesIT}.
 */
@Slf4j
public class MnzlFloatingRateLoanIT extends BaseLoanIntegrationTest {

    @Test
    @Disabled("Requires FloatingRateAdminHelper to seed m_floating_rates fixtures; deferred to follow-up."
            + " L2 unit covers the enricher logic in MnzlLoanTermVariationsEnricherTest;"
            + " L3 regression covers floating-rate recalculation in OutstandingFloatingRateRecalcAllChangesIT.")
    public void floatingRateLoan_rateChangesAppliedToFutureInstallments() {
        // Once FloatingRateAdminHelper exists, the test plan is:
        // 1) POST /floatingrates with an initial period rate (12%) effective at submission date
        // 2) build a product with isLinkedToFloatingInterestRates=true, floatingRatesId=<new>,
        // interestRateDifferential=2.0
        // 3) apply/approve/disburse a loan with isFloatingInterestRate=true, interestRateDifferential=2.0
        // 4) capture the schedule pre-rate-change
        // 5) POST /floatingrates/{id}?command=changeRate with a higher period rate effective on installment 4's accrual
        // 6) assert installments 1-3 are unchanged; installments 4+ reflect the new floating rate + differential
    }
}
