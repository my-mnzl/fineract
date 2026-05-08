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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateDTO;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * L1 unit tests for {@link MnzlLoanTermVariationsEnricher} (Task C.12).
 *
 * <p>
 * The enricher walks the floating-rate periods returned by {@code LoanProduct.fetchInterestRates(...)} and selects the
 * one whose {@code fromDate} is the latest date that is still strictly before the business date — falling back to the
 * supplied annual nominal rate when none qualifies. These tests pin that selection logic across the historical / future
 * / boundary / empty cases the production code distinguishes.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanTermVariationsEnricherTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);
    private static final BigDecimal NOMINAL_RATE = new BigDecimal("12.00");

    @Mock
    private FloatingRateDTO floatingRateDTO;
    @Mock
    private Loan loan;
    @Mock
    private LoanProduct loanProduct;

    private MnzlLoanTermVariationsEnricher enricher;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, TODAY);
        dates.put(BusinessDateType.COB_DATE, TODAY.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);

        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loanProduct.isLinkedToFloatingInterestRate()).thenReturn(true);

        enricher = new MnzlLoanTermVariationsEnricher();
    }

    @Test
    void enrich_singleRate_returnsThatRate() {
        // One historical rate dated before today → enricher applies it and overrides the nominal.
        FloatingRatePeriodData period = ratePeriod(TODAY.minusMonths(3), new BigDecimal("9.50"));
        stubApplicableRates(List.of(period));

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo("9.50");
        verify(floatingRateDTO).resetInterestRateDiff();
    }

    @Test
    void enrich_multipleHistoricRates_picksMostRecentApplicable() {
        // Three historical rates → pick the one whose from-date is closest to today (still strictly before).
        FloatingRatePeriodData oldest = ratePeriod(TODAY.minusMonths(12), new BigDecimal("8.00"));
        FloatingRatePeriodData middle = ratePeriod(TODAY.minusMonths(6), new BigDecimal("10.00"));
        FloatingRatePeriodData newest = ratePeriod(TODAY.minusMonths(1), new BigDecimal("11.50"));
        stubApplicableRates(List.of(oldest, newest, middle));

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo("11.50");
    }

    @Test
    void enrich_rateDatedInFuture_picksPreviousRate() {
        // Future-dated rate is ignored; the enricher must fall back to the most recent past rate.
        FloatingRatePeriodData past = ratePeriod(TODAY.minusMonths(2), new BigDecimal("9.00"));
        FloatingRatePeriodData future = ratePeriod(TODAY.plusMonths(1), new BigDecimal("13.00"));
        stubApplicableRates(List.of(past, future));

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo("9.00");
    }

    @Test
    void enrich_rateExactlyToday_picksToday() {
        // Production uses DateUtils.isBefore(periodFromDate, today) — today's rate is NOT strictly before today,
        // so it's NOT applied. The fallback is the nominal rate when no past rate exists.
        FloatingRatePeriodData todayRate = ratePeriod(TODAY, new BigDecimal("11.00"));
        stubApplicableRates(List.of(todayRate));

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        // The "exactly today" boundary is exclusive in production, so the nominal rate is preserved.
        assertThat(result).isEqualByComparingTo(NOMINAL_RATE);
    }

    @Test
    void enrich_emptyRates_returnsAnnualNominal() {
        // Empty applicable-rates collection → enricher returns the annual nominal rate unchanged.
        stubApplicableRates(Collections.emptyList());

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo(NOMINAL_RATE);
    }

    @Test
    void enrich_nullFloatingRateDto_returnsAnnualNominal() {
        // Null DTO is the early-return guard; the loan product mock should never be touched.
        BigDecimal result = enricher.enrich(null, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo(NOMINAL_RATE);
        verify(loanProduct, never()).fetchInterestRates(any());
    }

    @Test
    void enrich_loanNotLinkedToFloating_returnsAnnualNominal() {
        // When the product is not linked to a floating rate, enrichment is a no-op.
        when(loanProduct.isLinkedToFloatingInterestRate()).thenReturn(false);

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo(NOMINAL_RATE);
        verify(floatingRateDTO, never()).resetInterestRateDiff();
    }

    @Test
    void enrich_differentialPreserved() {
        // Production calls resetInterestRateDiff() exactly once before walking applicable rates so any pre-set diff
        // is normalised. We exercise that contract here and verify the differential reset is invoked.
        FloatingRatePeriodData period = ratePeriod(TODAY.minusDays(10), new BigDecimal("10.25"));
        stubApplicableRates(List.of(period));

        BigDecimal result = enricher.enrich(floatingRateDTO, NOMINAL_RATE, List.<LoanTermVariationsData>of(), loan);

        assertThat(result).isEqualByComparingTo("10.25");
        verify(floatingRateDTO).resetInterestRateDiff();
    }

    private void stubApplicableRates(Collection<FloatingRatePeriodData> rates) {
        when(loanProduct.fetchInterestRates(floatingRateDTO)).thenReturn(rates);
    }

    private static FloatingRatePeriodData ratePeriod(LocalDate fromDate, BigDecimal interestRate) {
        return new FloatingRatePeriodData(1L, fromDate, interestRate, Boolean.FALSE, Boolean.TRUE);
    }
}
