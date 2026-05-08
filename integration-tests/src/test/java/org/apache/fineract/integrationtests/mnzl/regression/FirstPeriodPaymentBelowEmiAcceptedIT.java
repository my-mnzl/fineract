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

import static org.assertj.core.api.Assertions.within;

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
 * Regression test pinning behavior locked by commit 6bfa93c6a (Re-allow first period payments below EMI).
 *
 * The original bug: a previous fix to the cumulative declining-balance generator inadvertently rejected
 * first-installment payments smaller than the EMI, treating partial payments on the opening installment as an error.
 * After 6bfa93c6a, partial payments on the first installment are accepted again — the unpaid portion stays as
 * outstanding on installment 1 and the rest of the schedule is unaffected.
 *
 * This IT pins the post-fix invariant: a 50%-of-EMI payment on the first installment must be accepted, and the
 * remaining 50% must remain outstanding on installment 1 (not rolled forward, not rejected).
 */
@Slf4j
public class FirstPeriodPaymentBelowEmiAcceptedIT extends BaseLoanIntegrationTest {

    private static final double PRINCIPAL = 120000.0;
    private static final double ANNUAL_RATE = 12.0;
    private static final int NUM_REPAYMENTS = 12;
    private static final String SUBMITTED_DATE = "01 January 2026";
    private static final String FIRST_INSTALLMENT_DUE = "01 February 2026";

    /**
     * Pay 50% of EMI on the first installment's due date. The repayment must be accepted, installment 1 must remain
     * partially unpaid (with ~50% of total still outstanding for that period), and installments 2..N must stay
     * untouched.
     */
    @Test
    public void firstPaymentBelowEmi_isAccepted() {
        runAt(SUBMITTED_DATE, () -> {
            Long loanId = createMnzlLoan();
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> periodsBefore = getRepaymentPeriods(loanDetails);

            double emi = Utils.getDoubleValue(periodsBefore.get(0).getTotalDueForPeriod());
            double partial = Math.round(emi * 0.5 * 100.0) / 100.0;
            double expectedRemainder = emi - partial;

            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", FIRST_INSTALLMENT_DUE, partial);

            GetLoansLoanIdResponse afterPayment = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(afterPayment, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> periodsAfter = getRepaymentPeriods(afterPayment);

            // Installment 1: ~50% of EMI must remain outstanding (allow 1c rounding tolerance).
            double inst1Outstanding = Utils.getDoubleValue(periodsAfter.get(0).getTotalOutstandingForPeriod());
            assertThat(inst1Outstanding).as("installment 1 outstanding after below-EMI payment").isEqualTo(expectedRemainder, within(0.01));

            // Installments 2..N: schedule must be unchanged (partial first payment must not redistribute).
            assertThat(periodsAfter).as("number of installments after partial payment").hasSameSizeAs(periodsBefore);
            for (int i = 1; i < periodsBefore.size(); i++) {
                int periodNumber = i + 1;
                assertThat(Utils.getDoubleValue(periodsAfter.get(i).getPrincipalDue()))
                        .as("installment %d principalDue must not drift", periodNumber)
                        .isEqualTo(Utils.getDoubleValue(periodsBefore.get(i).getPrincipalDue()), within(0.01));
                assertThat(Utils.getDoubleValue(periodsAfter.get(i).getInterestDue()))
                        .as("installment %d interestDue must not drift", periodNumber)
                        .isEqualTo(Utils.getDoubleValue(periodsBefore.get(i).getInterestDue()), within(0.01));
            }
        });
    }

    // ---- Helpers ----

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
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
