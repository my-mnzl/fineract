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

class MnzlLoanScheduleMathTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    @Test
    void getDifferenceInDaysFor30DayMonthTreatsEveryMonthAsThirtyDays() {
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 1, 8), LocalDate.of(2024, 2, 1))).isEqualTo(22);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 1)))
                .isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 1))).isZero();
    }

    @Test
    void getDailyNominalInterestRateUsesFull360DayPrecision() {
        LoanApplicationTerms terms = termsWithAnnualNominalRate(new BigDecimal("12.00"), DaysInYearType.DAYS_360);

        assertThat(MnzlLoanScheduleMath.getDailyNominalInterestRate(terms, MC)).isEqualByComparingTo(new BigDecimal("0.0333333333333"));
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
