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
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LoanInstallmentChargeTest {

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
    void updatePaidAmountBy_roundsBoundaryOutstandingToZero_marksInstallmentChargePaid() {
        LoanInstallmentCharge charge = createInstallmentCharge("20.833333");

        charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.830000")), Money.zero(EGP));

        assertEquals(0, charge.getAmountOutstanding().compareTo(BigDecimal.ZERO));
        assertEquals(0, charge.getAmountOutstanding(EGP).getAmount().compareTo(BigDecimal.ZERO));
        assertTrue(charge.isPaid());
        assertFalse(charge.isWaived());
        assertFalse(charge.isPending());
    }

    @Test
    void updatePaidAmountBy_keepsLegitimateUnderpaymentOutstanding() {
        LoanInstallmentCharge charge = createInstallmentCharge("20.833333");

        charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.820000")), Money.zero(EGP));

        assertEquals(0, charge.getAmountOutstanding().compareTo(new BigDecimal("0.01")));
        assertFalse(charge.isPaid());
        assertFalse(charge.isWaived());
        assertTrue(charge.isPending());
    }

    @Test
    void copyFrom_preservesRoundedPaidStateForBoundarySettlement() {
        LoanInstallmentCharge charge = createInstallmentCharge("20.833333");
        charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.830000")), Money.zero(EGP));

        LoanInstallmentCharge regeneratedCharge = createInstallmentCharge("20.833333");
        charge.copyFrom(regeneratedCharge);

        assertEquals(0, charge.getAmountOutstanding().compareTo(BigDecimal.ZERO));
        assertTrue(charge.isPaid());
        assertFalse(charge.isWaived());
    }

    @Test
    void multipleInstallmentCharges_doNotLeaveZeroOutstandingRowsUnpaid() {
        List<LoanInstallmentCharge> charges = List.of(createInstallmentCharge("20.833333"), createInstallmentCharge("20.833333"),
                createInstallmentCharge("20.833333"), createInstallmentCharge("20.833333"));

        charges.forEach(charge -> charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.830000")), Money.zero(EGP)));

        charges.forEach(charge -> {
            assertEquals(0, charge.getAmountOutstanding().compareTo(BigDecimal.ZERO));
            assertTrue(charge.isPaid());
            assertFalse(charge.isWaived());
        });
    }

    private LoanInstallmentCharge createInstallmentCharge(final String amount) {
        final Loan loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(EGP);

        final LoanCharge loanCharge = mock(LoanCharge.class);
        when(loanCharge.getLoan()).thenReturn(loan);

        return new LoanInstallmentCharge(new BigDecimal(amount), loanCharge, mock(LoanRepaymentScheduleInstallment.class));
    }
}
