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
package co.mnzl.fineract.custom.loan.grace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.holiday.domain.HolidayStatusType;
import org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlWorkingDayCalculatorTest {

    private static final long OFFICE_ID = 1L;
    // Egyptian banking week: Sun-Thu working, Fri-Sat off.
    private static final String EGYPT_RRULE = "FREQ=WEEKLY;BYDAY=SU,MO,TU,WE,TH";
    // Western Mon-Fri working week, used by the edge-case matrix below.
    private static final String MON_FRI_RRULE = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR";

    @Mock
    private WorkingDaysRepositoryWrapper workingDaysRepository;
    @Mock
    private HolidayRepositoryWrapper holidayRepository;

    private MnzlWorkingDayCalculator calculator;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        WorkingDays config = new WorkingDays(EGYPT_RRULE, RepaymentRescheduleType.SAME_DAY.getValue(), false, false);
        when(workingDaysRepository.findOne()).thenReturn(config);
        calculator = new MnzlWorkingDayCalculator(workingDaysRepository, holidayRepository);
    }

    @Test
    void fiveWorkingDaysFromSundayWithNoHolidaysLandsOnTheFollowingSunday() {
        LocalDate sunday = LocalDate.of(2025, 1, 5);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(Collections.emptyList());

        LocalDate result = calculator.addWorkingDays(sunday, 5, OFFICE_ID);

        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 12));
    }

    @Test
    void fiveWorkingDaysFromSundayWithMidWeekHolidayPushesOneCalendarDayFurther() {
        LocalDate sunday = LocalDate.of(2025, 1, 5);
        Holiday eidWednesday = activeHoliday(LocalDate.of(2025, 1, 8), LocalDate.of(2025, 1, 8));
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(List.of(eidWednesday));

        LocalDate result = calculator.addWorkingDays(sunday, 5, OFFICE_ID);

        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 13));
    }

    @Test
    void fiveWorkingDaysFromFridayCountsFromTheNextSunday() {
        LocalDate friday = LocalDate.of(2025, 1, 10);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(Collections.emptyList());

        LocalDate result = calculator.addWorkingDays(friday, 5, OFFICE_ID);

        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 16));
    }

    @Test
    void zeroWorkingDaysReturnsTheStartDate() {
        LocalDate sunday = LocalDate.of(2025, 1, 5);

        LocalDate result = calculator.addWorkingDays(sunday, 0, OFFICE_ID);

        assertThat(result).isEqualTo(sunday);
    }

    @Test
    void throwsInsteadOfLoopingForeverWhenNoWorkingDayCanBeFoundInTheCap() {
        // Pathological case: every day in the iteration window is a holiday. Without the safety cap this hangs the
        // COB thread; with it, we throw a diagnosable error.
        LocalDate start = LocalDate.of(2025, 1, 5);
        WorkingDays config = new WorkingDays("FREQ=WEEKLY;BYDAY=SU,MO,TU,WE,TH", RepaymentRescheduleType.SAME_DAY.getValue(), false, false);
        Holiday blanket = activeHoliday(start.plusDays(1), start.plusDays(120));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> calculator.addWorkingDays(start, 5, config, List.of(blanket)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("m_working_days RRULE");
    }

    @Test
    void workingDaysToCalendarDaysReportsTheDistanceIncludingSkippedDays() {
        LocalDate sunday = LocalDate.of(2025, 1, 5);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(Collections.emptyList());

        int calendarDays = calculator.workingDaysToCalendarDays(sunday, 5, OFFICE_ID);

        assertThat(calendarDays).isEqualTo(7);
    }

    private static Holiday activeHoliday(LocalDate from, LocalDate to) {
        return new Holiday().setName("Test holiday " + from).setFromDate(from).setToDate(to).setStatus(HolidayStatusType.ACTIVE.getValue())
                .setReschedulingType(0).setProcessed(false);
    }

    // ---------------------------------------------------------------------------------------------
    // Edge-case matrix (Task C.6). These tests use a Mon-Fri RRULE injected via the pre-fetched
    // addWorkingDays(start, count, config, holidays) overload so they exercise the production
    // logic directly without depending on the @BeforeEach Egyptian-week mock.
    // ---------------------------------------------------------------------------------------------

    private static WorkingDays monFriConfig() {
        return new WorkingDays(MON_FRI_RRULE, RepaymentRescheduleType.SAME_DAY.getValue(), false, false);
    }

    @Test
    void addWorkingDays_skipsSaturdaySunday() {
        // Friday + 3 working days = Mon (1), Tue (2), Wed (3) = start + 5 calendar days.
        LocalDate friday = LocalDate.of(2025, 1, 10);

        LocalDate result = calculator.addWorkingDays(friday, 3, monFriConfig(), Collections.emptyList());

        assertThat(result).isEqualTo(friday.plusDays(5));
        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 15));
    }

    @Test
    void addWorkingDays_skipsSingleHoliday() {
        // Monday + 3 wd with Wed as a holiday: Tue (1), Wed (skip), Thu (2), Fri (3) = +4 calendar days.
        LocalDate monday = LocalDate.of(2025, 1, 6);
        Holiday wednesday = activeHoliday(LocalDate.of(2025, 1, 8), LocalDate.of(2025, 1, 8));

        LocalDate result = calculator.addWorkingDays(monday, 3, monFriConfig(), List.of(wednesday));

        assertThat(result).isEqualTo(monday.plusDays(4));
        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    @Test
    void addWorkingDays_skipsHolidayBlock() {
        // Monday + 3 wd with Tue/Wed/Thu all holidays:
        // Tue (skip), Wed (skip), Thu (skip), Fri (1), Sat (weekend), Sun (weekend), Mon (2), Tue (3)
        // = +8 calendar days.
        LocalDate monday = LocalDate.of(2025, 1, 6);
        Holiday block = activeHoliday(LocalDate.of(2025, 1, 7), LocalDate.of(2025, 1, 9));

        LocalDate result = calculator.addWorkingDays(monday, 3, monFriConfig(), List.of(block));

        assertThat(result).isEqualTo(monday.plusDays(8));
        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 14));
    }

    @Test
    void addWorkingDays_holidayOnWeekend_noDoubleSkip() {
        // Friday + 3 wd with Saturday flagged as a holiday: Saturday is already non-working, so the
        // holiday flag adds nothing. Result: Mon (1), Tue (2), Wed (3) = +5 calendar days.
        LocalDate friday = LocalDate.of(2025, 1, 10);
        Holiday saturday = activeHoliday(LocalDate.of(2025, 1, 11), LocalDate.of(2025, 1, 11));

        LocalDate result = calculator.addWorkingDays(friday, 3, monFriConfig(), List.of(saturday));

        assertThat(result).isEqualTo(friday.plusDays(5));
    }

    @Test
    void addWorkingDays_zeroOffset_returnsStart() {
        // Production short-circuits when workingDays <= 0.
        LocalDate wednesday = LocalDate.of(2025, 1, 8);

        LocalDate result = calculator.addWorkingDays(wednesday, 0, monFriConfig(), Collections.emptyList());

        assertThat(result).isEqualTo(wednesday);
    }

    @Test
    void addWorkingDays_negativeOffset_returnsStart() {
        // Same short-circuit — negative offsets return start unchanged rather than walking backwards.
        LocalDate wednesday = LocalDate.of(2025, 1, 8);

        LocalDate result = calculator.addWorkingDays(wednesday, -1, monFriConfig(), Collections.emptyList());

        assertThat(result).isEqualTo(wednesday);
    }

    @Test
    void addWorkingDays_oneDay_returnsNextWorkingDay() {
        // Friday + 1 wd: Sat (skip), Sun (skip), Mon (1) = Monday.
        LocalDate friday = LocalDate.of(2025, 1, 10);

        LocalDate result = calculator.addWorkingDays(friday, 1, monFriConfig(), Collections.emptyList());

        assertThat(result).isEqualTo(LocalDate.of(2025, 1, 13));
        assertThat(result.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    @Test
    void addWorkingDays_safetyCap_throws() {
        // Pathological case: a valid RRULE but every candidate day is covered by a blanket holiday,
        // so the safety cap fires with a diagnostic that references the RRULE. (We can't use
        // BYDAY= directly — the iCal4j parser rejects an empty BYDAY before our code runs.)
        LocalDate start = LocalDate.of(2025, 1, 6);
        WorkingDays config = new WorkingDays(MON_FRI_RRULE, RepaymentRescheduleType.SAME_DAY.getValue(), false, false);
        Holiday blanket = activeHoliday(start.plusDays(1), start.plusDays(120));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> calculator.addWorkingDays(start, 1, config, List.of(blanket)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RRULE");
    }

    @Test
    void addWorkingDays_nullStart_returnsNull() {
        // Null start short-circuits before any calendar arithmetic.
        LocalDate result = calculator.addWorkingDays(null, 5, monFriConfig(), Collections.emptyList());

        assertThat(result).isNull();
    }

    @Test
    void addWorkingDays_largeOffset_terminates() {
        // 30 wd × 7/5 = 42 calendar days under a Mon-Fri RRULE; verifies the loop terminates well
        // within the safety cap (30*7+14 = 224 iterations) and produces the right answer.
        LocalDate monday = LocalDate.of(2025, 1, 6);

        LocalDate result = calculator.addWorkingDays(monday, 30, monFriConfig(), Collections.emptyList());

        assertThat(result).isEqualTo(monday.plusDays(42));
    }

    @Test
    void workingDaysToCalendarDays_basicSkipWeekend() {
        // Friday + 3 wd = Mon, Tue, Wed → 5 calendar days.
        LocalDate friday = LocalDate.of(2025, 1, 10);
        WorkingDays config = monFriConfig();
        when(workingDaysRepository.findOne()).thenReturn(config);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(Collections.emptyList());

        int calendarDays = calculator.workingDaysToCalendarDays(friday, 3, OFFICE_ID);

        assertThat(calendarDays).isEqualTo(5);
    }

    @Test
    void isWorkingDay_satReturnsFalse() {
        // Saturday under a Mon-Fri RRULE is not a working day.
        LocalDate saturday = LocalDate.of(2025, 1, 11);
        WorkingDays config = monFriConfig();
        when(workingDaysRepository.findOne()).thenReturn(config);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(Collections.emptyList());

        assertThat(calculator.isWorkingDay(saturday, OFFICE_ID)).isFalse();
    }

    @Test
    void isWorkingDay_holidayReturnsFalse() {
        // Wednesday is normally a working day, but a holiday flag overrides that.
        LocalDate wednesday = LocalDate.of(2025, 1, 8);
        WorkingDays config = monFriConfig();
        Holiday holiday = activeHoliday(wednesday, wednesday);
        when(workingDaysRepository.findOne()).thenReturn(config);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(eq(OFFICE_ID), any(LocalDate.class))).thenReturn(List.of(holiday));

        assertThat(calculator.isWorkingDay(wednesday, OFFICE_ID)).isFalse();
    }

    @Test
    void getActiveHolidaysForOffice_nullOfficeId_emptyList() {
        // Guards against the holiday repository being hit with a null office id from upstream callers.
        List<Holiday> result = calculator.getActiveHolidaysForOffice(null, LocalDate.of(2025, 1, 1));

        assertThat(result).isEmpty();
    }
}
