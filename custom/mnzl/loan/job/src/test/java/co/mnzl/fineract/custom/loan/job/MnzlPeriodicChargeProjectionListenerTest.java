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
package co.mnzl.fineract.custom.loan.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.event.business.BusinessEventListener;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pins the listener wiring locked in by commit 04a3180e4 — periodic charges are projected at loan submission via
 * {@link LoanCreatedBusinessEvent} instead of via the legacy cron job that the same commit removed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlPeriodicChargeProjectionListenerTest {

    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    @Mock
    private MnzlPeriodicChargeProjectionService projectionService;

    @Mock
    private Loan loan;

    @Mock
    private LoanCreatedBusinessEvent event;

    private MnzlPeriodicChargeProjectionListener listener;

    @BeforeEach
    void setUp() {
        listener = new MnzlPeriodicChargeProjectionListener(businessEventNotifierService, projectionService);
        when(loan.getId()).thenReturn(42L);
        when(event.get()).thenReturn(loan);
    }

    @Test
    void registerSubscribesPostListenerForLoanCreatedBusinessEvent() {
        listener.register();

        verify(businessEventNotifierService, times(1)).addPostBusinessEventListener(eq(LoanCreatedBusinessEvent.class),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onLoanCreatedBusinessEvent_callsProjectionServiceExactlyOnce() {
        when(projectionService.projectFullTermPeriodicCharges(loan)).thenReturn(3);

        captureListener().onBusinessEvent(event);

        verify(projectionService, times(1)).projectFullTermPeriodicCharges(loan);
    }

    @Test
    void onDuplicateEvent_invokesProjectionServiceForEachEvent() {
        // Production behaviour: the listener does not dedupe events. Each LoanCreatedBusinessEvent triggers a fresh
        // call to projectFullTermPeriodicCharges. Per-occurrence dedupe (by chargeId+dueDate) lives inside the
        // projection service, so two firings still produce a consistent, non-duplicated set of LoanCharge rows.
        when(projectionService.projectFullTermPeriodicCharges(loan)).thenReturn(2, 0);

        final BusinessEventListener<LoanCreatedBusinessEvent> captured = captureListener();
        captured.onBusinessEvent(event);
        captured.onBusinessEvent(event);

        verify(projectionService, times(2)).projectFullTermPeriodicCharges(loan);
    }

    @Test
    void onLoanCreatedNoLoanPeriodicCharges_noOp() {
        // When the loan's product has no LOAN_PERIODIC charges, the projection service returns 0 (no-op). The listener
        // must still complete cleanly without throwing.
        when(projectionService.projectFullTermPeriodicCharges(loan)).thenReturn(0);

        captureListener().onBusinessEvent(event);

        verify(projectionService, times(1)).projectFullTermPeriodicCharges(loan);
    }

    @Test
    void onLoanCreatedAtSubmission_projectsImmediately_regression04a3180e4() {
        // Regression for 04a3180e4: prior to that commit, periodic charges were applied by the cron job
        // 'Apply Periodic Loan Charges'. After the commit, they must be projected synchronously when the
        // LoanCreatedBusinessEvent fires (i.e. inside the same submission transaction).
        when(projectionService.projectFullTermPeriodicCharges(loan)).thenReturn(5);

        captureListener().onBusinessEvent(event);

        // Exactly one projection call, on the correct loan, happens in-line with the event — no scheduling/deferral.
        verify(projectionService, times(1)).projectFullTermPeriodicCharges(loan);
    }

    @Test
    void onBusinessEvent_propagatesProjectionFailures() {
        // The listener catches RuntimeExceptions only to log, then re-throws so the submission transaction rolls back.
        final RuntimeException boom = new RuntimeException("projection failed");
        when(projectionService.projectFullTermPeriodicCharges(loan)).thenThrow(boom);

        final BusinessEventListener<LoanCreatedBusinessEvent> captured = captureListener();

        assertThatThrownBy(() -> captured.onBusinessEvent(event)).isSameAs(boom);
    }

    @SuppressWarnings("unchecked")
    private BusinessEventListener<LoanCreatedBusinessEvent> captureListener() {
        listener.register();
        final ArgumentCaptor<BusinessEventListener<LoanCreatedBusinessEvent>> captor = ArgumentCaptor.forClass(BusinessEventListener.class);
        verify(businessEventNotifierService).addPostBusinessEventListener(eq(LoanCreatedBusinessEvent.class), captor.capture());
        final BusinessEventListener<LoanCreatedBusinessEvent> registered = captor.getValue();
        assertThat(registered).isNotNull();
        return registered;
    }
}
