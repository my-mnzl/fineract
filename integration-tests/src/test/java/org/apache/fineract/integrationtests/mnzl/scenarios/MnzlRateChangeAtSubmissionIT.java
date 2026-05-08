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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.client.models.PutLoansLoanIdRequest;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F.10 — Parameterized rate-change-at-submission scenario over each prod product config (REST-driven).
 *
 * Apply the loan at a baseline rate (12% per annum). Before approval, modify the loan application via {@code PUT
 * /loans/{loanId}?command=modify} to set a new rate (18%). Approve and disburse. Asserts the resulting schedule's
 * installment 1 interest matches the new-rate formula (principal × newRate × periodRatio), confirming the modification
 * took effect rather than the original rate.
 */
@Slf4j
public class MnzlRateChangeAtSubmissionIT extends BaseLoanIntegrationTest {

    private static final double INITIAL_RATE = 12.0;
    private static final double NEW_RATE = 18.0;
    private static final String SUBMITTED_DATE = "01 January 2026";

    @ParameterizedTest(name = "rate change at submission: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void rateChange_beforeApproval_scheduleUsesNewRate(String configName, Map<String, Object> productConfig) {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();

            // 1) Apply at the initial rate.
            PostLoansResponse loanResponse = loanTransactionHelper
                    .applyLoan(buildApplyRequest(clientId, productId, principal, INITIAL_RATE, terms, SUBMITTED_DATE));
            Long loanId = loanResponse.getLoanId();

            // 2) Modify the application to the new rate before approval.
            loanTransactionHelper.modifyApplicationForLoan(loanId, "modify",
                    new PutLoansLoanIdRequest().clientId(clientId).productId(productId).principal((long) principal).loanTermFrequency(terms)
                            .loanTermFrequencyType(2).numberOfRepayments(terms).repaymentEvery(1).repaymentFrequencyType(2)
                            .interestRatePerPeriod(BigDecimal.valueOf(NEW_RATE)).amortizationType(1).interestType(0)
                            .interestCalculationPeriodType(1).transactionProcessingStrategyCode("mifos-standard-strategy")
                            .expectedDisbursementDate(SUBMITTED_DATE).submittedOnDate(SUBMITTED_DATE).dateFormat(DATETIME_PATTERN)
                            .locale("en").loanType("individual"));

            // 3) Approve and disburse.
            loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(principal))
                    .dateFormat(DATETIME_PATTERN).approvedOnDate(SUBMITTED_DATE).locale("en"));
            loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(SUBMITTED_DATE)
                    .dateFormat(DATETIME_PATTERN).transactionAmount(BigDecimal.valueOf(principal)).locale("en"));

            // 4) Assert installment 1 interest reflects the new rate, not the initial one.
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> periods = loan.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
            assertThat(periods).as("schedule periods").hasSize(terms);

            int daysInMonth = ((Number) productConfig.get("daysInMonthEnum")).intValue();
            int daysInYear = ((Number) productConfig.get("daysInYearEnum")).intValue();
            // For 30/360 the per-month ratio is exactly 1/12; for 30/365 it's 30/365; for actual the simulator-style
            // ratio equals daysInPeriod/365 — installment 1 normally spans ~30 days, so 30/365 is a close
            // approximation.
            double periodRatio;
            if (daysInMonth == 30 && daysInYear == 360) {
                periodRatio = 1.0 / 12.0;
            } else {
                periodRatio = 30.0 / (double) daysInYear;
            }
            double expectedInst1AtNewRate = round2(principal * (NEW_RATE / 100.0) * periodRatio);
            double expectedInst1AtOldRate = round2(principal * (INITIAL_RATE / 100.0) * periodRatio);
            double actualInst1Interest = Utils.getDoubleValue(periods.get(0).getInterestDue());

            // Tolerate a few cents of rounding in the engine's per-period accrual.
            assertThat(actualInst1Interest).as("installment 1 interest reflects new rate (%s)", configName)
                    .isEqualTo(expectedInst1AtNewRate, within(0.5));
            assertThat(actualInst1Interest).as("installment 1 interest is NOT the old-rate value").isNotEqualTo(expectedInst1AtOldRate);
        });
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private PostLoansRequest buildApplyRequest(Long clientId, Long productId, double principal, double annualRate, int numberOfRepayments,
            String date) {
        return new PostLoansRequest().clientId(clientId).productId(productId).principal(BigDecimal.valueOf(principal))
                .loanTermFrequency(numberOfRepayments).loanTermFrequencyType(2).numberOfRepayments(numberOfRepayments).repaymentEvery(1)
                .repaymentFrequencyType(2).interestRatePerPeriod(BigDecimal.valueOf(annualRate)).amortizationType(1).interestType(0)
                .interestCalculationPeriodType(1).transactionProcessingStrategyCode("mifos-standard-strategy")
                .expectedDisbursementDate(date).submittedOnDate(date).dateFormat(DATETIME_PATTERN).locale("en").loanType("individual");
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
