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
package org.apache.fineract.portfolio.loanaccount.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.junit.jupiter.api.Test;

class LoanChargeDataTest {

    @Test
    void toLoanChargeDataKeepsRecurringChargeMetadata() {
        EnumOptionData feeFrequency = new EnumOptionData(3L, "feeFrequencyperiodFrequencyType.months", "Months");
        ChargeData chargeData = ChargeData.builder().id(9L).name("Insurance")
                .currency(new CurrencyData("USD", "US Dollar", 2, 0, "$", "USD")).amount(new BigDecimal("10.00"))
                .chargeTimeType(new EnumOptionData((long) ChargeTimeType.LOAN_PERIODIC.getValue(), ChargeTimeType.LOAN_PERIODIC.getCode(),
                        "Periodic Loan Charge"))
                .chargeCalculationType(new EnumOptionData(1L, "chargeCalculationType.flat", "Flat"))
                .chargePaymentMode(
                        new EnumOptionData((long) ChargePaymentMode.REGULAR.getValue(), ChargePaymentMode.REGULAR.getCode(), "Regular"))
                .feeInterval(2).feeFrequency(feeFrequency).build();

        LoanChargeData result = LoanAccountData.toLoanChargeData(chargeData);

        assertEquals(2, result.getFeeInterval());
        assertEquals(feeFrequency, result.getFeeFrequency());
    }

    @Test
    void copyConstructorPreservesRecurringChargeMetadata() {
        EnumOptionData feeFrequency = new EnumOptionData(4L, "feeFrequencyperiodFrequencyType.years", "Years");
        LoanChargeData original = LoanChargeData.builder().id(1L).chargeId(9L).feeInterval(1).feeFrequency(feeFrequency)
                .externalId(ExternalId.empty()).externalLoanId(ExternalId.empty()).build();

        LoanChargeData copied = new LoanChargeData(original, List.of());

        assertEquals(original.getFeeInterval(), copied.getFeeInterval());
        assertEquals(original.getFeeFrequency(), copied.getFeeFrequency());
    }
}
