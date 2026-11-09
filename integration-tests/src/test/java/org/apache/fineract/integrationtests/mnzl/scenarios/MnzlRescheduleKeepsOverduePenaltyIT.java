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
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.GetLoansLoanIdChargesChargeIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostCreateRescheduleLoansRequest;
import org.apache.fineract.client.models.PostCreateRescheduleLoansResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostUpdateRescheduleLoansRequest;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.LoanRescheduleRequestHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlAssertions;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlChargesHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.junit.jupiter.api.Test;

/**
 * An overdue penalty that was partly paid and partly waived keeps its amount, payments and waiver through an
 * interest-rate reschedule.
 *
 * <p>
 * Approving a reschedule rebuilds the schedule and calls {@code LoanChargeService.recalculateAllCharges} before the
 * transactions are replayed. Stock Fineract re-derives the overdue penalty there from the rebuilt installment, whose
 * balances are reset, so the penalty is recharged on the whole installment instead of what was overdue when it was
 * charged. MNZL's {@code MnzlLoanChargeService} (toggle {@code mnzl.charge.freeze-overdue-penalties}) keeps it as
 * charged. This drives the configured service end to end: a partial payment leaves part of installment 1 overdue, COB
 * charges the MNZL percentage penalty on that remainder, a repayment pays part of the penalty, the rest is waived, and
 * an interest-rate reschedule is approved from the next unpaid installment.
 * </p>
 *
 * <p>
 * mifos-standard allocates penalties first, and charging the backdated penalty replays the earlier partial payment onto
 * it. The partial payment is therefore kept below the penalty so the penalty stays partly paid; it still lowers what
 * was overdue when the penalty was charged, so a re-derivation on the whole installment changes the amount.
 * </p>
 */
@Slf4j
public class MnzlRescheduleKeepsOverduePenaltyIT extends BaseLoanIntegrationTest {

    private static final String DISBURSED_ON = "01 January 2026";
    private static final String PARTIAL_PAYMENT_ON = "28 January 2026";
    private static final String PENALTY_SETTLED_ON = "20 February 2026";
    private static final double PRINCIPAL = 120000.0;
    private static final double ANNUAL_RATE = 12.0;
    private static final int TERMS = 12;
    private static final double PARTIAL_INSTALLMENT_PAYMENT = 100.0;
    private static final double PENALTY_PAYMENT = 50.0;
    private static final BigDecimal NEW_ANNUAL_RATE = BigDecimal.valueOf(18.0);

    private final LoanRescheduleRequestHelper loanRescheduleRequestHelper = new LoanRescheduleRequestHelper(requestSpec, responseSpec);

