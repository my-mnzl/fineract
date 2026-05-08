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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlSimulationDriver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * F.13 — Calendar edge cases for mnzl schedules.
 *
 * Each test pins a specific business date and disbursement date so that the resulting schedule lands on a calendar
 * boundary worth verifying: a weekend installment, an office holiday, a year-end transition, or a leap-year Feb 29.
 * Schedules are previewed via the mnzl simulator's {@code preview-schedule} endpoint so we do not rely on a deployed
 * loan or business-date helpers.
 */
@Slf4j
public class MnzlCalendarEdgeCasesIT extends BaseLoanIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    private static final double ANNUAL_RATE = 12.0;
    private static final double PRINCIPAL = 12000.0;
    private static final int TERMS = 12;

    /**
     * F.13.a — When the natural first installment lands on a Saturday it must be kept as scheduled (mnzl does not
     * auto-shift to the next working day at preview time).
     */
    @Test
    public void firstInstallmentOnWeekend_keptAsScheduled() {
        // 01 Aug 2026 is a Saturday → disburse 01 Jul 2026 with monthly repayments puts inst 1 on 01 Aug 2026 (Sat).
        runAt("01 July 2026", () -> {
            Long productId = createProduct30_360();
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            List<Map<String, Object>> preview = previewSchedule(productId, "weekend_first_inst", "01 July 2026");
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).hasSize(TERMS);

            LocalDate inst1Due = LocalDate.parse((String) periods.get(0).get("dueDate"));
            assertThat(inst1Due.getDayOfWeek()).as("first installment day of week").isEqualTo(DayOfWeek.SATURDAY);
            assertThat(inst1Due).as("first installment kept on natural Saturday").isEqualTo(LocalDate.of(2026, 8, 1));
        });
    }

    /**
     * F.13.b — Per-test holiday seeding is not yet wired in this suite. Covered at L1.
     */
    @Test
    @Disabled("requires per-test holiday seeding for the test office; covered at L1 in MnzlLoanScheduleMath30E360ConventionTest")
    public void firstInstallmentOnHoliday_keptAsScheduled() {
        // Intentionally empty — see @Disabled reason.
    }

    /**
     * F.13.c — A schedule whose installments cross the calendar year boundary (Dec → Jan) must use the same 30/360
     * accrual count for the boundary period as for any other monthly period (i.e. days-in-period == 30, not the literal
     * 31 of December or 31 of January).
     */
    @Test
    public void yearEndTransition_correctSchedule() {
        // Disburse 01 Dec 2026 → installment 1 due 01 Jan 2027, installment 2 due 01 Feb 2027 (crosses Dec→Jan).
        runAt("01 December 2026", () -> {
            Long productId = createProduct30_360();
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            List<Map<String, Object>> preview = previewSchedule(productId, "year_end", "01 December 2026");
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).hasSize(TERMS);

            // Installment 1 covers Dec 1 → Jan 1. Under 30/360 the day count is 30 — interest must equal
            // outstanding × annualRate × 30/360 = 12000 × 0.12 × 30/360 = 120.00.
            LocalDate inst1Due = LocalDate.parse((String) periods.get(0).get("dueDate"));
            assertThat(inst1Due).as("installment 1 due date").isEqualTo(LocalDate.of(2027, 1, 1));
            double inst1Interest = ((Number) periods.get(0).get("interestDue")).doubleValue();
            assertThat(inst1Interest).as("Dec→Jan interest under 30/360").isEqualTo(120.00, within(0.01));

            // Installment 2 covers Jan 1 → Feb 1: principal has been amortized once so interest must be lower than
            // installment 1, but the day-count factor must still be 30/360 (no leap inflation).
            double inst2Interest = ((Number) periods.get(1).get("interestDue")).doubleValue();
            assertThat(inst2Interest).as("Jan→Feb interest must be < inst 1 (amortized)").isLessThan(inst1Interest);
        });
    }

    /**
     * F.13.d — Under 30/360 the days-in-month for an accrual window that includes Feb 29 must be capped to 30, not
     * inflate to 29 or 31.
     */
    @Test
    public void leapFeb29InSchedule_30360_normalizesTo30() {
        // 2028 is a leap year. Disburse 01 Feb 2028 → installment 1 due 01 Mar 2028 covers Feb 1 → Mar 1
        // (i.e. Feb 29 inside the accrual window). Under 30/360 the days-in-period is 30.
        runAt("01 February 2028", () -> {
            Long productId = createProduct30_360();
            new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);

            List<Map<String, Object>> preview = previewSchedule(productId, "leap_feb", "01 February 2028");
            List<Map<String, Object>> periods = extractSchedulePeriods(preview);
            assertThat(periods).hasSize(TERMS);

            LocalDate inst1Due = LocalDate.parse((String) periods.get(0).get("dueDate"));
            assertThat(inst1Due).as("installment 1 due date").isEqualTo(LocalDate.of(2028, 3, 1));

            // 30/360: 12000 × 0.12 × 30/360 = 120.00 — independent of leap day.
            double inst1Interest = ((Number) periods.get(0).get("interestDue")).doubleValue();
            assertThat(inst1Interest).as("interest for accrual window containing Feb 29 under 30/360").isEqualTo(120.00, within(0.01));
        });
    }

    private List<Map<String, Object>> previewSchedule(Long productId, String name, String disburseDate) {
        MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
        return driver.preview(driver.scenario(name, productId).principal(PRINCIPAL).rate(ANNUAL_RATE).repayments(TERMS)
                .disburseDate(disburseDate).disburse(disburseDate).body());
    }

    /** preview-schedule endpoint returns a JSON array of period maps directly. */
    private List<Map<String, Object>> extractSchedulePeriods(List<Map<String, Object>> preview) {
        return preview;
    }

    private Long createProduct30_360() {
        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        PostLoanProductsResponse response = loanProductHelper.createLoanProduct(builder.decliningBalance30_360());
        return response.getResourceId();
    }

    // Suppress the unused import warning if DATE_FMT is referenced elsewhere; currently it's available for callers.
    @SuppressWarnings("unused")
    private static String fmt(LocalDate d) {
        return d.format(DATE_FMT);
    }
}
