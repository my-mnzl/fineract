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

import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlChargesHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlSimulationDriver;
import org.junit.jupiter.api.Test;

/**
 * F.16 — Periodic charges lifecycle: a periodic monthly fee attached to the product is projected onto every installment
 * at schedule-create time and applied to the loan each period via COB.
 *
 * Drives the lifecycle through the mnzl simulator: build a product with a $50/month periodic fee, pin to the mnzl
 * strategy, preview the schedule and assert each installment carries the fee. Then drive DISBURSE through the simulator
 * and assert the disburse-snapshot's projected schedule reflects the same per-installment fee.
 */
@Slf4j
public class MnzlPeriodicChargesLifecycleIT extends BaseLoanIntegrationTest {

    private static final double ANNUAL_RATE = 12.0;
    private static final double PRINCIPAL = 12000.0;
    private static final int TERMS = 12;
    private static final double FEE_AMOUNT = 50.0;

    @Test
    public void periodicMonthlyFee_projectedAtCreate_appliedEachPeriod() {
        runAt("01 January 2026", () -> {
            // Create a periodic monthly fee using the mnzl charge helper.
            MnzlChargesHelper chargesHelper = new MnzlChargesHelper(requestSpec, responseSpec);
            Long chargeId = chargesHelper.createMnzlPeriodicMonthlyFee(FEE_AMOUNT).longValue();

            // Build a 30/360 mnzl product that includes the periodic fee.
            MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                    feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount,
                    interestReceivableAccount, feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount,
                    interestIncomeChargeOffAccount, feeChargeOffAccount, penaltyChargeOffAccount, chargeOffExpenseAccount,
                    chargeOffFraudExpenseAccount);
            PostLoanProductsRequest productRequest = builder.withCharges(builder.decliningBalance30_360(), chargeId);
            PostLoanProductsResponse productResponse = loanProductHelper.createLoanProduct(productRequest);
            Long productId = productResponse.getResourceId();
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            // Preview the schedule via the mnzl simulator and assert each installment carries the fee.
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            List<Map<String, Object>> preview = driver.preview(driver.scenario("periodic_fee_preview", productId).principal(PRINCIPAL)
                    .rate(ANNUAL_RATE).repayments(TERMS).disburseDate("01 January 2026").disburse("01 January 2026").body());
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).as("preview periods").hasSize(TERMS);
            for (int i = 0; i < periods.size(); i++) {
                double feeDue = ((Number) periods.get(i).get("feeChargesDue")).doubleValue();
                assertThat(feeDue).as("installment %d feeChargesDue", i + 1).isEqualTo(FEE_AMOUNT, within(0.01));
            }

            // Drive DISBURSE and assert the disburse snapshot's projected schedule still reflects the per-installment
            // fee.
            Map<String, Object> result = driver.scenario("periodic_fee_disburse", productId).principal(PRINCIPAL).rate(ANNUAL_RATE)
                    .repayments(TERMS).disburseDate("01 January 2026").disburse("01 January 2026").run();
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snapshots = (List<Map<String, Object>>) result.get("snapshots");
            assertThat(snapshots).as("snapshots").hasSize(1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snapSchedule = (List<Map<String, Object>>) snapshots.get(0).get("schedule");
            assertThat(snapSchedule).as("disburse-snapshot schedule").hasSize(TERMS);
            for (int i = 0; i < snapSchedule.size(); i++) {
                double feeDue = ((Number) snapSchedule.get(i).get("feeChargesDue")).doubleValue();
                assertThat(feeDue).as("disburse snapshot installment %d feeChargesDue", i + 1).isEqualTo(FEE_AMOUNT, within(0.01));
            }
        });
    }

    /** preview-schedule endpoint returns a JSON array of period maps directly. */
    private List<Map<String, Object>> extractSchedulePeriods(List<Map<String, Object>> preview) {
        return preview;
    }
}
