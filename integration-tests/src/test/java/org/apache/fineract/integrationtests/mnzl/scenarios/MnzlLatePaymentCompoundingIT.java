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
 * F.3 — Parameterized late + compounding scenario.
 *
 * Drives DISBURSE + PAY×1 + SKIP×2 + RUN_COB + PAY (catchup) + PAY (remaining) via the simulator. Two consecutive
 * skipped installments mean the COB run posts penalties on both, which under MNZL_INTEREST_AND_PENALTIES compounds
 * (penalties become part of the base for subsequent periodic charges). Asserts the simulation completes; the cleanup
 * payment after COB clears both arrears installments.
 */
@Slf4j
public class MnzlLatePaymentCompoundingIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    @ParameterizedTest(name = "late+compounding: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void latePayment_twoInstallmentsLate_penaltyCompounds(String configName, Map<String, Object> productConfig) {
        runAt("01 January 2026", () -> {
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            double rate = readRate(productConfig);

            Map<String, Object> preview = driver.preview(driver.scenario("compound_preview_" + configName, productId).principal(principal)
                    .rate(rate).repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026").body());
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).as("preview periods").hasSize(terms);

            MnzlSimulationDriver.ScenarioBuilder s = driver.scenario("compound_" + configName, productId).principal(principal).rate(rate)
                    .repayments(terms).disburseDate("01 January 2026").disburse("01 January 2026");
            // Pay installment 1 on time.
            s = s.pay(dueDateOf(periods, 1), totalDueOf(periods, 1));
            // Skip installments 2 and 3.
            s = s.skip(dueDateOf(periods, 2));
            s = s.skip(dueDateOf(periods, 3));
            // Run COB a few days after installment 3's due date — both 2 and 3 are now overdue.
            String cobDate = addDaysFormatted(dueDateOf(periods, 3), 5);
            s = s.runCob(cobDate);
            // Pay catchup covering installment 2 + 3 due (the simulator will allocate via the strategy).
            String catchupDate = addDaysFormatted(dueDateOf(periods, 3), 7);
            double catchupAmount = totalDueOf(periods, 2) + totalDueOf(periods, 3);
            s = s.pay(catchupDate, catchupAmount);
            // Finish remaining installments on their due dates.
            for (int i = 4; i <= terms; i++) {
                s = s.pay(dueDateOf(periods, i), totalDueOf(periods, i));
            }
            Map<String, Object> result = s.run();

            // Snapshot count: 1 disburse + 1 on-time + 2 skip + 1 cob + 1 catchup + (terms-3) remaining = 2 + terms.
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            @SuppressWarnings("unchecked")
            List<Object> snaps = (List<Object>) result.get("snapshots");
            assertThat(snaps).as("snapshot count").hasSize(2 + terms);
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
