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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        EnumOptionData feeFrequency = new EnumOptionData(4L, "feeFrequencyperiodFrequencyType.years", "Years");
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn(7L);
        when(charge.getName()).thenReturn("Insurance");
        when(charge.toData()).thenReturn(ChargeData.builder().currency(new CurrencyData("USD", "US Dollar", 2, 0, "$", "USD"))
                .feeInterval(1).feeFrequency(feeFrequency).build());

        Loan loan = mock(Loan.class);
        when(loan.getId()).thenReturn(5L);

        LoanCharge loanCharge = new LoanCharge();
        loanCharge.setLoan(loan);
        loanCharge.setCharge(charge);
        loanCharge.setAmount(new BigDecimal("10.00"));
        loanCharge.setAmountOutstanding(new BigDecimal("10.00"));
        loanCharge.setChargeTime(ChargeTimeType.LOAN_PERIODIC.getValue());
        loanCharge.setChargeCalculation(ChargeCalculationType.FLAT.getValue());
        loanCharge.setDueDate(LocalDate.of(2024, 2, 1));
        loanCharge.setChargePaymentMode(ChargePaymentMode.REGULAR.getValue());
        loanCharge.setExternalId(ExternalId.empty());

        LoanChargeData result = loanCharge.toData();

        assertEquals(1, result.getFeeInterval());
        assertEquals(feeFrequency, result.getFeeFrequency());
    }

    @Test
    void updatePaidAmountBy_roundsBoundaryOutstandingToZero_marksChargePaid() {
        Loan loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(EGP);

        LoanCharge loanCharge = new LoanCharge();
        loanCharge.setLoan(loan);
        loanCharge.setAmount(new BigDecimal("20.833333"));
        loanCharge.setAmountOutstanding(new BigDecimal("20.833333"));
        loanCharge.setChargeTime(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());

        loanCharge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.830000")), null, Money.zero(EGP));

        assertEquals(0, loanCharge.getAmountOutstanding(EGP).getAmount().compareTo(BigDecimal.ZERO));
        assertTrue(loanCharge.isPaid());
        assertFalse(loanCharge.isWaived());
    }

    @Test
    void undoPaidOrPartiallyAmountBy_preservesExistingWaiverInOutstandingAmount() {
        Loan loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(EGP);

        LoanCharge loanCharge = new LoanCharge();
        loanCharge.setLoan(loan);
        loanCharge.setAmount(new BigDecimal("100.00"));
        loanCharge.setAmountWaived(new BigDecimal("50.00"));
        loanCharge.setAmountPaid(new BigDecimal("50.00"));
        loanCharge.setAmountOutstanding(BigDecimal.ZERO);
        loanCharge.setChargeTime(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());

        loanCharge.undoPaidOrPartiallyAmountBy(Money.of(EGP, new BigDecimal("50.00")), null, Money.zero(EGP));

        assertEquals(0, loanCharge.getAmountOutstanding(EGP).getAmount().compareTo(new BigDecimal("50.00")));
        assertFalse(loanCharge.isPaid());
        assertFalse(loanCharge.isWaived());
    }
}
