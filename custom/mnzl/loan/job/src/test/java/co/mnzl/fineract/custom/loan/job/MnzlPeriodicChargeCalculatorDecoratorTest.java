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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelPeriod;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformServiceImpl;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlPeriodicChargeCalculatorDecoratorTest {

    private static final Long PRODUCT_ID = 2L;
    private static final Long PERIODIC_CHARGE_ID = 9L;

    @Mock
    private LoanScheduleCalculationPlatformServiceImpl delegate;

    @Mock
    private LoanProductRepository loanProductRepository;

    @Mock
    private MnzlPeriodicChargeProjectionService projectionService;

    @Mock
    private LoanProduct loanProduct;

    @Mock
    private Charge periodicCharge;

    @Mock
    private LoanScheduleModel scheduleModel;

    private MnzlPeriodicChargeCalculatorDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new MnzlPeriodicChargeCalculatorDecorator(delegate, loanProductRepository, projectionService);

        lenient().when(loanProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(loanProduct));
        lenient().when(loanProduct.getCharges()).thenReturn(List.of(periodicCharge));
        lenient().when(periodicCharge.isActive()).thenReturn(true);
        lenient().when(periodicCharge.isLoanCharge()).thenReturn(true);
        lenient().when(periodicCharge.isLoanPeriodic()).thenReturn(true);
        lenient().when(periodicCharge.getId()).thenReturn(PERIODIC_CHARGE_ID);
        lenient().when(periodicCharge.getAmount()).thenReturn(new BigDecimal("0.01"));
    }

    @Test
    void appendsProjectedChargesAndInvokesDelegateTwiceWhenProductHasPeriodicCharges() {
        final LocalDate anchor = LocalDate.of(2026, 5, 20);
        final LocalDate second = LocalDate.of(2027, 5, 20);
        final LocalDate maturity = LocalDate.of(2029, 4, 20);
        final LoanScheduleModelPeriod p1 = period(anchor, true, false);
        final LoanScheduleModelPeriod p2 = period(second, true, false);
        final LoanScheduleModelPeriod p3 = period(LocalDate.of(2028, 5, 20), true, false);
        final LoanScheduleModelPeriod p4 = period(maturity, true, false);
        when(scheduleModel.getPeriods()).thenReturn(List.of(p1, p2, p3, p4));
        when(delegate.calculateLoanSchedule(any(JsonQuery.class), anyBoolean())).thenReturn(scheduleModel);
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(anchor, second));

        final JsonQuery query = makeQuery("""
                {
                  "productId": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": []
                }
                """);

        decorator.calculateLoanSchedule(query, true);

        verify(delegate, times(2)).calculateLoanSchedule(any(JsonQuery.class), anyBoolean());
        final JsonArray charges = query.parsedJson().getAsJsonObject().getAsJsonArray("charges");
        assertThat(charges.size()).isEqualTo(2);
        assertThat(charges.get(0).getAsJsonObject().get("chargeId").getAsLong()).isEqualTo(PERIODIC_CHARGE_ID);
        assertThat(charges.get(0).getAsJsonObject().get("dueDate").getAsString()).isEqualTo("20 May 2026");
        assertThat(charges.get(1).getAsJsonObject().get("dueDate").getAsString()).isEqualTo("20 May 2027");
    }

    @Test
    void skipsOccurrencesAlreadyPresentInRequest() {
        final LocalDate existing = LocalDate.of(2026, 5, 20);
        final LocalDate other = LocalDate.of(2027, 5, 20);
        final LoanScheduleModelPeriod p1 = period(existing, true, false);
        final LoanScheduleModelPeriod p2 = period(other, true, false);
        when(scheduleModel.getPeriods()).thenReturn(List.of(p1, p2));
        when(delegate.calculateLoanSchedule(any(JsonQuery.class), anyBoolean())).thenReturn(scheduleModel);
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(existing, other));

        final JsonQuery query = makeQuery("""
                {
                  "productId": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [{"chargeId": 9, "amount": 0.01, "dueDate": "20 May 2026"}]
                }
                """);

        decorator.calculateLoanSchedule(query, false);

        final JsonArray charges = query.parsedJson().getAsJsonObject().getAsJsonArray("charges");
        assertThat(charges.size()).isEqualTo(2);
        assertThat(charges.get(0).getAsJsonObject().get("dueDate").getAsString()).isEqualTo("20 May 2026");
        assertThat(charges.get(1).getAsJsonObject().get("dueDate").getAsString()).isEqualTo("20 May 2027");
    }

    @Test
    void isSingleCallNoOpWhenProductHasNoPeriodicCharges() {
        when(loanProduct.getCharges()).thenReturn(List.of());
        when(delegate.calculateLoanSchedule(any(JsonQuery.class), anyBoolean())).thenReturn(scheduleModel);

        final JsonQuery query = makeQuery("""
                {"productId": 2, "charges": []}
                """);

        final LoanScheduleModel result = decorator.calculateLoanSchedule(query, false);

        assertThat(result).isSameAs(scheduleModel);
        verify(delegate, times(1)).calculateLoanSchedule(any(JsonQuery.class), anyBoolean());
        verify(projectionService, never()).occurrencesBetween(any(), any(), any());
    }

    @Test
    void isSingleCallNoOpWhenRequestHasNoProductId() {
        when(delegate.calculateLoanSchedule(any(JsonQuery.class), anyBoolean())).thenReturn(scheduleModel);

        final JsonQuery query = makeQuery("""
                {"charges": []}
                """);

        final LoanScheduleModel result = decorator.calculateLoanSchedule(query, false);

        assertThat(result).isSameAs(scheduleModel);
        verify(delegate, times(1)).calculateLoanSchedule(any(JsonQuery.class), anyBoolean());
    }

    @Test
    void ignoresDownPaymentPeriodsWhenDerivingAnchorAndMaturity() {
        final LocalDate downPaymentDate = LocalDate.of(2026, 4, 20);
        final LocalDate repaymentStart = LocalDate.of(2026, 5, 20);
        final LocalDate repaymentEnd = LocalDate.of(2029, 4, 20);
        final LoanScheduleModelPeriod dp = period(downPaymentDate, true, true);
        final LoanScheduleModelPeriod start = period(repaymentStart, true, false);
        final LoanScheduleModelPeriod end = period(repaymentEnd, true, false);
        when(scheduleModel.getPeriods()).thenReturn(List.of(dp, start, end));
        when(delegate.calculateLoanSchedule(any(JsonQuery.class), anyBoolean())).thenReturn(scheduleModel);
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(repaymentStart));

        final JsonQuery query = makeQuery("""
                {"productId": 2, "dateFormat": "dd MMMM yyyy", "locale": "en", "charges": []}
                """);

        decorator.calculateLoanSchedule(query, false);

        final ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
        final ArgumentCaptor<LocalDate> maturityCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(projectionService).occurrencesBetween(eq(periodicCharge), anchorCaptor.capture(), maturityCaptor.capture());
        assertThat(anchorCaptor.getValue()).isEqualTo(repaymentStart);
        assertThat(maturityCaptor.getValue()).isEqualTo(repaymentEnd);
    }

    private LoanScheduleModelPeriod period(final LocalDate dueDate, final boolean repayment, final boolean downPayment) {
        final LoanScheduleModelPeriod p = org.mockito.Mockito.mock(LoanScheduleModelPeriod.class);
        when(p.periodDueDate()).thenReturn(dueDate);
        when(p.isRepaymentPeriod()).thenReturn(repayment);
        when(p.isDownPaymentPeriod()).thenReturn(downPayment);
        return p;
    }

    private JsonQuery makeQuery(final String json) {
        final JsonElement parsed = JsonParser.parseString(json);
        return JsonQuery.from(json, parsed, null);
    }

    // ---------------- C.8 expansions ----------------
    //
    // Skipped (already covered by existing methods above):
    // * previewSchedule_includesProjectedPeriodicCharges -> see
    // appendsProjectedChargesAndInvokesDelegateTwiceWhenProductHasPeriodicCharges
    // * previewSchedule_dedupesExistingChargeWithSameKey -> see skipsOccurrencesAlreadyPresentInRequest
    //
    // Adapted: the prompt asked about repaymentsStartingFromDate overriding disbursement, but the decorator derives
    // anchor/maturity from the LoanScheduleModel returned by the delegate, NOT from JSON fields directly. The test
    // below pins this production behaviour: anchor always comes from the schedule model regardless of JSON values.

    @Test
    void anchorComesFromScheduleModelEvenWhenJsonHasRepaymentsStartingFromDate() {
        final LocalDate scheduleAnchor = LocalDate.of(2026, 7, 1);
        final LocalDate scheduleMaturity = LocalDate.of(2027, 6, 1);
        final LoanScheduleModelPeriod start = period(scheduleAnchor, true, false);
        final LoanScheduleModelPeriod end = period(scheduleMaturity, true, false);
        when(scheduleModel.getPeriods()).thenReturn(List.of(start, end));
        when(delegate.calculateLoanSchedule(any(JsonQuery.class), anyBoolean())).thenReturn(scheduleModel);
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(scheduleAnchor));

        final JsonQuery query = makeQuery("""
                {
                  "productId": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "expectedDisbursementDate": "01 January 2026",
                  "repaymentsStartingFromDate": "01 February 2026",
                  "charges": []
                }
                """);

        decorator.calculateLoanSchedule(query, false);

        final ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
        final ArgumentCaptor<LocalDate> maturityCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(projectionService).occurrencesBetween(eq(periodicCharge), anchorCaptor.capture(), maturityCaptor.capture());
        // Decorator anchor = schedule model first repayment (07/01), NOT JSON repaymentsStartingFromDate (02/01).
        assertThat(anchorCaptor.getValue()).isEqualTo(scheduleAnchor);
        assertThat(maturityCaptor.getValue()).isEqualTo(scheduleMaturity);
    }
}