    @Test
    public void interestRateReschedule_keepsPartlyPaidAndWaivedOverduePenaltyAsCharged() {
        runAt(DISBURSED_ON, () -> {
            Long clientId = ClientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long penaltyId = new MnzlChargesHelper(requestSpec, responseSpec).createMnzlOverduePenaltyPercent("2").longValue();
            MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                    feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount,
                    interestReceivableAccount, feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount,
                    interestIncomeChargeOffAccount, feeChargeOffAccount, penaltyChargeOffAccount, chargeOffExpenseAccount,
                    chargeOffFraudExpenseAccount);
            Long productId = loanProductHelper.createLoanProduct(builder.withCharges(builder.decliningBalance30_360(), penaltyId))
                    .getResourceId();
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);
            Long loanId = applyApproveDisburseLoan(clientId, productId);

            // Pay part of installment 1 before it falls due, so only the remainder is overdue.
            updateBusinessDate(PARTIAL_PAYMENT_ON);
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", PARTIAL_PAYMENT_ON, PARTIAL_INSTALLMENT_PAYMENT);

            // Well past installment 1's due date and penalty grace: COB charges the overdue penalty on the remainder.
            updateBusinessDate(PENALTY_SETTLED_ON);
            inlineLoanCOBHelper.executeInlineCOB(loanId);
            List<GetLoansLoanIdChargesChargeIdResponse> penalties = getOverdueInstallmentLoanCharges(loanId);
            assertThat(penalties).as("overdue penalties charged by COB").hasSize(1);
            Long penaltyLoanChargeId = penalties.get(0).getId();
            double charged = penalties.get(0).getAmount();
            double paid = PARTIAL_INSTALLMENT_PAYMENT + PENALTY_PAYMENT;
            assertThat(charged).as("penalty must exceed the part that will be paid").isGreaterThan(paid);

            // Pay part of the penalty (mifos-standard allocates penalties first), then waive the rest.
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", PENALTY_SETTLED_ON, PENALTY_PAYMENT);
            waiveLoanCharge(loanId, penaltyLoanChargeId, 1);

            GetLoansLoanIdChargesChargeIdResponse penaltyBefore = findLoanCharge(loanId, penaltyLoanChargeId);
            assertThat(penaltyBefore.getAmount()).as("penalty amount after waiver").isEqualTo(charged, within(0.01));
            assertThat(penaltyBefore.getAmountPaid()).as("penalty paid before reschedule").isEqualTo(paid, within(0.01));
            assertThat(penaltyBefore.getAmountWaived()).as("penalty waived before reschedule").isEqualTo(charged - paid, within(0.01));
            assertThat(penaltyBefore.getAmountOutstanding()).as("penalty outstanding before reschedule").isEqualTo(0.0, within(0.01));
            GetLoansLoanIdResponse loanBefore = loanTransactionHelper.getLoanDetails(loanId);

            // Interest-rate reschedule from the next unpaid installment the overdue-charge rule allows: the first whose
            // period starts on or after the penalty's due date (installment 2 with the default penalty grace).
            String rescheduleFrom = firstInstallmentStartingOnOrAfter(loanBefore, penaltyBefore.getDueDate()).getDueDate()
                    .format(dateTimeFormatter);
            PostCreateRescheduleLoansResponse reschedule = loanRescheduleRequestHelper
                    .createLoanRescheduleRequest(new PostCreateRescheduleLoansRequest().loanId(loanId).dateFormat(DATETIME_PATTERN)
                            .locale("en").submittedOnDate(PENALTY_SETTLED_ON).rescheduleFromDate(rescheduleFrom)
                            .newInterestRate(NEW_ANNUAL_RATE).rescheduleReasonId(1L));
            loanRescheduleRequestHelper.approveLoanRescheduleRequest(reschedule.getResourceId(),
                    new PostUpdateRescheduleLoansRequest().approvedOnDate(PENALTY_SETTLED_ON).dateFormat(DATETIME_PATTERN).locale("en"));

            GetLoansLoanIdResponse loanAfter = loanTransactionHelper.getLoanDetails(loanId);
            assertThat(interestOfInstallmentAfter(loanAfter, rescheduleFrom))
                    .as("reschedule applied: interest after the reschedule date changed")
                    .isNotEqualTo(interestOfInstallmentAfter(loanBefore, rescheduleFrom));

            assertThat(getOverdueInstallmentLoanCharges(loanId)).as("overdue penalties after reschedule").hasSize(1);
            GetLoansLoanIdChargesChargeIdResponse penaltyAfter = findLoanCharge(loanId, penaltyLoanChargeId);
            assertThat(penaltyAfter.getAmount()).as("penalty amount after reschedule").isEqualTo(penaltyBefore.getAmount(), within(0.01));
            assertThat(penaltyAfter.getAmountPaid()).as("penalty paid after reschedule").isEqualTo(penaltyBefore.getAmountPaid(),
                    within(0.01));
            assertThat(penaltyAfter.getAmountWaived()).as("penalty waived after reschedule").isEqualTo(penaltyBefore.getAmountWaived(),
                    within(0.01));
            assertThat(penaltyAfter.getAmountOutstanding()).as("penalty outstanding after reschedule")
                    .isEqualTo(penaltyBefore.getAmountOutstanding(), within(0.01));

            assertPenaltyTotalsUnchanged(loanBefore, loanAfter);
            MnzlAssertions.assertSchedulesEqual(periodsBefore(loanBefore, rescheduleFrom), periodsBefore(loanAfter, rescheduleFrom));
        });
    }

    private void assertPenaltyTotalsUnchanged(GetLoansLoanIdResponse before, GetLoansLoanIdResponse after) {
        assertThat(amount(after.getRepaymentSchedule().getTotalPenaltyChargesCharged())).as("schedule total penalty charged")
                .isEqualTo(amount(before.getRepaymentSchedule().getTotalPenaltyChargesCharged()), within(0.01));
        assertThat(amount(after.getSummary().getPenaltyChargesCharged())).as("summary penalty charged")
                .isEqualTo(amount(before.getSummary().getPenaltyChargesCharged()), within(0.01));
        assertThat(amount(after.getSummary().getPenaltyChargesPaid())).as("summary penalty paid")
                .isEqualTo(amount(before.getSummary().getPenaltyChargesPaid()), within(0.01));
        assertThat(amount(after.getSummary().getPenaltyChargesWaived())).as("summary penalty waived")
                .isEqualTo(amount(before.getSummary().getPenaltyChargesWaived()), within(0.01));
        assertThat(amount(after.getSummary().getPenaltyChargesOutstanding())).as("summary penalty outstanding")
                .isEqualTo(amount(before.getSummary().getPenaltyChargesOutstanding()), within(0.01));

        List<GetLoansLoanIdRepaymentPeriod> periodsBefore = repaymentPeriods(before);
        List<GetLoansLoanIdRepaymentPeriod> periodsAfter = repaymentPeriods(after);
        assertThat(periodsAfter).as("installment count").hasSameSizeAs(periodsBefore);
        for (int i = 0; i < periodsBefore.size(); i++) {
            GetLoansLoanIdRepaymentPeriod b = periodsBefore.get(i);
            GetLoansLoanIdRepaymentPeriod a = periodsAfter.get(i);
            int n = i + 1;
            assertThat(amount(a.getPenaltyChargesDue())).as("installment %d penalty due", n).isEqualTo(amount(b.getPenaltyChargesDue()),
                    within(0.01));
            assertThat(amount(a.getPenaltyChargesPaid())).as("installment %d penalty paid", n).isEqualTo(amount(b.getPenaltyChargesPaid()),
                    within(0.01));
            assertThat(amount(a.getPenaltyChargesWaived())).as("installment %d penalty waived", n)
                    .isEqualTo(amount(b.getPenaltyChargesWaived()), within(0.01));
            assertThat(amount(a.getPenaltyChargesOutstanding())).as("installment %d penalty outstanding", n)
                    .isEqualTo(amount(b.getPenaltyChargesOutstanding()), within(0.01));
        }
    }

    private static double amount(BigDecimal value) {
        return value == null ? 0.0 : Utils.getDoubleValue(value);
    }

    private static List<GetLoansLoanIdRepaymentPeriod> repaymentPeriods(GetLoansLoanIdResponse loan) {
        return loan.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
    }

    private List<GetLoansLoanIdRepaymentPeriod> periodsBefore(GetLoansLoanIdResponse loan, String rescheduleFrom) {
        LocalDate from = LocalDate.parse(rescheduleFrom, dateTimeFormatter);
        return repaymentPeriods(loan).stream().filter(p -> p.getDueDate().isBefore(from)).toList();
    }

    private double interestOfInstallmentAfter(GetLoansLoanIdResponse loan, String rescheduleFrom) {
        LocalDate from = LocalDate.parse(rescheduleFrom, dateTimeFormatter);
        return repaymentPeriods(loan).stream().filter(p -> p.getDueDate().isAfter(from)).findFirst().map(p -> amount(p.getInterestDue()))
                .orElseThrow();
    }

    private static GetLoansLoanIdRepaymentPeriod firstInstallmentStartingOnOrAfter(GetLoansLoanIdResponse loan, LocalDate date) {
        return repaymentPeriods(loan).stream().filter(p -> !p.getFromDate().isBefore(date)).findFirst().orElseThrow();
    }

    private GetLoansLoanIdChargesChargeIdResponse findLoanCharge(Long loanId, Long loanChargeId) {
        return loanTransactionHelper.getLoanCharges(loanId).stream().filter(c -> loanChargeId.equals(c.getId())).findFirst().orElseThrow();
    }

    private Long applyApproveDisburseLoan(Long clientId, Long productId) {
        Long loanId = loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                .principal(BigDecimal.valueOf(PRINCIPAL)).loanTermFrequency(TERMS).loanTermFrequencyType(2).numberOfRepayments(TERMS)
                .repaymentEvery(1).repaymentFrequencyType(2).interestRatePerPeriod(BigDecimal.valueOf(ANNUAL_RATE)).amortizationType(1)
                .interestType(0).interestCalculationPeriodType(1).transactionProcessingStrategyCode("mifos-standard-strategy")
                .expectedDisbursementDate(DISBURSED_ON).submittedOnDate(DISBURSED_ON).dateFormat(DATETIME_PATTERN).locale("en")
                .loanType("individual")).getLoanId();
        loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(PRINCIPAL))
                .dateFormat(DATETIME_PATTERN).approvedOnDate(DISBURSED_ON).locale("en"));
        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(DISBURSED_ON)
                .dateFormat(DATETIME_PATTERN).transactionAmount(BigDecimal.valueOf(PRINCIPAL)).locale("en"));
        return loanId;
    }
}
