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
package co.mnzl.fineract.custom.receivables.nativeinstrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyData;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.AdjustmentType;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.Allocation;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.FaceLeg;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepository;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NativeReceivableBridgeTest {

    private final LocalDate start = LocalDate.of(2026, 1, 1);
    private final LoanRepository loans = mock(LoanRepository.class);
    private final LoanProductRepository products = mock(LoanProductRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final MnzlLoanProductStrategyReadService strategies = mock(MnzlLoanProductStrategyReadService.class);
    private final AtomicLong ids = new AtomicLong(100);
    private NativeReceivableBridge bridge;
    private Loan saved;

    @BeforeEach
    void setup() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "native-test", "Native Test", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("native-test", 6);
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, start, BusinessDateType.COB_DATE, start.minusDays(1))));
        MonetaryCurrency currency = new MonetaryCurrency("EGP", 2, 1);
        LoanProduct product = mock(LoanProduct.class);
        when(product.getId()).thenReturn(1L);
        when(product.getCurrency()).thenReturn(currency);
        when(product.isAccountingDisabled()).thenReturn(true);
        when(product.getLoanProductRelatedDetail()).thenReturn(LoanProductRelatedDetail.fixedReceivable(currency, BigDecimal.ONE, 2));
        when(products.findById(1L)).thenReturn(Optional.of(product));
        when(strategies.findOne(1L)).thenReturn(MnzlLoanProductStrategyData.builder().loanProductId(1L)
                .instrumentCode(MnzlLoanProductStrategyCodes.INSTRUMENT_PURCHASED_RECEIVABLE)
                .scheduleStrategyCode(MnzlLoanProductStrategyCodes.SCHEDULE_FIXED_RECEIVABLE)
                .chargeStrategyCode(MnzlLoanProductStrategyCodes.CHARGE_NO_BORROWER_CHARGES)
                .cobStrategyCode(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS).build());
        Client client = mock(Client.class);
        when(client.isActive()).thenReturn(true);
        when(client.getOffice()).thenReturn(mock(Office.class));
        when(clients.findById(1L)).thenReturn(Optional.of(client));
        when(loans.findByExternalId(any())).thenReturn(Optional.empty());
        when(loans.saveAndFlush(any(Loan.class))).thenAnswer(invocation -> {
            saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            saved.getRepaymentScheduleInstallments().forEach(period -> {
                if (period.getId() == null) {
                    ReflectionTestUtils.setField(period, "id", ids.incrementAndGet());
                }
            });
            saved.getLoanTransactions().forEach(tx -> {
                if (tx.getId() == null) {
                    ReflectionTestUtils.setField(tx, "id", ids.incrementAndGet());
                }
            });
            return saved;
        });
        when(loans.findById(1L)).thenAnswer(ignored -> Optional.ofNullable(saved));
        bridge = new NativeReceivableBridge(clients, mock(OfficeRepository.class), loans, products, strategies,
                mock(PlatformSecurityContext.class), mock(EntityManager.class));
    }

    @AfterEach
    void cleanup() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCacheForTenant("native-test");
    }

    private NativeReceivableBridge.Booking book() {
        return bridge.bookExactFace(1, 1, "claim-1", start, List.of(new FaceLeg("tiny", start.plusDays(28), BigInteger.ONE),
                new FaceLeg("large", start.plusDays(62), BigInteger.valueOf(9999))));
    }

    @Test
    void exactFaceActivationIsNotCashAndCollectionKeepsSmallCheque() {
        var booked = book();
        assertThat(booked.state().outstandingMinor()).isEqualTo(10000);
        assertThat(saved.getLoanTransactions().getFirst().getTypeOf()).isEqualTo(LoanTransactionType.PURCHASED_RECEIVABLE_ACTIVATION);
        assertThat(saved.getNetDisbursalAmount()).isZero();
        assertThat(saved.getRepaymentScheduleInstallments()).extracting(p -> p.getDueDate()).containsExactly(start.plusDays(28),
                start.plusDays(62));
        var paid = bridge.collect(1, start.plusDays(28), List.of(new Allocation(booked.sourcePeriodIds().get("tiny"), BigInteger.ONE)),
                "cash-1");
        assertThat(paid.state().outstandingMinor()).isEqualTo(9999);
        assertThat(saved.getSummary().getTotalPrincipalRepaid()).isEqualByComparingTo("0.01");
        assertThat(saved.getSummary().getTotalOutstanding()).isEqualByComparingTo("99.99");
    }

    @Test
    void commercialAdjustmentIsNotRepaymentAndReversalRestoresOriginalDateHistory() {
        var booked = book();
        long period = booked.sourcePeriodIds().get("large");
        var paid = bridge.collect(1, start.plusDays(63), List.of(new Allocation(period, BigInteger.valueOf(8000))), "cash-1");
        bridge.reverse(1, paid.transactionId(), start.plusDays(64));
        assertThat(bridge.readIndependentState(1, start.plusDays(63), "AFTER_EVENTS").outstandingMinor()).isEqualTo(2000);
        assertThat(bridge.readIndependentState(1, start.plusDays(64), "BEFORE_EVENTS").outstandingMinor()).isEqualTo(2000);
        assertThat(bridge.readIndependentState(1, start.plusDays(64), "AFTER_EVENTS").outstandingMinor()).isEqualTo(10000);
        bridge.adjustFace(1, start.plusDays(64), AdjustmentType.COMMERCIAL_SETTLEMENT_ADJUSTMENT,
                List.of(new Allocation(period, BigInteger.valueOf(9999))), "discount-1");
        assertThat(saved.getSummary().getTotalPrincipalRepaid()).isZero();
        assertThat(saved.getSummary().getTotalOutstanding()).isEqualByComparingTo("0.01");
        assertThat(saved.getLoanTransactions().get(1).getLoanTransactionToRepaymentScheduleMappings()).hasSize(1);
    }

    @Test
    void eventWatermarkExcludesLaterSameDayCashAndReversal() {
        var booked = book();
        var paid = bridge.collect(1, start.plusDays(28),
                List.of(new Allocation(booked.sourcePeriodIds().get("large"), BigInteger.valueOf(8000))), "cash-1");
        bridge.reverse(1, paid.transactionId(), start.plusDays(28));
        long activation = saved.getLoanTransactions().getFirst().getId();
        assertThat(bridge.readIndependentState(1, start.plusDays(28), "AFTER_EVENTS", java.util.Set.of(activation), java.util.Set.of())
                .outstandingMinor()).isEqualTo(10000);
        assertThat(bridge.readIndependentState(1, start.plusDays(28), "AFTER_EVENTS", java.util.Set.of(activation, paid.transactionId()),
                java.util.Set.of()).outstandingMinor()).isEqualTo(2000);
        assertThat(bridge.readIndependentState(1, start.plusDays(28), "AFTER_EVENTS", java.util.Set.of(activation, paid.transactionId()),
                java.util.Set.of(paid.transactionId())).outstandingMinor()).isEqualTo(10000);
    }

    @Test
    void modificationRetainsOldPeriodsAndReconstructsOldFace() {
        book();
        bridge.replaceSchedule(1, start.plusDays(10), List.of(new FaceLeg("new", start.plusDays(80), BigInteger.valueOf(9500))), "legal-1");
        assertThat(saved.getRepaymentScheduleInstallments()).hasSize(3);
        assertThat(saved.getSummary().getTotalOutstanding()).isEqualByComparingTo("95.00");
        assertThat(bridge.readIndependentState(1, start.plusDays(9), "AFTER_EVENTS").outstandingMinor()).isEqualTo(10000);
        assertThat(bridge.readIndependentState(1, start.plusDays(10), "AFTER_EVENTS").outstandingMinor()).isEqualTo(9500);
    }

    @Test
    void rejectsDuplicatePeriodsAndOverCollectionBeforeChangingAnything() {
        var booked = book();
        long tiny = booked.sourcePeriodIds().get("tiny");
        assertThatThrownBy(() -> bridge.collect(1, start.plusDays(29), List.of(new Allocation(tiny, BigInteger.TWO)), "cash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(saved.getSummary().getTotalOutstanding()).isEqualByComparingTo("100");
        assertThat(saved.getLoanTransactions()).hasSize(1);
    }

    @Test
    void guardBlocksGenericRepaymentAndAllowsOrdinaryLoan() throws NoSuchMethodException {
        book();
        org.aspectj.lang.JoinPoint point = mock(org.aspectj.lang.JoinPoint.class);
        org.aspectj.lang.reflect.MethodSignature signature = mock(org.aspectj.lang.reflect.MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService.class
                .getMethod("writeOff", Long.class, org.apache.fineract.infrastructure.core.api.JsonCommand.class));
        when(point.getArgs()).thenReturn(new Object[] { 1L, null });
        PurchasedReceivableMutationGuard guard = new PurchasedReceivableMutationGuard(loans);
        assertThatThrownBy(() -> guard.guard(point)).isInstanceOf(IllegalStateException.class);
        when(loans.findById(1L)).thenReturn(Optional.of(mock(Loan.class)));
        guard.guard(point);
    }

    @Test
    void rejectsValuesExceedingNativePrecision() {
        assertThatThrownBy(() -> bridge.bookExactFace(1, 1, "too-large", start,
                List.of(new FaceLeg("leg", start.plusDays(1), new BigInteger("1000000000000000")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
