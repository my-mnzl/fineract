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

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdChargesRequest;
import org.apache.fineract.client.models.PostLoansLoanIdChargesResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Mnzl custom declining balance loan schedule with 30/360 day count convention.
 *
 * Tests verify that: - {@code CustomLoanScheduleGeneratorFactory} routes to the custom generator when
 * MNZL_DECLINING_BALANCE strategy is configured via {@code m_mnzl_loan_product_strategy} -
 * {@code CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator} produces correct 30/360 interest calculations -
 * The custom schedule differs from core Fineract default (actual days) - Late payments with penalty charges work
 * correctly with the custom charge calculator
 */
@Slf4j
public class MnzlDecliningBalanceLoanIntegrationTest extends BaseLoanIntegrationTest {

    private static final String MNZL_STRATEGY_URL = "/fineract-provider/api/v1/mnzl/loan-products/%d/strategies?" + Utils.TENANT_IDENTIFIER;

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
     * Test 2: Late payment with penalty charges.
     *
     * Creates a loan, makes first two payments on time, misses the third, advances the business date past the due date,
     * runs inline COB, adds a penalty charge, and verifies the loan state reflects the overdue installment with penalty
     * charges.
     */
    @Test
    public void testLatePaymentWithPenaltyCharges() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            // Create overdue penalty charge (1% of outstanding amount + interest)
            Integer penaltyChargeId = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanOverdueFeeJSONWithCalculationTypePercentage("1"));

            PostLoanProductsResponse loanProduct = createMnzlDecliningBalanceProduct();
            Long productId = loanProduct.getResourceId();
            setMnzlProductStrategy(productId);

            Long loanId = applyApproveDisburseLoan(clientId, productId, 120000.0, 12.0, 12, "01 January 2026");

            // Get schedule
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> repaymentPeriods = getRepaymentPeriods(loanDetails);

            // Pay period 1 on time (due 01 Feb)
            double period1Due = Utils.getDoubleValue(repaymentPeriods.get(0).getTotalDueForPeriod());
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("01 February 2026").dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "01 February 2026", period1Due);

            // Pay period 2 on time (due 01 Mar)
            double period2Due = Utils.getDoubleValue(repaymentPeriods.get(1).getTotalDueForPeriod());
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("01 March 2026").dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "01 March 2026", period2Due);

            // Skip period 3 (due 01 Apr) — advance business date past due date
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("05 April 2026").dateFormat(DATETIME_PATTERN).locale("en"));

            // Run inline COB to trigger due installment check
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            // Add penalty charge for overdue installment
            PostLoansLoanIdChargesResponse chargeResponse = loanTransactionHelper.addChargesForLoan(loanId,
                    new PostLoansLoanIdChargesRequest().chargeId(penaltyChargeId.longValue()).dateFormat(DATETIME_PATTERN).locale("en"));

            // Verify loan state after late payment + penalty
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());

            // Period 3 should be unpaid
            GetLoansLoanIdRepaymentPeriod period3 = loanDetails.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() == 3).findFirst().orElseThrow();
            assertTrue(Utils.getDoubleValue(period3.getPrincipalOutstanding()) > 0,
                    "Period 3 principal should be outstanding (missed payment)");
            assertTrue(Utils.getDoubleValue(period3.getInterestOutstanding()) > 0, "Period 3 interest should be outstanding");

            // Total outstanding should include the missed installment
            double totalOutstanding = Utils.getDoubleValue(loanDetails.getSummary().getTotalOutstanding());
            assertTrue(totalOutstanding > 0, "Total outstanding should be > 0 after missed payment");

            // Penalty charges should be present
            double penaltyOutstanding = Utils.getDoubleValue(loanDetails.getSummary().getPenaltyChargesOutstanding());
            assertTrue(penaltyOutstanding > 0, "Penalty charges should be applied for overdue installment");

            log.info("Period 3 outstanding principal: {}", Utils.getDoubleValue(period3.getPrincipalOutstanding()));
            log.info("Period 3 outstanding interest: {}", Utils.getDoubleValue(period3.getInterestOutstanding()));
            log.info("Total penalty outstanding: {}", penaltyOutstanding);
            log.info("Total loan outstanding: {}", totalOutstanding);

            // Now make the late payment covering period 3 + penalty
            double period3TotalDue = Utils.getDoubleValue(period3.getTotalDueForPeriod());
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "05 April 2026", period3TotalDue + penaltyOutstanding);

            // Verify period 3 is now paid
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            period3 = loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() == 3)
                    .findFirst().orElseThrow();
            assertEquals(0.0, Utils.getDoubleValue(period3.getPrincipalOutstanding()),
                    "Period 3 principal should be paid after late repayment");
        });
    }

    /**
     * Test 3: Verify MNZL 30/360 schedule differs from core default actual day count.
     *
     * Creates two identical loans — one using MNZL_DECLINING_BALANCE (30/360) and one using core default (actual/365) —
     * and verifies the interest calculations differ.
     */
    @Test
    public void testMnzlScheduleDiffersFromCoreDefault() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            // MNZL product (30/360)
            PostLoanProductsResponse mnzlProduct = createMnzlDecliningBalanceProduct();
            setMnzlProductStrategy(mnzlProduct.getResourceId());

            // Core product (actual/365) — same parameters but no mnzl strategy
            PostLoanProductsResponse coreProduct = createCoreDecliningBalanceProduct();

            Long mnzlLoanId = applyApproveDisburseLoan(clientId, mnzlProduct.getResourceId(), 120000.0, 12.0, 12, "01 January 2026");
            Long coreLoanId = applyApproveDisburseLoan(clientId, coreProduct.getResourceId(), 120000.0, 12.0, 12, "01 January 2026");

            List<GetLoansLoanIdRepaymentPeriod> mnzlPeriods = getRepaymentPeriods(loanTransactionHelper.getLoanDetails(mnzlLoanId));
            List<GetLoansLoanIdRepaymentPeriod> corePeriods = getRepaymentPeriods(loanTransactionHelper.getLoanDetails(coreLoanId));

            double mnzlTotalInterest = mnzlPeriods.stream().mapToDouble(p -> Utils.getDoubleValue(p.getInterestDue())).sum();
            double coreTotalInterest = corePeriods.stream().mapToDouble(p -> Utils.getDoubleValue(p.getInterestDue())).sum();

            log.info("MNZL total interest (30/360): {}", mnzlTotalInterest);
            log.info("Core total interest (actual/365): {}", coreTotalInterest);

            // 30/360 and actual/365 should produce different total interest
            assertTrue(Math.abs(mnzlTotalInterest - coreTotalInterest) > 0.01,
                    "MNZL (30/360) and Core (actual/365) should produce different total interest");

            // MNZL first period interest = 120000 * 12% * 30/360 = 1200
            double mnzlFirstInterest = Utils.getDoubleValue(mnzlPeriods.get(0).getInterestDue());
            assertEquals(1200.0, mnzlFirstInterest, 0.01, "MNZL first period interest = 120000 * 12% * 30/360");
        });
    }

    // ---- Helper methods ----

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
    }

    private PostLoanProductsResponse createMnzlDecliningBalanceProduct() {
        return loanProductHelper.createLoanProduct(buildDecliningBalanceProduct(30, 360));
    }

    private PostLoanProductsResponse createCoreDecliningBalanceProduct() {
        return loanProductHelper.createLoanProduct(buildDecliningBalanceProduct(1, 365));
    }

    private PostLoanProductsRequest buildDecliningBalanceProduct(int daysInMonth, int daysInYear) {
        return new PostLoanProductsRequest().name(Utils.uniqueRandomStringGenerator("MNZL_TEST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4))
                .description("Declining balance test product " + daysInMonth + "/" + daysInYear).currencyCode("USD").digitsAfterDecimal(2)
                .principal(120000.0).minPrincipal(1000.0).maxPrincipal(1000000.0).numberOfRepayments(12).repaymentEvery(1)
                .repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L).interestRatePerPeriod(12.0)
                .interestRateFrequencyType(InterestRateFrequencyType.YEARS).amortizationType(AmortizationType.EQUAL_INSTALLMENTS)
                .interestType(InterestType.DECLINING_BALANCE)
                .interestCalculationPeriodType(InterestCalculationPeriodType.SAME_AS_REPAYMENT_PERIOD).daysInMonthType(daysInMonth)
                .daysInYearType(daysInYear).includeInBorrowerCycle(false).useBorrowerCycle(false).isLinkedToFloatingInterestRates(false)
                .allowVariableInstallments(false).allowPartialPeriodInterestCalcualtion(false).isInterestRecalculationEnabled(false)
                .canDefineInstallmentAmount(false).holdGuaranteeFunds(false).isEqualAmortization(false).canUseForTopup(false)
                .multiDisburseLoan(false).enableDownPayment(false).enableInstallmentLevelDelinquency(false)
                .enableAccrualActivityPosting(false).overdueDaysForNPA(5).accountMovesOutOfNPAOnlyOnArrearsCompletion(false)
                .repaymentStartDateType(1).charges(List.of()).principalVariationsForBorrowerCycle(List.of())
                .interestRateVariationsForBorrowerCycle(List.of()).numberOfRepaymentVariationsForBorrowerCycle(List.of())
                .loanScheduleType(LoanScheduleType.CUMULATIVE.toString()).transactionProcessingStrategyCode("mifos-standard-strategy")
                .accountingRule(3) // accrual periodic
                .fundSourceAccountId(fundSource.getAccountID().longValue())
                .loanPortfolioAccountId(loansReceivableAccount.getAccountID().longValue())
                .transfersInSuspenseAccountId(suspenseAccount.getAccountID().longValue())
                .interestOnLoanAccountId(interestIncomeAccount.getAccountID().longValue())
                .incomeFromFeeAccountId(feeIncomeAccount.getAccountID().longValue())
                .incomeFromPenaltyAccountId(penaltyIncomeAccount.getAccountID().longValue())
                .incomeFromRecoveryAccountId(recoveriesAccount.getAccountID().longValue())
                .writeOffAccountId(writtenOffAccount.getAccountID().longValue())
                .overpaymentLiabilityAccountId(overpaymentAccount.getAccountID().longValue())
                .receivableInterestAccountId(interestReceivableAccount.getAccountID().longValue())
                .receivableFeeAccountId(feeReceivableAccount.getAccountID().longValue())
                .receivablePenaltyAccountId(penaltyReceivableAccount.getAccountID().longValue())
                .goodwillCreditAccountId(goodwillExpenseAccount.getAccountID().longValue())
                .incomeFromGoodwillCreditInterestAccountId(interestIncomeChargeOffAccount.getAccountID().longValue())
                .incomeFromGoodwillCreditFeesAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromGoodwillCreditPenaltyAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffInterestAccountId(interestIncomeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffFeesAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffPenaltyAccountId(penaltyChargeOffAccount.getAccountID().longValue())
                .chargeOffExpenseAccountId(chargeOffExpenseAccount.getAccountID().longValue())
                .chargeOffFraudExpenseAccountId(chargeOffFraudExpenseAccount.getAccountID().longValue()).dateFormat(DATETIME_PATTERN)
                .locale("en");
    }

    private void setMnzlProductStrategy(Long productId) {
        Map<String, String> strategyBody = new HashMap<>();
        strategyBody.put("instrumentCode", "MNZL_STANDARD_LOAN");
        strategyBody.put("scheduleStrategyCode", "MNZL_DECLINING_BALANCE");
        strategyBody.put("chargeStrategyCode", "MNZL_INTEREST_AND_PENALTIES");
        strategyBody.put("cobStrategyCode", "MNZL_DUE_INSTALLMENTS");

        String url = String.format(MNZL_STRATEGY_URL, productId);
        Utils.performServerPut(requestSpec, responseSpec, url, new Gson().toJson(strategyBody));
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
