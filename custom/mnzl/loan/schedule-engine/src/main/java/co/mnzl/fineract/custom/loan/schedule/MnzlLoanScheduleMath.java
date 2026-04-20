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
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;

public final class MnzlLoanScheduleMath {

    private MnzlLoanScheduleMath() {}

    public static BigDecimal getDailyNominalInterestRate(final LoanApplicationTerms loanApplicationTerms, final MathContext mc) {
        return getDailyNominalInterestRate(loanApplicationTerms, loanApplicationTerms.getExpectedDisbursementDate(), mc);
    }

    public static BigDecimal getDailyNominalInterestRate(final LoanApplicationTerms loanApplicationTerms, final LocalDate referenceDate,
            final MathContext mc) {
        final LoanProductRelatedDetail loanProductRelatedDetail = loanApplicationTerms.toLoanProductRelatedDetail();
        BigDecimal daysInYear = BigDecimal.valueOf(switch (loanProductRelatedDetail.fetchDaysInYearType()) {
            case DAYS_360 -> 360;
            case DAYS_364 -> 364;
            case DAYS_365 -> 365;
            case ACTUAL -> resolveActualDaysInYear(referenceDate, loanApplicationTerms);
            case INVALID -> throw new IllegalArgumentException("Days in year type is required");
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
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Dates must not be null to get difference");
        }
        if (startDate.equals(endDate)) {
            return 0;
        }
        if (startDate.isAfter(endDate)) {
            return -getDifferenceInDaysFor30DayMonth(endDate, startDate);
        }

        // 30E/360 convention
        final int adjustedStartDay = Math.min(30, startDate.getDayOfMonth());
        final int adjustedEndDay = Math.min(30, endDate.getDayOfMonth());
        return ((endDate.getYear() - startDate.getYear()) * 360) + ((endDate.getMonthValue() - startDate.getMonthValue()) * 30)
                + (adjustedEndDay - adjustedStartDay);
    }

    private static int resolveActualDaysInYear(final LocalDate referenceDate, final LoanApplicationTerms loanApplicationTerms) {
        LocalDate effectiveReferenceDate = referenceDate;
        if (effectiveReferenceDate == null) {
            effectiveReferenceDate = loanApplicationTerms.getInterestChargedFromDate();
        }
        if (effectiveReferenceDate == null) {
            effectiveReferenceDate = loanApplicationTerms.getExpectedDisbursementDate();
        }
        if (effectiveReferenceDate == null) {
            throw new IllegalArgumentException("Reference date is required when days in year type is ACTUAL");
        }
        return effectiveReferenceDate.lengthOfYear();
    }
}
