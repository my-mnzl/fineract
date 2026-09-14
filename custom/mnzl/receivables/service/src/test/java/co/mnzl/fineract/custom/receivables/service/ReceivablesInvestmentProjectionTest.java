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
package co.mnzl.fineract.custom.receivables.service;

import static org.assertj.core.api.Assertions.assertThat;

import co.mnzl.fineract.receivables.math.ReceivablesMath;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceivablesInvestmentProjectionTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 6);

    @Test
    void reproducesCrownInvestmentEconomicsAndRoundedAccountTargets() {
        var flows = List.of(flow("a", "2027-07-30", "112000000"), flow("b", "2028-07-30", "112000000"),
                flow("c", "2029-07-30", "112000000"), flow("d", "2030-07-30", "55000000"));
        var purchase = ReceivablesMath.price(START, flows, new BigDecimal("0.221"), new BigDecimal("0.01"));
        assertThat(purchase.grossPurchasePriceMinor()).isEqualTo(new BigInteger("246313030"));
        assertThat(purchase.netPurchaseCashMinor()).isEqualTo(new BigInteger("243849900"));
        var state = state(purchase.segment());
        var projection = ReceivablesInvestmentProjection.project(state, START, "ACTIVE");
        assertThat(projection.unavailableReason()).isNull();
        assertThat(projection.rows()).hasSize(4);
        var rows = projection.rows();
        assertThat(rows.getFirst().openingCarryingMinor()).isEqualTo("243849900");
        assertThat(rows.getFirst().eirIncomeMinor()).isEqualTo("55573107");
        assertThat(rows.getFirst().investmentRecoveryMinor()).isEqualTo("56426893");
        assertThat(rows.getLast().closingCarryingMinor()).isEqualTo("0");
        assertThat(rows.stream().map(row -> new BigInteger(row.investmentRecoveryMinor())).reduce(BigInteger.ZERO, BigInteger::add))
                .isEqualTo(purchase.netPurchaseCashMinor());
        assertThat(rows.stream().map(row -> new BigInteger(row.eirIncomeMinor())).reduce(BigInteger.ZERO, BigInteger::add))
                .isEqualTo(purchase.contractualFaceMinor().subtract(purchase.netPurchaseCashMinor()));
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).openingCarryingMinor()).isEqualTo(rows.get(i - 1).closingCarryingMinor());
        }
        var later = ReceivablesInvestmentProjection.project(state, START.plusDays(8), "ACTIVE");
        assertThat(later.rows().getFirst().openingCarryingMinor()).isEqualTo("245077775");
    }

    @Test
    void usesRemainingFaceAndGroupsSameDayCashflowsWithoutRepricing() {
        var flows = List.of(flow("a", "2027-07-30", "10000"), flow("b", "2027-07-30", "20000"), flow("c", "2028-07-30", "30000"));
        var segment = ReceivablesMath.price(START, flows, new BigDecimal("0.221"), new BigDecimal("0.01")).segment();
        var outstanding = new LinkedHashMap<String, BigInteger>();
        outstanding.put("a", new BigInteger("4000"));
        outstanding.put("b", new BigInteger("10000"));
        outstanding.put("c", new BigInteger("30000"));
        var state = new ReceivablesMath.MeasurementState(segment, ReceivablesMath.position(segment, START, outstanding), outstanding);
        var projection = ReceivablesInvestmentProjection.project(state, START, "ACTIVE");
        assertThat(projection.rows()).hasSize(2);
        assertThat(projection.rows().getFirst().cashReceiptMinor()).isEqualTo("14000");
        assertThat(projection.rows().getLast().cashReceiptMinor()).isEqualTo("30000");
        assertThat(state.outstandingMinor()).isEqualTo(outstanding);
    }

    @Test
    void doesNotInventCollectionTimingForOverdueOrClosedAccounts() {
        var segment = ReceivablesMath.price(START, List.of(flow("a", "2027-07-30", "10000")), new BigDecimal("0.2"), BigDecimal.ZERO)
                .segment();
        assertThat(ReceivablesInvestmentProjection.project(state(segment), LocalDate.of(2027, 7, 31), "ACTIVE").unavailableReason())
                .isEqualTo("OVERDUE_CASHFLOWS");
        assertThat(ReceivablesInvestmentProjection.project(state(segment), START, "CLOSED").unavailableReason())
                .isEqualTo("ACCOUNT_NOT_ACTIVE");
        var dueToday = ReceivablesInvestmentProjection.project(state(segment), LocalDate.of(2027, 7, 30), "ACTIVE");
        assertThat(dueToday.rows().getFirst().eirIncomeMinor()).isEqualTo("0");
    }

    private static ReceivablesMath.Cashflow flow(String id, String date, String amount) {
        return new ReceivablesMath.Cashflow(id, LocalDate.parse(date), new BigInteger(amount));
    }

    private static ReceivablesMath.MeasurementState state(ReceivablesMath.Segment segment) {
        var amounts = new LinkedHashMap<String, BigInteger>();
        segment.cashflows().forEach(flow -> amounts.put(flow.cashflowId(), flow.amountMinor()));
        return new ReceivablesMath.MeasurementState(segment, ReceivablesMath.position(segment, START, amounts), amounts);
    }
}
