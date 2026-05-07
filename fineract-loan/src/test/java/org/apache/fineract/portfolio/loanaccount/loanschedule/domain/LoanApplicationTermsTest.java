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
package org.apache.fineract.portfolio.loanaccount.loanschedule.domain;

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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.calendar.domain.Calendar;
import org.apache.fineract.portfolio.calendar.domain.CalendarType;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanApplicationTermsTest {

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @Test
    void calculatePeriodsBetweenDatesUsesThirtyDayMonthFractionForMonthlyPartialPeriods() {
        LoanApplicationTerms loanApplicationTerms = createLoanApplicationTerms(DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360);

        BigDecimal periods = loanApplicationTerms.calculatePeriodsBetweenDates(LocalDate.of(2026, 2, 17), LocalDate.of(2026, 4, 1));

        assertEquals(44.0d / 30.0d, periods.doubleValue(), 1.0E-12);
    }

    @Test
    void calculatePeriodsBetweenDatesUsesThirtyDayMonthFractionForSameMonthPartialPeriods() {
        LoanApplicationTerms loanApplicationTerms = createLoanApplicationTerms(DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360);

        BigDecimal periods = loanApplicationTerms.calculatePeriodsBetweenDates(LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 28));

        assertEquals(11.0d / 30.0d, periods.doubleValue(), 1.0E-12);
    }

    @Test
    void calculatePeriodsBetweenDatesKeepsActualMonthFractionForMonthlyPartialPeriods() {
        LoanApplicationTerms loanApplicationTerms = createLoanApplicationTerms(DaysInMonthType.ACTUAL, DaysInYearType.ACTUAL);

        BigDecimal periods = loanApplicationTerms.calculatePeriodsBetweenDates(LocalDate.of(2026, 2, 17), LocalDate.of(2026, 4, 1));

        assertEquals(1.0d + (15.0d / 31.0d), periods.doubleValue(), 1.0E-12);
    }

    @Test
    void calculatePeriodsBetweenDatesUsesThirtyDayMonthFractionForDayThirtyOneStubPeriods() {
        LoanApplicationTerms loanApplicationTerms = createLoanApplicationTerms(DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360, null,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 20));

        BigDecimal periods = loanApplicationTerms.calculatePeriodsBetweenDates(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 1));

        assertEquals(1.0d / 30.0d, periods.doubleValue(), 1.0E-12);
    }

    @Test
    void calculatePeriodsBetweenDatesUsesThirtyDayMonthFractionForLoanCalendarStubPeriods() {
        LocalDate disbursementDate = LocalDate.of(2026, 3, 31);
        Calendar loanCalendar = Calendar.createRepeatingCalendar("Loan", LocalDate.of(2026, 1, 1), CalendarType.COLLECTION.getValue(),
                "FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=1");
        LoanApplicationTerms loanApplicationTerms = createLoanApplicationTerms(DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360,
                loanCalendar, disbursementDate, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 3, 20));

        BigDecimal periods = loanApplicationTerms.calculatePeriodsBetweenDates(disbursementDate, LocalDate.of(2026, 5, 1));

        assertEquals(29.0d / 30.0d, periods.doubleValue(), 1.0E-12);
    }

    private LoanApplicationTerms createLoanApplicationTerms(DaysInMonthType daysInMonthType, DaysInYearType daysInYearType) {
        return createLoanApplicationTerms(daysInMonthType, daysInYearType, null, LocalDate.of(2026, 2, 17), LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 2, 6));
    }

    private LoanApplicationTerms createLoanApplicationTerms(DaysInMonthType daysInMonthType, DaysInYearType daysInYearType,
            Calendar loanCalendar, LocalDate expectedDisbursementDate, LocalDate firstRepaymentDate, LocalDate submittedOnDate) {
        ApplicationCurrency currency = new ApplicationCurrency("USD", "US Dollar", 2, 0, "currency.USD", "$");
        Money principalAmount = Money.of(fromApplicationCurrency(currency), BigDecimal.valueOf(650_000L));

        return LoanApplicationTerms.assembleFrom(currency.toData(), 12, MONTHS, 12, 1, MONTHS, null, INVALID, EQUAL_INSTALLMENTS,
                DECLINING_BALANCE, ZERO, MONTHS, BigDecimal.valueOf(25), SAME_AS_REPAYMENT_PERIOD, true, principalAmount,
                expectedDisbursementDate, firstRepaymentDate, firstRepaymentDate, null, null, null, null, expectedDisbursementDate,
                Money.of(fromApplicationCurrency(currency), ZERO), false, null, Collections.emptyList(), BigDecimal.valueOf(650_000L), null,
                daysInMonthType, daysInYearType, true, null, null, null, null, null, ZERO, null, NONE, loanCalendar, ZERO,
                Collections.emptyList(), true, 0, false, createHolidayDetailDTO(), false, false, false, null, false, false, null, false,
                DISBURSEMENT_DATE, submittedOnDate, CUMULATIVE, LoanScheduleProcessingType.HORIZONTAL, null, false, null, null, false, null,
                false, null, null, null, false, null, null, null, false);
    }

    private HolidayDetailDTO createHolidayDetailDTO() {
        return new HolidayDetailDTO(false, Collections.<Holiday>emptyList(),
                new WorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA,SU", MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false),
                false, false);
    }
}
