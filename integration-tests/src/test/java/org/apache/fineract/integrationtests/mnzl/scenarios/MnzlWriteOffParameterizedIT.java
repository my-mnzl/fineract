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
 * F.7 — Parameterized write-off scenario over each prod product config.
 *
 * Drives DISBURSE + PAY × 2 + WRITE_OFF via the simulator. After the WRITE_OFF action the loan is closed by writing off
 * the remaining balance — outstanding goes to zero via the writeoff transaction (not via repayment). Asserts the
 * simulation completes with the expected snapshot count and the final snapshot reports zero outstanding.
 */
@Slf4j
public class MnzlWriteOffParameterizedIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    @ParameterizedTest(name = "write off: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void writeOff_afterTwoPayments_closesViaWriteoff(String configName, Map<String, Object> productConfig) {
        runAt("01 January 2026", () -> {
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            double rate = readRate(productConfig);

            // Preview the schedule to read installment due dates and EMIs.
            Map<String, Object> preview = driver.preview(driver.scenario("writeoff_preview_" + configName, productId).principal(principal)
                    .rate(rate).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026").body());
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).as("preview periods").hasSize(terms);

            String inst1Due = dueDateOf(periods, 1);
            String inst2Due = dueDateOf(periods, 2);
            // Write off shortly after installment 2: gives the engine a non-zero outstanding to write off.
            String writeOffDate = addDaysFormatted(inst2Due, 5);

            // Build scenario: DISBURSE + PAY×2 + WRITE_OFF.
            Map<String, Object> result = driver.scenario("writeoff_" + configName, productId).principal(principal).rate(rate)
                    .repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026").pay(inst1Due, totalDueOf(periods, 1))
                    .pay(inst2Due, totalDueOf(periods, 2)).writeOff(writeOffDate).run();

            // Snapshot count: 1 disburse + 2 payments + 1 writeoff = 4.
            assertSimulationCompleted(result, 4);

            // Final snapshot must report zero total outstanding (writeoff closed the balance).
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snapshots = (List<Map<String, Object>>) result.get("snapshots");
            Map<String, Object> finalSnap = snapshots.get(snapshots.size() - 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) finalSnap.get("summary");
            assertThat(((Number) summary.get("totalOutstanding")).doubleValue()).as("total outstanding after writeoff").isEqualTo(0.0,
                    within(0.01));

            // Sanity: outstanding right before writeoff (snapshot index 2 = after 2 payments) was non-zero.
            @SuppressWarnings("unchecked")
            Map<String, Object> beforeWriteOffSummary = (Map<String, Object>) snapshots.get(2).get("summary");
            assertThat(((Number) beforeWriteOffSummary.get("totalOutstanding")).doubleValue()).as("outstanding before writeoff")
                    .isGreaterThan(0.0);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSchedulePeriods(Map<String, Object> preview) {
        Object schedule = preview.get("schedule");
        if (schedule == null) {
            schedule = preview.get("periods");
        }
        return (List<Map<String, Object>>) schedule;
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
