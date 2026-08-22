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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.client.models.GetLoansLoanIdLoanChargeData;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlChargesHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.junit.jupiter.api.Test;

public class MnzlPeriodicChargesLifecycleIT extends BaseLoanIntegrationTest {

    private static final double PRINCIPAL = 12000.0;
    private static final double PERIODIC_FEE = 50.0;
    private static final int REPAYMENTS = 12;

    @Test
    public void monthlyProductChargeIsProjectedAcrossTheFullLoanTerm() {
        runAt("01 January 2026", () -> {
            final Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            final Long chargeId = new MnzlChargesHelper(requestSpec, responseSpec).createMnzlPeriodicMonthlyFee(PERIODIC_FEE).longValue();

            final MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount,
                    interestIncomeAccount, feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount,
                    interestReceivableAccount, feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount,
                    interestIncomeChargeOffAccount, feeChargeOffAccount, penaltyChargeOffAccount, chargeOffExpenseAccount,
                    chargeOffFraudExpenseAccount);
            final PostLoanProductsRequest productRequest = builder.withCharges(builder.decliningBalance30_360(), chargeId);
            final PostLoanProductsResponse product = loanProductHelper.createLoanProduct(productRequest);
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(product.getResourceId());

            final Long loanId = applyApproveDisburseLoan(clientId, product.getResourceId());
            final GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            final List<GetLoansLoanIdRepaymentPeriod> periods = loan.getRepaymentSchedule().getPeriods().stream()
                    .filter(period -> period.getPeriod() != null && period.getPeriod() > 0).toList();

            assertEquals(REPAYMENTS, periods.size());
            for (int index = 0; index < periods.size(); index++) {
                assertEquals(PERIODIC_FEE, Utils.getDoubleValue(periods.get(index).getFeeChargesDue()), 0.01,
                        "periodic fee on installment " + (index + 1));
            }

            final List<GetLoansLoanIdLoanChargeData> projectedCharges = loan.getCharges().stream()
                    .filter(charge -> chargeId.equals(charge.getChargeId())).toList();
            assertEquals(REPAYMENTS, projectedCharges.size());
            assertEquals(LocalDate.of(2026, 2, 1), projectedCharges.get(0).getDueDate());
            assertEquals(LocalDate.of(2027, 1, 1), projectedCharges.get(REPAYMENTS - 1).getDueDate());
        });
    }

    private Long applyApproveDisburseLoan(final Long clientId, final Long productId) {
        final String date = "01 January 2026";
        final PostLoansResponse application = loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                .principal(BigDecimal.valueOf(PRINCIPAL)).loanTermFrequency(REPAYMENTS).loanTermFrequencyType(2)
                .numberOfRepayments(REPAYMENTS).repaymentEvery(1).repaymentFrequencyType(2).interestRatePerPeriod(BigDecimal.valueOf(12))
                .amortizationType(1).interestType(0).interestCalculationPeriodType(1)
                .transactionProcessingStrategyCode("mifos-standard-strategy").expectedDisbursementDate(date).submittedOnDate(date)
                .dateFormat(DATETIME_PATTERN).locale("en").loanType("individual"));
        final Long loanId = application.getLoanId();
        loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(PRINCIPAL))
                .dateFormat(DATETIME_PATTERN).approvedOnDate(date).locale("en"));
        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(date).dateFormat(DATETIME_PATTERN)
                .transactionAmount(BigDecimal.valueOf(PRINCIPAL)).locale("en"));
        return loanId;
    }
}
