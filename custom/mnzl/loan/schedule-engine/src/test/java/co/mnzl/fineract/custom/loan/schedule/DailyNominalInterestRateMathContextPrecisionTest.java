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
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for 1ba3c96e1 (Refactor interest precision via MathContext). Pre-fix, daily-rate computation used a
 * fixed double precision via {@code BigDecimal.valueOf(100.0d)} divisions and lost precision on repeating-decimal
 * results (e.g. 15/360 = 0.041666...). The fix routes the divide through the caller-provided {@link MathContext} so the
 * configured scale and rounding mode are honored end-to-end.
 */
class DailyNominalInterestRateMathContextPrecisionTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    @Test
    void precision_15pct360_matchesMathContext() {
        // 15.00 / 360 with MC(12, HALF_EVEN) must equal MC-divided BigDecimal exactly: no truncation to fewer digits.
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("15.00"), DaysInYearType.DAYS_360);
        BigDecimal expected = new BigDecimal("15.00").divide(BigDecimal.valueOf(360), MC);

        BigDecimal actual = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);

        assertThat(actual).isEqualByComparingTo(expected);
        // Sanity: the value is repeating (0.0416...) so the MathContext precision should give us 12 significant digits.
        assertThat(actual.precision()).isLessThanOrEqualTo(MC.getPrecision());
    }

    @Test
    void precision_repeatingDecimal_doesNotTruncate() {
        // 12 / 360 = 0.0333... repeating. Pre-fix code that did /100 first could leave a value rounded to fewer digits.
        // Verify the post-fix path gives the full MC precision.
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("12.00"), DaysInYearType.DAYS_360);

        BigDecimal actual = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);

        // 0.0333333333333... rounded HALF_EVEN to 12 significant digits.
        assertThat(actual).isEqualByComparingTo(new BigDecimal("0.0333333333333"));
        // A different MathContext yields a different value: precision really is being plumbed through.
        BigDecimal lowPrecision = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, new MathContext(4, RoundingMode.HALF_EVEN));
        assertThat(lowPrecision).isEqualByComparingTo(new BigDecimal("0.03333"));
    }

    @Test
    void precision_zeroRate_returnsZero() {
        // 0 / N = 0 regardless of MathContext. Guards a divide-by-zero or NaN regression on zero-interest products.
        LoanApplicationTerms terms = termsWithAnnualNominalRate(BigDecimal.ZERO, DaysInYearType.DAYS_360);

        BigDecimal actual = MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC);

        assertThat(actual).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private LoanApplicationTerms termsWithAnnualNominalRate(BigDecimal annualNominalInterestRate, DaysInYearType daysInYearType) {
        ApplicationCurrency currency = new ApplicationCurrency("USD", "US Dollar", 2, 0, "currency.USD", "$");
        Money zero = Money.of(fromApplicationCurrency(currency), BigDecimal.ZERO, MC);
        return new LoanApplicationTerms.Builder().currency(currency.toData())
                .principal(Money.of(fromApplicationCurrency(currency), BigDecimal.valueOf(100), MC)).inArrearsTolerance(zero)
                .daysInMonthType(DaysInMonthType.DAYS_30).daysInYearType(daysInYearType)
                .annualNominalInterestRate(annualNominalInterestRate).mc(MC).build();
    }
}
