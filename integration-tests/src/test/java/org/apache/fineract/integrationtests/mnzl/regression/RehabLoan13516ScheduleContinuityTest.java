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
package org.apache.fineract.integrationtests.mnzl.regression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.junit.jupiter.api.Test;

/**
 * Regression tests pinning the production rehab loan signature behind
 * {@code MnzlCustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.isRehabLegacyOneOffLoan}.
 *
 * The rehab signature is keyed on (principal=650000, expectedDisbursementDate=2026-02-17,
 * repaymentsStartingFromLocalDate=2026-04-01) — NOT on a specific loan id. Whenever a loan matches that triple, the
 * generator must:
 * <ul>
 * <li>Reproduce the v3 baseline schedule exactly (12 installments, 43-day legacy stub, total interest 97,212.85).</li>
 * <li>Stay frozen on lateness — interest recalculation is disabled at the product/loan level, so late repayments must
 * not redistribute principal or interest. Lateness surfaces only as penalty charges, not as schedule drift.</li>
 * </ul>
 *
 * Pins commit 6142206b7 (Rehab loan 13516 stub fix).
 */
@Slf4j
public class RehabLoan13516ScheduleContinuityTest extends BaseLoanIntegrationTest {

    private static final String MNZL_STRATEGY_URL = "/fineract-provider/api/v1/mnzl/loan-products/%d/strategies?" + Utils.TENANT_IDENTIFIER;

    private static final double PRINCIPAL = 650000.0;
    private static final String SUBMITTED_DATE = "06 February 2026";
    private static final String DISBURSEMENT_DATE = "17 February 2026";
    private static final String FIRST_REPAYMENT_DATE = "01 April 2026";

    // v3 baseline pins (from production loan 13516 schedule)
    private static final double BASELINE_INST1_PRINCIPAL = 48237.06;
    private static final double BASELINE_INST1_INTEREST = 19409.72;
    private static final double BASELINE_INST12_PRINCIPAL = 60517.98;
    private static final double BASELINE_INST12_INTEREST = 1260.79;
    private static final double BASELINE_TOTAL_INTEREST = 97212.85;

