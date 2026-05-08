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
 * F.4 — Parameterized partial payment scenario.
 *
 * Drives DISBURSE + PAY (50% of installment 1) + PAY (remaining 50% of installment 1) + PAY × (terms-1). Asserts the
 * outstanding tracks correctly across the partials and that the schedule reaches zero outstanding at the final
 * snapshot.
 */
@Slf4j
public class MnzlPartialPaymentParameterizedIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    @ParameterizedTest(name = "partial payment: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void partialPayment_outstandingTracksAcrossPartials(String configName, Map<String, Object> productConfig) {
        runAt("01 January 2026", () -> {
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            double rate = readRate(productConfig);

            List<Map<String, Object>> preview = driver.preview(driver.scenario("partial_preview_" + configName, productId)
                    .principal(principal).rate(rate).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026").body());
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).as("preview periods").hasSize(terms);

            double inst1Total = totalDueOf(periods, 1);
            double half = Math.round(inst1Total * 0.5 * 100.0) / 100.0;
            double remainder = inst1Total - half;

            String inst1Due = dueDateOf(periods, 1);
            String inst1Plus10 = addDaysFormatted(inst1Due, 10);

            MnzlSimulationDriver.ScenarioBuilder s = driver.scenario("partial_" + configName, productId).principal(principal).rate(rate)
                    .repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026")
                    // 50% on installment 1 due date.
                    .pay(inst1Due, half)
                    // remaining 50% ten days later, before installment 2 is due.
                    .pay(inst1Plus10, remainder);
            for (int i = 2; i <= terms; i++) {
                s = s.pay(dueDateOf(periods, i), totalDueOf(periods, i));
            }
            Map<String, Object> result = s.run();

            // Snapshot count: 1 disburse + 1 partial + 1 partial-clear + (terms-1) remaining = 2 + terms.
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snapshots = (List<Map<String, Object>>) result.get("snapshots");
            assertThat(snapshots).hasSize(2 + terms);

            // After the first partial: total outstanding should have dropped by exactly `half` from the disburse
            // snapshot.
            double afterDisburseOutstanding = totalOutstanding(snapshots.get(0));
            double afterFirstPartialOutstanding = totalOutstanding(snapshots.get(1));
            assertThat(afterDisburseOutstanding - afterFirstPartialOutstanding).as("first partial reduces outstanding by half")
                    .isEqualTo(half, within(0.01));

            // After the second partial: should have dropped by the remaining half compared to the first partial.
            double afterSecondPartialOutstanding = totalOutstanding(snapshots.get(2));
            assertThat(afterFirstPartialOutstanding - afterSecondPartialOutstanding).as("second partial clears installment 1")
                    .isEqualTo(remainder, within(0.01));

            // Final snapshot: zero outstanding.
            assertThat(totalOutstanding(snapshots.get(snapshots.size() - 1))).as("final outstanding").isEqualTo(0.0, within(0.01));
        });
    }

    @SuppressWarnings("unchecked")
    private double totalOutstanding(Map<String, Object> snapshot) {
        Map<String, Object> summary = (Map<String, Object>) snapshot.get("summary");
        return ((Number) summary.get("totalOutstanding")).doubleValue();
    }

    /** preview-schedule endpoint returns a JSON array of period maps directly. */
    private List<Map<String, Object>> extractSchedulePeriods(List<Map<String, Object>> preview) {
        return preview;
    }

    private String dueDateOf(List<Map<String, Object>> periods, int periodNumber) {
        for (Map<String, Object> p : periods) {
            if (((Number) p.get("period")).intValue() == periodNumber) {
                return LocalDate.parse((String) p.get("dueDate")).format(DATE_FMT);
            }
        }
        throw new IllegalArgumentException("no preview period " + periodNumber);
    }

    private double totalDueOf(List<Map<String, Object>> periods, int periodNumber) {
        for (Map<String, Object> p : periods) {
            if (((Number) p.get("period")).intValue() == periodNumber) {
                return ((Number) p.get("totalDue")).doubleValue();
            }
        }
        throw new IllegalArgumentException("no preview period " + periodNumber);
    }

    private String addDaysFormatted(String formatted, int days) {
        LocalDate base = LocalDate.parse(formatted, DATE_FMT);
        return base.plusDays(days).format(DATE_FMT);
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
