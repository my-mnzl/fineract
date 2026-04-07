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
package org.apache.fineract.integrationtests.loan.repayment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.junit.jupiter.api.Test;

class LoanMonthlyPartialPeriodRegenerationTest extends BaseLoanIntegrationTest {

    private static final double PRINCIPAL = 650_000.00d;
    private static final double ANNUAL_INTEREST_RATE = 25.00d;

    @Test
    void repaymentTriggeredRegenerationKeepsThirtyDayMonthPartialPeriodMathStable() {
        AtomicReference<Long> loanIdRef = new AtomicReference<>();
        AtomicReference<ScheduleSnapshot> scheduleSnapshotRef = new AtomicReference<>();

        runAt("17 February 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            PostLoanProductsRequest productRequest = createOnePeriod30DaysPeriodicAccrualProduct(ANNUAL_INTEREST_RATE) //
                    .currencyCode("EGP") //
                    .principal(PRINCIPAL) //
                    .minPrincipal(PRINCIPAL) //
                    .maxPrincipal(PRINCIPAL) //
                    .outstandingLoanBalance(PRINCIPAL) //
                    .numberOfRepayments(12) //
                    .maxNumberOfRepayments(12) //
                    .repaymentEvery(1) //
                    .repaymentFrequencyType(RepaymentFrequencyType.MONTHS.longValue()) //
                    .interestCalculationPeriodType(InterestCalculationPeriodType.SAME_AS_REPAYMENT_PERIOD) //
                    .allowPartialPeriodInterestCalcualtion(true) //
                    .daysInMonthType(30) //
                    .daysInYearType(360) //
                    .isInterestRecalculationEnabled(true) //
                    .recalculationRestFrequencyType(RecalculationRestFrequencyType.SAME_AS_REPAYMENT_PERIOD) //
                    .recalculationRestFrequencyInterval(1) //
                    .interestRecalculationCompoundingMethod(InterestRecalculationCompoundingMethod.NONE) //
                    .rescheduleStrategyMethod(RescheduleStrategyMethod.REDUCE_EMI_AMOUNT) //
                    .preClosureInterestCalculationStrategy(1) //
                    .loanScheduleType("CUMULATIVE") //
                    .loanScheduleProcessingType("HORIZONTAL") //
                    .disallowExpectedDisbursements(false) //
                    .allowApprovedDisbursedAmountsOverApplied(false) //
                    .overAppliedCalculationType(null) //
                    .overAppliedNumber(null) //
                    .multiDisburseLoan(null) //
                    .charges(Collections.emptyList());

            PostLoanProductsResponse loanProductResponse = loanProductHelper.createLoanProduct(productRequest);

            PostLoansRequest applicationRequest = applyCumulativeLoanRequest(clientId, loanProductResponse.getResourceId(), "2026-02-17",
                    PRINCIPAL, ANNUAL_INTEREST_RATE, 12, request -> request //
                            .dateFormat("yyyy-MM-dd") //
                            .submittedOnDate("2026-02-06") //
                            .expectedDisbursementDate("2026-02-17") //
                            .repaymentsStartingFromDate(LocalDate.of(2026, 4, 1)) //
                            .interestCalculationPeriodType(InterestCalculationPeriodType.SAME_AS_REPAYMENT_PERIOD) //
                            .loanScheduleProcessingType("HORIZONTAL"));

            PostLoansResponse postLoansResponse = loanTransactionHelper.applyLoan(applicationRequest);
            PostLoansLoanIdResponse approvedLoan = loanTransactionHelper.approveLoan(postLoansResponse.getResourceId(),
                    approveLoanRequest(PRINCIPAL, "17 February 2026"));

            loanIdRef.set(approvedLoan.getLoanId());
            disburseLoan(approvedLoan.getLoanId(), BigDecimal.valueOf(PRINCIPAL), "17 February 2026");

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(approvedLoan.getLoanId());
            scheduleSnapshotRef.set(captureScheduleState(loanDetails, false));
        });

        runAt("01 April 2026", () -> {
            Long loanId = loanIdRef.get();
            ScheduleSnapshot scheduleSnapshot = scheduleSnapshotRef.get();

            addRepaymentForLoan(loanId, scheduleSnapshot.totalDue(), "01 April 2026");

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            assertScheduleState(loanDetails, scheduleSnapshot);
        });
    }

    private ScheduleSnapshot captureScheduleState(GetLoansLoanIdResponse loanDetails, boolean completed) {
        GetLoansLoanIdRepaymentPeriod firstInstallment = loanDetails.getRepaymentSchedule().getPeriods().get(1);

        double principalDue = Utils.getDoubleValue(firstInstallment.getPrincipalDue());
        double interestDue = Utils.getDoubleValue(firstInstallment.getInterestDue());
        double totalDue = Utils.getDoubleValue(firstInstallment.getTotalDueForPeriod());
        double totalInterest = Utils.getDoubleValue(loanDetails.getSummary().getInterestCharged());
        double totalExpectedRepayment = Utils.getDoubleValue(loanDetails.getSummary().getTotalExpectedRepayment());

        assertEquals(LocalDate.of(2026, 4, 1), firstInstallment.getDueDate());
        assertEquals(completed, firstInstallment.getComplete());
        assertEquals(completed ? 0.0d : totalDue, Utils.getDoubleValue(firstInstallment.getTotalOutstandingForPeriod()));

        return new ScheduleSnapshot(firstInstallment.getDueDate(), principalDue, interestDue, totalDue, totalInterest,
                totalExpectedRepayment);
    }

    private void assertScheduleState(GetLoansLoanIdResponse loanDetails, ScheduleSnapshot expected) {
        GetLoansLoanIdRepaymentPeriod firstInstallment = loanDetails.getRepaymentSchedule().getPeriods().get(1);

        assertEquals(expected.dueDate(), firstInstallment.getDueDate());
        assertEquals(expected.principalDue(), Utils.getDoubleValue(firstInstallment.getPrincipalDue()));
        assertEquals(expected.interestDue(), Utils.getDoubleValue(firstInstallment.getInterestDue()));
        assertEquals(expected.totalDue(), Utils.getDoubleValue(firstInstallment.getTotalDueForPeriod()));
        assertEquals(true, firstInstallment.getComplete());
        assertEquals(0.0d, Utils.getDoubleValue(firstInstallment.getTotalOutstandingForPeriod()));
        assertEquals(expected.totalInterest(), Utils.getDoubleValue(loanDetails.getSummary().getInterestCharged()));
        assertEquals(expected.totalExpectedRepayment(), Utils.getDoubleValue(loanDetails.getSummary().getTotalExpectedRepayment()));
    }

    private record ScheduleSnapshot(LocalDate dueDate, double principalDue, double interestDue, double totalDue, double totalInterest,
            double totalExpectedRepayment) {
    }
}
