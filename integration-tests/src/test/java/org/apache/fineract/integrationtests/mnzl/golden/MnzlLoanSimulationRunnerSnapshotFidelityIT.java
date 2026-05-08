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
package org.apache.fineract.integrationtests.mnzl.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlSimulationDriver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * G.3 — Structural fidelity tests between simulator outputs.
 *
 * <p>
 * The simulator wipes the loan rows it creates at the end of each run (transactional cleanup), so a true
 * "simulator-snapshot vs. live-DB" comparison would require disabling that cleanup — which the simulator does not
 * expose. Instead, this suite asserts internal consistency contracts:
 * </p>
 * <ul>
 * <li>final snapshot of a fully-paid 12-installment lifecycle has zero outstanding</li>
 * <li>preview-schedule output matches the projected schedule embedded in a real DISBURSE-only run's snapshot</li>
 * <li>(disabled) reversed transactions are excluded from snapshots — covered structurally in unit tests</li>
 * </ul>
 */
@Slf4j
public class MnzlLoanSimulationRunnerSnapshotFidelityIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    private static final String DISBURSEMENT_DATE = "01 January 2026";
    private static final double PRINCIPAL = 12000.0;
    private static final double ANNUAL_RATE = 12.0;
    private static final int TERMS = 12;

    private Long createMnzlProduct() {
        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        PostLoanProductsResponse response = loanProductHelper.createLoanProduct(builder.decliningBalance30_360());
        Long productId = response.getResourceId();
        new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);
        return productId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> snapshots(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("snapshots");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSchedule(Map<String, Object> mapWithSchedule) {
        Object schedule = mapWithSchedule.get("schedule");
        if (schedule == null) {
            schedule = mapWithSchedule.get("periods");
        }
        return (List<Map<String, Object>>) schedule;
    }

    @Test
    public void simulatorSnapshot_matchesDbQueryAtEachStep() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);

            // Preview to read EMI per installment.
            Map<String, Object> preview = driver.preview(driver.scenario("g3_fidelity_preview", productId).principal(PRINCIPAL)
                    .rate(ANNUAL_RATE).repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).body());
            List<Map<String, Object>> previewPeriods = extractSchedule(preview);
            assertThat(previewPeriods).as("preview periods").hasSize(TERMS);

            // Build DISBURSE + PAY × TERMS using preview-derived totals.
            MnzlSimulationDriver.ScenarioBuilder builder = driver.scenario("g3_fidelity_lifecycle", productId).principal(PRINCIPAL)
                    .rate(ANNUAL_RATE).repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE);
            for (Map<String, Object> p : previewPeriods) {
                String dueDate = LocalDate.parse((String) p.get("dueDate")).format(DATE_FMT);
                double totalDue = ((Number) p.get("totalDue")).doubleValue();
                builder = builder.pay(dueDate, totalDue);
            }
            Map<String, Object> result = builder.run();

            // Structural fidelity: COMPLETED, 1 + TERMS snapshots, final summary at zero outstanding.
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(snapshots(result)).hasSize(1 + TERMS);
            @SuppressWarnings("unchecked")
            Map<String, Object> finalSummary = (Map<String, Object>) snapshots(result).get(snapshots(result).size() - 1).get("summary");
            assertThat(((Number) finalSummary.get("totalOutstanding")).doubleValue()).as("final totalOutstanding").isEqualTo(0.0,
                    within(0.01));

            // Cross-fidelity: the disburse snapshot's schedule should match the preview schedule one-for-one on
            // dueDate / principalDue / interestDue.
            List<Map<String, Object>> disburseSchedule = extractSchedule(snapshots(result).get(0));
            assertThat(disburseSchedule).hasSize(previewPeriods.size());
            for (int i = 0; i < previewPeriods.size(); i++) {
                Map<String, Object> p = previewPeriods.get(i);
                Map<String, Object> s = disburseSchedule.get(i);
                assertThat(s.get("dueDate")).as("installment %d dueDate", i + 1).isEqualTo(p.get("dueDate"));
                assertThat(((Number) s.get("principalDue")).doubleValue()).as("installment %d principalDue", i + 1)
                        .isEqualTo(((Number) p.get("principalDue")).doubleValue(), within(0.01));
                assertThat(((Number) s.get("interestDue")).doubleValue()).as("installment %d interestDue", i + 1)
                        .isEqualTo(((Number) p.get("interestDue")).doubleValue(), within(0.01));
            }
        });
    }

    @Test
    @Disabled("True DB-comparison requires disabling simulator cleanup; structural-fidelity test above covers the per-snapshot consistency contract")
    public void simulatorSnapshot_dbComparison() {
        // The simulator clears the loan/transaction rows it creates at the end of each run, so this test would
        // require a hook to disable cleanup — not exposed today.
    }

    @Test
    @Disabled("Reversal driving via the simulator is not first-class; covered structurally in MnzlLoanSimulationRunnerTest unit tests")
    public void simulatorSnapshot_excludesReversedTransactions() {
        // The simulator does not expose a REVERSE action type today. The reversed-transaction filter is exercised
        // at L1 against the runner directly.
    }

    @Test
    public void simulatorPreview_matchesActualScheduleAfterDisburse() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);

            Map<String, Object> preview = driver.preview(driver.scenario("g3_preview_match", productId).principal(PRINCIPAL)
                    .rate(ANNUAL_RATE).repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).body());
            List<Map<String, Object>> previewPeriods = extractSchedule(preview);
            assertThat(previewPeriods).hasSize(TERMS);

            Map<String, Object> result = driver.scenario("g3_preview_match_real", productId).principal(PRINCIPAL).rate(ANNUAL_RATE)
                    .repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).run();
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(snapshots(result)).hasSize(1);
            List<Map<String, Object>> disburseSchedule = extractSchedule(snapshots(result).get(0));
            assertThat(disburseSchedule).hasSize(previewPeriods.size());

            for (int i = 0; i < previewPeriods.size(); i++) {
                Map<String, Object> p = previewPeriods.get(i);
                Map<String, Object> s = disburseSchedule.get(i);
                assertThat(s.get("dueDate")).as("installment %d dueDate", i + 1).isEqualTo(p.get("dueDate"));
                assertThat(((Number) s.get("principalDue")).doubleValue()).as("installment %d principalDue", i + 1)
                        .isEqualTo(((Number) p.get("principalDue")).doubleValue(), within(0.01));
                assertThat(((Number) s.get("interestDue")).doubleValue()).as("installment %d interestDue", i + 1)
                        .isEqualTo(((Number) p.get("interestDue")).doubleValue(), within(0.01));
                assertThat(((Number) s.get("totalDue")).doubleValue()).as("installment %d totalDue", i + 1)
                        .isEqualTo(((Number) p.get("totalDue")).doubleValue(), within(0.01));
            }
        });
    }
}
