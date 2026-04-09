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
    void getDifferenceInDaysFor30DayMonthTreatsEveryMonthAsThirtyDays() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 1, 8), LocalDate.of(2024, 2, 1))).isEqualTo(22);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 1)))
                .isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 1))).isZero();
    }

    @Test
    void thirtyDayMonthConsecutiveFirstDaysReturns29() {
        // 1st-to-1st: 29 remaining days in start month (30-1) + 0 days into end month (day 1 = 0)
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1))).isEqualTo(29);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1))).isEqualTo(29);
        // Even for February (28 days actual) -> consistent 29 in this convention
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1))).isEqualTo(29);
    }

    @Test
    void thirtyDayMonthHandlesFullYearCorrectly() {
        // Jan 1 to Jan 1 next year: 29 remaining + 11 full months * 30 + 0 into end = 29 + 330 = 359
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .isEqualTo(359);
    }

    @Test
    void thirtyDayMonthHandlesMultipleMonthSpan() {
        // Jan 1 to Apr 1: 29 remaining + 2 full months * 30 + 0 = 89
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))).isEqualTo(89);
        // Jan 15 to Apr 15: 15 remaining + 2*30 + 14 days into Apr = 15 + 60 + 14 = 89
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15)))
                .isEqualTo(89);
    }

    @Test
    void thirtyDayMonthReturnsZeroForSameDateOrReversed() {
        LocalDate date = LocalDate.of(2026, 6, 15);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(date, date)).isZero();
        // Reversed dates should return 0
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 1))).isZero();
    }

    @Test
    void thirtyDayMonthHandlesDay31Correctly() {
        // Same month: min(30, 31) - 1 = 30 - 1 = 29
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isEqualTo(29);
        // Day 31 as start: remaining = max(0, 30-31) = 0
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1)))
                .isEqualTo(30);
    }

    @Test
    void thirtyDayMonthSameMonthPartialDays() {
        // Within the same month
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 20)))
                .isEqualTo(15);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 15)))
                .isEqualTo(14);
    }

    // ---- getDifferenceInDays with LoanApplicationTerms tests ----

    @Test
    void getDifferenceInDaysUses30DayMonthWhenConfigured() {
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        // Feb 1 to Mar 1 with 30-day month = 29 (actual = 28), uses 30-day convention
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1), terms);
        assertThat(days).isEqualTo(29);
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
        // 1st-to-1st period = 29 days in 30-day convention
        // Daily rate = 12/360 = 0.0333... (percentage)
        // Interest = 120000 * (0.0333.../100) * 29 = 1160
        BigDecimal principal = new BigDecimal("120000");
        BigDecimal annualRate = new BigDecimal("12.00");
        LoanApplicationTerms terms = termsWithAnnualNominalRate(annualRate, DaysInYearType.DAYS_360);

        BigDecimal dailyRate = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);
        int daysInPeriod = MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

        assertThat(daysInPeriod).isEqualTo(29);
        BigDecimal monthlyInterest = principal.multiply(dailyRate).multiply(BigDecimal.valueOf(daysInPeriod))
                .divide(BigDecimal.valueOf(100), MC);
        assertThat(monthlyInterest.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo(new BigDecimal("1160.00"));
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
}
