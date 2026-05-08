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
package org.apache.fineract.integrationtests.mnzl.regression;

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Regression test pinning behavior locked by commit ce543acb0 (Recalculate all outstanding floating interest rate
 * changes).
 *
 * The original bug:
 * <ul>
 * <li>{@code fetchLoansForInterestRecalculation()} only matched floating-rate loans whose rate period had been created
 * on the previous calendar day, and was reading from an unused column. Loans that had multiple historical rate changes
 * ahead of them would only get the most recent change picked up.</li>
 * <li>{@code constructFloatingRateDTO()} only considered loans explicitly flagged as floating, missing some production
 * loans configured at the product level.</li>
 * </ul>
 *
 * After the fix, every loan linked to a floating rate that has new periods since its last recalculation gets fully
 * recalculated against all outstanding rate changes.
 *
 * Pinning this end-to-end requires:
 * <ol>
 * <li>Creating a loan product with {@code isLinkedToFloatingInterestRates=true} and a base-rate / differential
 * configuration — the {@code MnzlProductBuilder} hard-codes {@code isLinkedToFloatingInterestRates(false)} and the
 * standard product builder in {@code BaseLoanIntegrationTest} doesn't expose floating-rate setup either.</li>
 * <li>Seeding three rows in {@code m_floating_rates_periods} via direct JDBC — the public API for managing floating
 * rate periods is admin-only and not surfaced through any helper in the integration-tests module.</li>
 * <li>Running the {@code Recalculate Interest For Loans} scheduler job and inspecting the resulting schedule for three
 * distinct rate inflections.</li>
 * </ol>
 *
 * The L2 unit-level coverage in {@code MnzlLoanTermVariationsEnricherTest} pins the rate-walk logic deterministically.
 * We keep this L3 class as a compile-clean placeholder; when L3 wants to actually run this, the right move is to add a
 * small {@code FloatingRateAdminHelper} alongside the existing helpers and seed periods through a SQL fixture.
 */
@Slf4j
public class OutstandingFloatingRateRecalcAllChangesIT extends BaseLoanIntegrationTest {

    /**
     * Floating-rate loan with multiple historical rate changes must reflect every change after the recalculation job
     * runs. Disabled because the test requires (a) a floating-rate product and (b) seeded
     * {@code m_floating_rates_periods} rows, neither of which is supported by the existing integration-tests helpers.
     * Behavior is unit-tested in {@code MnzlLoanTermVariationsEnricherTest}.
     */
    @Test
    @Disabled("Requires floating-rate product setup and direct DB seeding of m_floating_rates_periods; covered at L2 by MnzlLoanTermVariationsEnricherTest")
    public void floatingLoanWithMultipleRateChanges_recalculatesAllOnRecalcTrigger() {
        // Intentionally empty. See class-level Javadoc — to enable, add a FloatingRateAdminHelper that exposes
        // POST /v1/floatingrates and PUT /v1/floatingrates/{id}/floatingrateperiods, then seed three periods,
        // create a floating-rate product, advance the business date past all three period-start dates, and run
        // the "Recalculate Interest For Loans" job.
    }
}
