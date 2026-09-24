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

import static co.mnzl.fineract.receivables.math.CreditAndFunding.impairment;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.modify;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.reset;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.settlePortions;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.substitute;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.CALCULATION_VERSION;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.SIMPLE_CALCULATION_VERSION;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.SUPPORTED_VERSIONS;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.accrual;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.discount;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.explain;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.growth;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.income;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.major;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.price;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.pv;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.simpleAccrual;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.mnzl.fineract.receivables.math.CreditAndFunding.Payer;
import co.mnzl.fineract.receivables.math.CreditAndFunding.Recovery;
import co.mnzl.fineract.receivables.math.CreditAndFunding.Scenario;
import co.mnzl.fineract.receivables.math.ReceivableEvents.AdjustmentLot;
import co.mnzl.fineract.receivables.math.ReceivableEvents.Direction;
import co.mnzl.fineract.receivables.math.ReceivableEvents.Reset;
import co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
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

/** The simple rule is additive: it shares pricing, rounding and income with the daily rule and never changes it. */
class SimpleAccretionTest {

    private static final LocalDate START = LocalDate.of(2028, 1, 1);
    private static final BigDecimal RATE = new BigDecimal("0.24");
    private static final BigDecimal TOLERANCE = new BigDecimal("1e-10");

    private static List<Cashflow> flows() {
        return List.of(new Cashflow("a", LocalDate.of(2028, 4, 1), BigInteger.valueOf(4_000_000)),
                new Cashflow("b", LocalDate.of(2028, 7, 30), BigInteger.valueOf(5_000_000)),
                new Cashflow("c", LocalDate.of(2029, 1, 26), BigInteger.valueOf(6_000_000)));
    }

    private static Purchase simple() {
        return price(SIMPLE_CALCULATION_VERSION, START, flows(), RATE, new BigDecimal("0.01"));
    }

    private static void near(BigDecimal a, BigDecimal b) {
        assertTrue(a.subtract(b).abs().compareTo(TOLERANCE) < 0, a + " != " + b);
    }

    @Test
    void versionsAreExplicitAndTheDailyDefaultIsUnchanged() {
        assertEquals(List.of(CALCULATION_VERSION, SIMPLE_CALCULATION_VERSION), SUPPORTED_VERSIONS);
        assertEquals(CALCULATION_VERSION, version(null));
        assertEquals(CALCULATION_VERSION, version(" "));
        assertEquals(SIMPLE_CALCULATION_VERSION, version(SIMPLE_CALCULATION_VERSION));
        assertThrows(IllegalArgumentException.class, () -> version("EG_RECEIVABLES_ACT365_SIMPLE_V1"));
        assertThrows(IllegalArgumentException.class, () -> price("EG_RECEIVABLES_ACT365_SIMPLE_V1", START, flows(), RATE, BigDecimal.ZERO));
        Purchase daily = price(START, flows(), RATE, new BigDecimal("0.01"));
        assertEquals(daily, price(CALCULATION_VERSION, START, flows(), RATE, new BigDecimal("0.01")));
        assertEquals(CALCULATION_VERSION, daily.segment().calculationVersion());
        assertEquals(daily.segment(), new Segment(START, flows(), daily.grossPurchasePriceMinor(), daily.netPurchaseCashMinor(),
                daily.segment().grossYield(), daily.segment().netEir()));
        assertEquals(growth(RATE, 31), accrual(CALCULATION_VERSION, RATE, 31));
        assertEquals(growth(RATE, 31), discount(CALCULATION_VERSION, RATE, START, START, START.plusDays(31), List.of(START.plusDays(10))));
        assertEquals(pv(START, flows(), RATE), pv(CALCULATION_VERSION, START, flows(), RATE));
        assertEquals("DAILY", explain(daily.segment(), position(daily.segment(), START, Map.of())).compounding());
    }

