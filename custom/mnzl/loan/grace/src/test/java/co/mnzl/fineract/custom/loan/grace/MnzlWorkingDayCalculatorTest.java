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
}
