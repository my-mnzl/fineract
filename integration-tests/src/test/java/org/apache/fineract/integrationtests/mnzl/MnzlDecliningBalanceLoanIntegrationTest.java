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
package org.apache.fineract.integrationtests.mnzl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Mnzl custom declining balance loan schedule with 30/360 day count convention.
 *
 * Tests verify that: - {@code CustomLoanScheduleGeneratorFactory} routes to the custom generator when
 * MNZL_DECLINING_BALANCE strategy is configured via {@code m_mnzl_loan_product_strategy} -
 * {@code CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator} produces correct 30/360 interest calculations -
 * The custom schedule remains stable through the normal repayment lifecycle.
 */
public class MnzlDecliningBalanceLoanIntegrationTest extends BaseLoanIntegrationTest {

    /**
     * Test 1: Standard declining balance loan with 30/360 — full happy path.
     *
     * Creates a loan product with MNZL_DECLINING_BALANCE schedule strategy, applies for a 12-month declining balance
     * loan at 12% annual rate, disburses it, validates the schedule uses 30/360 day count, makes all repayments on
     * time, and verifies the loan closes with zero balance.
     */
    @Test
    public void testStandardDecliningBalance30_360HappyPath() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            PostLoanProductsResponse loanProduct = createMnzlDecliningBalanceProduct();
            Long productId = loanProduct.getResourceId();
            setMnzlProductStrategy(productId);

            Long loanId = applyApproveDisburseLoan(clientId, productId, 120000.0, 12.0, 12, "01 January 2026");

            // Validate schedule structure
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());

            List<GetLoansLoanIdRepaymentPeriod> repaymentPeriods = getRepaymentPeriods(loanDetails);
            assertEquals(12, repaymentPeriods.size(), "Should have 12 repayment periods");

            // Total principal across all periods equals disbursed amount
            double totalPrincipal = repaymentPeriods.stream().mapToDouble(p -> Utils.getDoubleValue(p.getPrincipalDue())).sum();
            assertEquals(120000.0, totalPrincipal, 0.01, "Total principal should equal disbursed amount");

            // Interest is declining (declining balance)
            double firstPeriodInterest = Utils.getDoubleValue(repaymentPeriods.get(0).getInterestDue());
            double lastPeriodInterest = Utils.getDoubleValue(repaymentPeriods.get(11).getInterestDue());
            assertTrue(firstPeriodInterest > 0, "First period should have interest");
            assertTrue(firstPeriodInterest > lastPeriodInterest, "Interest should decline over time in declining balance");

            // 30/360: first period interest = 120000 * (12/100) * (30/360) = 1200.00
            assertEquals(1200.0, firstPeriodInterest, 0.01, "First period interest should be 120000 * 12% * 30/360 = 1200");

            // Due dates are monthly
            assertEquals(LocalDate.of(2026, 2, 1), repaymentPeriods.get(0).getDueDate());
            assertEquals(LocalDate.of(2027, 1, 1), repaymentPeriods.get(11).getDueDate());

            // Make all repayments on time
            for (GetLoansLoanIdRepaymentPeriod period : repaymentPeriods) {
                double totalDue = Utils.getDoubleValue(period.getTotalDueForPeriod());
                String dueDate = period.getDueDate().format(dateTimeFormatter);
                businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                        .date(dueDate).dateFormat(DATETIME_PATTERN).locale("en"));
                loanTransactionHelper.makeLoanRepayment(loanId, "repayment", dueDate, totalDue);
            }

            // Verify loan is closed
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getClosedObligationsMet());
            assertEquals(0.0, Utils.getDoubleValue(loanDetails.getSummary().getTotalOutstanding()),
                    "Total outstanding should be zero after full repayment");
        });
    }

    /**
     * Test 3: Verify MNZL 30/360 interest formula across all repayment periods.
     *
     * Each period's interest must equal outstanding_principal * 12% * 30/360 (i.e., 1% monthly rate). This validates
     * the custom schedule generator uses 30/360 day counting for every period, not just the first.
     */
    @Test
    public void testMnzlScheduleFollows30_360FormulaAcrossAllPeriods() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            PostLoanProductsResponse mnzlProduct = createMnzlDecliningBalanceProduct();
            setMnzlProductStrategy(mnzlProduct.getResourceId());

            Long loanId = applyApproveDisburseLoan(clientId, mnzlProduct.getResourceId(), 120000.0, 12.0, 12, "01 January 2026");

            List<GetLoansLoanIdRepaymentPeriod> periods = getRepaymentPeriods(loanTransactionHelper.getLoanDetails(loanId));
            assertEquals(12, periods.size(), "Should have 12 repayment periods");

            // 30/360 monthly rate = 12% * 30/360 = 1%
            double monthlyRate = 0.01;
            double outstandingPrincipal = 120000.0;

            for (int i = 0; i < periods.size(); i++) {
                GetLoansLoanIdRepaymentPeriod period = periods.get(i);
                double expectedInterest = Math.round(outstandingPrincipal * monthlyRate * 100.0) / 100.0;
                double actualInterest = Utils.getDoubleValue(period.getInterestDue());

                assertEquals(expectedInterest, actualInterest, 0.01,
                        "Period " + (i + 1) + " interest should match 30/360 formula: " + outstandingPrincipal + " * 1%");

                // Reduce outstanding by principal paid this period
                outstandingPrincipal -= Utils.getDoubleValue(period.getPrincipalDue());
            }

            // Outstanding should be zero after all periods
            assertEquals(0.0, outstandingPrincipal, 0.01, "All principal should be allocated across 12 periods");
        });
    }

    // ---- Helper methods ----

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
    }

    private PostLoanProductsResponse createMnzlDecliningBalanceProduct() {
        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        return loanProductHelper.createLoanProduct(builder.decliningBalance30_360());
    }

    private void setMnzlProductStrategy(Long productId) {
        new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);
    }

    private PostLoansResponse applyForMnzlLoan(Long clientId, Long productId, double principal, double annualRate, int numberOfRepayments,
            String submittedDate) {
        return loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                .principal(BigDecimal.valueOf(principal)).loanTermFrequency(numberOfRepayments).loanTermFrequencyType(2) // MONTHS
                .numberOfRepayments(numberOfRepayments).repaymentEvery(1).repaymentFrequencyType(2) // MONTHS
                .interestRatePerPeriod(BigDecimal.valueOf(annualRate)).amortizationType(1) // EQUAL_INSTALLMENTS
                .interestType(0) // DECLINING_BALANCE
                .interestCalculationPeriodType(1) // SAME_AS_REPAYMENT_PERIOD
                .transactionProcessingStrategyCode("mifos-standard-strategy").expectedDisbursementDate(submittedDate)
                .submittedOnDate(submittedDate).dateFormat(DATETIME_PATTERN).locale("en").loanType("individual"));
    }

    private Long applyApproveDisburseLoan(Long clientId, Long productId, double principal, double annualRate, int numberOfRepayments,
            String date) {
        PostLoansResponse loanResponse = applyForMnzlLoan(clientId, productId, principal, annualRate, numberOfRepayments, date);
        Long loanId = loanResponse.getLoanId();

        loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(principal))
                .dateFormat(DATETIME_PATTERN).approvedOnDate(date).locale("en"));

        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(date).dateFormat(DATETIME_PATTERN)
                .transactionAmount(BigDecimal.valueOf(principal)).locale("en"));

        return loanId;
    }
}
