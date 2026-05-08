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
package org.apache.fineract.integrationtests.mnzl.helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.fineract.client.models.LoanProductChargeData;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest.AmortizationType;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest.InterestCalculationPeriodType;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest.InterestRateFrequencyType;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest.InterestType;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest.RepaymentFrequencyType;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;

/**
 * Builds {@link PostLoanProductsRequest} payloads pre-configured for mnzl-flavored loan products. Each public method
 * returns a fresh request; callers may chain additional {@code PostLoanProductsRequest} setters on the returned object.
 */
public final class MnzlProductBuilder {

    public static final String DATETIME_PATTERN = "dd MMMM yyyy";

    private final Account fundSource;
    private final Account loansReceivableAccount;
    private final Account suspenseAccount;
    private final Account interestIncomeAccount;
    private final Account feeIncomeAccount;
    private final Account penaltyIncomeAccount;
    private final Account recoveriesAccount;
    private final Account writtenOffAccount;
    private final Account overpaymentAccount;
    private final Account interestReceivableAccount;
    private final Account feeReceivableAccount;
    private final Account penaltyReceivableAccount;
    private final Account goodwillExpenseAccount;
    private final Account interestIncomeChargeOffAccount;
    private final Account feeChargeOffAccount;
    private final Account penaltyChargeOffAccount;
    private final Account chargeOffExpenseAccount;
    private final Account chargeOffFraudExpenseAccount;

    public MnzlProductBuilder(Account fundSource, Account loansReceivableAccount, Account suspenseAccount, Account interestIncomeAccount,
            Account feeIncomeAccount, Account penaltyIncomeAccount, Account recoveriesAccount, Account writtenOffAccount,
            Account overpaymentAccount, Account interestReceivableAccount, Account feeReceivableAccount, Account penaltyReceivableAccount,
            Account goodwillExpenseAccount, Account interestIncomeChargeOffAccount, Account feeChargeOffAccount,
            Account penaltyChargeOffAccount, Account chargeOffExpenseAccount, Account chargeOffFraudExpenseAccount) {
        this.fundSource = fundSource;
        this.loansReceivableAccount = loansReceivableAccount;
        this.suspenseAccount = suspenseAccount;
        this.interestIncomeAccount = interestIncomeAccount;
        this.feeIncomeAccount = feeIncomeAccount;
        this.penaltyIncomeAccount = penaltyIncomeAccount;
        this.recoveriesAccount = recoveriesAccount;
        this.writtenOffAccount = writtenOffAccount;
        this.overpaymentAccount = overpaymentAccount;
        this.interestReceivableAccount = interestReceivableAccount;
        this.feeReceivableAccount = feeReceivableAccount;
        this.penaltyReceivableAccount = penaltyReceivableAccount;
        this.goodwillExpenseAccount = goodwillExpenseAccount;
        this.interestIncomeChargeOffAccount = interestIncomeChargeOffAccount;
        this.feeChargeOffAccount = feeChargeOffAccount;
        this.penaltyChargeOffAccount = penaltyChargeOffAccount;
        this.chargeOffExpenseAccount = chargeOffExpenseAccount;
        this.chargeOffFraudExpenseAccount = chargeOffFraudExpenseAccount;
    }

    /** Standard mnzl declining balance product with 30/360 day count. */
    public PostLoanProductsRequest decliningBalance30_360() {
        return base("MNZL declining 30/360").daysInMonthType(30).daysInYearType(360);
    }

    /** Standard mnzl declining balance product with 30/365 day count. */
    public PostLoanProductsRequest decliningBalance30_365() {
        return base("MNZL declining 30/365").daysInMonthType(30).daysInYearType(365);
    }

    /** Standard mnzl declining balance product with ACTUAL/ACTUAL day count (Fineract enum value 1 = ACTUAL). */
    public PostLoanProductsRequest decliningBalanceActual() {
        return base("MNZL declining ACTUAL").daysInMonthType(1).daysInYearType(1);
    }

    /**
     * Balloon variant. Same product shape as {@link #decliningBalance30_360()} — the balloon flag is selected via the
     * {@code MNZL_BALLOON_LOAN} instrument code on the strategy table, not via the product config itself. Use this
     * method via {@link MnzlProductStrategyHelper#setMnzlBalloon} to pin the strategy after creation.
     */
    public PostLoanProductsRequest balloon30_360() {
        return decliningBalance30_360();
    }

    /**
     * Replaces the charges on {@code base} with the supplied charge IDs and returns the same instance. Unlike the
     * variant factory methods on this class, this is a mutator — call it as the last step in the chain when you've
     * already chosen which variant to use. Existing charges on {@code base} are discarded.
     */
    public PostLoanProductsRequest withCharges(PostLoanProductsRequest base, Long... chargeIds) {
        List<LoanProductChargeData> charges = new ArrayList<>();
        for (Long id : Arrays.asList(chargeIds)) {
            charges.add(new LoanProductChargeData().id(id));
        }
        base.charges(charges);
        return base;
    }

    private PostLoanProductsRequest base(String description) {
        return new PostLoanProductsRequest().name(Utils.uniqueRandomStringGenerator("MNZL_TEST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description(description).currencyCode("USD").digitsAfterDecimal(2)
                .principal(120000.0).minPrincipal(1000.0).maxPrincipal(1000000.0).numberOfRepayments(12).repaymentEvery(1)
                .repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L).interestRatePerPeriod(12.0)
                .interestRateFrequencyType(InterestRateFrequencyType.YEARS).amortizationType(AmortizationType.EQUAL_INSTALLMENTS)
                .interestType(InterestType.DECLINING_BALANCE)
                .interestCalculationPeriodType(InterestCalculationPeriodType.SAME_AS_REPAYMENT_PERIOD).includeInBorrowerCycle(false)
                .useBorrowerCycle(false).isLinkedToFloatingInterestRates(false).allowVariableInstallments(false)
                .allowPartialPeriodInterestCalcualtion(false).isInterestRecalculationEnabled(false).canDefineInstallmentAmount(false)
                .holdGuaranteeFunds(false).isEqualAmortization(false).canUseForTopup(false).multiDisburseLoan(false)
                .enableDownPayment(false).enableInstallmentLevelDelinquency(false).enableAccrualActivityPosting(false).overdueDaysForNPA(5)
                .accountMovesOutOfNPAOnlyOnArrearsCompletion(false).repaymentStartDateType(1).charges(List.of())
                .principalVariationsForBorrowerCycle(List.of()).interestRateVariationsForBorrowerCycle(List.of())
                .numberOfRepaymentVariationsForBorrowerCycle(List.of()).loanScheduleType(LoanScheduleType.CUMULATIVE.toString())
                .transactionProcessingStrategyCode("mifos-standard-strategy").accountingRule(3) // accrual periodic
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
                // TODO(mnzl): mirrors a pre-existing bug at MnzlDecliningBalanceLoanIntegrationTest.java:397
                // — should likely be penaltyChargeOffAccount; preserved here so any fix lands in one place
                .incomeFromGoodwillCreditPenaltyAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffInterestAccountId(interestIncomeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffFeesAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffPenaltyAccountId(penaltyChargeOffAccount.getAccountID().longValue())
                .chargeOffExpenseAccountId(chargeOffExpenseAccount.getAccountID().longValue())
                .chargeOffFraudExpenseAccountId(chargeOffFraudExpenseAccount.getAccountID().longValue()).dateFormat(DATETIME_PATTERN)
                .locale("en");
    }
}
