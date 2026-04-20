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

import io.restassured.path.json.JsonPath;
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
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.FinancialActivityAccountHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.junit.jupiter.api.Test;

public class LoanPeriodicChargeIntegrationTest extends BaseLoanIntegrationTest {

    private static final double PERIODIC_CHARGE_AMOUNT = 100.0;
    private static final String TRANSFER_FEE_JOB_SHORT_NAME = "LA_TFFS";
    private static final String LOAN_DISBURSEMENT_DATE = "01 January 2024";

    @Test
    public void testPeriodicLoanChargeIsProjectedAtSubmissionForShortLoanWithSingleOccurrence() {
        AtomicReference<PeriodicLoanContext> contextRef = new AtomicReference<>();

        runAt(LOAN_DISBURSEMENT_DATE, () -> contextRef.set(createPeriodicLoan(6)));

        PeriodicLoanContext context = contextRef.get();
        List<GetLoansLoanIdChargesChargeIdResponse> loanCharges = loanTransactionHelper.getLoanCharges(context.loanId());
        assertEquals(1, loanCharges.size());

        GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(context.loanId());
        GetLoansLoanIdRepaymentPeriod firstPeriod = repaymentPeriod(loanDetails, 1);
        assertEquals(context.firstRepaymentDate(), firstPeriod.getDueDate());
        assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesDue()));
        assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesOutstanding()));
    }

    @Test
    public void testPeriodicLoanChargeProjectsAllYearlyOccurrencesAcrossLongLoanTerm() {
        AtomicReference<PeriodicLoanContext> contextRef = new AtomicReference<>();

        runAt(LOAN_DISBURSEMENT_DATE, () -> contextRef.set(createPeriodicLoan(36)));

        PeriodicLoanContext context = contextRef.get();
        List<GetLoansLoanIdChargesChargeIdResponse> loanCharges = loanTransactionHelper.getLoanCharges(context.loanId());
        assertEquals(3, loanCharges.size());

        GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(context.loanId());
        assertFeeChargeOnRepaymentPeriod(loanDetails, context.firstRepaymentDate());
        assertFeeChargeOnRepaymentPeriod(loanDetails, context.firstRepaymentDate().plusYears(1));
        assertFeeChargeOnRepaymentPeriod(loanDetails, context.firstRepaymentDate().plusYears(2));
    }

    @Test
    public void testPeriodicLoanChargeResponsesIncludeRecurrenceMetadata() {
        AtomicReference<PeriodicLoanContext> contextRef = new AtomicReference<>();

        runAt(LOAN_DISBURSEMENT_DATE, () -> contextRef.set(createPeriodicLoan(6)));

        PeriodicLoanContext context = contextRef.get();
        JsonPath templateJson = JsonPath.from(Utils.performServerGet(requestSpec, responseSpec,
                "/fineract-provider/api/v1/loans/template?templateType=individual&clientId=" + context.clientId() + "&productId="
                        + context.loanProductId() + "&" + Utils.TENANT_IDENTIFIER));
        assertEquals(1, templateJson.getList("charges").size());
        assertEquals(1, templateJson.getInt("charges[0].feeInterval"));
        assertEquals(ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS, templateJson.getInt("charges[0].feeFrequency.id"));

        JsonPath loanChargesJson = JsonPath.from(Utils.performServerGet(requestSpec, responseSpec,
                "/fineract-provider/api/v1/loans/" + context.loanId() + "/charges?" + Utils.TENANT_IDENTIFIER));
        assertEquals(1, loanChargesJson.getList("$").size());
        assertEquals(1, loanChargesJson.getInt("[0].feeInterval"));
        assertEquals(ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS, loanChargesJson.getInt("[0].feeFrequency.id"));
    }

    @Test
    public void testCalculateLoanSchedulePreviewProjectsPeriodicChargesAcrossFullTerm() {
        runAt(LOAN_DISBURSEMENT_DATE, () -> {
            final Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            final Long loanProductId = createPeriodicLoanProductWithYearlyCharge();

            final PostLoansResponse preview = loanTransactionHelper.calculateRepaymentScheduleForApplyLoan(
                    applyLoanRequest(clientId, loanProductId, LOAN_DISBURSEMENT_DATE, 1000.0, 36,
                            request -> request.repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS)
                                    .loanTermFrequency(36).loanTermFrequencyType(RepaymentFrequencyType.MONTHS)),
                    "calculateLoanSchedule");

            assertEquals(3, preview.getPeriods().stream()
                    .filter(p -> p.getFeeChargesDue() != null && p.getFeeChargesDue() > 0L).count(),
                    "Yearly periodic charge on a 36-month loan should project exactly 3 occurrences into the preview");
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
                double accountBalanceBefore = ((Number) savingsAccountHelper.getSavingsSummary(context.savingsAccountId())
                        .get("accountBalance")).doubleValue();

                schedulerJobHelper.executeAndAwaitJobByShortName(TRANSFER_FEE_JOB_SHORT_NAME);

                GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(context.loanId());
                GetLoansLoanIdRepaymentPeriod firstPeriod = repaymentPeriod(loanDetails, 1);
                assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesDue()));
                assertEquals(0.0, Utils.getDoubleValue(firstPeriod.getFeeChargesOutstanding()));
                assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(firstPeriod.getFeeChargesPaid()));

                double accountBalanceAfter = ((Number) savingsAccountHelper.getSavingsSummary(context.savingsAccountId())
                        .get("accountBalance")).doubleValue();
                assertEquals(accountBalanceBefore - PERIODIC_CHARGE_AMOUNT, accountBalanceAfter);
            } finally {
                cleanupFinancialActivityMapping(createdFinancialActivityMappingId);
            }
        });
    }

    private Long createPeriodicLoanProductWithYearlyCharge() {
        final Long periodicChargeId = new ChargesHelper()
                .createCharges(ChargesHelper.loanPeriodicChargeRequest(PERIODIC_CHARGE_AMOUNT, ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS, 1))
                .getResourceId();
        final PostLoanProductsRequest loanProduct = createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct()
                .numberOfRepayments(12).repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L).multiDisburseLoan(false)
                .disallowExpectedDisbursements(false).allowApprovedDisbursedAmountsOverApplied(false).overAppliedNumber(null)
                .overAppliedCalculationType(null).charges(List.of(new LoanProductChargeData().id(periodicChargeId)));
        return loanProductHelper.createLoanProduct(loanProduct).getResourceId();
    }

    private PeriodicLoanContext createPeriodicLoan(final int numberOfRepayments) {
        Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        Long periodicChargeId = new ChargesHelper()
                .createCharges(ChargesHelper.loanPeriodicChargeRequest(PERIODIC_CHARGE_AMOUNT, ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS, 1))
                .getResourceId();

        PostLoanProductsRequest loanProduct = createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct()
                .numberOfRepayments(numberOfRepayments).repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L)
                .multiDisburseLoan(false).disallowExpectedDisbursements(false).allowApprovedDisbursedAmountsOverApplied(false)
                .overAppliedNumber(null).overAppliedCalculationType(null)
                .charges(List.of(new LoanProductChargeData().id(periodicChargeId)));

        Long loanProductId = loanProductHelper.createLoanProduct(loanProduct).getResourceId();
        Long loanId = applyAndApproveLoan(clientId, loanProductId, LOAN_DISBURSEMENT_DATE, 1000.0, numberOfRepayments,
                request -> request.repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS)
                        .loanTermFrequency(numberOfRepayments).loanTermFrequencyType(RepaymentFrequencyType.MONTHS));
        disburseLoan(loanId, BigDecimal.valueOf(1000.0), LOAN_DISBURSEMENT_DATE);

        GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
        LocalDate firstRepaymentDate = repaymentPeriod(loanDetails, 1).getDueDate();
        return new PeriodicLoanContext(clientId, loanProductId, loanId, firstRepaymentDate, null);
    }

    private PeriodicLoanContext createPeriodicLoanWithLinkedSavings(final int numberOfRepayments) {
        Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        SavingsAccountHelper savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsAccountId = createSavingsAccountDailyPosting(savingsAccountHelper, clientId.intValue(), LOAN_DISBURSEMENT_DATE);
        savingsAccountHelper.depositToSavingsAccount(savingsAccountId, "10000", LOAN_DISBURSEMENT_DATE,
                CommonConstants.RESPONSE_RESOURCE_ID);

        Long periodicChargeId = new ChargesHelper().createCharges(ChargesHelper.loanPeriodicChargeRequest(PERIODIC_CHARGE_AMOUNT,
                ChargesHelper.CHARGE_FEE_FREQUENCY_YEARS, 1, ChargePaymentMode.ACCOUNT_TRANSFER.getValue())).getResourceId();

        PostLoanProductsRequest loanProduct = createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct()
                .numberOfRepayments(numberOfRepayments).repaymentEvery(1).repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L)
                .multiDisburseLoan(false).disallowExpectedDisbursements(false).allowApprovedDisbursedAmountsOverApplied(false)
                .overAppliedNumber(null).overAppliedCalculationType(null)
                .charges(List.of(new LoanProductChargeData().id(periodicChargeId)));

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
        return new PeriodicLoanContext(clientId, loanProductId, loanId, firstRepaymentDate, savingsAccountId);
    }

    private Integer createSavingsAccountDailyPosting(final SavingsAccountHelper savingsAccountHelper, final Integer clientId,
            final String startDate) {
        Integer savingsProductId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsMonthly()
                        .withInterestCalculationPeriodTypeAsDailyBalance().withMinimumOpenningBalance("10000.0").build(),
                requestSpec, responseSpec);
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

    private void assertFeeChargeOnRepaymentPeriod(final GetLoansLoanIdResponse loanDetails, final LocalDate dueDate) {
        GetLoansLoanIdRepaymentPeriod period = loanDetails.getRepaymentSchedule().getPeriods().stream()
                .filter(p -> dueDate.equals(p.getDueDate())).findFirst().orElseThrow();
        assertEquals(PERIODIC_CHARGE_AMOUNT, Utils.getDoubleValue(period.getFeeChargesDue()));
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

    private record PeriodicLoanContext(Long clientId, Long loanProductId, Long loanId, LocalDate firstRepaymentDate,
            Integer savingsAccountId) {
    }
}
