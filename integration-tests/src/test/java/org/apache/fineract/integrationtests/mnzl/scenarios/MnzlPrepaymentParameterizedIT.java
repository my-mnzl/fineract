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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdTransactionsTemplateResponse;
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
 * F.6 — Parameterized prepayment scenario over each prod product config (REST-driven).
 *
 * The simulator's action set ({@code DISBURSE/PAY/SKIP/RUN_COB/...}) doesn't model a true prepayment-with-closure
 * cleanly — that requires the loan engine's prepayLoan transaction template, which is only available on a deployed loan
 * via REST. So this scenario uses the standard {@code LoanTransactionHelper} path: apply, approve, disburse, then on
 * installment 3's due date pay the full prepay amount returned by {@code getPrepayAmount}. Asserts the loan closes
 * early (closedObligationsMet) and no future installments remain due.
 */
@Slf4j
public class MnzlPrepaymentParameterizedIT extends BaseLoanIntegrationTest {

    private static final double ANNUAL_RATE = 12.0;
    private static final String SUBMITTED_DATE = "01 January 2026";

    @ParameterizedTest(name = "prepayment: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void prepayment_onInstallment3DueDate_closesEarly(String configName, Map<String, Object> productConfig) {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            Long loanId = applyApproveDisburseLoan(clientId, productId, principal, ANNUAL_RATE, terms, SUBMITTED_DATE);

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());
            List<GetLoansLoanIdRepaymentPeriod> periods = getRepaymentPeriods(loanDetails);
            assertThat(periods).as("schedule periods").hasSize(terms);

            // Pay installments 1 and 2 on time so the loan is partway through its life when prepayment hits.
            for (int i = 0; i < 2; i++) {
                GetLoansLoanIdRepaymentPeriod p = periods.get(i);
                String due = p.getDueDate().format(dateTimeFormatter);
                businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                        .date(due).dateFormat(DATETIME_PATTERN).locale("en"));
                loanTransactionHelper.makeLoanRepayment(loanId, "repayment", due, Utils.getDoubleValue(p.getTotalDueForPeriod()));
            }

            // Advance to installment 3's due date and prepay the full outstanding.
            String inst3Due = periods.get(2).getDueDate().format(dateTimeFormatter);
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date(inst3Due).dateFormat(DATETIME_PATTERN).locale("en"));
            GetLoansLoanIdTransactionsTemplateResponse prepayTemplate = getPrepayAmount(loanId, inst3Due);
            assertThat(prepayTemplate.getAmount()).as("prepay amount").isNotNull().isGreaterThan(0.0);
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", inst3Due, prepayTemplate.getAmount());

            // Loan must be closed and have zero outstanding.
            GetLoansLoanIdResponse afterPrepay = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(afterPrepay, status -> status.getClosedObligationsMet());
            assertThat(Utils.getDoubleValue(afterPrepay.getSummary().getTotalOutstanding())).as("total outstanding after prepayment")
                    .isEqualTo(0.0, within(0.01));

            // No installment past 3 may carry an outstanding balance.
            List<GetLoansLoanIdRepaymentPeriod> afterPeriods = getRepaymentPeriods(afterPrepay);
            for (GetLoansLoanIdRepaymentPeriod p : afterPeriods) {
                if (p.getPeriod() != null && p.getPeriod() > 3) {
                    assertThat(Utils.getDoubleValue(p.getPrincipalOutstanding()))
                            .as("post-prepay period %d principal outstanding", p.getPeriod()).isEqualTo(0.0, within(0.01));
                    assertThat(Utils.getDoubleValue(p.getInterestOutstanding()))
                            .as("post-prepay period %d interest outstanding", p.getPeriod()).isEqualTo(0.0, within(0.01));
                }
            }
        });
    }

    // ---- Helpers ----

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
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
