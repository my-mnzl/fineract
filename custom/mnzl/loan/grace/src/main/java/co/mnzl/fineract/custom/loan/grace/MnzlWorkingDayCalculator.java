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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.holiday.service.HolidayUtil;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.service.WorkingDaysUtil;

/**
 * Counts grace periods in working days, skipping non-working days per the global {@code m_working_days} RRULE and
 * active per-office entries in {@code m_holiday}. Holiday rescheduling semantics are intentionally ignored — the
 * holiday list is consumed only as a "skip these dates" calendar.
 *
 * <p>
 * Two flavours of {@link #addWorkingDays}: a convenience form that fetches working-days config and per-office holidays
 * from the database on every call, and a pre-fetched form for hot loops where the caller has already loaded both.
 * </p>
 */
@RequiredArgsConstructor
public class MnzlWorkingDayCalculator {

    private final WorkingDaysRepositoryWrapper workingDaysRepository;
    private final HolidayRepositoryWrapper holidayRepository;

    /**
     * Returns the calendar date that is {@code workingDays} working days after {@code start}. The {@code start} date
     * itself is never counted, regardless of whether it is a working day. Fetches working-days config and the office's
     * holiday list on every call — prefer {@link #addWorkingDays(LocalDate, int, WorkingDays, List)} when calling in a
     * loop.
     */
    public LocalDate addWorkingDays(LocalDate start, int workingDays, Long officeId) {
        if (start == null || workingDays <= 0) {
            return start;
        }
        return addWorkingDays(start, workingDays, getWorkingDays(), getActiveHolidaysForOffice(officeId, start));
    }

    /**
     * Pre-fetched-context variant of {@link #addWorkingDays(LocalDate, int, Long)}. Hoist the {@code WorkingDays} and
     * {@code holidays} lookups outside loops to avoid repeated DB hits.
     */
    public LocalDate addWorkingDays(LocalDate start, int workingDays, WorkingDays config, List<Holiday> holidays) {
        if (start == null || workingDays <= 0) {
            return start;
        }
        // Safety cap. With a healthy RRULE (>=1 working day per week) and reasonable holiday density, advancing
        // N working days takes at most ~N*7/5 calendar days; the buffer covers long holiday runs. If the RRULE
        // is misconfigured (e.g. no working days), this prevents the COB thread from hanging forever.
        final int maxIterations = workingDays * 7 + 14;
        LocalDate cursor = start;
        int counted = 0;
        for (int i = 0; i < maxIterations && counted < workingDays; i++) {
            cursor = cursor.plusDays(1);
            if (isWorkingDay(cursor, config, holidays)) {
                counted++;
            }
        }
        if (counted < workingDays) {
            throw new IllegalStateException("Could not advance " + workingDays + " working days from " + start + " within " + maxIterations
                    + " iterations — check m_working_days RRULE and m_holiday entries (RRULE was: " + config.getRecurrence() + ")");
        }
        return cursor;
    }

    /**
     * Calendar-day distance between {@code start} and {@code start + workingDays} working days. Useful when an upstream
     * calculation expects an integer day count.
     */
    public int workingDaysToCalendarDays(LocalDate start, int workingDays, Long officeId) {
        LocalDate end = addWorkingDays(start, workingDays, officeId);
        return (int) ChronoUnit.DAYS.between(start, end);
    }

    public boolean isWorkingDay(LocalDate date, Long officeId) {
        return isWorkingDay(date, getWorkingDays(), getActiveHolidaysForOffice(officeId, date));
    }

    /** Loads the global {@code m_working_days} config. Exposed so callers can hoist outside loops. */
    public WorkingDays getWorkingDays() {
        return workingDaysRepository.findOne();
    }

    /**
     * Active holidays for {@code officeId} occurring on or after {@code from}. Returns an empty list when
     * {@code officeId} is {@code null}. Exposed so callers can hoist outside loops.
     */
    public List<Holiday> getActiveHolidaysForOffice(Long officeId, LocalDate from) {
        if (officeId == null) {
            return Collections.emptyList();
        }
        return holidayRepository.findByOfficeIdAndGreaterThanDate(officeId, from);
    }

    private boolean isWorkingDay(LocalDate date, WorkingDays config, List<Holiday> holidays) {
        return WorkingDaysUtil.isWorkingDay(config, date) && !HolidayUtil.isHoliday(date, holidays);
    }
}
