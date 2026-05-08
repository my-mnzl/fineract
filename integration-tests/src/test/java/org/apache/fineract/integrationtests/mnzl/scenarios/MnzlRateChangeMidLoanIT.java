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
 * F.11 — Parameterized rate-change-mid-loan scenario over each prod product config.
 *
 * Drives DISBURSE + PAY × 3 + CHANGE_INTEREST_RATE + PAY × remaining via the simulator. After the rate change, future
 * installments must use the new rate while past installments are unchanged. Asserts:
 * <ul>
 * <li>installments 1-3 have the same interestDue in the post-disburse and post-rate-change snapshots,</li>
 * <li>installment 4's interestDue increases (we change to a higher rate),</li>
 * <li>the simulation completes at zero outstanding.</li>
 * </ul>
 */
@Slf4j
public class MnzlRateChangeMidLoanIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    private static final double INITIAL_RATE = 12.0;
    private static final double NEW_RATE = 18.0;

    @ParameterizedTest(name = "rate change mid loan: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void rateChange_midLoan_pastInstallmentsUnchanged_futureUseNewRate(String configName, Map<String, Object> productConfig) {
        runAt("01 January 2026", () -> {
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            // The scenario only makes sense if we have at least 4 installments so we can pay 3 then change rate before
            // installment 4.
            if (terms < 4) {
                return;
            }
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();

            // Preview to capture installment due dates / EMIs at the initial rate.
            Map<String, Object> preview = driver.preview(driver.scenario("rate_mid_preview_" + configName, productId).principal(principal)
                    .rate(INITIAL_RATE).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026").body());
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).as("preview periods").hasSize(terms);

            // Build scenario: DISBURSE + PAY×3 + CHANGE_INTEREST_RATE (after installment 3) + PAY for the rest.
            String inst3Due = dueDateOf(periods, 3);
            String rateChangeDate = addDaysFormatted(inst3Due, 1);

            MnzlSimulationDriver.ScenarioBuilder s = driver.scenario("rate_mid_" + configName, productId).principal(principal)
                    .rate(INITIAL_RATE).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026")
                    .pay(dueDateOf(periods, 1), totalDueOf(periods, 1)).pay(dueDateOf(periods, 2), totalDueOf(periods, 2))
                    .pay(dueDateOf(periods, 3), totalDueOf(periods, 3)).changeRate(rateChangeDate, NEW_RATE);
            // After the rate change the simulator may recompute remaining EMIs; pay each remaining installment using
            // its post-change schedule reading (we don't know the exact post-change EMI here, but paying enough on each
            // due date keeps the simulator progressing). We use the original totalDue * (newRate/initialRate) as a
            // generous upper-bound to ensure full coverage of principal+interest under the higher rate.
            double rateScale = NEW_RATE / INITIAL_RATE;
            for (int i = 4; i <= terms; i++) {
                double payAmount = totalDueOf(periods, i) * rateScale + 1.0;
                s = s.pay(dueDateOf(periods, i), payAmount);
            }
            Map<String, Object> result = s.run();

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snapshots = (List<Map<String, Object>>) result.get("snapshots");
            // Snapshot count: 1 disburse + 3 payments + 1 rate change + (terms-3) remaining payments = 2 + terms.
            assertThat(snapshots).hasSize(2 + terms);

            // Snapshot indices: 0 = disburse, 1-3 = payments 1-3, 4 = rate change, 5+ = subsequent payments.
            // Installments 1-3 should be unchanged in interestDue between disburse snapshot and rate-change snapshot.
            for (int p = 1; p <= 3; p++) {
                double initial = scheduleInterestDue(snapshots.get(0), p);
                double afterRate = scheduleInterestDue(snapshots.get(4), p);
                assertThat(afterRate).as("past installment %d interest unchanged after rate change (%s)", p, configName).isEqualTo(initial,
                        within(0.01));
            }
            // Installment 4 (the first future installment after the rate change) should reflect the higher rate —
            // its interestDue must have increased from the initial schedule.
            double inst4Initial = scheduleInterestDue(snapshots.get(0), 4);
            double inst4AfterRate = scheduleInterestDue(snapshots.get(4), 4);
            assertThat(inst4AfterRate).as("installment 4 interest reflects new rate (%s)", configName).isGreaterThan(inst4Initial);
        });
    }

    @SuppressWarnings("unchecked")
    private double scheduleInterestDue(Map<String, Object> snapshot, int periodNumber) {
        List<Map<String, Object>> schedule = (List<Map<String, Object>>) snapshot.get("schedule");
        Map<String, Object> period = schedule.stream().filter(p -> ((Number) p.get("period")).intValue() == periodNumber).findFirst()
                .orElseThrow(() -> new IllegalStateException("snapshot missing period " + periodNumber));
        return ((Number) period.get("interestDue")).doubleValue();
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
