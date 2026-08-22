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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainService;
import org.apache.fineract.portfolio.loanaccount.data.CollectionData;
import org.apache.fineract.portfolio.loanaccount.data.LoanDelinquencyData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanDelinquencyDomainServiceTest {

    private static final long OFFICE_ID = 7L;

    @Mock
    private LoanDelinquencyDomainService delegate;
    @Mock
    private MnzlWorkingDayCalculator workingDayCalculator;
    @Mock
    private Loan loan;
    @Mock
    private Office office;
    @Mock
    private LoanProductRelatedDetail productDetail;

    private MnzlLoanDelinquencyDomainService service;

    @BeforeEach
    void setUp() {
        service = new MnzlLoanDelinquencyDomainService(delegate, workingDayCalculator);
        when(loan.getLoanProductRelatedDetail()).thenReturn(productDetail);
        when(loan.getOffice()).thenReturn(office);
        when(office.getId()).thenReturn(OFFICE_ID);
    }

    @Test
    void noGraceConfiguredLeavesDelegateOutputUnchanged() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(0);
        CollectionData base = collectionDataWith(LocalDate.of(2025, 1, 10), 5L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(result.getDelinquentDays()).isEqualTo(5L);
        verify(workingDayCalculator, never()).addWorkingDays(any(), anyInt(), any());
    }

    @Test
    void nullDelinquentDateLeavesDelegateOutputUnchanged() {
        CollectionData base = collectionDataWith(null, 0L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isNull();
        verify(workingDayCalculator, never()).addWorkingDays(any(), anyInt(), any());
    }

    @Test
    void workingDayGraceLandingOnSameDayAsCalendarLeavesOutputUnchanged() {
        // No weekends/holidays inside the grace window — addWorkingDays returns the same date as calendar plusDays.
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(5);
        LocalDate calendarDelinquent = LocalDate.of(2025, 1, 15);
        LocalDate overdueSince = LocalDate.of(2025, 1, 10);
        CollectionData base = collectionDataWith(calendarDelinquent, 3L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(5), eq(OFFICE_ID))).thenReturn(calendarDelinquent);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(calendarDelinquent);
        assertThat(result.getDelinquentDays()).isEqualTo(3L);
    }

    @Test
    void workingDayGraceShiftsDelinquentDateForwardAndReducesDelinquentDays() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(5);
        LocalDate overdueSince = LocalDate.of(2025, 1, 5);
        LocalDate calendarDelinquent = LocalDate.of(2025, 1, 10);
        LocalDate workingDelinquent = LocalDate.of(2025, 1, 12); // 2 weekend days inside the window
        CollectionData base = collectionDataWith(calendarDelinquent, 4L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(5), eq(OFFICE_ID))).thenReturn(workingDelinquent);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(workingDelinquent);
        assertThat(result.getDelinquentDays()).isEqualTo(2L); // 4 calendar delinquent − 2 extra grace days = 2
    }

    @Test
    void workingDayGraceClampsDelinquentDaysAtZero() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(5);
        LocalDate overdueSince = LocalDate.of(2025, 1, 5);
        LocalDate calendarDelinquent = LocalDate.of(2025, 1, 10);
        LocalDate workingDelinquent = LocalDate.of(2025, 1, 13); // 3 extra grace days
        CollectionData base = collectionDataWith(calendarDelinquent, 1L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(5), eq(OFFICE_ID))).thenReturn(workingDelinquent);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(workingDelinquent);
        assertThat(result.getDelinquentDays()).isZero();
    }

    @Test
    void workingDayGraceLeavesZeroDelinquentDaysAtZero() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(5);
        LocalDate overdueSince = LocalDate.of(2025, 1, 5);
        LocalDate calendarDelinquent = LocalDate.of(2025, 1, 10);
        LocalDate workingDelinquent = LocalDate.of(2025, 1, 12);
        CollectionData base = collectionDataWith(calendarDelinquent, 0L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(5), eq(OFFICE_ID))).thenReturn(workingDelinquent);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(workingDelinquent);
        assertThat(result.getDelinquentDays()).isZero();
    }

    @Test
    void getLoanDelinquencyDataAdjustsTheLoanCollectionData() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(5);
        LocalDate overdueSince = LocalDate.of(2025, 1, 5);
        LocalDate calendarDelinquent = LocalDate.of(2025, 1, 10);
        LocalDate workingDelinquent = LocalDate.of(2025, 1, 12);
        CollectionData loanLevel = collectionDataWith(calendarDelinquent, 4L);
        LoanDelinquencyData base = new LoanDelinquencyData(loanLevel, Collections.emptyMap());
        when(delegate.getLoanDelinquencyData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(5), eq(OFFICE_ID))).thenReturn(workingDelinquent);

        LoanDelinquencyData result = service.getLoanDelinquencyData(loan, Collections.emptyList());

        assertThat(result.getLoanCollectionData().getDelinquentDate()).isEqualTo(workingDelinquent);
        assertThat(result.getLoanCollectionData().getDelinquentDays()).isEqualTo(2L);
    }

    private static CollectionData collectionDataWith(LocalDate delinquentDate, Long delinquentDays) {
        CollectionData data = CollectionData.template();
        data.setDelinquentDate(delinquentDate);
        data.setDelinquentDays(delinquentDays);
        data.setDelinquentAmount(BigDecimal.ZERO);
        return data;
    }

    // ---------------------------------------------------------------------
    // Task C.11 expansions — working-day-aware delinquency semantics
    // ---------------------------------------------------------------------

    /**
     * Smoke: when grace=0 the wrapper is a strict passthrough — delegate result is returned unchanged and the
     * working-day calculator is never consulted. Complements the existing noGraceConfigured / nullDelinquentDate
     * coverage by asserting the actual returned object identity is the same as the delegate's.
     */
    @Test
    void passthroughBehaviorPreserved() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(0);
        CollectionData base = collectionDataWith(LocalDate.of(2025, 2, 20), 7L);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result).isSameAs(base);
        assertThat(result.getDelinquentDate()).isEqualTo(LocalDate.of(2025, 2, 20));
        assertThat(result.getDelinquentDays()).isEqualTo(7L);
        verify(workingDayCalculator, never()).addWorkingDays(any(), anyInt(), any());
    }

    /**
     * Weekend in the grace range — mnzl shifts the delinquent date forward and reduces delinquentDays vs upstream.
     */
    @Test
    void workingDayAwareDelinquencyDays_differsFromUpstream_whenWeekendInRange() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(5);
        LocalDate overdueSince = LocalDate.of(2025, 1, 5);
        LocalDate calendarDelinquent = LocalDate.of(2025, 1, 10);
        // 2 weekend days inside the grace window push the working-day-aware delinquent date out by 2.
        LocalDate workingDelinquent = LocalDate.of(2025, 1, 12);
        Long upstreamDelinquentDays = 6L;
        CollectionData base = collectionDataWith(calendarDelinquent, upstreamDelinquentDays);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(5), eq(OFFICE_ID))).thenReturn(workingDelinquent);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(workingDelinquent);
        assertThat(result.getDelinquentDays()).isLessThan(upstreamDelinquentDays);
        assertThat(result.getDelinquentDays()).isEqualTo(4L); // 6 − 2 extra grace days
    }

    /**
     * All-business-days grace range — mnzl returns the same delinquentDate / delinquentDays as upstream.
     */
    @Test
    void workingDayAwareDelinquencyDays_matchesUpstream_whenNoWeekendOrHoliday() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(3);
        // Tuesday → Friday, no weekend/holiday in range.
        LocalDate overdueSince = LocalDate.of(2025, 1, 7);
        LocalDate sameDayBothCalcs = LocalDate.of(2025, 1, 10);
        Long upstreamDelinquentDays = 4L;
        CollectionData base = collectionDataWith(sameDayBothCalcs, upstreamDelinquentDays);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(3), eq(OFFICE_ID))).thenReturn(sameDayBothCalcs);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(sameDayBothCalcs);
        assertThat(result.getDelinquentDays()).isEqualTo(upstreamDelinquentDays);
    }

    /**
     * Holiday in the grace range — mnzl skips the holiday and returns fewer delinquentDays than upstream.
     */
    @Test
    void workingDayAwareDelinquencyDays_holidayInRange_skipsHoliday() {
        when(productDetail.getGraceOnArrearsAgeing()).thenReturn(3);
        LocalDate overdueSince = LocalDate.of(2025, 3, 3); // Monday
        LocalDate calendarDelinquent = LocalDate.of(2025, 3, 6); // 3 calendar days later
        // One mid-week holiday inside the window pushes the working-day-aware delinquent date out by 1.
        LocalDate workingDelinquent = LocalDate.of(2025, 3, 7);
        Long upstreamDelinquentDays = 5L;
        CollectionData base = collectionDataWith(calendarDelinquent, upstreamDelinquentDays);
        when(delegate.getOverdueCollectionData(eq(loan), any())).thenReturn(base);
        when(workingDayCalculator.addWorkingDays(eq(overdueSince), eq(3), eq(OFFICE_ID))).thenReturn(workingDelinquent);

        CollectionData result = service.getOverdueCollectionData(loan, Collections.emptyList());

        assertThat(result.getDelinquentDate()).isEqualTo(workingDelinquent);
        assertThat(result.getDelinquentDays()).isLessThan(upstreamDelinquentDays);
        assertThat(result.getDelinquentDays()).isEqualTo(4L); // 5 − 1 extra grace day
    }
}