    @Test
    void simpleAccrualIsLinearBetweenChequesAndCapitalisesOnlyOnChequeDates() {
        assertEquals(new BigDecimal("1.02"), simpleAccrual(RATE, 30).stripTrailingZeros());
        assertTrue(simpleAccrual(RATE, 30).compareTo(growth(RATE, 30)) < 0);
        // Without a boundary the factor over 60 days is one straight accrual, not two compounded halves.
        BigDecimal straight = discount(SIMPLE_CALCULATION_VERSION, RATE, START, START, START.plusDays(60), List.of());
        BigDecimal capitalised = discount(SIMPLE_CALCULATION_VERSION, RATE, START, START, START.plusDays(60), List.of(START.plusDays(30)));
        assertEquals(new BigDecimal("1.04"), straight.stripTrailingZeros());
        near(new BigDecimal("1.0404"), capitalised);
        // Boundaries outside the interval never matter.
        assertEquals(straight, discount(SIMPLE_CALCULATION_VERSION, RATE, START, START, START.plusDays(60),
                List.of(START, START.plusDays(60), START.plusDays(90))));
        // Mid-period the value is the anchored walk with the accrual since the anchor taken out, not a fresh
        // discounting.
        BigDecimal anchored = discount(SIMPLE_CALCULATION_VERSION, RATE, START, START.plusDays(15), START.plusDays(60),
                List.of(START.plusDays(30)));
        near(capitalised.divide(simpleAccrual(RATE, 15), ReceivablesMath.MC), anchored);
        assertTrue(anchored.compareTo(discount(SIMPLE_CALCULATION_VERSION, RATE, START.plusDays(15), START.plusDays(15), START.plusDays(60),
                List.of(START.plusDays(30)))) < 0);
        assertEquals(START, ReceivablesMath.anchor(START, START.plusDays(15), List.of(START.plusDays(30))));
        assertEquals(START.plusDays(30), ReceivablesMath.anchor(START, START.plusDays(30), List.of(START.plusDays(30))));
        assertThrows(IllegalArgumentException.class, () -> ReceivablesMath.anchor(START, START.minusDays(1), List.of()));
    }

    @Test
    void samePriceDifferentYieldsAndZeroClosingBalance() {
        Purchase daily = price(START, flows(), RATE, new BigDecimal("0.01"));
        Purchase simple = simple();
        assertEquals(daily.unroundedGrossPrice(), simple.unroundedGrossPrice());
        assertEquals(daily.grossPurchasePriceMinor(), simple.grossPurchasePriceMinor());
        assertEquals(daily.integralFeeMinor(), simple.integralFeeMinor());
        assertEquals(daily.netPurchaseCashMinor(), simple.netPurchaseCashMinor());
        assertNotEquals(daily.segment().grossYield().rate(), simple.segment().grossYield().rate());
        assertTrue(simple.segment().grossYield().rate().compareTo(daily.segment().grossYield().rate()) > 0,
                "less frequent capitalisation needs a higher nominal rate for the same price");
        assertTrue(simple.segment().netEir().rate().compareTo(simple.segment().grossYield().rate()) > 0);
        assertTrue(simple.segment().grossYield().residual().abs().compareTo(TOLERANCE) <= 0);
        Segment s = simple.segment();
        near(major(s.grossBasisMinor()), pv(SIMPLE_CALCULATION_VERSION, START, flows(), s.grossYield().rate()));
        // The walked balance ends at exactly the last cheque, so maturity clears discount and fee.
        Map<String, BigInteger> collected = new LinkedHashMap<>(Map.of("a", BigInteger.ZERO, "b", BigInteger.ZERO));
        Position beforeLast = position(s, LocalDate.of(2029, 1, 26), collected);
        assertEquals(BigInteger.valueOf(6_000_000), beforeLast.grossPurchaseBasisMinor());
        assertEquals(BigInteger.valueOf(6_000_000), beforeLast.amortizedCostMinor());
        assertEquals(BigInteger.ZERO, beforeLast.deferredDiscountMinor());
        assertEquals(BigInteger.ZERO, beforeLast.deferredIntegralFeeMinor());
        var explanation = explain(s, beforeLast);
        assertEquals(SIMPLE_CALCULATION_VERSION, explanation.calculationVersion());
        assertEquals("SIMPLE_AT_CHEQUES", explanation.compounding());
        assertEquals("Actual/360", explanation.dayCount());
        assertTrue(explanation.formula().contains("B(prev)*(1+r*actualDays/360)-C"));
    }

