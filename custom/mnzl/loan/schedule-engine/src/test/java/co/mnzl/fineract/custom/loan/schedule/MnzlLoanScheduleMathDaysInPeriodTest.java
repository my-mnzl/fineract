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

/**
 * Regression tests for e3d4d3b2e (Fix 30/360 days-in-period calculations). The dispatcher in
 * {@link MnzlLoanScheduleMath#getDifferenceInDays} chooses between the 30E/360 day-count and the actual day-count based
 * on {@code DaysInMonthType}. These tests cover every behavior the bug-fix established for typical period lengths
 * across day-30/31 boundaries and February (both leap and non-leap).
 */
class MnzlLoanScheduleMathDaysInPeriodTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    @Test
    void daysInPeriod_30dayMonth() {
        // April has 30 actual days. Under 30E/360 the answer must also be 30 (no day-31 cap to apply).
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1), terms);
        assertThat(days).isEqualTo(30);
    }

    @Test
    void daysInPeriod_31dayMonth_endsOnDay30() {
        // Jan-1 -> Jan-30 is 29 actual days; under 30E/360 it is 30 - 1 = 29 as well. Same value.
        LoanApplicationTerms terms = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        int days = MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30), terms);
        assertThat(days).isEqualTo(29);
    }

    @Test
    void daysInPeriod_february_28days() {
        // Non-leap February. 30E/360 dispatch: Feb-1 -> Mar-1 is 30 (full month). Actual dispatch: 28.
        LoanApplicationTerms terms30 = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        LoanApplicationTerms termsActual = termsWithDaysInMonth(DaysInMonthType.ACTUAL);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1), terms30)).isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1), termsActual)).isEqualTo(28);
    }

    @Test
    void daysInPeriod_leapFebruary_29days() {
        // 2024 is leap. 30E/360 dispatch: Feb-1 -> Mar-1 is still 30. Actual dispatch: 29.
        LoanApplicationTerms terms30 = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        LoanApplicationTerms termsActual = termsWithDaysInMonth(DaysInMonthType.ACTUAL);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1), terms30)).isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1), termsActual)).isEqualTo(29);
    }

    @Test
    void daysInPeriod_yearCross() {
        // Dec-15 -> Feb-15 across two months and a year boundary. 30E/360 -> 60 days. Actual -> 62 days.
        LoanApplicationTerms terms30 = termsWithDaysInMonth(DaysInMonthType.DAYS_30);
        LoanApplicationTerms termsActual = termsWithDaysInMonth(DaysInMonthType.ACTUAL);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 12, 15), LocalDate.of(2026, 2, 15), terms30)).isEqualTo(60);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDays(LocalDate.of(2025, 12, 15), LocalDate.of(2026, 2, 15), termsActual))
                .isEqualTo(62);
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
