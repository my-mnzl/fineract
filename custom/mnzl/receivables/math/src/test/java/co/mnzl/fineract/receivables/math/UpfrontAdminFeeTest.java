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
package co.mnzl.fineract.receivables.math;

import static co.mnzl.fineract.receivables.math.ReceivableEvents.modify;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.reset;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.settlePortions;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.substitute;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.CALCULATION_VERSION;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.SIMPLE_CALCULATION_VERSION;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.explain;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.feeTreatment;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.income;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.price;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import co.mnzl.fineract.receivables.math.ReceivablesMath.FeeTreatment;
import co.mnzl.fineract.receivables.math.ReceivablesMath.MeasurementState;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Position;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Purchase;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The upfront admin fee changes only how the fee is recognised; pricing and gross accretion are the daily rule's. */
class UpfrontAdminFeeTest {

    private static final LocalDate START = LocalDate.of(2030, 1, 1);
    private static final BigDecimal RATE = new BigDecimal("0.24");
    private static final BigDecimal FEE = new BigDecimal("0.01");

    private static List<Cashflow> flows() {
        return List.of(new Cashflow("cf-01", LocalDate.of(2030, 4, 1), BigInteger.valueOf(4_000_000)),
                new Cashflow("cf-02", LocalDate.of(2030, 7, 30), BigInteger.valueOf(5_000_000)),
                new Cashflow("cf-03", LocalDate.of(2031, 1, 26), BigInteger.valueOf(6_000_000)));
    }

    @Test
    void versionsDispatchExplicitlyToAccretionAndFeeTreatment() {
        assertEquals(FeeTreatment.DEFERRED, feeTreatment(null));
        assertEquals(FeeTreatment.DEFERRED, feeTreatment(CALCULATION_VERSION));
        assertEquals(FeeTreatment.DEFERRED, feeTreatment(SIMPLE_CALCULATION_VERSION));
        assertEquals(FeeTreatment.UPFRONT, feeTreatment(UPFRONT_FEE_CALCULATION_VERSION));
        assertThrows(IllegalArgumentException.class, () -> feeTreatment("EG_RECEIVABLES_ACT360_DAILY_V3"));
        Purchase upfront = price(UPFRONT_FEE_CALCULATION_VERSION, START, flows(), RATE, FEE);
        var explanation = explain(upfront.segment(), position(upfront.segment(), START, Map.of()));
        assertEquals(UPFRONT_FEE_CALCULATION_VERSION, explanation.calculationVersion());
        assertEquals("DAILY", explanation.compounding());
        assertEquals("PV=sum(C/(1+r/360)^actualDays); U=F-G; N=G; H=0; A recognised at purchase", explanation.formula());
        Purchase daily = price(START, flows(), RATE, FEE);
        assertEquals("PV=sum(C/(1+r/360)^actualDays); U=F-G; H=G-N",
                explain(daily.segment(), position(daily.segment(), START, Map.of())).formula());
    }

    @Test
    void upfrontFeeKeepsPricingAndLeavesNothingDeferred() {
        Purchase daily = price(START, flows(), RATE, FEE);
        Purchase upfront = price(UPFRONT_FEE_CALCULATION_VERSION, START, flows(), RATE, FEE);
        assertEquals(daily.grossPurchasePriceMinor(), upfront.grossPurchasePriceMinor());
        assertEquals(daily.adminFeeMinor(), upfront.adminFeeMinor());
        assertEquals(daily.netPurchaseCashMinor(), upfront.netPurchaseCashMinor());
        assertEquals(BigInteger.valueOf(127_408), upfront.adminFeeMinor());
        Segment s = upfront.segment();
        assertEquals(UPFRONT_FEE_CALCULATION_VERSION, s.calculationVersion());
        assertEquals(s.grossBasisMinor(), s.netBasisMinor());
        assertEquals(upfront.grossPurchasePriceMinor(), s.netBasisMinor());
        assertEquals(daily.segment().grossYield(), s.grossYield());
        assertEquals(s.grossYield(), s.netEir());
        Position opening = position(s, START, Map.of());
        assertEquals(upfront.grossPurchasePriceMinor(), opening.amortizedCostMinor());
        assertEquals(BigInteger.ZERO, opening.deferredAdminFeeMinor());
        Map<String, BigInteger> collected = new LinkedHashMap<>();
        Position previous = opening;
        BigInteger lifetime = BigInteger.ZERO;
        for (Cashflow cf : s.cashflows()) {
            Position due = position(s, cf.dueDate(), collected);
            var dailyDue = position(daily.segment(), cf.dueDate(), collected);
            assertEquals(dailyDue.grossPurchaseBasisMinor(), due.grossPurchaseBasisMinor(), "gross accretion is the daily rule's");
            assertEquals(due.grossPurchaseBasisMinor(), due.amortizedCostMinor());
            assertEquals(BigInteger.ZERO, due.deferredAdminFeeMinor());
            var interval = income(previous, due, BigInteger.ZERO);
            assertEquals(BigInteger.ZERO, interval.adminFeeIncomeMinor());
            assertEquals(interval.grossDiscountIncomeMinor(), interval.interestIncomeMinor());
            lifetime = lifetime.add(interval.interestIncomeMinor());
            collected.put(cf.cashflowId(), BigInteger.ZERO);
            previous = position(s, cf.dueDate(), collected);
        }
        assertEquals(daily.contractualFaceMinor().subtract(daily.grossPurchasePriceMinor()), lifetime);
    }

