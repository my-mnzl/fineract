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
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdTransactions;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsRequest;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsResponse;
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
 * F.9 — Parameterized interest waiver scenario over each prod product config (REST-driven).
 *
 * Apply, approve, disburse a loan; on installment 1's due date waive the interest portion via {@code POST
 * /loans/{loanId}/transactions?command=waiveinterest}. Asserts an interest-waiver transaction is recorded, total
 * interest outstanding is reduced by the waived amount, and principal outstanding is unchanged.
 */
@Slf4j
public class MnzlInterestWaiverIT extends BaseLoanIntegrationTest {

    private static final double ANNUAL_RATE = 12.0;
    private static final String SUBMITTED_DATE = "01 January 2026";

    @ParameterizedTest(name = "interest waiver: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void interestWaiver_onInstallment1_reducesInterestOutstanding(String configName, Map<String, Object> productConfig) {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            Long loanId = applyApproveDisburseLoan(clientId, productId, principal, ANNUAL_RATE, terms, SUBMITTED_DATE);

            // Capture installment 1 interest due, total principal outstanding before waiver.
            GetLoansLoanIdResponse beforeWaiver = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> periods = beforeWaiver.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
            assertThat(periods).as("schedule periods").hasSize(terms);
            GetLoansLoanIdRepaymentPeriod inst1 = periods.get(0);
            double inst1Interest = Utils.getDoubleValue(inst1.getInterestDue());
            double beforePrincipal = Utils.getDoubleValue(beforeWaiver.getSummary().getPrincipalOutstanding());
            double beforeInterest = Utils.getDoubleValue(beforeWaiver.getSummary().getInterestOutstanding());
            assertThat(inst1Interest).as("installment 1 interest due").isGreaterThan(0.0);

            // Advance to installment 1 due date and waive its interest amount.
            String inst1Due = inst1.getDueDate().format(dateTimeFormatter);
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date(inst1Due).dateFormat(DATETIME_PATTERN).locale("en"));
            PostLoansLoanIdTransactionsResponse waiver = loanTransactionHelper.makeWaiveInterest(loanId,
                    new PostLoansLoanIdTransactionsRequest().dateFormat(DATETIME_PATTERN).transactionDate(inst1Due).locale("en")
                            .transactionAmount(inst1Interest));
            assertThat(waiver.getResourceId()).as("waiver transaction id").isNotNull();

            // Verify waiver transaction is present and principal/interest summaries moved as expected.
            GetLoansLoanIdResponse afterWaiver = loanTransactionHelper.getLoanDetails(loanId);
            boolean hasWaiverTx = afterWaiver.getTransactions().stream().anyMatch(MnzlInterestWaiverIT::isInterestWaiverTransaction);
            assertThat(hasWaiverTx).as("interest waiver transaction recorded").isTrue();

            assertThat(Utils.getDoubleValue(afterWaiver.getSummary().getPrincipalOutstanding())).as("principal unchanged after waiver")
                    .isEqualTo(beforePrincipal, within(0.01));
            assertThat(Utils.getDoubleValue(afterWaiver.getSummary().getInterestOutstanding())).as("interest reduced by waived amount")
                    .isEqualTo(beforeInterest - inst1Interest, within(0.01));
        });
    }

    private static boolean isInterestWaiverTransaction(GetLoansLoanIdTransactions tx) {
        if (tx.getType() == null) {
            return false;
        }
        // Match either the typed boolean (when present) or the textual code.
        Boolean waiveInterest = tx.getType().getWaiveInterest();
        if (Boolean.TRUE.equals(waiveInterest)) {
            return true;
        }
        String code = tx.getType().getCode();
        return code != null && code.toLowerCase().contains("waiveinterest");
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
