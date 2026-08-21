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
package org.apache.fineract.portfolio.loanaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LoanChargeTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = mockStatic(MoneyHelper.class);
    private static final MonetaryCurrency EGP = new MonetaryCurrency("EGP", 2, null);

    @BeforeAll
    static void init() {
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
    }

    @AfterAll
    static void destroy() {
        MONEY_HELPER.close();
    }

    @Test
    void toDataIncludesRecurringChargeMetadata() {
        final EnumOptionData feeFrequency = new EnumOptionData(3L, "periodFrequencyType.years", "Years");
        final Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn(7L);
        when(charge.getName()).thenReturn("Insurance");
        when(charge.toData()).thenReturn(ChargeData.builder().currency(new CurrencyData("USD", "US Dollar", 2, 0, "$", "USD"))
                .feeInterval(1).feeFrequency(feeFrequency).build());

        final Loan loan = mock(Loan.class);
        when(loan.getId()).thenReturn(5L);
        final LoanCharge loanCharge = createLoanCharge(loan, "10.00");
        loanCharge.setCharge(charge);
        loanCharge.setChargeTime(ChargeTimeType.LOAN_PERIODIC.getValue());
        loanCharge.setChargeCalculation(ChargeCalculationType.FLAT.getValue());
        loanCharge.setDueDate(LocalDate.of(2024, 2, 1));
        loanCharge.setChargePaymentMode(ChargePaymentMode.REGULAR.getValue());
        loanCharge.setExternalId(ExternalId.empty());

        final LoanChargeData result = loanCharge.toData();

        assertThat(result.getFeeInterval()).isEqualTo(1);
        assertThat(result.getFeeFrequency()).isEqualTo(feeFrequency);
    }

    @Test
    void roundedBoundarySettlementMarksChargePaid() {
        final Loan loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(EGP);
        final LoanCharge charge = createLoanCharge(loan, "20.833333");

        charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.830000")), null, Money.zero(EGP));

        assertThat(charge.getAmountOutstanding(EGP).getAmount()).isZero();
        assertThat(charge.isPaid()).isTrue();
        assertThat(charge.isWaived()).isFalse();
    }

    @Test
    void undoPaymentPreservesExistingWaiverInOutstandingAmount() {
        final Loan loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(EGP);
        final LoanCharge charge = createLoanCharge(loan, "100.00");
        charge.setAmountWaived(new BigDecimal("50.00"));
        charge.setAmountPaid(new BigDecimal("50.00"));
        charge.setAmountOutstanding(BigDecimal.ZERO);

        charge.undoPaidOrPartiallyAmountBy(Money.of(EGP, new BigDecimal("50.00")), null, Money.zero(EGP));

        assertThat(charge.getAmountOutstanding(EGP).getAmount()).isEqualByComparingTo("50.00");
        assertThat(charge.isPaid()).isFalse();
        assertThat(charge.isWaived()).isFalse();
    }

    private LoanCharge createLoanCharge(final Loan loan, final String amount) {
        final LoanCharge charge = new LoanCharge();
        charge.setLoan(loan);
        charge.setAmount(new BigDecimal(amount));
        charge.setAmountOutstanding(new BigDecimal(amount));
        charge.setChargeTime(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());
        return charge;
    }
}
