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
    void roundedBoundarySettlementMarksInstallmentChargePaid() {
        final LoanInstallmentCharge charge = createInstallmentCharge("20.833333");

        charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.830000")), Money.zero(EGP));

        assertThat(charge.getAmountOutstanding()).isZero();
        assertThat(charge.isPaid()).isTrue();
        assertThat(charge.isWaived()).isFalse();
        assertThat(charge.isPending()).isFalse();
    }

    @Test
    void legitimateUnderpaymentRemainsOutstanding() {
        final LoanInstallmentCharge charge = createInstallmentCharge("20.833333");

        charge.updatePaidAmountBy(Money.of(EGP, new BigDecimal("20.820000")), Money.zero(EGP));

        assertThat(charge.getAmountOutstanding()).isEqualByComparingTo("0.01");
        assertThat(charge.isPaid()).isFalse();
        assertThat(charge.isPending()).isTrue();
    }

    private LoanInstallmentCharge createInstallmentCharge(final String amount) {
        final Loan loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(EGP);
        final LoanCharge loanCharge = mock(LoanCharge.class);
        when(loanCharge.getLoan()).thenReturn(loan);
        return new LoanInstallmentCharge(new BigDecimal(amount), loanCharge, mock(LoanRepaymentScheduleInstallment.class));
    }
}
