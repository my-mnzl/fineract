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
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomAmountInterestPenaltiesChargeCalculatorTest {

    private static final MonetaryCurrency USD = new MonetaryCurrency("USD", 4, null);

    private final CustomAmountInterestPenaltiesChargeCalculator calculator = new CustomAmountInterestPenaltiesChargeCalculator();

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCacheForTenant("default");
    }

    @Test
    void identifiesPersistedCustomCalculationType() {
        assertThat(calculator.calculationType()).isEqualTo(ChargeCalculationType.CUSTOM.getValue());
    }

    @Test
    void loanCalculationIncludesOutstandingPenalties() {
        final Loan loan = mockLoan("100000", "5000", "2000");
        final LoanCharge charge = mock(LoanCharge.class);

        assertThat(calculator.calculateAmountPercentageAppliedTo(loan, charge)).isEqualByComparingTo("107000");
    }

    @Test
    void overdueCalculationCompoundsOutstandingPenalties() {
        final Loan loan = mockLoan("100000", "5000", "0");
        final LoanRepaymentScheduleInstallment installment = mockInstallment("10000", "100", "101");

        final Money base = calculator.calculateOverdueAmountPercentageAppliedTo(loan, installment);

        assertThat(base.getAmount()).isEqualByComparingTo("10201");
        assertThat(LoanCharge.percentageOf(base.getAmount(), BigDecimal.ONE)).isEqualByComparingTo("102.01");
    }

    @Test
    void creationUsesInstallmentPenaltyForOverdueCharge() {
        final Loan loan = mockLoan("100000", "5000", "4242");
        final JsonCommand command = mock(JsonCommand.class);
        final LoanRepaymentScheduleInstallment installment = mockInstallment("10000", "500", "200");

        assertThat(calculator.calculateCreationAmountPercentageAppliedTo(loan, command, installment)).isEqualByComparingTo("10700");
    }

    private Loan mockLoan(final String principal, final String interest, final String penalties) {
        final Loan loan = mock(Loan.class);
        final LoanSummary summary = mock(LoanSummary.class);
        lenient().when(loan.getCurrency()).thenReturn(USD);
        lenient().when(loan.getPrincipal()).thenReturn(Money.of(USD, new BigDecimal(principal)));
        lenient().when(loan.getTotalInterest()).thenReturn(new BigDecimal(interest));
        lenient().when(loan.getSummary()).thenReturn(summary);
        lenient().when(summary.getTotalPenaltyChargesOutstanding()).thenReturn(new BigDecimal(penalties));
        return loan;
    }

    private LoanRepaymentScheduleInstallment mockInstallment(final String principal, final String interest, final String penalties) {
        final LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getPrincipalOutstanding(USD)).thenReturn(Money.of(USD, new BigDecimal(principal)));
        when(installment.getInterestOutstanding(USD)).thenReturn(Money.of(USD, new BigDecimal(interest)));
        when(installment.getPenaltyChargesOutstanding(USD)).thenReturn(Money.of(USD, new BigDecimal(penalties)));
        return installment;
    }
}
