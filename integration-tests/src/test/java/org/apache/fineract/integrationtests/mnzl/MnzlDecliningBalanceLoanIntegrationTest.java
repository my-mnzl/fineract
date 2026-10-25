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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.BusinessDateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.GetLoansLoanIdTransactions;
import org.apache.fineract.client.models.LoanProductChargeData;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.PeriodicAccrualAccountingHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Mnzl custom declining balance loan schedule with 30/360 day count convention.
 *
 * Tests verify that: - {@code CustomLoanScheduleGeneratorFactory} routes to the custom generator when
 * MNZL_DECLINING_BALANCE strategy is configured via {@code m_mnzl_loan_product_strategy} -
 * {@code CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator} produces correct 30/360 interest calculations -
 * The custom schedule differs from core Fineract default (actual days) - Late payments with penalty charges work
 * correctly with the custom charge calculator
 */
@Slf4j
public class MnzlDecliningBalanceLoanIntegrationTest extends BaseLoanIntegrationTest {

    private static final String MNZL_STRATEGY_URL = "/fineract-provider/api/v1/mnzl/loan-products/%d/strategies?" + Utils.TENANT_IDENTIFIER;

    // Default working week (all 7 days = nothing reschedules) so the working-day-grace tests can restore it.
    private static final String DEFAULT_WORKING_DAYS_RECURRENCE = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA,SU";

    // Holidays created by the working-day-grace tests, deleted in @AfterEach so they don't leak into other tests.
    private final List<Integer> createdHolidayIds = new ArrayList<>();

    /**
     * The working-day-grace tests mutate global state shared across the whole integration-test database — the working
     * week, penalty-wait-period, backdate/business-date flags, and an office holiday. Restore all of it after every
     * test so later tests (schedule and penalty assertions) are not affected.
     */
    @AfterEach
    void restoreGlobalConfigAndHolidays() {
        setWorkingDays(DEFAULT_WORKING_DAYS_RECURRENCE, 1);
        globalConfigurationHelper.resetAllDefaultGlobalConfigurations();
        for (Integer holidayId : createdHolidayIds) {
            Utils.performServerDelete(requestSpec, responseSpec,
                    "/fineract-provider/api/v1/holidays/" + holidayId + "?" + Utils.TENANT_IDENTIFIER, "{}", "resourceId");
        }
        createdHolidayIds.clear();
    }

    /**
     * Test 1: Standard declining balance loan with 30/360 — full happy path.
     *
     * Creates a loan product with MNZL_DECLINING_BALANCE schedule strategy, applies for a 12-month declining balance
     * loan at 12% annual rate, disburses it, validates the schedule uses 30/360 day count, makes all repayments on
     * time, and verifies the loan closes with zero balance.
     */
    @Test
    public void testStandardDecliningBalance30_360HappyPath() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            PostLoanProductsResponse loanProduct = createMnzlDecliningBalanceProduct();
            Long productId = loanProduct.getResourceId();
            setMnzlProductStrategy(productId);

            Long loanId = applyApproveDisburseLoan(clientId, productId, 120000.0, 12.0, 12, "01 January 2026");

