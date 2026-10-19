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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.monetary.mapper.CurrencyMapper;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultPaymentPeriodsInOneYearCalculator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Accruals on a 30-day-month product follow the months the schedule priced the interest on. Figures are production HEL
 * loans at the August 2026 month-end that Finance reconciled by hand.
 */
class AccrualOn30DayMonthsTest {

    private static final MonetaryCurrency EGP = new MonetaryCurrency("EGP", 2, 0);
    private final CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator generator = new CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(
            new DefaultScheduledDateGenerator(), new DefaultPaymentPeriodsInOneYearCalculator(), mock(LoanTransactionRepository.class),
            mock(CurrencyMapper.class));

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @Test
    void accruedDaysCloseEveryMonthOnAWholeNumberOf30DayMonths() {
        // Mid-month period: 15 Aug -> 31 Aug is 15 of the period's 30 days (actual days would give 16 of 31).
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 31))).isEqualTo(15);
        // A period opening on the 1st has earned the whole month by its last day.
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))).isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEqualTo(30);
        // February closes on its last day too.
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28))).isEqualTo(30);
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2027, 2, 15), LocalDate.of(2027, 2, 28))).isEqualTo(15);
        // Irregular first periods and across a year end.
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 31))).isEqualTo(9);
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 30))).isEqualTo(20);
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 14))).isEqualTo(29);
        // Nothing before the period opens.
        assertThat(MnzlLoanScheduleMath.getAccruedDaysFor30DayMonth(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 15))).isZero();
    }

    @Test
    void midMonthPeriodAccruesHalfItsInterestAtTheAugustMonthEnd() {
        // Loan 23299: 15 Aug -> 15 Sep instalment, 516,396.88 interest.
        LoanRepaymentScheduleInstallment installment = installment(DaysInMonthType.DAYS_30, "2026-08-15", "2026-09-15", "516396.88");
        assertThat(earned(installment, "2026-08-31")).isEqualByComparingTo("258198.44");
        assertThat(earned(installment, "2026-09-15")).isEqualByComparingTo("516396.88");
    }

    @Test
    void periodOpeningOnTheFirstIsFullyAccruedByThatMonthEnd() {
        // Loan 13516: 1 Aug -> 1 Sep instalment, 8,303.20 interest.
        LoanRepaymentScheduleInstallment installment = installment(DaysInMonthType.DAYS_30, "2026-08-01", "2026-09-01", "8303.20");
        assertThat(earned(installment, "2026-08-31")).isEqualByComparingTo("8303.20");
        // By the end of 16 Aug, 16 of the month's 30 days.
        assertThat(earned(installment, "2026-08-16")).isEqualByComparingTo("4428.37");
    }

    @Test
    void irregularFirstPeriodIsSpreadOverIts30DayMonthLength() {
        // Loan 23299: 21 Jul -> 15 Aug first instalment, 416,666.67 interest over 24 thirty-day-month days.
        LoanRepaymentScheduleInstallment installment = installment(DaysInMonthType.DAYS_30, "2026-07-21", "2026-08-15", "416666.67");
        assertThat(earned(installment, "2026-07-31")).isEqualByComparingTo("156250.00");
    }

    @Test
    void actualDayProductsKeepTheUpstreamCalculation() {
        LoanRepaymentScheduleInstallment installment = installment(DaysInMonthType.ACTUAL, "2026-08-15", "2026-09-15", "516396.88");
        // 16 of 31 actual days.
        assertThat(earned(installment, "2026-08-31")).isEqualByComparingTo("266527.42");
    }

    private BigDecimal earned(LoanRepaymentScheduleInstallment installment, String date) {
        Money money = generator.getPeriodInterestTillDate(installment, LocalDate.parse(date));
        return money.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private static LoanRepaymentScheduleInstallment installment(DaysInMonthType daysInMonth, String from, String due, String interest) {
        LoanProductRelatedDetail detail = mock(LoanProductRelatedDetail.class);
        when(detail.fetchDaysInMonthType()).thenReturn(daysInMonth);
        when(detail.getCurrency()).thenReturn(EGP);
        Loan loan = mock(Loan.class);
        when(loan.getLoanProductRelatedDetail()).thenReturn(detail);
        when(loan.getInterestChargedFromDate()).thenReturn(LocalDate.parse(from).minusMonths(6));
        LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getLoan()).thenReturn(loan);
        when(installment.getFromDate()).thenReturn(LocalDate.parse(from));
        when(installment.getDueDate()).thenReturn(LocalDate.parse(due));
        when(installment.getInterestCharged()).thenReturn(new BigDecimal(interest));
        when(installment.getInterestCharged(EGP)).thenReturn(Money.of(EGP, new BigDecimal(interest)));
        return installment;
    }
}
