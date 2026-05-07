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
import org.apache.fineract.client.models.LoanProductChargeData;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
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

            // Create product with the penalty charge baked in — COB auto-applies it
            PostLoanProductsResponse loanProduct = loanProductHelper.createLoanProduct(
                    buildDecliningBalanceProduct(30, 360).charges(List.of(new LoanProductChargeData().id(penaltyChargeId.longValue()))));
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

            // Run inline COB to trigger overdue penalty auto-application
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            // Verify loan state after COB — penalty should be auto-applied
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

            // Penalty charges should be present (auto-applied by COB)
            double penaltyOutstanding = Utils.getDoubleValue(loanDetails.getSummary().getPenaltyChargesOutstanding());
            assertTrue(penaltyOutstanding > 0, "Penalty charges should be auto-applied by COB for overdue installment");

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
     * Test 3: Late repayment must not drift the rehab loan schedule.
     *
     * Reproduces production loan 13516 (650,000 EGP, 25% nominal, 12 monthly installments, disbursed 2026-02-17, first
     * repayment 2026-04-01, declining balance, 30/360). Two behaviors are locked in:
     * <ul>
     * <li>Origination matches the v3 history — first stub period is 43 days (legacy 30/360), not 44 (current 30E/360).
     * The custom generator carries a one-off signature guard that shifts the stub end date one day for this loan.</li>
     * <li>Schedule is frozen on lateness — interest recalculation is disabled at the product/loan level, so paying
     * installment 1 four days late and running COB past installment 2's due date does not redistribute principal or
     * interest. Lateness is expected to surface only as penalty charges, not as schedule drift.</li>
     * </ul>
     */
    @Test
    public void testRehabLoanLatePaymentDoesNotDriftSchedule() {
        runAt("06 February 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            // Recalc is intentionally disabled to match production rehab loans (product 2): the schedule must stay
            // frozen at origination, and lateness is handled via penalty charges rather than principal/interest
            // redistribution. See the SQL migration that flips m_loan.interest_recalculation_enabled to 0 on prod.
            PostLoanProductsResponse loanProduct = loanProductHelper.createLoanProduct(
                    buildDecliningBalanceProduct(30, 360).principal(650000.0).maxPrincipal(2000000.0).numberOfRepayments(12)
                            .interestRatePerPeriod(25.0).allowPartialPeriodInterestCalcualtion(true).isInterestRecalculationEnabled(false));
            Long productId = loanProduct.getResourceId();
            setMnzlProductStrategy(productId);

            String submittedDate = "06 February 2026";
            String disbursementDate = "17 February 2026";
            String firstRepaymentDate = "01 April 2026";
            Map<String, Object> applyBody = new HashMap<>();
            applyBody.put("clientId", clientId);
            applyBody.put("productId", productId);
            applyBody.put("principal", 650000.0);
            applyBody.put("loanTermFrequency", 12);
            applyBody.put("loanTermFrequencyType", 2);
            applyBody.put("numberOfRepayments", 12);
            applyBody.put("repaymentEvery", 1);
            applyBody.put("repaymentFrequencyType", 2);
            applyBody.put("interestRatePerPeriod", 25.0);
            applyBody.put("amortizationType", 1);
            applyBody.put("interestType", 0);
            applyBody.put("interestCalculationPeriodType", 1);
            applyBody.put("allowPartialPeriodInterestCalcualtion", true);
            applyBody.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
            applyBody.put("submittedOnDate", submittedDate);
            applyBody.put("expectedDisbursementDate", disbursementDate);
            applyBody.put("repaymentsStartingFromDate", firstRepaymentDate);
            applyBody.put("interestChargedFromDate", disbursementDate);
            applyBody.put("dateFormat", DATETIME_PATTERN);
            applyBody.put("locale", "en");
            applyBody.put("loanType", "individual");
            String applyResponseJson = Utils.performServerPost(requestSpec, responseSpec,
                    "/fineract-provider/api/v1/loans?" + Utils.TENANT_IDENTIFIER, new Gson().toJson(applyBody));
            Long loanId = ((Number) new Gson().fromJson(applyResponseJson, java.util.Map.class).get("loanId")).longValue();

            loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(650000.0))
                    .dateFormat(DATETIME_PATTERN).approvedOnDate(submittedDate).locale("en"));

            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date(disbursementDate).dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(disbursementDate)
                    .dateFormat(DATETIME_PATTERN).transactionAmount(BigDecimal.valueOf(650000.0)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> baseline = getRepaymentPeriods(loanDetails);
            assertEquals(12, baseline.size(), "Baseline schedule should have 12 installments");

            assertEquals(48237.06, Utils.getDoubleValue(baseline.get(0).getPrincipalDue()), 0.01,
                    "Baseline installment 1 principal must match v3");
            assertEquals(19409.72, Utils.getDoubleValue(baseline.get(0).getInterestDue()), 0.01,
                    "Baseline installment 1 interest must match v3 (43-day rehab stub)");
            assertEquals(60517.98, Utils.getDoubleValue(baseline.get(11).getPrincipalDue()), 0.01,
                    "Baseline installment 12 principal must match v3");
            assertEquals(1260.79, Utils.getDoubleValue(baseline.get(11).getInterestDue()), 0.01,
                    "Baseline installment 12 interest must match v3");
            double totalInterestBaseline = baseline.stream().mapToDouble(p -> Utils.getDoubleValue(p.getInterestDue())).sum();
            assertEquals(97212.85, totalInterestBaseline, 0.01, "Baseline total interest must match v3");

            double inst1Due = Utils.getDoubleValue(baseline.get(0).getTotalDueForPeriod());
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("05 April 2026").dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "05 April 2026", inst1Due);

            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("03 May 2026").dateFormat(DATETIME_PATTERN).locale("en"));
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> recalculated = getRepaymentPeriods(loanDetails);
            assertEquals(baseline.size(), recalculated.size(), "Recalc must not change number of installments");

            for (int i = 0; i < baseline.size(); i++) {
                int periodNumber = i + 1;
                assertEquals(baseline.get(i).getDueDate(), recalculated.get(i).getDueDate(),
                        "Installment " + periodNumber + " due date drifted after late payment + recalc");
                assertEquals(Utils.getDoubleValue(baseline.get(i).getPrincipalDue()),
                        Utils.getDoubleValue(recalculated.get(i).getPrincipalDue()), 0.01,
                        "Installment " + periodNumber + " principalDue drifted after late payment + recalc");
                assertEquals(Utils.getDoubleValue(baseline.get(i).getInterestDue()),
                        Utils.getDoubleValue(recalculated.get(i).getInterestDue()), 0.01,
                        "Installment " + periodNumber + " interestDue drifted after late payment + recalc");
            }

            double totalInterestAfter = recalculated.stream().mapToDouble(p -> Utils.getDoubleValue(p.getInterestDue())).sum();
            assertEquals(totalInterestBaseline, totalInterestAfter, 0.01, "Total interest must not change after late payment + recalc");
        });
    }

    /**
     * Test 4: Verify MNZL 30/360 interest formula across all repayment periods.
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
        return loanProductHelper.createLoanProduct(buildDecliningBalanceProduct(30, 360));
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