    @Test
    void incomeIsTheTargetDifferenceAndSumsToLifetimeDiscountAndFee() {
        Segment s = simple().segment();
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        Position previous = position(s, START, outstanding);
        BigInteger gross = BigInteger.ZERO;
        BigInteger fee = BigInteger.ZERO;
        for (LocalDate month = START.plusMonths(1); !month.isAfter(LocalDate.of(2029, 2, 1)); month = month.plusMonths(1)) {
            Position before = position(s, month, outstanding);
            var inc = income(previous, before, BigInteger.ZERO);
            gross = gross.add(inc.grossDiscountIncomeMinor());
            fee = fee.add(inc.integralFeeIncomeMinor());
            assertTrue(inc.grossDiscountIncomeMinor().signum() >= 0 && inc.integralFeeIncomeMinor().signum() >= 0, month.toString());
            for (Cashflow cf : flows()) {
                if (!cf.dueDate().isAfter(month)) {
                    outstanding.put(cf.cashflowId(), BigInteger.ZERO);
                }
            }
            previous = position(s, month, outstanding);
            var maturity = income(before, previous, before.pastDueMinor());
            assertEquals(BigInteger.ZERO, maturity.interestIncomeMinor(), "collecting due face on its date is not income");
        }
        assertEquals(ReceivablesMath.face(flows()).subtract(s.grossBasisMinor()), gross);
        assertEquals(s.grossBasisMinor().subtract(s.netBasisMinor()), fee);
        assertEquals(BigInteger.ZERO, previous.contractualOutstandingMinor());
    }

    @Test
    void midPeriodPositionIsTheLastChequeBalanceGrownSimplyAndDueLegsStopAccreting() {
        Segment s = simple().segment();
        LocalDate first = LocalDate.of(2028, 4, 1);
        Position atFirst = position(s, first, Map.of("a", BigInteger.ZERO));
        Position later = position(s, first.plusDays(45), Map.of("a", BigInteger.ZERO));
        near(atFirst.analyticalGrossBasis().multiply(simpleAccrual(s.grossYield().rate(), 45)), later.analyticalGrossBasis());
        near(atFirst.analyticalNetBasis().multiply(simpleAccrual(s.netEir().rate(), 45)), later.analyticalNetBasis());
        // A partially paid due leg sits at remaining face and does not accrete; reversal restores it.
        Position partial = position(s, first.plusDays(45), Map.of("a", BigInteger.valueOf(1_500_000)));
        assertEquals(later.grossPurchaseBasisMinor().add(BigInteger.valueOf(1_500_000)), partial.grossPurchaseBasisMinor());
        assertEquals(BigInteger.valueOf(1_500_000), partial.pastDueMinor());
        assertEquals(45, partial.daysPastDue());
        Position restored = position(s, first.plusDays(45), Map.of());
        assertEquals(later.grossPurchaseBasisMinor().add(BigInteger.valueOf(4_000_000)), restored.grossPurchaseBasisMinor());
        assertEquals(major(BigInteger.valueOf(4_000_000)), restored.legs().getFirst().grossBasis());
        assertEquals(0, restored.legs().getFirst().discountDays());
        assertEquals(75, restored.legs().get(1).discountDays());
        // Future legs are shares of one balance: they sum to it and each is its face discounted through the cheque
        // dates.
        BigDecimal shares = later.legs().stream().map(ReceivablesMath.Leg::grossBasis).reduce(BigDecimal.ZERO, BigDecimal::add);
        near(shares, later.analyticalGrossBasis());
        BigDecimal c = major(BigInteger.valueOf(6_000_000)).divide(discount(SIMPLE_CALCULATION_VERSION, s.grossYield().rate(), first, first,
                LocalDate.of(2029, 1, 26), List.of(LocalDate.of(2028, 7, 30))), ReceivablesMath.MC)
                .multiply(simpleAccrual(s.grossYield().rate(), 45));
        near(c, later.legs().get(2).grossBasis());
    }

