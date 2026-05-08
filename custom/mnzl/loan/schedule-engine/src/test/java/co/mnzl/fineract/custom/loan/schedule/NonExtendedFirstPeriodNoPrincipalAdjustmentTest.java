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
package co.mnzl.fineract.custom.loan.schedule;

import static java.math.BigDecimal.ZERO;
import static org.apache.fineract.organisation.monetary.domain.MonetaryCurrency.fromApplicationCurrency;
import static org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType.MOVE_TO_NEXT_WORKING_DAY;
import static org.apache.fineract.portfolio.common.domain.DayOfWeekType.INVALID;
import static org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.MONTHS;
import static org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType.CUMULATIVE;
import static org.apache.fineract.portfolio.loanproduct.domain.AmortizationMethod.EQUAL_INSTALLMENTS;
import static org.apache.fineract.portfolio.loanproduct.domain.InterestCalculationPeriodMethod.SAME_AS_REPAYMENT_PERIOD;
import static org.apache.fineract.portfolio.loanproduct.domain.InterestMethod.DECLINING_BALANCE;
import static org.apache.fineract.portfolio.loanproduct.domain.LoanPreCloseInterestCalculationStrategy.NONE;
import static org.apache.fineract.portfolio.loanproduct.domain.RepaymentStartDateType.DISBURSEMENT_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.monetary.mapper.CurrencyMapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultPaymentPeriodsInOneYearCalculator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelPeriod;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleProcessingType;
import org.apache.fineract.portfolio.loanproduct.domain.InterestRecalculationCompoundingMethod;
import org.apache.fineract.portfolio.loanproduct.domain.RecalculationFrequencyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Regression tests for 07efed350 (Disable principal adjustment and extra interest for non-extended first periods).
 * Pre-fix the generator always overrode principal_1 with the ideal-PMT principal and added an extra-interest term, even
 * when the first period was exactly one repayment period (i.e. no stub). The fix gates the override on
 * {@code actualPeriodDays != idealPeriodDays && actualPeriodDays > 0}, restoring standard EMI amortization for
 * non-extended first periods.
 */
@ExtendWith(MockitoExtension.class)
class NonExtendedFirstPeriodNoPrincipalAdjustmentTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);
    private static final ApplicationCurrency CURRENCY = new ApplicationCurrency("EGP", "Egyptian Pound", 2, 0, "currency.EGP", "E£");
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(120_000L);
    private static final BigDecimal ANNUAL_RATE = BigDecimal.valueOf(12);
    /** Disburse on first-of-month so first period is exactly one repayment period (Apr-1 -> May-1). */
    private static final LocalDate DISBURSED_ON = LocalDate.of(2026, 4, 1);
    private static final LocalDate FIRST_REPAYMENT_DATE = LocalDate.of(2026, 5, 1);
    private static final LocalDate SUBMITTED_ON_DATE = LocalDate.of(2026, 3, 25);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, DISBURSED_ON);
        businessDates.put(BusinessDateType.COB_DATE, DISBURSED_ON.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @Test
    void normalFirstPeriod_noPrincipalAdjustment() {
        // For a non-stubbed first period, principal_1 must be EMI - interest_1, not the "ideal" override that the
        // pre-fix path always applied. EMI ~ 10661.85; interest_1 = 1200; so principal_1 ~ 9461.85.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(), Collections.emptySet(), holidayDetailDTO());

        LoanScheduleModelPeriod first = installment(schedule, 1);
        assertThat(first.principalDue()).as("first installment principal under standard EMI").isBetween(new BigDecimal("9461.00"),
                new BigDecimal("9462.00"));
    }

    @Test
    void normalFirstPeriod_noExtraInterest() {
        // No extra-interest term: interest_1 must be exactly 30 days @ 12%/360 on 120k = 1200, not 1200 + extraDays.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(), Collections.emptySet(), holidayDetailDTO());

        assertThat(installment(schedule, 1).interestDue()).as("first installment interest equals one normal period")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    @Test
    void normalFirstPeriod_matchesStandardEmiAmortization() {
        // Schedule shape must match the upstream PMT-based amortization: total principal = disbursed amount, and total
        // interest does not include any extra-day surplus.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(), Collections.emptySet(), holidayDetailDTO());

        BigDecimal totalPrincipal = schedule.getPeriods().stream().filter(p -> p.principalDue() != null)
                .map(LoanScheduleModelPeriod::principalDue).reduce(ZERO, BigDecimal::add);
        assertThat(totalPrincipal).as("total principal sums to disbursed amount").isEqualByComparingTo(PRINCIPAL);
        // Standard PMT-based amortization for 120k @ 12% / 12 monthly periods: total interest ~ 7942.04 EGP. Lock to a
        // 2-EGP envelope so we tolerate end-of-loan rounding without admitting a regressed schedule (which would
        // deviate
        // by 100+ EGP from an unintended extra-interest term).
        assertThat(schedule.getTotalInterestCharged()).as("no extra-interest contamination").isBetween(new BigDecimal("7941.00"),
                new BigDecimal("7943.50"));
    }

    private static LoanScheduleModelPeriod installment(LoanScheduleModel schedule, int number) {
        return schedule.getPeriods().stream().filter(p -> p.periodNumber() != null && p.periodNumber() == number).findFirst()
                .orElseThrow(() -> new AssertionError("missing installment " + number));
    }

    private CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator newGenerator() {
        return new CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(new DefaultScheduledDateGenerator(),
                new DefaultPaymentPeriodsInOneYearCalculator(), mock(LoanTransactionRepository.class), mock(CurrencyMapper.class));
    }

    private LoanApplicationTerms loanApplicationTerms() {
        Money principalMoney = Money.of(fromApplicationCurrency(CURRENCY), PRINCIPAL);
        Money zeroTolerance = Money.of(fromApplicationCurrency(CURRENCY), ZERO);
        return LoanApplicationTerms.assembleFrom(CURRENCY.toData(), 12, MONTHS, 12, 1, MONTHS, null, INVALID, EQUAL_INSTALLMENTS,
                DECLINING_BALANCE, ZERO, MONTHS, ANNUAL_RATE, SAME_AS_REPAYMENT_PERIOD, true, principalMoney, DISBURSED_ON,
                FIRST_REPAYMENT_DATE, FIRST_REPAYMENT_DATE, null, null, null, null, DISBURSED_ON, zeroTolerance, false, null,
                Collections.emptyList(), PRINCIPAL, null, DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360, true,
                RecalculationFrequencyType.SAME_AS_REPAYMENT_PERIOD, null, InterestRecalculationCompoundingMethod.NONE, null, null, ZERO,
                null, NONE, null, ZERO, Collections.emptyList(), true, 0, false, holidayDetailDTO(), false, false, false, null, false,
                false, null, false, DISBURSEMENT_DATE, SUBMITTED_ON_DATE, CUMULATIVE, LoanScheduleProcessingType.HORIZONTAL, null, false,
                null, null, false, null, false, null, null, null, false, null, null, null, false);
    }

    private static HolidayDetailDTO holidayDetailDTO() {
        return new HolidayDetailDTO(false, Collections.<Holiday>emptyList(),
                new WorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA,SU", MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false),
                false, false);
    }
}
