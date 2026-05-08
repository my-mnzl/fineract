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

import static org.apache.fineract.integrationtests.mnzl.helpers.MnzlAssertions.assertSimulationCompleted;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F.1 — Parameterized happy path scenario over each prod product config.
 *
 * For each config: build a matching mnzl declining-balance product, pin it to the mnzl strategy, then drive DISBURSE +
 * PAY × N through the simulator using EMIs taken from a preview call. Asserts the simulation reaches COMPLETED,
 * produces 1 + N snapshots, and lands at zero outstanding.
 */
@Slf4j
public class MnzlHappyPathParameterizedIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    @ParameterizedTest(name = "happy path: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void happyPath_onTimePayments_closesAtZero(String configName, Map<String, Object> productConfig) {
        runAt("01 January 2026", () -> {
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            double rate = readRate(productConfig);

            // Preview the schedule to read EMI amounts and due dates.
            MnzlSimulationDriver.ScenarioBuilder previewScenario = driver.scenario("happy_preview_" + configName, productId)
                    .principal(principal).rate(rate).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026");
            List<Map<String, Object>> preview = driver.preview(previewScenario.body());
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).as("preview must have %d periods", terms).hasSize(terms);

            // Build the real scenario: DISBURSE + PAY × terms.
            MnzlSimulationDriver.ScenarioBuilder s = driver.scenario("happy_" + configName, productId).principal(principal).rate(rate)
                    .repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026");
            for (Map<String, Object> p : periods) {
                String dueDate = formatIsoToDateString((String) p.get("dueDate"));
                double totalDue = ((Number) p.get("totalDue")).doubleValue();
                s = s.pay(dueDate, totalDue);
            }
            Map<String, Object> result = s.run();

            assertSimulationCompleted(result, 1 + terms);
            assertFinalSnapshotZeroOutstanding(result);
        });
    }

    /** preview-schedule endpoint returns a JSON array of period maps directly. */
    private List<Map<String, Object>> extractSchedulePeriods(List<Map<String, Object>> preview) {
        return preview;
    }

    @SuppressWarnings("unchecked")
    private void assertFinalSnapshotZeroOutstanding(Map<String, Object> result) {
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) result.get("snapshots");
        Map<String, Object> last = snapshots.get(snapshots.size() - 1);
        Map<String, Object> summary = (Map<String, Object>) last.get("summary");
        assertThat(((Number) summary.get("totalOutstanding")).doubleValue()).as("total outstanding at final snapshot").isEqualTo(0.0,
                within(0.01));
    }

    private String formatIsoToDateString(String iso) {
        return LocalDate.parse(iso).format(DATE_FMT);
    }

    private double readRate(Map<String, Object> productConfig) {
        Object raw = productConfig.get("annualNominalInterestRate");
        if (raw == null) {
            throw new IllegalStateException("Prod-config fixture missing 'annualNominalInterestRate' for " + productConfig.get("configName")
                    + " — re-run :integration-tests:refreshMnzlProdConfigs");
        }
        return ((Number) raw).doubleValue();
    }

    private Long createProductFromConfig(Map<String, Object> productConfig) {
        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        int daysInMonth = ((Number) productConfig.get("daysInMonthEnum")).intValue();
        int daysInYear = ((Number) productConfig.get("daysInYearEnum")).intValue();
        PostLoanProductsResponse response;
        if (daysInMonth == 30 && daysInYear == 360) {
            response = loanProductHelper.createLoanProduct(builder.decliningBalance30_360());
        } else if (daysInMonth == 30 && daysInYear == 365) {
            response = loanProductHelper.createLoanProduct(builder.decliningBalance30_365());
        } else {
            response = loanProductHelper.createLoanProduct(builder.decliningBalanceActual());
        }
        return response.getResourceId();
    }
}
