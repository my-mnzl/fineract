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
 * Regression tests for 19a4036ac (Fix first period interest when repayment start date is not specified). Pre-fix the
 * generator dereferenced {@code getRepaymentsStartingFromLocalDate()} for the aligned/ideal first-period anchor, which
 * NPE'd or returned bad values when the borrower hadn't supplied an explicit first-repayment date. The fix routes
 * through the seedDate, which falls back to the disbursement date when {@code repaymentsStartingFromDate} is null.
 */
@ExtendWith(MockitoExtension.class)
class FirstPeriodNoRepaymentsStartingFromDateTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);
    private static final ApplicationCurrency CURRENCY = new ApplicationCurrency("EGP", "Egyptian Pound", 2, 0, "currency.EGP", "E£");
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(120_000L);
    private static final BigDecimal ANNUAL_RATE = BigDecimal.valueOf(12);
    /** Disburse on first-of-month so a default 1-month repayment cycle aligns to May-1. */
    private static final LocalDate DISBURSED_ON = LocalDate.of(2026, 4, 1);
    private static final LocalDate EXPLICIT_FIRST_REPAYMENT_DATE = LocalDate.of(2026, 5, 1);
    private static final LocalDate SUBMITTED_ON_DATE = LocalDate.of(2026, 3, 25);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, DISBURSED_ON);
        businessDates.put(BusinessDateType.COB_DATE, DISBURSED_ON.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @Test
    void noRepaymentsStartingFromDate_firstPeriodInterestCorrect() {
        // repaymentsStartingFromDate=null. Pre-fix this NPE'd dereferencing the field. Post-fix the schedule generates
        // and the first installment carries the standard one-month interest of 120k * 12% / 12 = 1200.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(null, null), Collections.emptySet(),
                holidayDetailDTO());

        LoanScheduleModelPeriod first = installment(schedule, 1);
        assertThat(first.interestDue()).as("first installment interest with implicit start date")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    @Test
    void noRepaymentsStartingFromDate_periodLengthDefaultsToOnePeriod() {
        // With no explicit repayments-starting-from date, the schedule still has 12 monthly installments and the first
        // due-date is one month after disbursement (May-1).
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(null, null), Collections.emptySet(),
                holidayDetailDTO());

        assertThat(schedule.getPeriods()).extracting(LoanScheduleModelPeriod::periodNumber).filteredOn(n -> n != null).hasSize(12);
        assertThat(installment(schedule, 1).periodDueDate()).as("first due date defaults to disbursement+1month")
                .isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void noRepaymentsStartingFromDate_matchesExplicitDateCase() {
        // The schedule with null repayments-starting-from must equal the schedule with the explicit date set to the
        // anchored first-repayment (May-1). This locks the equivalence the fix established.
        LoanScheduleModel implicit = newGenerator().generate(MC, loanApplicationTerms(null, null), Collections.emptySet(),
                holidayDetailDTO());
        LoanScheduleModel explicit = newGenerator().generate(MC,
                loanApplicationTerms(EXPLICIT_FIRST_REPAYMENT_DATE, EXPLICIT_FIRST_REPAYMENT_DATE), Collections.emptySet(),
                holidayDetailDTO());

        assertThat(implicit.getTotalInterestCharged()).as("total interest matches explicit-date case")
                .isEqualByComparingTo(explicit.getTotalInterestCharged());
        assertThat(installment(implicit, 1).interestDue()).isEqualByComparingTo(installment(explicit, 1).interestDue());
        assertThat(installment(implicit, 1).principalDue()).isEqualByComparingTo(installment(explicit, 1).principalDue());
    }

    private static LoanScheduleModelPeriod installment(LoanScheduleModel schedule, int number) {
        return schedule.getPeriods().stream().filter(p -> p.periodNumber() != null && p.periodNumber() == number).findFirst()
                .orElseThrow(() -> new AssertionError("missing installment " + number));
    }

    private CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator newGenerator() {
        return new CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(new DefaultScheduledDateGenerator(),
                new DefaultPaymentPeriodsInOneYearCalculator(), mock(LoanTransactionRepository.class), mock(CurrencyMapper.class));
    }

    private LoanApplicationTerms loanApplicationTerms(LocalDate repaymentsStartingFrom, LocalDate calculatedRepaymentsStartingFrom) {
        Money principalMoney = Money.of(fromApplicationCurrency(CURRENCY), PRINCIPAL);
        Money zeroTolerance = Money.of(fromApplicationCurrency(CURRENCY), ZERO);
        return LoanApplicationTerms.assembleFrom(CURRENCY.toData(), 12, MONTHS, 12, 1, MONTHS, null, INVALID, EQUAL_INSTALLMENTS,
                DECLINING_BALANCE, ZERO, MONTHS, ANNUAL_RATE, SAME_AS_REPAYMENT_PERIOD, true, principalMoney, DISBURSED_ON,
                repaymentsStartingFrom, calculatedRepaymentsStartingFrom, null, null, null, null, DISBURSED_ON, zeroTolerance, false, null,
                Collections.emptyList(), PRINCIPAL, null, DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360, true,
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
