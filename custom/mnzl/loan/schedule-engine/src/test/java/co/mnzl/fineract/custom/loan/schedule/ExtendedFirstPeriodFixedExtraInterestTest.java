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
 * Regression tests for 6f67757b9 (Apply fixed extra interest for extended first repayment periods). Pre-fix the
 * generator dropped the stub-period interest entirely when fixedEmi was active and the first period was extended; the
 * fix routes the stub through {@code calculateFixedInterestWithRateChanges} to charge the actual day-count of interest
 * while preserving the EMI for downstream installments.
 *
 * Note: in the cumulative generator, {@code pmtForInstallment} populates {@code fixedEmiAmount} on first invocation
 * even when the borrower didn't supply one explicitly, so the fixed-EMI path is exercised for every loan that reaches
 * period 1. These tests therefore drive the behavior via stubbed vs non-stubbed first periods rather than by toggling
 * fixedEmi at the API level.
 */
@ExtendWith(MockitoExtension.class)
class ExtendedFirstPeriodFixedExtraInterestTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);
    private static final ApplicationCurrency CURRENCY = new ApplicationCurrency("EGP", "Egyptian Pound", 2, 0, "currency.EGP", "E£");
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(120_000L);
    private static final BigDecimal ANNUAL_RATE = BigDecimal.valueOf(12);
    private static final LocalDate SUBMITTED_ON_DATE = LocalDate.of(2026, 3, 25);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 15));
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 4, 14));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @Test
    void fixedEmi_extendedPeriod_emiPreserved() {
        // Stubbed first period: disburse Apr-15, first repayment Jun-1. 30E/360 day-count: Apr-15 -> Jun-1 = 46 days.
        // The principal_1 must equal pmt - idealInterest (i.e. driven by the *normal* 30-day interest, not the stub
        // 46-day interest). EMI ~ 10661.85, idealInterest = 1200, so principal_1 ~ 9461.85.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 6, 1)),
                Collections.emptySet(), holidayDetailDTO());

        LoanScheduleModelPeriod first = installment(schedule, 1);
        // EMI is preserved on installments 2..N; pin installment 2's total to the canonical PMT amount.
        LoanScheduleModelPeriod second = installment(schedule, 2);
        BigDecimal totalDueSecond = second.principalDue().add(second.interestDue());
        assertThat(totalDueSecond).as("EMI preserved on installment 2").isBetween(new BigDecimal("10661.00"), new BigDecimal("10662.00"));
        // Principal_1 follows the ideal-EMI distribution, NOT EMI - stubInterest (which would be much smaller).
        assertThat(first.principalDue()).as("principal_1 follows ideal EMI distribution").isBetween(new BigDecimal("9461.00"),
                new BigDecimal("9462.00"));
    }

    @Test
    void fixedEmi_extendedPeriod_extraInterestForStubDays() {
        // Stub period interest must reflect the actual 46 days under 30E/360. At 12%/360 on 120k:
        // 46 * 120000 * 0.12 / 360 = 1840.00 EGP. The pre-fix code charged only the 30-day base.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 6, 1)),
                Collections.emptySet(), holidayDetailDTO());

        BigDecimal interestFirst = installment(schedule, 1).interestDue();
        assertThat(interestFirst).as("interest_1 reflects 46-day stub at 12%/360 on 120k").isEqualByComparingTo(new BigDecimal("1840.00"));
    }

    @Test
    void fixedEmi_normalPeriod_noExtraInterest() {
        // Non-stubbed first period: actualPeriodDays == idealPeriodDays. The extra-interest branch must not fire,
        // leaving interest_1 at the standard 30-day value (1200) regardless of fixedEmi presence.
        LoanScheduleModel schedule = newGenerator().generate(MC, loanApplicationTerms(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1)),
                Collections.emptySet(), holidayDetailDTO());

        assertThat(installment(schedule, 1).interestDue()).as("interest_1 at standard one-period level on a non-stubbed schedule")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    private static LoanScheduleModelPeriod installment(LoanScheduleModel schedule, int number) {
        return schedule.getPeriods().stream().filter(p -> p.periodNumber() != null && p.periodNumber() == number).findFirst()
                .orElseThrow(() -> new AssertionError("missing installment " + number));
    }

    private CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator newGenerator() {
        return new CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(new DefaultScheduledDateGenerator(),
                new DefaultPaymentPeriodsInOneYearCalculator(), mock(LoanTransactionRepository.class), mock(CurrencyMapper.class));
    }

    private LoanApplicationTerms loanApplicationTerms(LocalDate disbursementDate, LocalDate firstRepaymentDate) {
        Money principalMoney = Money.of(fromApplicationCurrency(CURRENCY), PRINCIPAL);
        Money zeroTolerance = Money.of(fromApplicationCurrency(CURRENCY), ZERO);
        return LoanApplicationTerms.assembleFrom(CURRENCY.toData(), 12, MONTHS, 12, 1, MONTHS, null, INVALID, EQUAL_INSTALLMENTS,
                DECLINING_BALANCE, ZERO, MONTHS, ANNUAL_RATE, SAME_AS_REPAYMENT_PERIOD, true, principalMoney, disbursementDate,
                firstRepaymentDate, firstRepaymentDate, null, null, null, null, disbursementDate, zeroTolerance, false, null,
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
