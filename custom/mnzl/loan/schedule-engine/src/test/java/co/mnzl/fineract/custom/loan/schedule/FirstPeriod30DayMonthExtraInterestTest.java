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
 * Regression tests for 1f264cd20 (Fix 30-day month handling and first period extra interest calculation). Pre-fix the
 * extra-interest computation for an extended first period mixed actual day-count for the stub with a 30-day month
 * convention for the EMI baseline, producing inconsistent results when day-31 fell inside the stub. The fix routes the
 * stub day-count through {@link MnzlLoanScheduleMath#getDifferenceInDays} so the same convention applies end-to-end.
 */
@ExtendWith(MockitoExtension.class)
class FirstPeriod30DayMonthExtraInterestTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);
    private static final ApplicationCurrency CURRENCY = new ApplicationCurrency("EGP", "Egyptian Pound", 2, 0, "currency.EGP", "E£");
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(120_000L);
    private static final BigDecimal ANNUAL_RATE = BigDecimal.valueOf(12);
    private static final LocalDate SUBMITTED_ON_DATE = LocalDate.of(2026, 3, 25);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 15));
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 4, 14));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @Test
    void daysInMonth30_extendedPeriod_extraInterestProportional() {
        // 30-day-month config + extended first period: stub Apr-15 -> Jun-1 = 46 days under 30E/360.
        // Extra interest must be (46 - 30) days * 120000 * 0.12 / 360 = 16 * 40 = 640.00 EGP added to the base 1200.00,
        // i.e. interest_1 = 1840.00. The fix locks this proportional handling.
        LoanScheduleModel schedule = newGenerator().generate(MC,
                loanApplicationTerms(DaysInMonthType.DAYS_30, LocalDate.of(2026, 4, 15), LocalDate.of(2026, 6, 1)), Collections.emptySet(),
                holidayDetailDTO());

        assertThat(installment(schedule, 1).interestDue()).as("interest_1 reflects 46-day stub at 12%/360 on 120k under 30E/360")
                .isEqualByComparingTo(new BigDecimal("1840.00"));
    }

    @Test
    void daysInMonth30_normalPeriod_noExtraInterest() {
        // 30-day-month config + non-stubbed first period (Apr-1 -> May-1 = 30 days). Extra-interest must NOT fire.
        // interest_1 must be the standard one-period value of 1200.00 EGP.
        LoanScheduleModel schedule = newGenerator().generate(MC,
                loanApplicationTerms(DaysInMonthType.DAYS_30, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1)), Collections.emptySet(),
                holidayDetailDTO());

        assertThat(installment(schedule, 1).interestDue()).as("interest_1 stays at one normal period for non-stubbed schedule")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    @Test
    void daysInMonth30_31dayActualMonth_treatedAs30() {
        // 30-day-month config: a calendar month with 31 actual days (e.g. Mar) collapses to 30 in 30E/360.
        // Disburse Mar-31, first repayment May-1: 30E/360 day-count = (5-3)*30 + (1-30) = 60 - 29 = 31. The stub is 1
        // day longer than the ideal 30; extra interest = 1 day * 120000 * 0.12 / 360 = 40.00 EGP. So interest_1 = 1240.
        LoanScheduleModel schedule = newGenerator().generate(MC,
                loanApplicationTerms(DaysInMonthType.DAYS_30, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 1)), Collections.emptySet(),
                holidayDetailDTO());

        // Day-31 caps to 30 under 30E/360 so the stub-vs-ideal difference is exactly 1 day. interest_1 = 1240.00 EGP.
        assertThat(installment(schedule, 1).interestDue()).as("day-31 disbursement caps to 30 under 30E/360")
                .isEqualByComparingTo(new BigDecimal("1240.00"));
    }

    private static LoanScheduleModelPeriod installment(LoanScheduleModel schedule, int number) {
        return schedule.getPeriods().stream().filter(p -> p.periodNumber() != null && p.periodNumber() == number).findFirst()
                .orElseThrow(() -> new AssertionError("missing installment " + number));
    }

    private CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator newGenerator() {
        return new CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(new DefaultScheduledDateGenerator(),
                new DefaultPaymentPeriodsInOneYearCalculator(), mock(LoanTransactionRepository.class), mock(CurrencyMapper.class));
    }

    private LoanApplicationTerms loanApplicationTerms(DaysInMonthType daysInMonthType, LocalDate disbursementDate,
            LocalDate firstRepaymentDate) {
        Money principalMoney = Money.of(fromApplicationCurrency(CURRENCY), PRINCIPAL);
        Money zeroTolerance = Money.of(fromApplicationCurrency(CURRENCY), ZERO);
        return LoanApplicationTerms.assembleFrom(CURRENCY.toData(), 12, MONTHS, 12, 1, MONTHS, null, INVALID, EQUAL_INSTALLMENTS,
                DECLINING_BALANCE, ZERO, MONTHS, ANNUAL_RATE, SAME_AS_REPAYMENT_PERIOD, true, principalMoney, disbursementDate,
                firstRepaymentDate, firstRepaymentDate, null, null, null, null, disbursementDate, zeroTolerance, false, null,
                Collections.emptyList(), PRINCIPAL, null, daysInMonthType, DaysInYearType.DAYS_360, true,
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
