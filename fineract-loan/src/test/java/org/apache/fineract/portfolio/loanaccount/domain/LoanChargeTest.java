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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargeData;
import org.junit.jupiter.api.Test;

class LoanChargeTest {

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
}
