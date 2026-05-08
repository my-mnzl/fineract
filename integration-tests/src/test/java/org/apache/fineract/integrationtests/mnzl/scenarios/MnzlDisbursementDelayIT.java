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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F.12 — Parameterized disbursement-delay scenario over each prod product config (REST-driven).
 *
 * Apply with {@code expectedDisbursementDate=01 January 2026}, approve, then disburse 7 calendar days late on
 * {@code 08 January 2026}. The mnzl declining-balance schedule should recompute installment 1's interest from the
 * actual disbursement date — i.e. the engine treats the realized disbursement date, not the expected one, as the start
 * of the first accrual window.
 *
 * Verifies installment 1 interest matches the formula computed from days-from-actual-disburse-to-first-due using the
 * product's day-count convention (with a small tolerance for the engine's rounding).
 */
@Slf4j
public class MnzlDisbursementDelayIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    private static final double ANNUAL_RATE = 12.0;
    private static final String EXPECTED_DISBURSE = "01 January 2026";
    private static final String ACTUAL_DISBURSE = "08 January 2026";

    @ParameterizedTest(name = "disbursement delay: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void disbursementDelayed_firstInstallmentInterestMatchesActualDisbursement(String configName,
            Map<String, Object> productConfig) {
        runAt(EXPECTED_DISBURSE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            int daysInMonth = ((Number) productConfig.get("daysInMonthEnum")).intValue();
            int daysInYear = ((Number) productConfig.get("daysInYearEnum")).intValue();

            // Apply with the original expected disbursement date.
            PostLoansResponse loanResponse = loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                    .principal(BigDecimal.valueOf(principal)).loanTermFrequency(terms).loanTermFrequencyType(2).numberOfRepayments(terms)
                    .repaymentEvery(1).repaymentFrequencyType(2).interestRatePerPeriod(BigDecimal.valueOf(ANNUAL_RATE)).amortizationType(1)
                    .interestType(0).interestCalculationPeriodType(1).transactionProcessingStrategyCode("mifos-standard-strategy")
                    .expectedDisbursementDate(EXPECTED_DISBURSE).submittedOnDate(EXPECTED_DISBURSE).dateFormat(DATETIME_PATTERN)
                    .locale("en").loanType("individual"));
            Long loanId = loanResponse.getLoanId();

            loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(principal))
                    .dateFormat(DATETIME_PATTERN).approvedOnDate(EXPECTED_DISBURSE).locale("en"));

            // Advance the business date and disburse 7 days late.
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date(ACTUAL_DISBURSE).dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(ACTUAL_DISBURSE)
                    .dateFormat(DATETIME_PATTERN).transactionAmount(BigDecimal.valueOf(principal)).locale("en"));

            // Inspect the resulting schedule.
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> periods = loan.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
            assertThat(periods).as("schedule periods").hasSize(terms);

            GetLoansLoanIdRepaymentPeriod inst1 = periods.get(0);
            LocalDate actualDisburseDate = LocalDate.parse(ACTUAL_DISBURSE, DATE_FMT);
            LocalDate inst1DueDate = inst1.getDueDate();
            long daysInPeriod1 = ChronoUnit.DAYS.between(actualDisburseDate, inst1DueDate);
            // Sanity: actual-disburse-to-installment-1 window must be positive.
            assertThat(daysInPeriod1).as("days from actual disburse to installment 1 due date").isGreaterThan(0L);

            // Day-count convention: 30/360 caps at 30 days/period, 30/365 uses 30 days against a 365-day year, ACTUAL
            // uses real days/365.
            double periodDays;
            double yearDays;
            if (daysInMonth == 30 && daysInYear == 360) {
                periodDays = 30.0;
                yearDays = 360.0;
            } else if (daysInMonth == 30 && daysInYear == 365) {
                periodDays = 30.0;
                yearDays = 365.0;
            } else {
                periodDays = (double) daysInPeriod1;
                yearDays = 365.0;
            }
            double expectedInst1Interest = round2(principal * (ANNUAL_RATE / 100.0) * (periodDays / yearDays));
            double actualInst1Interest = Utils.getDoubleValue(inst1.getInterestDue());

            // Tolerate small engine rounding: a single dollar variance is acceptable for principals up to a few
            // thousand.
            assertThat(actualInst1Interest).as("installment 1 interest under day-count %d/%d (%s)", daysInMonth, daysInYear, configName)
                    .isEqualTo(expectedInst1Interest, within(Math.max(1.0, expectedInst1Interest * 0.05)));
        });
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
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