    @Test
    void upfrontSegmentsNeverDeferAFeeThroughLaterEvents() {
        Segment s = price(UPFRONT_FEE_CALCULATION_VERSION, START, flows(), RATE, FEE).segment();
        assertThrows(IllegalArgumentException.class, () -> new Segment(START, flows(), s.grossBasisMinor(),
                s.grossBasisMinor().subtract(BigInteger.ONE), s.grossYield(), s.netEir(), UPFRONT_FEE_CALCULATION_VERSION));
        assertThrows(IllegalArgumentException.class, () -> ReceivablesMath.segment(UPFRONT_FEE_CALCULATION_VERSION, START, flows(),
                s.grossBasisMinor(), s.grossBasisMinor().subtract(BigInteger.ONE)));
        LocalDate date = LocalDate.of(2030, 5, 1);
        Map<String, BigInteger> collected = Map.of("cf-01", BigInteger.ZERO);
        var reset = reset(s, date, collected, new BigDecimal("0.20"));
        assertEquals(UPFRONT_FEE_CALCULATION_VERSION, reset.futureSegment().calculationVersion());
        assertEquals(reset.futureSegment().grossBasisMinor(), reset.futureSegment().netBasisMinor());
        assertEquals(UPFRONT_FEE_CALCULATION_VERSION, reset.lot().calculationVersion());
        Position at = position(s, date, collected);
        var replacement = substitute(UPFRONT_FEE_CALCULATION_VERSION, at,
                List.of(new Cashflow("r-01", LocalDate.of(2031, 3, 1), BigInteger.valueOf(12_000_000))), BigInteger.ZERO).replacement();
        assertEquals(replacement.grossBasisMinor(), replacement.netBasisMinor());
        var modified = modify(UPFRONT_FEE_CALCULATION_VERSION, date,
                List.of(new Cashflow("m-01", LocalDate.of(2031, 6, 1), BigInteger.valueOf(11_500_000))), s.netEir().rate(),
                at.amortizedCostMinor());
        assertEquals(
                ReceivablesMath.minor(
                        ReceivablesMath.pv(UPFRONT_FEE_CALCULATION_VERSION, date, modified.modifiedCashflows(), s.grossYield().rate())),
                modified.modifiedNetBasisMinor());
        var settled = settlePortions(at, Map.of("cf-02", BigInteger.valueOf(5_000_000)), BigInteger.valueOf(5_000_000), BigInteger.ZERO);
        assertEquals(BigInteger.ZERO, settled.settlement().deferredAdminFeeMinor());
        assertEquals(BigInteger.ZERO, settled.retainedPosition().deferredAdminFeeMinor());
    }

    @Test
    void persistedUpfrontStateRoundTrips() throws Exception {
        Segment s = price(UPFRONT_FEE_CALCULATION_VERSION, START, flows(), RATE, FEE).segment();
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        s.cashflows().forEach(cf -> outstanding.put(cf.cashflowId(), cf.amountMinor()));
        var state = new MeasurementState(s, position(s, START, outstanding), outstanding);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = mapper.writeValueAsString(state);
        assertTrue(json.contains("\"deferredAdminFeeMinor\":0"), json);
        assertEquals(state, mapper.readValue(json, MeasurementState.class));
    }
}
