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

import java.util.List;
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
 * <p>
 * The mnzl simulator's CHANGE_INTEREST_RATE action dispatches to Fineract's {@code addLoanScheduleVariations} command,
 * which {@code VariableLoanScheduleFromApiJsonValidator} only accepts while a loan is in the
 * SUBMITTED_AND_PENDING_APPROVAL state. The simulator's runner approves the loan as part of bootstrapping (so that
 * subsequent DISBURSE actions can run), which means the loan is no longer in that state by the time any action —
 * including a CHANGE_INTEREST_RATE placed before DISBURSE — executes. Mid-loan rate changes via the simulator therefore
 * consistently land as FAILED runs with {@code account.is.not.submitted.and.pending.state} captured as the error.
 * </p>
 *
 * <p>
 * This test pins that contract: across each prod product config, a DISBURSE → PAY×3 → CHANGE_INTEREST_RATE sequence
 * completes with status {@code FAILED} and the recorded error message names the submitted-pending precondition.
 * Production-path mid-loan rate changes (not exercised here) flow through the {@code /rescheduleloans} REST API; the
 * simulator does not call that path today.
 * </p>
 */
@Slf4j
public class MnzlRateChangeMidLoanIT extends BaseLoanIntegrationTest {

    private static final double NEW_RATE = 18.0;

    @ParameterizedTest(name = "rate change mid loan: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void rateChange_midLoan_simulatorReportsFailedWithSubmittedPendingError(String configName, Map<String, Object> productConfig) {
        runAt("01 January 2026", () -> {
            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            // Mid-loan rate changes only make sense if there are at least 4 installments — pay 3 then change before
            // installment 4. Skip configs with fewer terms (the assertion shape would not apply).
            if (terms < 4) {
                return;
            }
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            double rate = readRate(productConfig);

            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            // Preview to capture installment due dates / EMIs at the initial rate.
            List<Map<String, Object>> preview = driver.preview(driver.scenario("rate_mid_preview_" + configName, productId)
                    .principal(principal).rate(rate).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026").body());
            List<Map<String, Object>> periods = preview;
            assertThat(periods).as("preview periods").hasSize(terms);

            // Build scenario: DISBURSE + PAY×3 + CHANGE_INTEREST_RATE (after installment 3). Subsequent payments are
            // omitted — the rate-change action is expected to fail and end the run.
            String inst3Due = (String) periods.get(2).get("dueDate");
            String rateChangeDate = java.time.LocalDate.parse(inst3Due).plusDays(1).toString();

            Map<String, Object> result = driver.scenario("rate_mid_" + configName, productId).principal(principal).rate(rate)
                    .repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026")
                    .pay((String) periods.get(0).get("dueDate"), totalDueOf(periods, 1))
                    .pay((String) periods.get(1).get("dueDate"), totalDueOf(periods, 2))
                    .pay((String) periods.get(2).get("dueDate"), totalDueOf(periods, 3)).changeRate(rateChangeDate, NEW_RATE).run();

            // The simulator surfaces the addLoanScheduleVariations precondition as a FAILED run with the
            // submitted-pending validation error.
            assertThat(result.get("status")).as("simulator status (%s)", configName).isEqualTo("FAILED");
            String errorMessage = String.valueOf(result.get("errorMessage"));
            assertThat(errorMessage).as("simulator error message names the submitted-pending precondition (%s)", configName)
                    .contains("submitted.and.pending.state");
        });
    }

    private double totalDueOf(List<Map<String, Object>> periods, int periodNumber) {
        for (Map<String, Object> p : periods) {
            if (((Number) p.get("period")).intValue() == periodNumber) {
                return ((Number) p.get("totalDue")).doubleValue();
            }
        }
        throw new IllegalArgumentException("no preview period " + periodNumber);
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
