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
package co.mnzl.fineract.custom.charge.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.service.DefaultAmountInterestPenaltiesChargeCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Regression test pinning the behaviour introduced in commit {@code 37ef1d676} ("Add compounded penalty fee support").
 *
 * The custom calculator's
 * {@link CustomAmountInterestPenaltiesChargeCalculator#calculateOverdueAmountPercentageAppliedTo} adds
 * {@code installment.getPenaltyChargesOutstanding} to the per-period base. When a prior period's penalty has been
 * applied to an installment it propagates into that installment's penalty-outstanding figure, so subsequent
 * percentage-based penalty fees are levied on a base that already contains earlier penalties. This produces
 * penalty-on-penalty compounding across consecutive overdue periods.
 */
@ExtendWith(MockitoExtension.class)
class CompoundedPenaltyFeeAcrossPeriodsTest {

    private static final MonetaryCurrency USD = new MonetaryCurrency("USD", 4, null);

    private CustomAmountInterestPenaltiesChargeCalculator calculator;
    private DefaultAmountInterestPenaltiesChargeCalculator defaultCalculator;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6); // HALF_EVEN
        calculator = new CustomAmountInterestPenaltiesChargeCalculator();
        defaultCalculator = new DefaultAmountInterestPenaltiesChargeCalculator();
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCacheForTenant("default");
    }

    @Test
    void penaltyOnPenalty_threeConsecutivePeriods_compoundsCorrectly() {
        Loan loan = mockLoan(BigDecimal.ZERO);
        BigDecimal percentage = BigDecimal.valueOf(1); // 1%

        // Period 1: principal=10000, interest=100, no carried penalty.
        LoanRepaymentScheduleInstallment p1 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), BigDecimal.ZERO);
        Money base1 = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p1);
        BigDecimal penalty1 = LoanCharge.percentageOf(base1.getAmount(), percentage);
        assertThat(base1.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10100));
        assertThat(penalty1).isEqualByComparingTo(new BigDecimal("101"));

        // Period 2: principal=10000, interest=100, carried penalty from P1 = 101.
        LoanRepaymentScheduleInstallment p2 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), penalty1);
        Money base2 = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p2);
        BigDecimal penalty2 = LoanCharge.percentageOf(base2.getAmount(), percentage);
        assertThat(base2.getAmount()).isEqualByComparingTo(new BigDecimal("10201"));
        assertThat(penalty2).isEqualByComparingTo(new BigDecimal("102.01"));

        // Period 3: principal=10000, interest=100, carried penalty from P1 + P2 = 203.01.
        BigDecimal carriedToP3 = penalty1.add(penalty2);
        LoanRepaymentScheduleInstallment p3 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), carriedToP3);
        Money base3 = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p3);
        BigDecimal penalty3 = LoanCharge.percentageOf(base3.getAmount(), percentage);
        assertThat(base3.getAmount()).isEqualByComparingTo(new BigDecimal("10303.01"));
        assertThat(penalty3).isEqualByComparingTo(new BigDecimal("103.0301"));
    }

    @Test
    void penaltyOnPenalty_zeroBaseLine_noCompounding() {
        Loan loan = mockLoan(BigDecimal.ZERO);

        // All three installments carry zero penalty-outstanding -> no compounding contribution.
        LoanRepaymentScheduleInstallment p1 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), BigDecimal.ZERO);
        LoanRepaymentScheduleInstallment p2 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), BigDecimal.ZERO);
        LoanRepaymentScheduleInstallment p3 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), BigDecimal.ZERO);

        for (LoanRepaymentScheduleInstallment p : new LoanRepaymentScheduleInstallment[] { p1, p2, p3 }) {
            Money customBase = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p);
            Money defaultBase = defaultCalculator.calculateOverdueAmountPercentageAppliedTo(loan, p);
            assertThat(customBase.getAmount()).isEqualByComparingTo(defaultBase.getAmount());
            assertThat(customBase.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10100));
        }
    }

    @Test
    void penaltyOnPenalty_partialPayment_reducesCompounding() {
        Loan loan = mockLoan(BigDecimal.ZERO);
        BigDecimal percentage = BigDecimal.valueOf(1);

        // Period 1 accrues a penalty of 101 on (10000 + 100).
        LoanRepaymentScheduleInstallment p1 = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), BigDecimal.ZERO);
        Money base1 = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p1);
        BigDecimal penalty1 = LoanCharge.percentageOf(base1.getAmount(), percentage);
        assertThat(penalty1).isEqualByComparingTo(new BigDecimal("101"));

        // Period 2 (compounding): inherits penalty 101 from P1.
        LoanRepaymentScheduleInstallment p2Compounding = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100), penalty1);
        Money baseCompounding = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p2Compounding);
        BigDecimal penalty2Compounding = LoanCharge.percentageOf(baseCompounding.getAmount(), percentage);
        assertThat(baseCompounding.getAmount()).isEqualByComparingTo(new BigDecimal("10201"));
        assertThat(penalty2Compounding).isEqualByComparingTo(new BigDecimal("102.01"));

        // Period 2 (after the borrower paid off P1's penalty): outstanding penalty drops back to zero,
        // so the compounded contribution disappears and the base equals the default calculator's.
        LoanRepaymentScheduleInstallment p2AfterPayment = mockInstallment(BigDecimal.valueOf(10000), BigDecimal.valueOf(100),
                BigDecimal.ZERO);
        Money baseAfterPayment = calculator.calculateOverdueAmountPercentageAppliedTo(loan, p2AfterPayment);
        Money defaultBaseAfterPayment = defaultCalculator.calculateOverdueAmountPercentageAppliedTo(loan, p2AfterPayment);
        BigDecimal penalty2AfterPayment = LoanCharge.percentageOf(baseAfterPayment.getAmount(), percentage);

        assertThat(baseAfterPayment.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10100));
        assertThat(baseAfterPayment.getAmount()).isEqualByComparingTo(defaultBaseAfterPayment.getAmount());
        assertThat(penalty2AfterPayment).isEqualByComparingTo(new BigDecimal("101"));

        // Concretely: paying off P1's penalty saves the borrower the compounding delta on P2.
        assertThat(penalty2Compounding.subtract(penalty2AfterPayment)).isEqualByComparingTo(new BigDecimal("1.01"));
    }

    // ---- Helpers ----

    private Loan mockLoan(BigDecimal penaltyOutstanding) {
        Loan loan = mock(Loan.class);
        LoanSummary summary = mock(LoanSummary.class);
        lenient().when(loan.getCurrency()).thenReturn(USD);
        lenient().when(loan.getSummary()).thenReturn(summary);
        lenient().when(summary.getTotalPenaltyChargesOutstanding()).thenReturn(penaltyOutstanding);
        return loan;
    }

    private LoanRepaymentScheduleInstallment mockInstallment(BigDecimal principalOutstanding, BigDecimal interestOutstanding,
            BigDecimal penaltyOutstanding) {
        LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getPrincipalOutstanding(USD)).thenReturn(Money.of(USD, principalOutstanding));
        when(installment.getInterestOutstanding(USD)).thenReturn(Money.of(USD, interestOutstanding));
        when(installment.getPenaltyChargesOutstanding(USD)).thenReturn(Money.of(USD, penaltyOutstanding));
        return installment;
    }
}
