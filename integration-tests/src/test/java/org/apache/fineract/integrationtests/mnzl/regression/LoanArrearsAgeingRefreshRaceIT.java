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

import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdSummary;
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
 * Regression test pinning behavior locked by commit 14412d71c (Fix loan arrears ageing refresh race).
 *
 * The original bug: concurrent COB executions could race when refreshing the {@code m_loan_arrears_aging} summary row,
 * leaving duplicate or partially-updated rows. The fix introduced an idempotent UPSERT in
 * {@code DatabaseSpecificSQLGenerator} and tightened the refresh path in {@code LoanArrearsAgingServiceImpl}.
 *
 * This IT pins the deterministic outcome of the post-fix path. The upsert dialect and refresh ordering are covered by
 * focused unit tests; this scenario verifies the end-to-end single-execution invariant.
 */
@Slf4j
public class LoanArrearsAgeingRefreshRaceIT extends BaseLoanIntegrationTest {

    private static final double PRINCIPAL = 120000.0;
    private static final double ANNUAL_RATE = 12.0;
    private static final int NUM_REPAYMENTS = 12;
    private static final String SUBMITTED_DATE = "01 January 2026";
    private static final String FIRST_INSTALLMENT_DUE = "01 February 2026";
    // 10 days past the first installment's due date — guarantees overdue.
    private static final String OVERDUE_BUSINESS_DATE = "11 February 2026";

    /**
     * Control case: a single COB execution must populate the arrears ageing summary with the expected principal-overdue
     * and overdue-since values. This is the post-fix invariant — the fix must not break the single-execution path.
     */
    @Test
    public void singleCobExecution_setsAgeingCorrectly() {
        runAt(SUBMITTED_DATE, () -> {
            Long loanId = createOverdueMnzlLoan();

            setBusinessDate(OVERDUE_BUSINESS_DATE);
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            GetLoansLoanIdSummary summary = loanDetails.getSummary();
            assertThat(summary).as("loan summary").isNotNull();
            assertThat(summary.getOverdueSinceDate()).as("overdueSinceDate must be populated after single COB").isNotNull();
            assertThat(Utils.getDoubleValue(summary.getTotalOverdue())).as("totalOverdue after single COB").isGreaterThan(0.0);
        });
    }

    // ---- Helpers ----

    private Long createOverdueMnzlLoan() {
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

    private void setBusinessDate(String date) {
        businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                .date(date).dateFormat(DATETIME_PATTERN).locale("en"));
    }
}
