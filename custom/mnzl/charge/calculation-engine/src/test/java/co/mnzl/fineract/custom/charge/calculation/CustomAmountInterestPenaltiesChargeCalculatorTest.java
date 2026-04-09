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
 * Tests for {@link CustomAmountInterestPenaltiesChargeCalculator}.
 *
 * The custom calculator extends the default by including outstanding penalty charges in the calculation base for
 * percentage-based charges. This tests that penalty amounts are correctly added to the base in all calculation methods.
 */
@ExtendWith(MockitoExtension.class)
class CustomAmountInterestPenaltiesChargeCalculatorTest {

    private static final MonetaryCurrency USD = new MonetaryCurrency("USD", 2, null);

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
    void calculateAmountPercentageAppliedToIncludesPenalties() {
        Loan loan = mockLoan(BigDecimal.valueOf(100000), BigDecimal.valueOf(5000), BigDecimal.valueOf(2000));
        LoanCharge loanCharge = mock(LoanCharge.class);
        lenient().when(loanCharge.isDisbursementCharge()).thenReturn(false);

        BigDecimal customBase = calculator.calculateAmountPercentageAppliedTo(loan, loanCharge);
        BigDecimal defaultBase = defaultCalculator.calculateAmountPercentageAppliedTo(loan, loanCharge);

        // Default: principal + interest = 100000 + 5000 = 105000
        assertThat(defaultBase).isEqualByComparingTo(BigDecimal.valueOf(105000));

        // Custom: principal + interest + penalty outstanding = 100000 + 5000 + 2000 = 107000
        assertThat(customBase).isEqualByComparingTo(BigDecimal.valueOf(107000));

        // Difference is exactly the penalty outstanding
        assertThat(customBase.subtract(defaultBase)).isEqualByComparingTo(BigDecimal.valueOf(2000));
    }

    @Test
    void calculateOverdueAmountPercentageAppliedToIncludesPenalties() {
        Loan loan = mockLoan(BigDecimal.valueOf(100000), BigDecimal.valueOf(5000), BigDecimal.valueOf(2000));

        LoanRepaymentScheduleInstallment installment = mockInstallment(BigDecimal.valueOf(10000), // principal
                                                                                                  // outstanding
                BigDecimal.valueOf(500), // interest outstanding
                BigDecimal.valueOf(200)); // penalty outstanding

        Money customBase = calculator.calculateOverdueAmountPercentageAppliedTo(loan, installment);
        Money defaultBase = defaultCalculator.calculateOverdueAmountPercentageAppliedTo(loan, installment);

        // Default: principal outstanding + interest outstanding = 10000 + 500 = 10500
        assertThat(defaultBase.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10500));

        // Custom: principal outstanding + interest outstanding + penalty outstanding = 10000 + 500 + 200 = 10700
        assertThat(customBase.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10700));
    }

    @Test
    void calculateInstallmentChargeAmountIncludesPenalties() {
        Loan loan = mockLoan(BigDecimal.valueOf(100000), BigDecimal.valueOf(5000), BigDecimal.valueOf(2000));

        LoanRepaymentScheduleInstallment installment = mockInstallmentForChargeCalc(BigDecimal.valueOf(10000), // principal
                BigDecimal.valueOf(500), // interest
                BigDecimal.valueOf(200)); // penalty outstanding

        BigDecimal percentage = BigDecimal.valueOf(1); // 1%

        Money customCharge = calculator.calculateInstallmentChargeAmount(loan, percentage, installment);
        Money defaultCharge = defaultCalculator.calculateInstallmentChargeAmount(loan, percentage, installment);

        // Default: 1% of (principal + interest) = 1% of 10500 = 105
        assertThat(defaultCharge.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(105));

        // Custom: 1% of (principal + interest) + 1% of penalty outstanding = 105 + 2 = 107
        assertThat(customCharge.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(107));
    }

    @Test
    void zeroPenaltiesProducesSameResultAsDefault() {
        Loan loan = mockLoan(BigDecimal.valueOf(100000), BigDecimal.valueOf(5000), BigDecimal.ZERO);
        LoanCharge loanCharge = mock(LoanCharge.class);
        lenient().when(loanCharge.isDisbursementCharge()).thenReturn(false);

        BigDecimal customBase = calculator.calculateAmountPercentageAppliedTo(loan, loanCharge);
        BigDecimal defaultBase = defaultCalculator.calculateAmountPercentageAppliedTo(loan, loanCharge);

        // With zero penalties, both should produce the same result
        assertThat(customBase).isEqualByComparingTo(defaultBase);
    }

    // ---- Helpers ----

    private Loan mockLoan(BigDecimal principal, BigDecimal totalInterest, BigDecimal penaltyOutstanding) {
        Loan loan = mock(Loan.class);
        LoanSummary summary = mock(LoanSummary.class);
        lenient().when(loan.getCurrency()).thenReturn(USD);
        lenient().when(loan.getPrincipal()).thenReturn(Money.of(USD, principal));
        lenient().when(loan.getTotalInterest()).thenReturn(totalInterest);
        lenient().when(loan.getSummary()).thenReturn(summary);
        lenient().when(summary.getTotalPenaltyChargesOutstanding()).thenReturn(penaltyOutstanding);
        lenient().when(loan.isMultiDisburmentLoan()).thenReturn(false);
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

    private LoanRepaymentScheduleInstallment mockInstallmentForChargeCalc(BigDecimal principal, BigDecimal interestCharged,
            BigDecimal penaltyOutstanding) {
        LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getPrincipal(USD)).thenReturn(Money.of(USD, principal));
        when(installment.getInterestCharged(USD)).thenReturn(Money.of(USD, interestCharged));
        when(installment.getPenaltyChargesOutstanding(USD)).thenReturn(Money.of(USD, penaltyOutstanding));
        return installment;
    }
}