    @Test
    void measurementStateAndLotsPinTheVersionAndLegacySnapshotsDefaultToDaily() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Segment s = simple().segment();
        Map<String, BigInteger> outstanding = new LinkedHashMap<>();
        flows().forEach(cf -> outstanding.put(cf.cashflowId(), cf.amountMinor()));
        var state = new MeasurementState(s, position(s, START.plusDays(40), outstanding), outstanding);
        MeasurementState round = mapper.readValue(mapper.writeValueAsString(state), MeasurementState.class);
        assertEquals(SIMPLE_CALCULATION_VERSION, round.segment().calculationVersion());
        // JSON drops trailing zeros of analytical decimals, so compare targets exactly and analytics within tolerance.
        assertEquals(state.boundaryPosition().grossPurchaseBasisMinor(), round.boundaryPosition().grossPurchaseBasisMinor());
        assertEquals(state.boundaryPosition().amortizedCostMinor(), round.boundaryPosition().amortizedCostMinor());
        near(state.boundaryPosition().analyticalGrossBasis(), position(round, START.plusDays(40)).analyticalGrossBasis());
        near(position(s, START.plusDays(70), outstanding).analyticalNetBasis(), position(round, START.plusDays(70)).analyticalNetBasis());
        // A snapshot written before versions existed carries no field and must measure as daily.
        Purchase daily = price(START, flows(), RATE, new BigDecimal("0.01"));
        var legacy = new MeasurementState(daily.segment(), position(daily.segment(), START, outstanding), outstanding);
        var node = mapper.valueToTree(legacy);
        assertEquals(CALCULATION_VERSION, node.path("segment").path("calculationVersion").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("segment")).remove("calculationVersion");
        MeasurementState restored = mapper.treeToValue(node, MeasurementState.class);
        assertEquals(CALCULATION_VERSION, restored.segment().calculationVersion());
        assertEquals(legacy.boundaryPosition().grossPurchaseBasisMinor(), restored.boundaryPosition().grossPurchaseBasisMinor());
        near(position(daily.segment(), START.plusDays(70), outstanding).analyticalGrossBasis(),
                position(restored, START.plusDays(70)).analyticalGrossBasis());
        // Mixing a simple segment with a daily boundary snapshot is a changed measurement, not a silent
        // reinterpretation.
        assertThrows(IllegalArgumentException.class, () -> new MeasurementState(s, legacy.boundaryPosition(), outstanding));
        var lot = new AdjustmentLot(START, START.plusDays(90), RATE, Direction.PAYABLE, BigInteger.valueOf(100_000),
                BigInteger.valueOf(106_000), SIMPLE_CALCULATION_VERSION);
        assertEquals(BigInteger.valueOf(106_000), lot.balanceMinor(START.plusDays(90)));
        assertEquals(BigInteger.valueOf(106_000), lot.balanceMinor(START.plusDays(120)));
        var legacyLot = mapper.valueToTree(lot);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacyLot).remove("calculationVersion");
        AdjustmentLot dailyLot = mapper.treeToValue(legacyLot, AdjustmentLot.class);
        assertEquals(CALCULATION_VERSION, dailyLot.calculationVersion());
        assertEquals(dailyLot, new AdjustmentLot(START, START.plusDays(90), RATE, Direction.PAYABLE, BigInteger.valueOf(100_000),
                BigInteger.valueOf(106_000)));
        assertTrue(dailyLot.balanceMinor(START.plusDays(90)).compareTo(lot.balanceMinor(START.plusDays(90))) > 0);
    }

    @Test
    void eventsKeepThePinnedVersionAndRestartSimpleSegmentsFromRoundedBases() {
        Segment s = simple().segment();
        Map<String, BigInteger> outstanding = new LinkedHashMap<>(Map.of("a", BigInteger.ZERO));
        LocalDate date = LocalDate.of(2028, 5, 1);
        Reset r = reset(s, date, outstanding, new BigDecimal("0.20"));
        assertEquals(SIMPLE_CALCULATION_VERSION, r.futureSegment().calculationVersion());
        assertEquals(SIMPLE_CALCULATION_VERSION, r.lot().calculationVersion());
        near(pv(SIMPLE_CALCULATION_VERSION, date, r.futureSegment().cashflows(), new BigDecimal("0.20")), r.newPvSameDate());
        assertEquals(major(r.futureSegment().grossBasisMinor()).setScale(2), major(r.futureSegment().grossBasisMinor()));
        near(major(r.futureSegment().grossBasisMinor()),
                pv(SIMPLE_CALCULATION_VERSION, date, r.futureSegment().cashflows(), r.futureSegment().grossYield().rate()));
        assertEquals(r.lot().amountDueMinor(),
                ReceivablesMath.minor(major(r.deltaMinor().abs()).multiply(simpleAccrual(new BigDecimal("0.20"), 90))));
        Reset daily = reset(price(START, flows(), RATE, new BigDecimal("0.01")).segment(), date, outstanding, new BigDecimal("0.20"));
        assertEquals(CALCULATION_VERSION, daily.futureSegment().calculationVersion());
        assertNotEquals(daily.newPvSameDate(), r.newPvSameDate());

        Position before = position(s, date, outstanding);
        var substitution = substitute(SIMPLE_CALCULATION_VERSION, before,
                List.of(new Cashflow("x", LocalDate.of(2029, 6, 1), BigInteger.valueOf(12_000_000))), BigInteger.ZERO);
        assertEquals(SIMPLE_CALCULATION_VERSION, substitution.replacement().calculationVersion());
        assertEquals(CALCULATION_VERSION,
                substitute(before, substitution.replacement().cashflows(), BigInteger.ZERO).replacement().calculationVersion());
        var modification = modify(SIMPLE_CALCULATION_VERSION, date, substitution.replacement().cashflows(), s.netEir().rate(),
                before.amortizedCostMinor());
        near(pv(SIMPLE_CALCULATION_VERSION, date, substitution.replacement().cashflows(), s.netEir().rate()),
                modification.modifiedNetBasis());
        assertNotEquals(modify(date, substitution.replacement().cashflows(), s.netEir().rate(), before.amortizedCostMinor())
                .modifiedNetBasisMinor(), modification.modifiedNetBasisMinor());

        var partial = settlePortions(before, Map.of("b", BigInteger.valueOf(5_000_000)), BigInteger.valueOf(4_900_000), BigInteger.ZERO);
        MeasurementState retained = partial.remainingMeasurement(s);
        assertEquals(SIMPLE_CALCULATION_VERSION, retained.segment().calculationVersion());
        Position later = position(retained, date.plusDays(20));
        assertEquals(BigInteger.valueOf(6_000_000), later.contractualOutstandingMinor());
        near(later.analyticalGrossBasis(),
                position(s, date.plusDays(20), Map.of("a", BigInteger.ZERO, "b", BigInteger.ZERO)).analyticalGrossBasis());
    }

    @Test
    void forecastRecoveriesDiscountThroughChequeDatesUnderTheSimpleRule() {
        Segment s = simple().segment();
        LocalDate date = LocalDate.of(2028, 5, 1);
        Position p = position(s, date, Map.of("a", BigInteger.ZERO));
        var contractual = List.of(new Scenario("base", BigDecimal.ONE, null,
                List.of(new Recovery("b", Payer.BORROWER, LocalDate.of(2028, 7, 30), BigInteger.valueOf(5_000_000)),
                        new Recovery("c", Payer.BORROWER, LocalDate.of(2029, 1, 26), BigInteger.valueOf(6_000_000)))));
        var simple = impairment(s, p, 1, contractual);
        assertEquals(BigInteger.ZERO, simple.lossAllowanceMinor());
        near(p.analyticalNetBasis(), simple.expectedRecoveriesPv());
        // The same contractual forecast discounted daily does not reproduce a simple position.
        assertThrows(IllegalArgumentException.class, () -> impairment(p, s.netEir().rate(), 1, contractual));
        // Between cheques the position is a share of the walked balance, so a fresh discounting from the date fails
        // too.
        assertThrows(IllegalArgumentException.class,
                () -> impairment(SIMPLE_CALCULATION_VERSION, date, p, s.netEir().rate(), 1, contractual));
        var shortfall = List.of(new Scenario("loss", BigDecimal.ONE, LocalDate.of(2028, 4, 30),
                List.of(new Recovery("b", Payer.BORROWER, LocalDate.of(2028, 7, 30), BigInteger.valueOf(5_000_000)),
                        new Recovery("c", Payer.DEVELOPER, LocalDate.of(2029, 4, 1), BigInteger.valueOf(3_000_000)))));
        var loss = impairment(s, p, 3, shortfall);
        BigDecimal late = major(BigInteger.valueOf(3_000_000)).divide(discount(SIMPLE_CALCULATION_VERSION, s.netEir().rate(),
                LocalDate.of(2028, 4, 1), date, LocalDate.of(2029, 4, 1), List.of(LocalDate.of(2028, 7, 30), LocalDate.of(2029, 1, 26))),
                ReceivablesMath.MC);
        near(p.legs().get(2).netBasis().subtract(late), loss.lossByCashflow().get("c"));
        assertTrue(loss.lossAllowanceMinor().signum() > 0);
    }
}
