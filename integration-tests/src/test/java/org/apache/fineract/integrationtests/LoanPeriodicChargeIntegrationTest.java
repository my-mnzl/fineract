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
package org.apache.fineract.integrationtests;

import static org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity.LIABILITY_TRANSFER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.client.models.GetLoansLoanIdChargesChargeIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.LoanProductChargeData;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoansLoanIdResponse;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.FinancialActivityAccountHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.junit.jupiter.api.Test;

public class LoanPeriodicChargeIntegrationTest extends BaseLoanIntegrationTest {

    private static final double PERIODIC_CHARGE_AMOUNT = 100.0;
    private static final String PERIODIC_JOB_SHORT_NAME = "LA_PLC";
    private static final String TRANSFER_FEE_JOB_SHORT_NAME = "LA_TFFS";
    private static final String LOAN_DISBURSEMENT_DATE = "01 January 2024";

    @Test
    public void testPeriodicLoanChargeGeneratesOnAnchorDateAndIsIdempotent() {
        AtomicReference<PeriodicLoanContext> contextRef = new AtomicReference<>();

        runAt(LOAN_DISBURSEMENT_DATE, () -> contextRef.set(createPeriodicLoan(6)));

        PeriodicLoanContext context = contextRef.get();
        String firstRepaymentDate = formatDate(context.firstRepaymentDate());

        runAt(firstRepaymentDate, () -> {
            schedulerJobHelper.executeAndAwaitJobByShortName(PERIODIC_JOB_SHORT_NAME);

            List<GetLoansLoanIdChargesChargeIdResponse> loanCharges = loanTransactionHelper.getLoanCharges(context.loanId());
            assertEquals(1, loanCharges.size());

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(context.loanId());
            GetLoansLoanIdRepaymentPeriod firstPeriod = repaymentPeriod(loanDetails, 1);
            assertEquals(context.firstRepaymentDate(), firstPeriod.getDueDate());
            assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesDue()));
            assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesOutstanding()));

            schedulerJobHelper.executeAndAwaitJobByShortName(PERIODIC_JOB_SHORT_NAME);
            assertEquals(1, loanTransactionHelper.getLoanCharges(context.loanId()).size());
        });
    }

    @Test
    public void testPeriodicLoanChargeBackfillsPastMaturityAndStopsOnceLoanIsClosed() {
        AtomicReference<PeriodicLoanContext> contextRef = new AtomicReference<>();

        runAt(LOAN_DISBURSEMENT_DATE, () -> contextRef.set(createPeriodicLoan(6)));

        PeriodicLoanContext context = contextRef.get();
        LocalDate secondAnnualChargeDate = context.firstRepaymentDate().plusYears(1);
        LocalDate thirdAnnualChargeDate = context.firstRepaymentDate().plusYears(2);

        runAt(formatDate(secondAnnualChargeDate), () -> {
            schedulerJobHelper.executeAndAwaitJobByShortName(PERIODIC_JOB_SHORT_NAME);

            List<GetLoansLoanIdChargesChargeIdResponse> loanCharges = loanTransactionHelper.getLoanCharges(context.loanId());
            assertEquals(2, loanCharges.size());

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(context.loanId());
            GetLoansLoanIdRepaymentPeriod annualChargePeriod = loanDetails.getRepaymentSchedule().getPeriods().stream()
                    .filter(period -> secondAnnualChargeDate.equals(period.getDueDate())).findFirst().orElseThrow();
            assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(annualChargePeriod.getFeeChargesDue()));
            assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(annualChargePeriod.getFeeChargesOutstanding()));

            verifyPrepayAmountByRepayment(context.loanId(), formatDate(secondAnnualChargeDate));
            verifyLoanStatus(context.loanId(), LoanStatus.CLOSED_OBLIGATIONS_MET);
        });

        runAt(formatDate(thirdAnnualChargeDate), () -> {
            schedulerJobHelper.executeAndAwaitJobByShortName(PERIODIC_JOB_SHORT_NAME);

            assertEquals(2, loanTransactionHelper.getLoanCharges(context.loanId()).size());
            verifyLoanStatus(context.loanId(), LoanStatus.CLOSED_OBLIGATIONS_MET);
        });
    }

    @Test
    public void testPeriodicLoanChargeWithAccountTransferGetsPaidFromLinkedSavings() {
        AtomicReference<PeriodicLoanContext> contextRef = new AtomicReference<>();

        runAt(LOAN_DISBURSEMENT_DATE, () -> contextRef.set(createPeriodicLoanWithLinkedSavings(6)));

        PeriodicLoanContext context = contextRef.get();
        String firstRepaymentDate = formatDate(context.firstRepaymentDate());

        runAt(firstRepaymentDate, () -> {
            Integer createdFinancialActivityMappingId = ensureLiabilityTransferFinancialActivityMapping();
            SavingsAccountHelper savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
            try {
                double accountBalanceBefore = ((Number) savingsAccountHelper.getSavingsSummary(context.savingsAccountId()).get("accountBalance"))
                        .doubleValue();

                schedulerJobHelper.executeAndAwaitJobByShortName(PERIODIC_JOB_SHORT_NAME);
                schedulerJobHelper.executeAndAwaitJobByShortName(TRANSFER_FEE_JOB_SHORT_NAME);

                GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(context.loanId());
                GetLoansLoanIdRepaymentPeriod firstPeriod = repaymentPeriod(loanDetails, 1);
                assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesDue()));
                assertEquals(0.0, Utils.getDoubleValue(firstPeriod.getFeeChargesOutstanding()));
                assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesPaid()));

                double accountBalanceAfter = ((Number) savingsAccountHelper.getSavingsSummary(context.savingsAccountId()).get("accountBalance"))
                        .doubleValue();
                assertEquals(accountBalanceBefore - PERIODIC_CHARGE_AMOUNT, accountBalanceAfter);
            } finally {
                cleanupFinancialActivityMapping(createdFinancialActivityMappingId);
            }
        });
    }

    private PeriodicLoanContext createPeriodicLoan(final int numberOfRepayments) {
        Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        Long periodicChargeId = new ChargesHelper().createCharges(ChargesHelper.loanPeriodicChargeRequest(PERIODIC_CHARGE_AMOUNT,
                ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS, 1)).getResourceId();

        PostLoanProductsRequest loanProduct = createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct().numberOfRepayments(numberOfRepayments)
                .repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L).multiDisburseLoan(false)
                .disallowExpectedDisbursements(false).allowApprovedDisbursedAmountsOverApplied(false).overAppliedNumber(null)
                .overAppliedCalculationType(null).charges(List.of(new LoanProductChargeData().id(periodicChargeId)));

        Long loanProductId = loanProductHelper.createLoanProduct(loanProduct).getResourceId();
        Long loanId = applyAndApproveLoan(clientId, loanProductId, LOAN_DISBURSEMENT_DATE, 1000.0, numberOfRepayments, request -> request
                .repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS).loanTermFrequency(numberOfRepayments)
                .loanTermFrequencyType(RepaymentFrequencyType.MONTHS));
        disburseLoan(loanId, BigDecimal.valueOf(1000.0), LOAN_DISBURSEMENT_DATE);

        assertEquals(0, loanTransactionHelper.getLoanCharges(loanId).size());

        GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
        LocalDate firstRepaymentDate = repaymentPeriod(loanDetails, 1).getDueDate();
        return new PeriodicLoanContext(loanId, firstRepaymentDate, null);
    }

    private PeriodicLoanContext createPeriodicLoanWithLinkedSavings(final int numberOfRepayments) {
        Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        SavingsAccountHelper savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsAccountId = createSavingsAccountDailyPosting(savingsAccountHelper, clientId.intValue(), LOAN_DISBURSEMENT_DATE);
        savingsAccountHelper.depositToSavingsAccount(savingsAccountId, "10000", LOAN_DISBURSEMENT_DATE,
                CommonConstants.RESPONSE_RESOURCE_ID);

        Long periodicChargeId = new ChargesHelper()
                .createCharges(ChargesHelper.loanPeriodicChargeRequest(PERIODIC_CHARGE_AMOUNT, ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS,
                        1, ChargePaymentMode.ACCOUNT_TRANSFER.getValue()))
                .getResourceId();

        PostLoanProductsRequest loanProduct = createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct().numberOfRepayments(numberOfRepayments)
                .repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L).multiDisburseLoan(false)
                .disallowExpectedDisbursements(false).allowApprovedDisbursedAmountsOverApplied(false).overAppliedNumber(null)
                .overAppliedCalculationType(null).charges(List.of(new LoanProductChargeData().id(periodicChargeId)));

        Long loanProductId = loanProductHelper.createLoanProduct(loanProduct).getResourceId();
        String loanApplicationJson = new LoanApplicationTestBuilder().withPrincipal("1000.0")
                .withLoanTermFrequency(String.valueOf(numberOfRepayments)).withLoanTermFrequencyAsMonths()
                .withNumberOfRepayments(String.valueOf(numberOfRepayments)).withRepaymentEveryAfter("1")
                .withRepaymentFrequencyTypeAsMonths().withInterestRatePerPeriod("0").withInterestTypeAsDecliningBalance()
                .withAmortizationTypeAsEqualInstallments().withInterestCalculationPeriodTypeSameAsRepaymentPeriod()
                .withExpectedDisbursementDate(LOAN_DISBURSEMENT_DATE).withSubmittedOnDate(LOAN_DISBURSEMENT_DATE)
                .build(clientId.toString(), loanProductId.toString(), savingsAccountId.toString());

        Long submittedLoanId = ((Number) loanTransactionHelper.createLoanAccount(loanApplicationJson, CommonConstants.RESPONSE_RESOURCE_ID))
                .longValue();
        PostLoansLoanIdResponse approvedLoanResult = loanTransactionHelper.approveLoan(submittedLoanId,
                approveLoanRequest(1000.0, LOAN_DISBURSEMENT_DATE));
        Long loanId = approvedLoanResult.getLoanId();
        disburseLoan(loanId, BigDecimal.valueOf(1000.0), LOAN_DISBURSEMENT_DATE);

        GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
        LocalDate firstRepaymentDate = repaymentPeriod(loanDetails, 1).getDueDate();
        return new PeriodicLoanContext(loanId, firstRepaymentDate, savingsAccountId);
    }

    private Integer createSavingsAccountDailyPosting(final SavingsAccountHelper savingsAccountHelper, final Integer clientId,
            final String startDate) {
        Integer savingsProductId = SavingsProductHelper.createSavingsProduct(new SavingsProductHelper()
                .withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsMonthly()
                .withInterestCalculationPeriodTypeAsDailyBalance().withMinimumOpenningBalance("10000.0").build(), requestSpec,
                responseSpec);
        Integer savingsAccountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, savingsProductId,
                SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, startDate);
        savingsAccountHelper.approveSavingsOnDate(savingsAccountId, startDate);
        savingsAccountHelper.activateSavingsAccount(savingsAccountId, startDate);
        return savingsAccountId;
    }

    private GetLoansLoanIdRepaymentPeriod repaymentPeriod(final GetLoansLoanIdResponse loanDetails, final Integer periodNumber) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(period -> Objects.equals(period.getPeriod(), periodNumber))
                .findFirst().orElseThrow();
    }

    private String formatDate(final LocalDate date) {
        return date.format(dateTimeFormatter);
    }

    @SuppressWarnings("rawtypes")
    private Integer ensureLiabilityTransferFinancialActivityMapping() {
        FinancialActivityAccountHelper financialActivityAccountHelper = new FinancialActivityAccountHelper(requestSpec);
        List<HashMap> financialActivities = financialActivityAccountHelper.getAllFinancialActivityAccounts(responseSpec);
        for (HashMap financialActivity : financialActivities) {
            HashMap financialActivityData = (HashMap) financialActivity.get("financialActivityData");
            if (Objects.equals(financialActivityData.get("id"), FinancialActivityAccountsTest.LIABILITY_TRANSFER_FINANCIAL_ACTIVITY_ID)) {
                return null;
            }
        }

        Account liabilityTransferAccount = accountHelper.createLiabilityAccount("periodicChargeTransfer");
        return (Integer) financialActivityAccountHelper.createFinancialActivityAccount(LIABILITY_TRANSFER.getValue(),
                liabilityTransferAccount.getAccountID(), responseSpec, CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private void cleanupFinancialActivityMapping(final Integer financialActivityMappingId) {
        if (financialActivityMappingId == null) {
            return;
        }

        FinancialActivityAccountHelper financialActivityAccountHelper = new FinancialActivityAccountHelper(requestSpec);
        financialActivityAccountHelper.deleteFinancialActivityAccount(financialActivityMappingId, responseSpec,
                CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private record PeriodicLoanContext(Long loanId, LocalDate firstRepaymentDate, Integer savingsAccountId) {
    }
}
