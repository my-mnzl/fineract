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

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Period;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;

public final class MnzlLoanScheduleMath {

    private MnzlLoanScheduleMath() {}

    public static BigDecimal getDailyNominalInterestRate(final LoanApplicationTerms loanApplicationTerms, final MathContext mc) {
        final LoanProductRelatedDetail loanProductRelatedDetail = loanApplicationTerms.toLoanProductRelatedDetail();
        BigDecimal daysInYear = BigDecimal.valueOf(switch (loanProductRelatedDetail.fetchDaysInYearType()) {
            case DAYS_360 -> 360;
            case DAYS_364 -> 364;
            case DAYS_365 -> 365;
            default -> 365;
        });
        return loanApplicationTerms.getAnnualNominalInterestRate().divide(daysInYear, mc);
    }

    public static int getDifferenceInDays(final LocalDate startDate, final LocalDate endDate,
            final LoanApplicationTerms loanApplicationTerms) {
        final DaysInMonthType daysInMonthType = loanApplicationTerms.toLoanProductRelatedDetail().fetchDaysInMonthType();
        if (daysInMonthType.isDaysInMonth_30()) {
            return getDifferenceInDaysFor30DayMonth(startDate, endDate);
        }
        return DateUtils.getExactDifferenceInDays(startDate, endDate);
    }

    public static int getDifferenceInDaysFor30DayMonth(final LocalDate startDate, final LocalDate endDate) {
        if (startDate.isAfter(endDate) || startDate.equals(endDate)) {
            return 0;
        }

        int startDay = startDate.getDayOfMonth();
        int endDay = endDate.getDayOfMonth();
        if (startDate.getMonthValue() == endDate.getMonthValue() && startDate.getYear() == endDate.getYear()) {
            return Math.min(30, endDay) - startDay;
        }

        int daysRemainingInStartMonth = Math.max(0, 30 - startDay);
        LocalDate startOfNextMonth = startDate.withDayOfMonth(1).plusMonths(1);
        LocalDate endOfStartMonth = endDate.withDayOfMonth(1);

        int fullMonthsBetween = 0;
        if (!startOfNextMonth.isAfter(endOfStartMonth)) {
            Period period = Period.between(startOfNextMonth, endOfStartMonth);
            fullMonthsBetween = period.getYears() * 12 + period.getMonths();
        }

        int daysIntoEndMonth = endDay == 1 ? 0 : endDay - 1;
        return Math.max(0, daysRemainingInStartMonth + (fullMonthsBetween * 30) + daysIntoEndMonth);
    }
}
