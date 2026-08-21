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

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for d5d8cf882 (Use 30E/360 convention for 30-day month calculation). The 30E/360 convention caps
 * both endpoints' day-of-month at 30, regardless of the actual month length. These tests pin the day-31 capping
 * behavior in both directions so a reversion to the unbounded ((endDay - startDay) + months*30) form is caught.
 */
class MnzlLoanScheduleMath30E360ConventionTest {

    @Test
    void bothEndpointsAtDay31_areCapped() {
        // Both Jan-31 and Mar-31 cap to 30: 0*360 + 2*30 + (30 - 30) = 60.
        // Without 30E/360 capping the formula degenerates to a different value (e.g. 59 actual days).
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31)))
                .isEqualTo(60);
    }

    @Test
    void startEndpointDay31_capped() {
        // Jan-31 caps to 30, Feb-15 unchanged: 0 + 1*30 + (15 - 30) = 15. Pre-fix would have under-counted.
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 15)))
                .isEqualTo(15);
    }

    @Test
    void endEndpointDay31_capped() {
        // Jan-15 unchanged, Jan-31 caps to 30: 0 + 0 + (30 - 15) = 15. Without cap this would have been 16.
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31)))
                .isEqualTo(15);
    }

    @Test
    void neitherEndpointDay31_unchanged() {
        // Both endpoints below 31: cap is a no-op. 0 + 1*30 + (15 - 5) = 40.
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 4, 15)))
                .isEqualTo(40);
    }

    @Test
    void crossYearDay31_capped() {
        // 2025-12-31 -> 2026-01-01 with 30E/360: start day caps to 30.
        // (2026-2025)*360 + (1-12)*30 + (1-30) = 360 - 330 - 29 = 1.
        assertThat(MnzlLoanScheduleMath.getDifferenceInDaysFor30DayMonth(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)))
                .isEqualTo(1);
    }
}