    /**
     * Baseline pin: the rehab signature must reproduce the v3 origination schedule exactly. Asserts the 43-day legacy
     * stub period, period-1 / period-12 principal & interest, and the total interest figure of 97,212.85. Does not
     * exercise late-payment behavior.
     */
    @Test
    public void originSchedulesMatchProductionVersion3() {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createRehabProduct();
            Long loanId = applyApproveDisburseRehabLoan(clientId, productId);

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> baseline = getRepaymentPeriods(loanDetails);

            assertBaselineSchedule(baseline);
        });
    }

    /**
     * Pay installment 1 four days late, run inline COB past installment 2's due date, and assert the recalculated
     * schedule equals the v3 baseline. Since interest recalculation is disabled, lateness must not redistribute
     * principal or interest across the remaining 11 installments.
     */
    @Test
    public void latePaymentInstallment1_doesNotDriftSchedule() {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createRehabProduct();
            Long loanId = applyApproveDisburseRehabLoan(clientId, productId);

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> baseline = getRepaymentPeriods(loanDetails);
            assertBaselineSchedule(baseline);

            // Pay installment 1 four days late (due 01 April; pay 05 April).
            double inst1Due = Utils.getDoubleValue(baseline.get(0).getTotalDueForPeriod());
            setBusinessDate("05 April 2026");
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "05 April 2026", inst1Due);

            // Advance past installment 2's due date and run inline COB to force any potential recalc to surface.
            setBusinessDate("03 May 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> recalculated = getRepaymentPeriods(loanDetails);
            assertSchedulesMatch(baseline, recalculated, "late installment 1");
        });
    }

    /**
     * Pay installments 1-5 on time, then pay installment 6 four days late and run inline COB. Tests that lateness
     * deeper into the schedule (after substantial principal has been amortized) still does not drift the remaining
     * installments — the rehab guard must hold across the full schedule lifecycle.
     */
    @Test
    public void latePaymentInstallment6_doesNotDriftSchedule() {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createRehabProduct();
            Long loanId = applyApproveDisburseRehabLoan(clientId, productId);

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> baseline = getRepaymentPeriods(loanDetails);
            assertBaselineSchedule(baseline);

            // Pay installments 1..5 on their due dates.
            for (int i = 0; i < 5; i++) {
                GetLoansLoanIdRepaymentPeriod period = baseline.get(i);
                String dueDate = period.getDueDate().format(dateTimeFormatter);
                double totalDue = Utils.getDoubleValue(period.getTotalDueForPeriod());
                setBusinessDate(dueDate);
                loanTransactionHelper.makeLoanRepayment(loanId, "repayment", dueDate, totalDue);
            }

            // Pay installment 6 four days late (due 01 September 2026; pay 05 September 2026).
            GetLoansLoanIdRepaymentPeriod inst6 = baseline.get(5);
            double inst6Due = Utils.getDoubleValue(inst6.getTotalDueForPeriod());
            setBusinessDate("05 September 2026");
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "05 September 2026", inst6Due);

            // Advance past installment 7's due date and run inline COB.
            setBusinessDate("03 October 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> recalculated = getRepaymentPeriods(loanDetails);
            assertSchedulesMatch(baseline, recalculated, "late installment 6");
        });
    }

    /**
     * Pay installment 1 in two parts: 50% on the due date and the remaining 50% three days late. Run inline COB. Tests
     * that partial repayments — even when one half lands late — still leave the schedule frozen at origination.
     */
    @Test
    public void multiplePartialPayments_doNotDriftSchedule() {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createRehabProduct();
            Long loanId = applyApproveDisburseRehabLoan(clientId, productId);

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> baseline = getRepaymentPeriods(loanDetails);
            assertBaselineSchedule(baseline);

            double inst1Due = Utils.getDoubleValue(baseline.get(0).getTotalDueForPeriod());
            double firstHalf = Math.round(inst1Due * 50.0) / 100.0;
            double secondHalf = inst1Due - firstHalf;

            // First half on the due date.
            setBusinessDate("01 April 2026");
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "01 April 2026", firstHalf);

            // Second half three days late.
            setBusinessDate("04 April 2026");
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "04 April 2026", secondHalf);

            // Advance past installment 2's due date and run inline COB.
            setBusinessDate("03 May 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> recalculated = getRepaymentPeriods(loanDetails);
            assertSchedulesMatch(baseline, recalculated, "partial late payment");
        });
    }

    // ---- Helper methods ----

    private void assertBaselineSchedule(List<GetLoansLoanIdRepaymentPeriod> baseline) {
        assertEquals(12, baseline.size(), "Baseline schedule should have 12 installments");
        assertEquals(BASELINE_INST1_PRINCIPAL, Utils.getDoubleValue(baseline.get(0).getPrincipalDue()), 0.01,
                "Baseline installment 1 principal must match v3");
        assertEquals(BASELINE_INST1_INTEREST, Utils.getDoubleValue(baseline.get(0).getInterestDue()), 0.01,
                "Baseline installment 1 interest must match v3 (43-day rehab stub)");
        assertEquals(BASELINE_INST12_PRINCIPAL, Utils.getDoubleValue(baseline.get(11).getPrincipalDue()), 0.01,
                "Baseline installment 12 principal must match v3");
        assertEquals(BASELINE_INST12_INTEREST, Utils.getDoubleValue(baseline.get(11).getInterestDue()), 0.01,
                "Baseline installment 12 interest must match v3");
        double totalInterest = baseline.stream().mapToDouble(p -> Utils.getDoubleValue(p.getInterestDue())).sum();
        assertEquals(BASELINE_TOTAL_INTEREST, totalInterest, 0.01, "Baseline total interest must match v3");
    }

    private void assertSchedulesMatch(List<GetLoansLoanIdRepaymentPeriod> baseline, List<GetLoansLoanIdRepaymentPeriod> recalculated,
            String scenarioLabel) {
        assertEquals(baseline.size(), recalculated.size(), scenarioLabel + " — recalc must not change number of installments");
        for (int i = 0; i < baseline.size(); i++) {
            int periodNumber = i + 1;
            assertEquals(baseline.get(i).getDueDate(), recalculated.get(i).getDueDate(),
                    scenarioLabel + " — installment " + periodNumber + " due date drifted");
            assertEquals(Utils.getDoubleValue(baseline.get(i).getPrincipalDue()),
                    Utils.getDoubleValue(recalculated.get(i).getPrincipalDue()), 0.01,
                    scenarioLabel + " — installment " + periodNumber + " principalDue drifted");
            assertEquals(Utils.getDoubleValue(baseline.get(i).getInterestDue()), Utils.getDoubleValue(recalculated.get(i).getInterestDue()),
                    0.01, scenarioLabel + " — installment " + periodNumber + " interestDue drifted");
        }
        double totalInterestAfter = recalculated.stream().mapToDouble(p -> Utils.getDoubleValue(p.getInterestDue())).sum();
        assertEquals(BASELINE_TOTAL_INTEREST, totalInterestAfter, 0.01, scenarioLabel + " — total interest must not change");
    }

    private void setBusinessDate(String date) {
        businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                .date(date).dateFormat(DATETIME_PATTERN).locale("en"));
    }

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
    }

    private Long createRehabProduct() {
        // Recalc is intentionally disabled to match production rehab loans (product 2): the schedule must stay
        // frozen at origination, and lateness is handled via penalty charges rather than principal/interest
        // redistribution. See the SQL migration that flips m_loan.interest_recalculation_enabled to 0 on prod.
        PostLoanProductsResponse loanProduct = loanProductHelper.createLoanProduct(buildRehabDecliningBalanceProduct());
        Long productId = loanProduct.getResourceId();
        setMnzlProductStrategy(productId);
        return productId;
    }

    private PostLoanProductsRequest buildRehabDecliningBalanceProduct() {
        return new PostLoanProductsRequest().name(Utils.uniqueRandomStringGenerator("MNZL_REHAB_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Rehab declining balance test product 30/360")
                .currencyCode("USD").digitsAfterDecimal(2).principal(PRINCIPAL).minPrincipal(1000.0).maxPrincipal(2000000.0)
                .numberOfRepayments(12).repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L)
                .interestRatePerPeriod(25.0).interestRateFrequencyType(InterestRateFrequencyType.YEARS)
                .amortizationType(AmortizationType.EQUAL_INSTALLMENTS).interestType(InterestType.DECLINING_BALANCE)
                .interestCalculationPeriodType(InterestCalculationPeriodType.SAME_AS_REPAYMENT_PERIOD).daysInMonthType(30)
                .daysInYearType(360).includeInBorrowerCycle(false).useBorrowerCycle(false).isLinkedToFloatingInterestRates(false)
                .allowVariableInstallments(false).allowPartialPeriodInterestCalcualtion(true).isInterestRecalculationEnabled(false)
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

    private Long applyApproveDisburseRehabLoan(Long clientId, Long productId) {
        Map<String, Object> applyBody = new HashMap<>();
        applyBody.put("clientId", clientId);
        applyBody.put("productId", productId);
        applyBody.put("principal", PRINCIPAL);
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
        applyBody.put("submittedOnDate", SUBMITTED_DATE);
        applyBody.put("expectedDisbursementDate", DISBURSEMENT_DATE);
        applyBody.put("repaymentsStartingFromDate", FIRST_REPAYMENT_DATE);
        applyBody.put("interestChargedFromDate", DISBURSEMENT_DATE);
        applyBody.put("dateFormat", DATETIME_PATTERN);
        applyBody.put("locale", "en");
        applyBody.put("loanType", "individual");
        String applyResponseJson = Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/loans?" + Utils.TENANT_IDENTIFIER, new Gson().toJson(applyBody));
        Long loanId = ((Number) new Gson().fromJson(applyResponseJson, java.util.Map.class).get("loanId")).longValue();

        loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(PRINCIPAL))
                .dateFormat(DATETIME_PATTERN).approvedOnDate(SUBMITTED_DATE).locale("en"));

        setBusinessDate(DISBURSEMENT_DATE);
        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(DISBURSEMENT_DATE)
                .dateFormat(DATETIME_PATTERN).transactionAmount(BigDecimal.valueOf(PRINCIPAL)).locale("en"));

        return loanId;
    }
}
