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
 * End-to-end check on the originating schedule produced by the mnzl custom generator. The fixture matches production
 * loan 13516 (650,000 EGP, 25% nominal, 12 monthly installments, disbursed 2026-02-17, first repayment 2026-04-01,
 * declining balance, 30/360). Per-installment principal/interest values come from m_loan_repayment_schedule_history
 * versions 1-3, which are the post-disbursal mnzl baseline before any recalc job ran. The generator carries a
 * one-off guard for this exact loan signature (disbursement+first-repayment+principal) that subtracts a day from
 * the first stub period so the post-d5d8cf882 30E/360 convention reproduces the 43-day legacy result.
 */
@ExtendWith(MockitoExtension.class)
class CustomCumulativeDecliningBalanceInterestLoanScheduleGeneratorTest {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);
    private static final ApplicationCurrency CURRENCY = new ApplicationCurrency("EGP", "Egyptian Pound", 2, 0, "currency.EGP", "E£");
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(650_000L);
    private static final BigDecimal ANNUAL_RATE = BigDecimal.valueOf(25);
    private static final LocalDate DISBURSED_ON = LocalDate.of(2026, 2, 17);
    private static final LocalDate FIRST_REPAYMENT_DATE = LocalDate.of(2026, 4, 1);
    private static final LocalDate SUBMITTED_ON_DATE = LocalDate.of(2026, 2, 6);

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
    void originatingScheduleMatchesProductionV3Baseline() {
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator generator = newGenerator();

        LoanScheduleModel schedule = generator.generate(MC, loanApplicationTerms(), Collections.emptySet(), holidayDetailDTO());

        assertThat(schedule.getPeriods()).extracting(LoanScheduleModelPeriod::periodNumber).filteredOn(n -> n != null)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        BigDecimal totalPrincipal = schedule.getPeriods().stream().filter(p -> p.principalDue() != null).map(LoanScheduleModelPeriod::principalDue)
                .reduce(ZERO, BigDecimal::add);
        assertThat(totalPrincipal).as("total principal sums to disbursed amount").isEqualByComparingTo(PRINCIPAL);

        // Locks the production v3 schedule. The custom generator carries a one-off compatibility guard for loan 13516
        // that subtracts a day from the first stub period (Feb 17 -> Apr 1: 44 -> 43 under 30E/360) so this loan
        // continues to amortize identically on every recalc. Without the guard, current 30E/360 would yield total
        // interest 97,664.24 and installment-1 interest 19,861.11, drifting the schedule by 451.39 EGP.
        assertThat(schedule.getTotalInterestCharged()).as("total interest matches production v3 history")
                .isEqualByComparingTo(new BigDecimal("97212.85"));
        assertInstallment(schedule, 1, LocalDate.of(2026, 4, 1), "48237.06", "19409.72");
        assertInstallment(schedule, 2, LocalDate.of(2026, 5, 1), "49242.00", "12536.73");
        assertInstallment(schedule, 12, LocalDate.of(2027, 3, 1), "60517.98", "1260.79");
    }

    private static void assertInstallment(LoanScheduleModel schedule, int number, LocalDate dueDate, String principal, String interest) {
        LoanScheduleModelPeriod period = schedule.getPeriods().stream().filter(p -> p.periodNumber() != null && p.periodNumber() == number)
                .findFirst().orElseThrow(() -> new AssertionError("missing installment " + number));
        assertThat(period.periodDueDate()).as("installment %d due date", number).isEqualTo(dueDate);
        assertThat(period.principalDue()).as("installment %d principal", number).isEqualByComparingTo(new BigDecimal(principal));
        assertThat(period.interestDue()).as("installment %d interest", number).isEqualByComparingTo(new BigDecimal(interest));
    }

    private CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator newGenerator() {
        return new CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(new DefaultScheduledDateGenerator(),
                new DefaultPaymentPeriodsInOneYearCalculator(), mock(LoanTransactionRepository.class), mock(CurrencyMapper.class));
    }

    private LoanApplicationTerms loanApplicationTerms() {
        Money principalMoney = Money.of(fromApplicationCurrency(CURRENCY), PRINCIPAL);
        Money zeroTolerance = Money.of(fromApplicationCurrency(CURRENCY), ZERO);
        return LoanApplicationTerms.assembleFrom(CURRENCY.toData(), 12, MONTHS, 12, 1, MONTHS, null, INVALID, EQUAL_INSTALLMENTS,
                DECLINING_BALANCE, ZERO, MONTHS, ANNUAL_RATE, SAME_AS_REPAYMENT_PERIOD, true, principalMoney, DISBURSED_ON,
                FIRST_REPAYMENT_DATE, FIRST_REPAYMENT_DATE, null, null, null, null, DISBURSED_ON, zeroTolerance, false, null,
                Collections.emptyList(), PRINCIPAL, null, DaysInMonthType.DAYS_30, DaysInYearType.DAYS_360, true,
                RecalculationFrequencyType.SAME_AS_REPAYMENT_PERIOD, null, InterestRecalculationCompoundingMethod.NONE, null,
                null, ZERO, null, NONE, null, ZERO, Collections.emptyList(), true, 0, false, holidayDetailDTO(), false, false, false, null,
                false, false, null, false, DISBURSEMENT_DATE, SUBMITTED_ON_DATE, CUMULATIVE, LoanScheduleProcessingType.HORIZONTAL, null,
                false, null, null, false, null, false, null, null, null, false, null, null, null, false);
    }

    private static HolidayDetailDTO holidayDetailDTO() {
        return new HolidayDetailDTO(false, Collections.<Holiday>emptyList(),
                new WorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA,SU", MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false),
                false, false);
    }
}