            // Validate schedule structure
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());

            List<GetLoansLoanIdRepaymentPeriod> repaymentPeriods = getRepaymentPeriods(loanDetails);
            assertEquals(12, repaymentPeriods.size(), "Should have 12 repayment periods");

            // Total principal across all periods equals disbursed amount
            double totalPrincipal = repaymentPeriods.stream().mapToDouble(p -> Utils.getDoubleValue(p.getPrincipalDue())).sum();
            assertEquals(120000.0, totalPrincipal, 0.01, "Total principal should equal disbursed amount");

            // Interest is declining (declining balance)
            double firstPeriodInterest = Utils.getDoubleValue(repaymentPeriods.get(0).getInterestDue());
            double lastPeriodInterest = Utils.getDoubleValue(repaymentPeriods.get(11).getInterestDue());
            assertTrue(firstPeriodInterest > 0, "First period should have interest");
            assertTrue(firstPeriodInterest > lastPeriodInterest, "Interest should decline over time in declining balance");

            // 30/360: first period interest = 120000 * (12/100) * (30/360) = 1200.00
            assertEquals(1200.0, firstPeriodInterest, 0.01, "First period interest should be 120000 * 12% * 30/360 = 1200");

            // Due dates are monthly
            assertEquals(LocalDate.of(2026, 2, 1), repaymentPeriods.get(0).getDueDate());
            assertEquals(LocalDate.of(2027, 1, 1), repaymentPeriods.get(11).getDueDate());

            // Make all repayments on time
            for (GetLoansLoanIdRepaymentPeriod period : repaymentPeriods) {
                double totalDue = Utils.getDoubleValue(period.getTotalDueForPeriod());
                String dueDate = period.getDueDate().format(dateTimeFormatter);
                businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                        .date(dueDate).dateFormat(DATETIME_PATTERN).locale("en"));
                loanTransactionHelper.makeLoanRepayment(loanId, "repayment", dueDate, totalDue);
            }

            // Verify loan is closed
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getClosedObligationsMet());
            assertEquals(0.0, Utils.getDoubleValue(loanDetails.getSummary().getTotalOutstanding()),
                    "Total outstanding should be zero after full repayment");
        });
    }

    /**
     * Test 2: Late payment with penalty charges.
     *
     * Creates a loan, makes first two payments on time, misses the third, advances the business date past the due date,
     * runs inline COB, adds a penalty charge, and verifies the loan state reflects the overdue installment with penalty
     * charges.
     */
    @Test
    public void testLatePaymentWithPenaltyCharges() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            // Create overdue penalty charge (1% of outstanding amount + interest)
            Integer penaltyChargeId = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanOverdueFeeJSONWithCalculationTypePercentage("1"));

            // Create product with the penalty charge baked in — COB auto-applies it
            PostLoanProductsResponse loanProduct = loanProductHelper.createLoanProduct(
                    buildDecliningBalanceProduct(30, 360).charges(List.of(new LoanProductChargeData().id(penaltyChargeId.longValue()))));
            Long productId = loanProduct.getResourceId();
            setMnzlProductStrategy(productId);

            Long loanId = applyApproveDisburseLoan(clientId, productId, 120000.0, 12.0, 12, "01 January 2026");

            // Get schedule
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            List<GetLoansLoanIdRepaymentPeriod> repaymentPeriods = getRepaymentPeriods(loanDetails);

            // Pay period 1 on time (due 01 Feb)
            double period1Due = Utils.getDoubleValue(repaymentPeriods.get(0).getTotalDueForPeriod());
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("01 February 2026").dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "01 February 2026", period1Due);

            // Pay period 2 on time (due 01 Mar)
            double period2Due = Utils.getDoubleValue(repaymentPeriods.get(1).getTotalDueForPeriod());
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("01 March 2026").dateFormat(DATETIME_PATTERN).locale("en"));
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "01 March 2026", period2Due);

            // Skip period 3 (due 01 Apr) — advance business date past due date
            businessDateHelper.updateBusinessDate(new BusinessDateUpdateRequest().type(BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE)
                    .date("05 April 2026").dateFormat(DATETIME_PATTERN).locale("en"));

            // Run inline COB to trigger overdue penalty auto-application
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));

            // Verify loan state after COB — penalty should be auto-applied
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            verifyLoanStatus(loanDetails, status -> status.getActive());

            // Period 3 should be unpaid
            GetLoansLoanIdRepaymentPeriod period3 = loanDetails.getRepaymentSchedule().getPeriods().stream()
                    .filter(p -> p.getPeriod() != null && p.getPeriod() == 3).findFirst().orElseThrow();
            assertTrue(Utils.getDoubleValue(period3.getPrincipalOutstanding()) > 0,
                    "Period 3 principal should be outstanding (missed payment)");
            assertTrue(Utils.getDoubleValue(period3.getInterestOutstanding()) > 0, "Period 3 interest should be outstanding");

            // Total outstanding should include the missed installment
            double totalOutstanding = Utils.getDoubleValue(loanDetails.getSummary().getTotalOutstanding());
            assertTrue(totalOutstanding > 0, "Total outstanding should be > 0 after missed payment");

            // Penalty charges should be present (auto-applied by COB)
            double penaltyOutstanding = Utils.getDoubleValue(loanDetails.getSummary().getPenaltyChargesOutstanding());
            assertTrue(penaltyOutstanding > 0, "Penalty charges should be auto-applied by COB for overdue installment");

            log.info("Period 3 outstanding principal: {}", Utils.getDoubleValue(period3.getPrincipalOutstanding()));
            log.info("Period 3 outstanding interest: {}", Utils.getDoubleValue(period3.getInterestOutstanding()));
            log.info("Total penalty outstanding: {}", penaltyOutstanding);
            log.info("Total loan outstanding: {}", totalOutstanding);

            // Now make the late payment covering period 3 + penalty
            double period3TotalDue = Utils.getDoubleValue(period3.getTotalDueForPeriod());
            loanTransactionHelper.makeLoanRepayment(loanId, "repayment", "05 April 2026", period3TotalDue + penaltyOutstanding);

            // Verify period 3 is now paid
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            period3 = loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() == 3)
                    .findFirst().orElseThrow();
            assertEquals(0.0, Utils.getDoubleValue(period3.getPrincipalOutstanding()),
                    "Period 3 principal should be paid after late repayment");
        });
    }

    /**
     * Test 2b: Working-day grace on the overdue-penalty COB job — reproduces Mnzl staging loan 39550.
     *
     * Egyptian working week (Sun–Thu) with MOVE_TO_NEXT_WORKING_DAY rescheduling, {@code penalty-wait-period = 4} (the
     * due date plus four days: five working days, the due date included), an active office-1 holiday 24–28 June 2026,
     * and a loan whose first installment falls due Sunday 21 June 2026 (disbursed 21 May 2026, monthly). 21 June is a
     * Sunday — a working day in the Sun–Thu week — so it is not rescheduled, and it counts.
     *
     * <p>
     * Counting 5 <em>working</em> days from 21 June, skipping Fri/Sat weekends AND the 24–28 June holiday:
     *
     * <pre>
     *   Jun 21 Sun  working day 1 (due date)
     *   Jun 22 Mon  working day 2
     *   Jun 23 Tue  working day 3
     *   Jun 24 Wed  HOLIDAY
     *   Jun 25 Thu  HOLIDAY
     *   Jun 26 Fri  weekend
     *   Jun 27 Sat  weekend
     *   Jun 28 Sun  HOLIDAY
     *   Jun 29 Mon  working day 4
     *   Jun 30 Tue  working day 5
     *   Jul 01 Wed  penalty first applies here
     * </pre>
     *
     * The overdue-penalty COB step evaluates the loan as of the COB date (= business date - 1), so the penalty must be
     * ABSENT while the COB date is 28 June (business date 29 June — where upstream's calendar grace, over since 26
     * June, would already charge) and PRESENT once the COB date reaches 01 July (business date 02 July).
     */
    @Test
    public void testOverduePenaltyGraceCountsWorkingDaysSkippingWeekendsAndHolidays() {
        runAt("21 May 2026", () -> {
            // Egyptian working week with MOVE_TO_NEXT_WORKING_DAY rescheduling (matches staging m_working_days).
            setWorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU,MO,TU,WE,TH", 2);
            // Ops penalty wait period: the due date plus four days.
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.PENALTY_WAIT_PERIOD,
                    new PutGlobalConfigurationsRequest().value(4L));
            // Active office-1 holiday spanning the same dates as the staging "testing" holiday.
            createAndActivateHoliday("24 June 2026", "28 June 2026");

            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Integer penaltyChargeId = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanOverdueFeeJSONWithCalculationTypePercentage("1"));
            PostLoanProductsResponse product = loanProductHelper.createLoanProduct(
                    buildDecliningBalanceProduct(30, 360).charges(List.of(new LoanProductChargeData().id(penaltyChargeId.longValue()))));
            setMnzlProductStrategy(product.getResourceId());

            // Disburse 21 May 2026 -> installment 1 due Sunday 21 June 2026 (matches staging loan 39550).
            Long loanId = applyApproveDisburseLoan(clientId, product.getResourceId(), 120000.0, 12.0, 12, "21 May 2026");
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(LocalDate.of(2026, 6, 21), firstInstallmentDueDate(loan), "First installment should be due Sunday 21 June 2026");

            // The overdue-penalty COB step reads the COB date (= business date - 1), so a business date of D evaluates
            // the loan as of COB date D-1.

            // (1) Business date 29 June -> COB date 28 June. Upstream's calendar grace ended 26 June and WOULD charge
            // here; the working-day grace (24-28 June holiday + Fri/Sat) runs to 30 June, so there must still be NO
            // penalty.
            updateBusinessDate("29 June 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()), 0.0001,
                    "No penalty while COB date is 28 June: holiday 24-28 + weekend push the 5th working day to 01 July");

            // (2) Business date 01 July -> COB date 30 June = the 5th working day, still inside the grace. No penalty.
            updateBusinessDate("01 July 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()), 0.0001,
                    "No penalty on the 5th working day (30 June): the grace includes it");

            // (3) Business date 02 July -> COB date 01 July, the day after the grace. Applies.
            updateBusinessDate("02 July 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertTrue(Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()) > 0,
                    "Overdue penalty should be applied on 01 July, the day after the 5th working day");
        });
    }

    /**
     * Test 2c: Working-day grace via the standalone "Apply penalty to overdue loans" scheduled job — the exact job run
     * on Mnzl staging (not COB). Same staging config as the COB test above. Unlike the COB step, this job evaluates the
     * loan as of the business date directly (no COB-date lag), so the penalty must be ABSENT at business date 29 June
     * (where the calendar-day query already surfaces the installment and a holiday-blind run would charge) and on 30
     * June (the 5th working day), and PRESENT at business date 01 July. {@code backdate-penalties-enabled = true} so
     * the job's query keeps surfacing the installment up to the working-day date.
     */
    @Test
    public void testScheduledJobOverduePenaltyHonoursWorkingDayGrace() {
        runAt("21 May 2026", () -> {
            setWorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU,MO,TU,WE,TH", 2);
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.PENALTY_WAIT_PERIOD,
                    new PutGlobalConfigurationsRequest().value(4L));
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.BACKDATE_PENALTIES_ENABLED,
                    new PutGlobalConfigurationsRequest().enabled(true));
            createAndActivateHoliday("24 June 2026", "28 June 2026");

            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Integer penaltyChargeId = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanOverdueFeeJSONWithCalculationTypePercentage("1"));
            PostLoanProductsResponse product = loanProductHelper.createLoanProduct(
                    buildDecliningBalanceProduct(30, 360).charges(List.of(new LoanProductChargeData().id(penaltyChargeId.longValue()))));
            setMnzlProductStrategy(product.getResourceId());

            Long loanId = applyApproveDisburseLoan(clientId, product.getResourceId(), 120000.0, 12.0, 12, "21 May 2026");
            assertEquals(LocalDate.of(2026, 6, 21), firstInstallmentDueDate(loanTransactionHelper.getLoanDetails(loanId)),
                    "First installment should be due Sunday 21 June 2026");

            // (1) Business date 29 June: calendar grace (over since 26 June) has elapsed and the job's query surfaces
            // the installment, but the working-day grace runs to 30 June -> the aspect must withhold. NO penalty.
            updateBusinessDate("29 June 2026");
            schedulerJobHelper.executeAndAwaitJob("Apply penalty to overdue loans");
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()), 0.0001,
                    "Scheduled job must NOT charge on 29 June: working-day grace runs to 30 June");

            // (2) Business date 30 June = the 5th working day, still inside the grace. No penalty.
            updateBusinessDate("30 June 2026");
            schedulerJobHelper.executeAndAwaitJob("Apply penalty to overdue loans");
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()), 0.0001,
                    "Scheduled job must NOT charge on the 5th working day (30 June)");

            // (3) Business date 01 July = the day after the grace. Penalty applies.
            updateBusinessDate("01 July 2026");
            schedulerJobHelper.executeAndAwaitJob("Apply penalty to overdue loans");
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertTrue(Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()) > 0,
                    "Scheduled job should charge on 01 July, the day after the 5th working day");
        });
    }

    /**
     * Test 2d: Working-day grace on arrears ageing via the standalone "Update Loan Arrears Ageing" job (the path
     * production uses). Reproduces staging loan 120944: Sun–Thu week, {@code graceOnArrearsAgeing = 3}, installment 1
     * due Thursday 12 November 2026. Upstream counts the grace in calendar days and reports the loan overdue from 16
     * November; counting the due date plus three days as four working days (Thu 12, Sun 15, Mon 16, Tue 17), it must
     * stay out of arrears until 18 November.
     */
    @Test
    public void testArrearsAgeingJobCountsGraceInWorkingDays() {
        runAt("12 October 2026", () -> {
            setWorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU,MO,TU,WE,TH", 2);

            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            PostLoanProductsResponse product = loanProductHelper
                    .createLoanProduct(buildDecliningBalanceProduct(30, 360).graceOnArrearsAgeing(3));
            setMnzlProductStrategy(product.getResourceId());
            Long loanId = applyApproveDisburseLoan(clientId, product.getResourceId(), 120000.0, 12.0, 12, "12 October 2026");
            assertEquals(LocalDate.of(2026, 11, 12), firstInstallmentDueDate(loanTransactionHelper.getLoanDetails(loanId)),
                    "First installment should be due Thursday 12 November 2026");

            // (1) 17 November: calendar grace ended 15 November, working-day grace runs through Tuesday 17 November.
            updateBusinessDate("17 November 2026");
            schedulerJobHelper.executeAndAwaitJob("Update Loan Arrears Ageing");
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getTotalOverdue()), 0.0001,
                    "Not in arrears on 17 November: the Fri/Sat weekend extends the 3-day grace to Tuesday 17 November");

            // (2) 18 November: grace has elapsed.
            updateBusinessDate("18 November 2026");
            schedulerJobHelper.executeAndAwaitJob("Update Loan Arrears Ageing");
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertTrue(Utils.getDoubleValue(loan.getSummary().getTotalOverdue()) > 0, "In arrears from 18 November");
            assertEquals(LocalDate.of(2026, 11, 12), loan.getSummary().getOverdueSinceDate(), "Overdue since the 12 November due date");
        });
    }

    /**
     * Test 2e: A recurring ("monthly") overdue penalty that upstream would repeat on a non-working day is charged on
     * the next working day instead. Installment 1 is due Thursday 12 November 2026 with {@code penalty-wait-period = 5}
     * (six working days with the due date: 12 to 19 November, first penalty Friday 20 November): upstream's occurrences
     * start 18 November and repeat monthly, so the second lands on Friday 18 December. With a Sun–Thu week it must wait
     * for Sunday 20 December. Upstream dates that repeat charge 12 December (due date + one interval), which is how it
     * is told apart from installment 2's own penalty.
     */
    @Test
    public void testRecurringOverduePenaltyRollsOffNonWorkingDay() {
        runAt("12 October 2026", () -> {
            setWorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU,MO,TU,WE,TH", 2);
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.PENALTY_WAIT_PERIOD,
                    new PutGlobalConfigurationsRequest().value(5L));
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.BACKDATE_PENALTIES_ENABLED,
                    new PutGlobalConfigurationsRequest().enabled(true));

            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Integer penaltyChargeId = ChargesHelper.createCharges(requestSpec, responseSpec, ChargesHelper
                    .getLoanOverdueFeeJSONWithCalculationTypePercentageWithFeeInterval("2", ChargesHelper.CHARGE_FEE_FREQUENCY_MONTHS, 1));
            PostLoanProductsResponse product = loanProductHelper.createLoanProduct(
                    buildDecliningBalanceProduct(30, 360).charges(List.of(new LoanProductChargeData().id(penaltyChargeId.longValue()))));
            setMnzlProductStrategy(product.getResourceId());
            Long loanId = applyApproveDisburseLoan(clientId, product.getResourceId(), 120000.0, 12.0, 12, "12 October 2026");

            // (1) Close through Friday 20 November, the day after the grace (19 November): the first penalty.
            updateBusinessDate("21 November 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            assertTrue(penaltyDueDates(loanId, penaltyChargeId).contains(LocalDate.of(2026, 11, 12)),
                    "First penalty should be charged on 20 November");

            // (2) Close through Friday 18 December, where upstream would charge the repeat: it must be withheld.
            updateBusinessDate("19 December 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            assertTrue(!penaltyDueDates(loanId, penaltyChargeId).contains(LocalDate.of(2026, 12, 12)),
                    "Repeat penalty must not be charged on Friday 18 December or Saturday 19 December");

            // (3) Close through Sunday 20 December: the next working day, so the repeat is charged.
            updateBusinessDate("21 December 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            assertTrue(penaltyDueDates(loanId, penaltyChargeId).contains(LocalDate.of(2026, 12, 12)),
                    "Repeat penalty should be charged on Sunday 20 December");
        });
    }

    /**
     * Test 3: Verify MNZL 30/360 interest formula across all repayment periods.
     *
     * Each period's interest must equal outstanding_principal * 12% * 30/360 (i.e., 1% monthly rate). This validates
     * the custom schedule generator uses 30/360 day counting for every period, not just the first.
     */
    @Test
    public void testMnzlScheduleFollows30_360FormulaAcrossAllPeriods() {
        runAt("01 January 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

            PostLoanProductsResponse mnzlProduct = createMnzlDecliningBalanceProduct();
            setMnzlProductStrategy(mnzlProduct.getResourceId());

            Long loanId = applyApproveDisburseLoan(clientId, mnzlProduct.getResourceId(), 120000.0, 12.0, 12, "01 January 2026");

            List<GetLoansLoanIdRepaymentPeriod> periods = getRepaymentPeriods(loanTransactionHelper.getLoanDetails(loanId));
            assertEquals(12, periods.size(), "Should have 12 repayment periods");

            // 30/360 monthly rate = 12% * 30/360 = 1%
            double monthlyRate = 0.01;
            double outstandingPrincipal = 120000.0;

            for (int i = 0; i < periods.size(); i++) {
                GetLoansLoanIdRepaymentPeriod period = periods.get(i);
                double expectedInterest = Math.round(outstandingPrincipal * monthlyRate * 100.0) / 100.0;
                double actualInterest = Utils.getDoubleValue(period.getInterestDue());

                assertEquals(expectedInterest, actualInterest, 0.01,
                        "Period " + (i + 1) + " interest should match 30/360 formula: " + outstandingPrincipal + " * 1%");

                // Reduce outstanding by principal paid this period
                outstandingPrincipal -= Utils.getDoubleValue(period.getPrincipalDue());
            }

            // Outstanding should be zero after all periods
            assertEquals(0.0, outstandingPrincipal, 0.01, "All principal should be allocated across 12 periods");
        });
    }

    // ---- Helper methods ----

    private List<GetLoansLoanIdRepaymentPeriod> getRepaymentPeriods(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
    }

    private List<LocalDate> penaltyDueDates(Long loanId, Integer penaltyChargeId) {
        var charges = loanTransactionHelper.getLoanDetails(loanId).getCharges();
        if (charges == null) {
            return List.of();
        }
        return charges.stream().filter(charge -> penaltyChargeId.longValue() == charge.getChargeId()).map(charge -> charge.getDueDate())
                .toList();
    }

    private LocalDate firstInstallmentDueDate(GetLoansLoanIdResponse loanDetails) {
        return loanDetails.getRepaymentSchedule().getPeriods().stream().filter(p -> p.getPeriod() != null && p.getPeriod() == 1).findFirst()
                .orElseThrow().getDueDate();
    }

    private void setWorkingDays(String recurrence, int repaymentRescheduleType) {
        Map<String, Object> body = new HashMap<>();
        body.put("recurrence", recurrence);
        body.put("locale", "en");
        body.put("repaymentRescheduleType", repaymentRescheduleType);
        body.put("extendTermForDailyRepayments", false);
        Utils.performServerPut(requestSpec, responseSpec, "/fineract-provider/api/v1/workingdays?" + Utils.TENANT_IDENTIFIER,
                new Gson().toJson(body), "");
    }

    private void createAndActivateHoliday(String fromDate, String toDate) {
        Map<String, Object> office = new HashMap<>();
        office.put("officeId", "1");
        List<Map<String, Object>> offices = new ArrayList<>();
        offices.add(office);
        Map<String, Object> body = new HashMap<>();
        body.put("offices", offices);
        body.put("locale", "en");
        body.put("dateFormat", DATETIME_PATTERN);
        // Name is unique to dodge the m_holiday.name constraint across reruns; it does not affect grace behaviour.
        body.put("name", Utils.uniqueRandomStringGenerator("testing_", 5));
        body.put("fromDate", fromDate);
        body.put("toDate", toDate);
        body.put("reschedulingType", 1); // RESCHEDULE_TO_NEXT_REPAYMENT_DATE (matches staging); no due date hits the
                                         // holiday window
        Integer holidayId = Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/holidays?" + Utils.TENANT_IDENTIFIER, new Gson().toJson(body), "resourceId");
        Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/holidays/" + holidayId + "?command=activate&" + Utils.TENANT_IDENTIFIER, "{}", "resourceId");
        createdHolidayIds.add(holidayId);
    }

    private PostLoanProductsResponse createMnzlDecliningBalanceProduct() {
        return loanProductHelper.createLoanProduct(buildDecliningBalanceProduct(30, 360));
    }

    private PostLoanProductsRequest buildDecliningBalanceProduct(int daysInMonth, int daysInYear) {
        return new PostLoanProductsRequest().name(Utils.uniqueRandomStringGenerator("MNZL_TEST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4))
                .description("Declining balance test product " + daysInMonth + "/" + daysInYear).currencyCode("USD").digitsAfterDecimal(2)
                .principal(120000.0).minPrincipal(1000.0).maxPrincipal(1000000.0).numberOfRepayments(12).repaymentEvery(1)
                .repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L).interestRatePerPeriod(12.0)
                .interestRateFrequencyType(InterestRateFrequencyType.YEARS).amortizationType(AmortizationType.EQUAL_INSTALLMENTS)
                .interestType(InterestType.DECLINING_BALANCE)
                .interestCalculationPeriodType(InterestCalculationPeriodType.SAME_AS_REPAYMENT_PERIOD).daysInMonthType(daysInMonth)
                .daysInYearType(daysInYear).includeInBorrowerCycle(false).useBorrowerCycle(false).isLinkedToFloatingInterestRates(false)
                .allowVariableInstallments(false).allowPartialPeriodInterestCalcualtion(false).isInterestRecalculationEnabled(false)
                .canDefineInstallmentAmount(false).holdGuaranteeFunds(false).isEqualAmortization(false).canUseForTopup(false)
                .multiDisburseLoan(false).enableDownPayment(false).enableInstallmentLevelDelinquency(false)
                .enableAccrualActivityPosting(false).overdueDaysForNPA(5).accountMovesOutOfNPAOnlyOnArrearsCompletion(false)
                .repaymentStartDateType(1).charges(List.of()).principalVariationsForBorrowerCycle(List.of())
                .interestRateVariationsForBorrowerCycle(List.of()).numberOfRepaymentVariationsForBorrowerCycle(List.of())
                .loanScheduleType(LoanScheduleType.CUMULATIVE.toString()).transactionProcessingStrategyCode("mifos-standard-strategy")
                .accountingRule(3) // accrual periodic
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
                .incomeFromGoodwillCreditPenaltyAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffInterestAccountId(interestIncomeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffFeesAccountId(feeChargeOffAccount.getAccountID().longValue())
                .incomeFromChargeOffPenaltyAccountId(penaltyChargeOffAccount.getAccountID().longValue())
                .chargeOffExpenseAccountId(chargeOffExpenseAccount.getAccountID().longValue())
                .chargeOffFraudExpenseAccountId(chargeOffFraudExpenseAccount.getAccountID().longValue()).dateFormat(DATETIME_PATTERN)
                .locale("en");
    }

    /**
     * Rolling out 30-day-month accruals on a loan already accrued on actual days.
     *
     * A 15 Aug -> 15 Sep instalment accrued on actual days has 16 of 31 days behind it on 31 Aug, above the 15 of 30
     * that 30-day months give. Switching the product to the MNZL strategy (what deploying the change does) must post
     * nothing while the loan is ahead — never a negative or adjustment entry — post nothing again on a rerun, catch up
     * once the target passes what is booked, and have accrued exactly the instalment's interest by its due date, in
     * transactions and in the interest income ledger alike.
     */
    @Test
    public void testAccrualSwitchedTo30DayMonthsPausesThenCatchesUp() {
        runAt("15 August 2026", () -> {
            Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            Long productId = createMnzlDecliningBalanceProduct().getResourceId();
            setMnzlProductStrategy(productId);
            Long loanId = applyApproveDisburseLoan(clientId, productId, 120000.0, 12.0, 12, "15 August 2026");
            GetLoansLoanIdRepaymentPeriod first = getRepaymentPeriods(loanTransactionHelper.getLoanDetails(loanId)).get(0);
            assertEquals(LocalDate.of(2026, 9, 15), first.getDueDate());
            double instalmentInterest = Utils.getDoubleValue(first.getInterestDue());
            assertEquals(1200.0, instalmentInterest, 0.01, "120,000 at 12% for one 30-day month");

            PeriodicAccrualAccountingHelper accruals = new PeriodicAccrualAccountingHelper(requestSpec, responseSpec);

            // Before the change: the product on the core generator accrues on actual days, 16 of 31 by 31 Aug.
            setScheduleStrategy(productId, "CORE_DEFAULT");
            updateBusinessDate("01 September 2026");
            accruals.runPeriodicAccrualAccounting("31 August 2026");
            double actualDayAccrual = instalmentInterest * 16 / 31;
            assertEquals(actualDayAccrual, accruedInterest(loanId), 0.05, "actual days: 16 of 31");

            // Deploy: the loan is now ahead of its 30-day-month target (15 of 30 = 600.00).
            setScheduleStrategy(productId, "MNZL_DECLINING_BALANCE");
            int transactionsBefore = accrualTransactionCount(loanId);
            accruals.runPeriodicAccrualAccounting("31 August 2026");
            accruals.runPeriodicAccrualAccounting("31 August 2026");
            assertEquals(transactionsBefore, accrualTransactionCount(loanId), "a loan ahead of its target posts nothing, on reruns too");
            assertEquals(actualDayAccrual, accruedInterest(loanId), 0.05);

            // The target passes what is booked on 1 Sep (16 of 30 = 640.00) and the accrual catches up to it.
            updateBusinessDate("02 September 2026");
            accruals.runPeriodicAccrualAccounting("01 September 2026");
            assertEquals(640.0, accruedInterest(loanId), 0.01, "30-day months: 16 of 30");

            // By the due date exactly the instalment's interest, once, however often the job runs.
            updateBusinessDate("16 September 2026");
            accruals.runPeriodicAccrualAccounting("15 September 2026");
            accruals.runPeriodicAccrualAccounting("15 September 2026");
            assertEquals(instalmentInterest, accruedInterest(loanId), 0.001, "full instalment interest by its due date");
            assertEquals(instalmentInterest, interestIncomeCredited(loanId), 0.001, "interest income ledger matches the accruals");

            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            assertTrue(
                    loan.getTransactions().stream().filter(tx -> tx.getType() != null)
                            .noneMatch(tx -> tx.getType().getCode() != null && tx.getType().getCode().contains("accrualAdjustment")),
                    "no accrual adjustment entries");
            assertTrue(loan.getTransactions().stream().filter(this::isAccrual).allMatch(tx -> Utils.getDoubleValue(tx.getAmount()) > 0),
                    "no negative accrual entries");
        });
    }

    private void setScheduleStrategy(Long productId, String scheduleStrategyCode) {
        Map<String, String> strategyBody = new HashMap<>();
        strategyBody.put("instrumentCode", "MNZL_STANDARD_LOAN");
        strategyBody.put("scheduleStrategyCode", scheduleStrategyCode);
        strategyBody.put("chargeStrategyCode", "MNZL_INTEREST_AND_PENALTIES");
        strategyBody.put("cobStrategyCode", "MNZL_DUE_INSTALLMENTS");
        Utils.performServerPut(requestSpec, responseSpec, String.format(MNZL_STRATEGY_URL, productId), new Gson().toJson(strategyBody));
    }

    private boolean isAccrual(GetLoansLoanIdTransactions tx) {
        return tx.getType() != null && Boolean.TRUE.equals(tx.getType().getAccrual()) && !Boolean.TRUE.equals(tx.getManuallyReversed());
    }

    private double accruedInterest(Long loanId) {
        return loanTransactionHelper.getLoanDetails(loanId).getTransactions().stream().filter(this::isAccrual)
                .mapToDouble(tx -> Utils.getDoubleValue(tx.getInterestPortion())).sum();
    }

    private int accrualTransactionCount(Long loanId) {
        return (int) loanTransactionHelper.getLoanDetails(loanId).getTransactions().stream().filter(this::isAccrual).count();
    }

    /** Net credit to the interest income account from this loan's journal entries. */
    private double interestIncomeCredited(Long loanId) {
        String json = Utils.performServerGet(requestSpec, responseSpec,
                "/fineract-provider/api/v1/journalentries?loanId=" + loanId + "&limit=-1&" + Utils.TENANT_IDENTIFIER, null);
        JsonArray items = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("pageItems");
        long incomeAccountId = interestIncomeAccount.getAccountID().longValue();
        double net = 0;
        for (JsonElement item : items) {
            JsonObject entry = item.getAsJsonObject();
            if (entry.get("glAccountId").getAsLong() != incomeAccountId) {
                continue;
            }
            double amount = entry.get("amount").getAsDouble();
            net += "CREDIT".equals(entry.getAsJsonObject("entryType").get("value").getAsString()) ? amount : -amount;
        }
        return net;
    }

    private void setMnzlProductStrategy(Long productId) {
        Map<String, String> strategyBody = new HashMap<>();
        strategyBody.put("instrumentCode", "MNZL_STANDARD_LOAN");
        strategyBody.put("scheduleStrategyCode", "MNZL_DECLINING_BALANCE");
        strategyBody.put("chargeStrategyCode", "MNZL_INTEREST_AND_PENALTIES");
        strategyBody.put("cobStrategyCode", "MNZL_DUE_INSTALLMENTS");

        String url = String.format(MNZL_STRATEGY_URL, productId);
        Utils.performServerPut(requestSpec, responseSpec, url, new Gson().toJson(strategyBody));
    }

    private PostLoansResponse applyForMnzlLoan(Long clientId, Long productId, double principal, double annualRate, int numberOfRepayments,
            String submittedDate) {
        return loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                .principal(BigDecimal.valueOf(principal)).loanTermFrequency(numberOfRepayments).loanTermFrequencyType(2) // MONTHS
                .numberOfRepayments(numberOfRepayments).repaymentEvery(1).repaymentFrequencyType(2) // MONTHS
                .interestRatePerPeriod(BigDecimal.valueOf(annualRate)).amortizationType(1) // EQUAL_INSTALLMENTS
                .interestType(0) // DECLINING_BALANCE
                .interestCalculationPeriodType(1) // SAME_AS_REPAYMENT_PERIOD
                .transactionProcessingStrategyCode("mifos-standard-strategy").expectedDisbursementDate(submittedDate)
                .submittedOnDate(submittedDate).dateFormat(DATETIME_PATTERN).locale("en").loanType("individual"));
    }

    private Long applyApproveDisburseLoan(Long clientId, Long productId, double principal, double annualRate, int numberOfRepayments,
            String date) {
        PostLoansResponse loanResponse = applyForMnzlLoan(clientId, productId, principal, annualRate, numberOfRepayments, date);
        Long loanId = loanResponse.getLoanId();

        loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(principal))
                .dateFormat(DATETIME_PATTERN).approvedOnDate(date).locale("en"));

        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(date).dateFormat(DATETIME_PATTERN)
                .transactionAmount(BigDecimal.valueOf(principal)).locale("en"));

        return loanId;
    }
}
