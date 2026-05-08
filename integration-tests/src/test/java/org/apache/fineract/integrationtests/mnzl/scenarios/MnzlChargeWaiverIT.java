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
import org.apache.fineract.client.models.GetLoansLoanIdChargesChargeIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostChargesResponse;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdChargesResponse;
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
 * F.8 — Parameterized loan charge waiver scenario over each prod product config (REST-driven).
 *
 * Apply, approve, disburse a loan, attach a flat fee charge after disbursement, then waive that charge via {@code POST
 * /loans/{loanId}/charges/{chargeId}?command=waive}. Asserts the fee outstanding goes to zero while principal/interest
 * remain unchanged from before the waiver.
 */
@Slf4j
public class MnzlChargeWaiverIT extends BaseLoanIntegrationTest {

    private static final double ANNUAL_RATE = 12.0;
    private static final String SUBMITTED_DATE = "01 January 2026";
    private static final double FEE_AMOUNT = 25.0;

    @ParameterizedTest(name = "charge waiver: {0}")
    @MethodSource("org.apache.fineract.integrationtests.mnzl.helpers.MnzlScenarioFixtures#prodProductConfigs")
    public void chargeWaiver_feeOutstandingGoesToZero(String configName, Map<String, Object> productConfig) {
        runAt(SUBMITTED_DATE, () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createProductFromConfig(productConfig);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            int terms = ((Number) productConfig.get("numberOfRepayments")).intValue();
            double principal = ((Number) productConfig.get("principalAmount")).doubleValue();
            Long loanId = applyApproveDisburseLoan(clientId, productId, principal, ANNUAL_RATE, terms, SUBMITTED_DATE);

            // Capture pre-waiver principal/interest outstanding so we can assert they are unchanged after the waiver.
            GetLoansLoanIdResponse beforeCharge = loanTransactionHelper.getLoanDetails(loanId);
            double beforePrincipal = Utils.getDoubleValue(beforeCharge.getSummary().getPrincipalOutstanding());
            double beforeInterest = Utils.getDoubleValue(beforeCharge.getSummary().getInterestOutstanding());

            // Attach a flat fee charge on the disbursement date.
            PostChargesResponse chargeResult = createCharge(FEE_AMOUNT);
            Long chargeId = chargeResult.getResourceId();
            PostLoansLoanIdChargesResponse loanChargeResult = addLoanCharge(loanId, chargeId, SUBMITTED_DATE, FEE_AMOUNT);
            Long loanChargeId = loanChargeResult.getResourceId();

            // Sanity check: the fee should now be outstanding.
            GetLoansLoanIdChargesChargeIdResponse loanChargeBefore = findLoanCharge(loanId, loanChargeId);
            assertThat(loanChargeBefore.getAmountOutstanding()).as("fee outstanding before waiver").isEqualTo(FEE_AMOUNT, within(0.01));

            // Waive the charge — installment 1 carries the disbursement-time fee.
            waiveLoanCharge(loanId, loanChargeId, 1);

            // After waiver: fee outstanding == 0 and amount waived == FEE_AMOUNT.
            GetLoansLoanIdChargesChargeIdResponse loanChargeAfter = findLoanCharge(loanId, loanChargeId);
            assertThat(loanChargeAfter.getAmountOutstanding()).as("fee outstanding after waiver").isEqualTo(0.0, within(0.01));
            assertThat(loanChargeAfter.getAmountWaived()).as("fee amount waived").isEqualTo(FEE_AMOUNT, within(0.01));

            // Principal and interest outstanding must be unchanged by the waiver.
            GetLoansLoanIdResponse afterWaiver = loanTransactionHelper.getLoanDetails(loanId);
            assertThat(Utils.getDoubleValue(afterWaiver.getSummary().getPrincipalOutstanding())).as("principal unchanged after waiver")
                    .isEqualTo(beforePrincipal, within(0.01));
            assertThat(Utils.getDoubleValue(afterWaiver.getSummary().getInterestOutstanding())).as("interest unchanged after waiver")
                    .isEqualTo(beforeInterest, within(0.01));
        });
    }

    private GetLoansLoanIdChargesChargeIdResponse findLoanCharge(Long loanId, Long loanChargeId) {
        List<GetLoansLoanIdChargesChargeIdResponse> charges = loanTransactionHelper.getLoanCharges(loanId);
        return charges.stream().filter(c -> loanChargeId.equals(c.getId())).findFirst().orElseThrow();
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
