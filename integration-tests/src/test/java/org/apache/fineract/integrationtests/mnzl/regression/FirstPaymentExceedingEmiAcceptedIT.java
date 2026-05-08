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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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
 * Regression test pinning behavior locked by commit b268fc23a (feat: Allow first payment to exceed EMI).
 *
 * The original bug: the cumulative declining-balance generator rejected first-installment payments larger than the EMI,
 * treating the overpayment as an error. After the fix, the generator accepts payments exceeding the EMI on any
 * installment — including the first — and applies the surplus toward future installments per the configured
 * transaction-processing strategy.
 *
 * This IT pins the post-fix invariant: a first-period repayment for 150% of the EMI must be accepted, and the excess
 * must reduce the principal outstanding on subsequent installments.
 */
@Slf4j
public class FirstPaymentExceedingEmiAcceptedIT extends BaseLoanIntegrationTest {

    private static final double PRINCIPAL = 120000.0;
    private static final double ANNUAL_RATE = 12.0;
    private static final int NUM_REPAYMENTS = 12;
    private static final String SUBMITTED_DATE = "01 January 2026";
    private static final String FIRST_INSTALLMENT_DUE = "01 February 2026";
    private static final String SECOND_INSTALLMENT_DUE = "01 March 2026";

    /**
     * Pay 150% of EMI on the first installment's due date. Pre-fix this would have been rejected; post-fix it must be
     * accepted and the surplus must be visible on installment 2.
     */
    @Test
    public void firstPaymentExceedingEmi_isAccepted() {
        runAt(SUBMITTED_DATE, () -> {
            Long loanId = createMnzlLoan();
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> periods = getRepaymentPeriods(loanDetails);

            double emi = Utils.getDoubleValue(periods.get(0).getTotalDueForPeriod());
            double overpayment = Math.round(emi * 1.5 * 100.0) / 100.0;

            // Make the over-EMI repayment on the first installment's due date — the fix must allow it.
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", FIRST_INSTALLMENT_DUE, overpayment);

            // The repayment must succeed and the loan must remain active (over-EMI is allowed, not an error).
            GetLoansLoanIdResponse afterPayment = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(afterPayment, status -> status.getActive());

            // Surplus must reduce the total outstanding for the remaining schedule.
            double totalOutstandingBefore = sumPrincipalOutstanding(periods);
            double totalOutstandingAfter = sumPrincipalOutstanding(getRepaymentPeriods(afterPayment));
            assertThat(totalOutstandingAfter).as("total principal outstanding must drop by more than one EMI's principal portion")
                    .isLessThan(totalOutstandingBefore - Utils.getDoubleValue(periods.get(0).getPrincipalDue()));

            // Installment 1 must be fully paid.
            GetLoansLoanIdRepaymentPeriod inst1After = afterPayment.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() == 1).findFirst().orElseThrow();
            assertThat(Utils.getDoubleValue(inst1After.getTotalOutstandingForPeriod())).as("installment 1 outstanding must be zero")
                    .isEqualTo(0.0, org.assertj.core.api.Assertions.within(0.01));
        });
    }

    /**
     * Control case: pay 150% of EMI on installment 2 (after a normal installment-1 payment). Pre-fix this was already
     * accepted on later installments — the fix only opened up installment 1 — so this case must continue to pass and
     * proves the assertion shape is right before relying on it for the first-installment case.
     */
    @Test
    public void secondPaymentExceedingEmi_isAccepted() {
        runAt(SUBMITTED_DATE, () -> {
            Long loanId = createMnzlLoan();
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> periods = getRepaymentPeriods(loanDetails);

            double emi1 = Utils.getDoubleValue(periods.get(0).getTotalDueForPeriod());
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", FIRST_INSTALLMENT_DUE, emi1);

            double emi2 = Utils.getDoubleValue(periods.get(1).getTotalDueForPeriod());
            double overpayment = Math.round(emi2 * 1.5 * 100.0) / 100.0;
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", SECOND_INSTALLMENT_DUE, overpayment);

            GetLoansLoanIdResponse afterPayment = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(afterPayment, status -> status.getActive());

            GetLoansLoanIdRepaymentPeriod inst2After = afterPayment.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() == 2).findFirst().orElseThrow();
            assertThat(Utils.getDoubleValue(inst2After.getTotalOutstandingForPeriod())).as("installment 2 outstanding must be zero")
                    .isEqualTo(0.0, org.assertj.core.api.Assertions.within(0.01));
        });
    }

    // ---- Helpers ----

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
    }

    private double sumPrincipalOutstanding(List<GetLoansLoanIdRepaymentPeriod> periods) {
        return periods.stream().mapToDouble(p -> Utils.getDoubleValue(p.getPrincipalOutstanding())).sum();
    }

    private Long createMnzlLoan() {
        Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        PostLoanProductsResponse loanProduct = loanProductHelper.createLoanProduct(builder.decliningBalance30_360());
        Long productId = loanProduct.getResourceId();
        new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

        return applyApproveDisburseLoan(clientId, productId, PRINCIPAL, ANNUAL_RATE, NUM_REPAYMENTS, SUBMITTED_DATE);
    }

    private Long applyApproveDisburseLoan(Long clientId, Long productId, double principal, double annualRate, int numberOfRepayments,
            String date) {
        PostLoansResponse loanResponse = loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                .principal(BigDecimal.valueOf(principal)).loanTermFrequency(numberOfRepayments).loanTermFrequencyType(2)
                .numberOfRepayments(numberOfRepayments).repaymentEvery(1).repaymentFrequencyType(2)
                .interestRatePerPeriod(BigDecimal.valueOf(annualRate)).amortizationType(1).interestType(0).interestCalculationPeriodType(1)
                .transactionProcessingStrategyCode("mifos-standard-strategy").expectedDisbursementDate(date).submittedOnDate(date)
                .dateFormat(DATETIME_PATTERN).locale("en").loanType("individual"));
        Long loanId = loanResponse.getLoanId();
        loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(principal))
                .dateFormat(DATETIME_PATTERN).approvedOnDate(date).locale("en"));
        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(date).dateFormat(DATETIME_PATTERN)
                .transactionAmount(BigDecimal.valueOf(principal)).locale("en"));
        return loanId;
    }
}
