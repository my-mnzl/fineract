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

import static org.apache.fineract.organisation.monetary.domain.MonetaryCurrency.fromApplicationCurrency;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MnzlLoanScheduleMathTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    // ---- getDifferenceInDaysFor30DayMonth tests ----

    @Test
    void getDifferenceInDaysFor30DayMonthMatchesThirtyEThreeSixtyConvention() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 1, 8), LocalDate.of(2024, 2, 1))).isEqualTo(23);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 1)))
                .isEqualTo(31);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 1))).isZero();
    }

    @Test
    void thirtyDayMonthConsecutiveFirstDaysReturnThirty() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1))).isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1))).isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1))).isEqualTo(30);
    }

    @Test
    void thirtyDayMonthHandlesFullYearCorrectly() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .isEqualTo(360);
    }

    @Test
    void thirtyDayMonthHandlesMultipleMonthSpan() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))).isEqualTo(90);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15)))
                .isEqualTo(90);
    }

    @Test
    void thirtyDayMonthReturnsNegativeDifferenceForReversedDates() {
        LocalDate date = LocalDate.of(2026, 6, 15);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(date, date)).isZero();
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 17)))
                .isEqualTo(-14);
    }

    @Test
    void thirtyDayMonthHandlesDay31Correctly() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isEqualTo(29);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1)))
                .isEqualTo(31);
    }

    @Test
    void thirtyDayMonthSameMonthPartialDays() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 20)))
                .isEqualTo(15);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 15)))
                .isEqualTo(14);
    }

    @Test
    void thirtyDayMonthMatchesIssueScenario() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 15)))
                .isEqualTo(25);
    }

    // ---- getDifferenceInDays with LoanApplicationTerms tests ----

    @Test
    void getDifferenceInDaysUses30DayMonthWhenConfigured() {
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1), terms);
        assertThat(days).isEqualTo(30);
    }

    @Test
    void getDifferenceInDaysUsesActualDaysWhenConfigured() {
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.ACTUAL);
        // Feb 1 to Mar 1 with actual days = 28
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1), terms);
        assertThat(days).isEqualTo(28);
    }

    // ---- getDailyNominalInterestRate tests ----

    @Test
    void getDailyNominalInterestRateUsesFull360DayPrecision() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("12.00"), DaysInYearType.DAYS_360);

        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC)).isEqualByComparingTo(new BigDecimal("0.0333333333333"));
    }

    @Test
    void getDailyNominalInterestRateWith365Days() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("12.00"), DaysInYearType.DAYS_365);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);
        // 12 / 365 = 0.0328767123...
        assertThat(dailyRate).isEqualByComparingTo(new BigDecimal("0.0328767123288"));
    }

    @Test
    void getDailyNominalInterestRateUsesLeapYearLengthForActualDaysInYear() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("12.00"), DaysInYearType.ACTUAL);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, LocalDate.of(2024, 1, 1), MC);
        assertThat(dailyRate).isEqualByComparingTo(new BigDecimal("0.0327868852459"));
    }

    @Test
    void getDailyNominalInterestRateUsesNonLeapYearLengthForActualDaysInYear() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("12.00"), DaysInYearType.ACTUAL);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, LocalDate.of(2025, 1, 1), MC);
        assertThat(dailyRate).isEqualByComparingTo(new BigDecimal("0.0328767123288"));
    }

    @ParameterizedTest
    @CsvSource({ "5.00, 360, 0.0138888888889", "10.00, 360, 0.0277777777778", "15.00, 360, 0.0416666666667", "25.00, 360, 0.0694444444444",
            "12.00, 364, 0.0329670329670", })
    void getDailyNominalInterestRateVariousConfigurations(String annualRate, int daysInYear, String expectedDailyRate) {
        DaysInYearType yearType = DaysInYearType.fromInt(daysInYear);
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal(annualRate), yearType);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);
        assertThat(dailyRate).isEqualByComparingTo(new BigDecimal(expectedDailyRate));
    }

    @Test
    void getDailyNominalInterestRateZeroRate() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(BigDecimal.ZERO, DaysInYearType.DAYS_360);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);
        assertThat(dailyRate).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- Monthly interest calculation validation ----

    @Test
    void monthlyInterestWith30_360MatchesExpected() {
        // 120,000 principal, 12% annual, 30/360
        // 1st-to-1st period = 30 days in 30E/360
        // Daily rate = 12/360 = 0.0333... (percentage)
        // Interest = 120000 * (0.0333.../100) * 30 = 1200
        BigDecimal principal = new BigDecimal("120000");
        BigDecimal annualRate = new BigDecimal("12.00");
        LoanApplicationTerms terms = termsWithAnnualNominalRate(annualRate, DaysInYearType.DAYS_360);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);
        int daysInPeriod = MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

        assertThat(daysInPeriod).isEqualTo(30);
        BigDecimal monthlyInterest = principal.multiply(dailyRate).multiply(BigDecimal.valueOf(daysInPeriod))
                .divide(BigDecimal.valueOf(100), MC);
        assertThat(monthlyInterest.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    // ---- Helpers ----

    private LoanApplicationTerms termsWithAnnualNominalRate(BigDecimal annualNominalInterestRate, DaysInYearType daysInYearType) {
        ApplicationCurrency currency = new ApplicationCurrency("USD", "US Dollar", 2, 0, "currency.USD", "$");
        Money zero = Money.of(fromApplicationCurrency(currency), BigDecimal.ZERO, MC);
        return new LoanApplicationTerms.Builder().currency(currency.toData())
                .principal(Money.of(fromApplicationCurrency(currency), BigDecimal.valueOf(100), MC)).inArrearsTolerance(zero)
                .daysInMonthType(DaysInMonthType.DAYS_30).daysInYearType(daysInYearType)
                .annualNominalInterestRate(annualNominalInterestRate).mc(MC).build();
    }

    private LoanApplicationTerms termsWithDaysInMonth(DaysInMonthType daysInMonthType) {
        ApplicationCurrency currency = new ApplicationCurrency("USD", "US Dollar", 2, 0, "currency.USD", "$");
        Money zero = Money.of(fromApplicationCurrency(currency), BigDecimal.ZERO, MC);
        return new LoanApplicationTerms.Builder().currency(currency.toData())
                .principal(Money.of(fromApplicationCurrency(currency), BigDecimal.valueOf(100), MC)).inArrearsTolerance(zero)
                .daysInMonthType(daysInMonthType).daysInYearType(DaysInYearType.DAYS_360).annualNominalInterestRate(BigDecimal.valueOf(12))
                .mc(MC).build();
    }

    // ---- Additional 30E/360 edge case tests (Tasks C.1) ----

    @Test
    void getDifferenceInDaysFor30DayMonth_endOfFebToStartOfMar() {
        // adjustedStart=28, adjustedEnd=1: 0*360 + (3-2)*30 + (1-28) = 30 - 27 = 3
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1))).isEqualTo(3);
    }

    @Test
    void getDifferenceInDaysFor30DayMonth_leapFeb29() {
        // Same formula as non-leap: leap year does not change 30E/360 outcome
        // adjustedStart=28, adjustedEnd=1: 0 + 30 + (1-28) = 3
        int leap = MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1));
        int nonLeap = MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1));
        assertThat(leap).isEqualTo(3);
        assertThat(leap).isEqualTo(nonLeap);
    }

    @Test
    void getDifferenceInDaysFor30DayMonth_yearCross() {
        // start day-31 capped to 30, end day=1: (2026-2025)*360 + (1-12)*30 + (1-30) = 360 - 330 - 29 = 1
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)))
                .isEqualTo(1);
    }

    @Test
    void getDifferenceInDaysFor30DayMonth_identicalDates() {
        LocalDate date = LocalDate.of(2026, 6, 15);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(date, date)).isZero();
    }

    @Test
    void getDifferenceInDaysFor30DayMonth_day31AtStart() {
        // start capped to 30: 0*360 + (2-1)*30 + (15-30) = 30 - 15 = 15
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 15)))
                .isEqualTo(15);
    }

    @Test
    void getDifferenceInDaysFor30DayMonth_day31AtEnd() {
        // end capped to 30: 0 + 0 + (30-15) = 15
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31)))
                .isEqualTo(15);
    }

    @Test
    void getDifferenceInDaysFor30DayMonth_bothDay31() {
        // both capped to 30: 0 + 2*30 + 0 = 60
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31)))
                .isEqualTo(60);
    }

    // ---- Additional getDailyNominalInterestRate tests ----

    @Test
    void getDailyNominalInterestRate_360_15pct() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("15.00"), DaysInYearType.DAYS_360);
        BigDecimal expected = new BigDecimal("15.00").divide(BigDecimal.valueOf(360), MC);
        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC)).isEqualByComparingTo(expected);
    }

    @Test
    void getDailyNominalInterestRate_365_15pct() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("15.00"), DaysInYearType.DAYS_365);
        BigDecimal expected = new BigDecimal("15.00").divide(BigDecimal.valueOf(365), MC);
        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC)).isEqualByComparingTo(expected);
    }

    @Test
    void getDailyNominalInterestRate_5pct_360() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("5.00"), DaysInYearType.DAYS_360);
        BigDecimal expected = new BigDecimal("5.00").divide(BigDecimal.valueOf(360), MC);
        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC)).isEqualByComparingTo(expected);
    }

    @Test
    void getDailyNominalInterestRate_25pct_360() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("25.00"), DaysInYearType.DAYS_360);
        BigDecimal expected = new BigDecimal("25.00").divide(BigDecimal.valueOf(360), MC);
        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC)).isEqualByComparingTo(expected);
    }

    @Test
    void getDailyNominalInterestRate_actual_365NonLeap() {
        // ACTUAL day count uses lengthOfYear() of reference date; 2025 = 365 days.
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("15.00"), DaysInYearType.ACTUAL);
        BigDecimal expected = new BigDecimal("15.00").divide(BigDecimal.valueOf(365), MC);
        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, LocalDate.of(2025, 6, 1), MC)).isEqualByComparingTo(expected);
    }

    @Test
    void getDailyNominalInterestRate_actual_366Leap() {
        // ACTUAL day count uses lengthOfYear() of reference date; 2024 = 366 days (leap).
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("15.00"), DaysInYearType.ACTUAL);
        BigDecimal expected = new BigDecimal("15.00").divide(BigDecimal.valueOf(366), MC);
        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, LocalDate.of(2024, 6, 1), MC)).isEqualByComparingTo(expected);
    }

    // ---- Additional getDifferenceInDays dispatch tests ----

    @Test
    void getDifferenceInDays_30360PathSelected_byDaysInMonth30() {
        // With DAYS_30 configured, dispatcher should use 30E/360 convention.
        // 2026-01-31 -> 2026-03-31 with both capped: 60 days (vs actual 59)
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), terms);
        assertThat(days).isEqualTo(60);
    }

    @Test
    void getDifferenceInDays_actualDaysPath_otherwise() {
        // With ACTUAL configured, dispatcher should use exact day difference.
        // 2026-01-31 -> 2026-03-31 actual = 59 days
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.ACTUAL);
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), terms);
        assertThat(days).isEqualTo(59);
    }
}
