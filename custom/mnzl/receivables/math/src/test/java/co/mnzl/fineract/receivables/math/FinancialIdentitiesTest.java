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

import static co.mnzl.fineract.receivables.math.CreditAndFunding.FundingAccrual;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.FundingInterval;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.Scenario;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.funding;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.impairment;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.stage;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.stage3Unwind;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.Modification;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.Reset;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.Settlement;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.developerMemo;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.modify;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.reset;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.settle;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.settlePortions;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.substitute;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Income;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.MC;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Purchase;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.allocate;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.allocateGross;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.allocateNet;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.face;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.growth;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.income;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.major;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.price;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.pv;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.solve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.mnzl.fineract.receivables.math.CreditAndFunding.Payer;
import co.mnzl.fineract.receivables.math.CreditAndFunding.Recovery;
import co.mnzl.fineract.receivables.math.CreditAndFunding.Stage3Unwind;
import co.mnzl.fineract.receivables.math.ReceivableEvents.PartialSettlement;
import co.mnzl.fineract.receivables.math.ReceivablesMath.MeasurementState;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinancialIdentitiesTest {

    private static final LocalDate START = LocalDate.of(2028, 1, 1);
    private static final BigDecimal RATE = new BigDecimal("0.24");

    private static List<Cashflow> flows() {
        return List.of(new Cashflow("a", START.plusDays(60), BigInteger.valueOf(4000000)),
                new Cashflow("b", START.plusDays(60), BigInteger.valueOf(1000000)),
                new Cashflow("c", START.plusDays(365), BigInteger.valueOf(6000000)));
    }

    private static Purchase purchase() {
        return price(START, flows(), RATE, new BigDecimal("0.01"));
    }

    private static void near(BigDecimal a, BigDecimal b) {
        assertTrue(a.subtract(b).abs().compareTo(new BigDecimal("1e-10")) < 0, a + " != " + b);
    }

    @Test
    void independentDailyRecurrenceMatchesEveryMonthlyAndChequeBoundary() {
        Purchase p = purchase();
        Segment s = p.segment();
        MathContext independent = new MathContext(75);
        Map<String, BigDecimal> gross = new HashMap<>();
        Map<String, BigDecimal> net = new HashMap<>();
        Map<String, BigInteger> outstanding = new HashMap<>();
        BigDecimal qg = BigDecimal.ONE.add(s.grossYield().rate().divide(new BigDecimal("360"), independent));
        BigDecimal qe = BigDecimal.ONE.add(s.netEir().rate().divide(new BigDecimal("360"), independent));
        for (Cashflow cf : flows()) {
            BigDecimal g = new BigDecimal(cf.amountMinor(), 2);
            BigDecimal n = g;
            for (LocalDate day = START; day.isBefore(cf.dueDate()); day = day.plusDays(1)) {
                g = g.divide(qg, independent);
                n = n.divide(qe, independent);
            }
            gross.put(cf.cashflowId(), g);
            net.put(cf.cashflowId(), n);
        }
        BigInteger cumulativeGross = BigInteger.ZERO;
        BigInteger cumulativeNet = BigInteger.ZERO;
        Position previous = position(s, START, outstanding);
        for (LocalDate date = START.plusDays(1); !date.isAfter(START.plusDays(365)); date = date.plusDays(1)) {
            for (Cashflow cf : flows()) {
                if (!date.isAfter(cf.dueDate())) {
                    gross.compute(cf.cashflowId(), (id, v) -> v.multiply(qg, independent));
                    net.compute(cf.cashflowId(), (id, v) -> v.multiply(qe, independent));
                }
            }
            BigInteger received = BigInteger.ZERO;
            for (Cashflow cf : flows()) {
                if (cf.dueDate().equals(date)) {
                    received = received.add(cf.amountMinor());
                    outstanding.put(cf.cashflowId(), BigInteger.ZERO);
                    gross.put(cf.cashflowId(), BigDecimal.ZERO);
                    net.put(cf.cashflowId(), BigDecimal.ZERO);
                }
            }
            Position current = position(s, date, outstanding);
            BigDecimal recurrenceG = gross.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal recurrenceN = net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            near(recurrenceG, current.analyticalGrossBasis());
            near(recurrenceN, current.analyticalNetBasis());
            assertEquals(recurrenceG.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact(),
                    current.grossPurchaseBasisMinor());
            assertEquals(recurrenceN.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact(), current.amortizedCostMinor());
            Income daily = income(previous, current, received);
            cumulativeGross = cumulativeGross.add(daily.grossDiscountIncomeMinor());
            cumulativeNet = cumulativeNet.add(daily.interestIncomeMinor());
            assertEquals(current.amortizedCostMinor(), current.contractualOutstandingMinor().subtract(current.deferredDiscountMinor())
                    .subtract(current.deferredIntegralFeeMinor()));
            assertEquals(daily.interestIncomeMinor(), daily.grossDiscountIncomeMinor().add(daily.integralFeeIncomeMinor()));
            previous = current;
        }
        assertEquals(p.contractualFaceMinor().subtract(p.grossPurchasePriceMinor()), cumulativeGross);
        assertEquals(p.contractualFaceMinor().subtract(p.netPurchaseCashMinor()), cumulativeNet);
        assertEquals(BigInteger.ZERO, previous.deferredDiscountMinor());
        assertEquals(BigInteger.ZERO, previous.deferredIntegralFeeMinor());
    }

    @Test
    void partitionsMonotonicityAndDayCountDiscriminate() {
        BigDecimal qa = growth(RATE, 61);
        BigDecimal qb = growth(RATE, 304);
        near(growth(RATE, 365), qa.multiply(qb, MC));
        BigDecimal basis = new BigDecimal("100000");
        near(basis.multiply(growth(RATE, 365).subtract(BigDecimal.ONE), MC),
                basis.multiply(qa.subtract(BigDecimal.ONE), MC).add(basis.multiply(qa, MC).multiply(qb.subtract(BigDecimal.ONE), MC), MC));
        assertTrue(pv(START, flows(), RATE).compareTo(pv(START, flows(), RATE.add(new BigDecimal("0.01")))) > 0);
        near(pv(START, flows(), BigDecimal.ZERO), major(face(flows())));
        BigDecimal actual365 = BigDecimal.ONE.add(RATE.divide(new BigDecimal("365"), MC)).pow(365, MC);
        BigDecimal simple = BigDecimal.ONE.add(RATE.multiply(new BigDecimal("365")).divide(new BigDecimal("360"), MC));
        assertNotEquals(0, actual365.compareTo(growth(RATE, 365)));
        assertNotEquals(0, simple.compareTo(growth(RATE, 365)));
        Purchase p = purchase();
        assertTrue(p.segment().netEir().rate().compareTo(p.segment().grossYield().rate()) > 0);
        Purchase zeroFee = price(START, flows(), RATE, BigDecimal.ZERO);
        assertEquals(zeroFee.segment().grossYield(), zeroFee.segment().netEir());
    }

    @Test
    void partialLateReversalAndPartialSettlementRetainUnselectedYields() {
        Purchase p = purchase();
        LocalDate date = START.plusDays(90);
        Position before = position(p.segment(), date, Map.of());
        Position paid = position(p.segment(), date, Map.of("a", BigInteger.valueOf(3000000)));
        assertEquals(BigInteger.valueOf(1000000), before.amortizedCostMinor().subtract(paid.amortizedCostMinor()));
        assertEquals(before, position(p.segment(), date, Map.of("a", BigInteger.valueOf(4000000))));
        assertEquals(paid.pastDueMinor(),
                position(p.segment(), date.plusDays(10), Map.of("a", BigInteger.valueOf(3000000))).pastDueMinor());
        Settlement selected = settlePortions(paid, Map.of("c", BigInteger.valueOf(3000000)), BigInteger.valueOf(3000000), BigInteger.ZERO)
                .settlement();
        assertEquals(BigInteger.valueOf(3000000), selected.faceExtinguishedMinor());
        assertEquals(paid.grossPurchaseBasisMinor(), allocateGross(paid).values().stream().reduce(BigInteger.ZERO, BigInteger::add));
        assertEquals(paid.amortizedCostMinor(), allocateNet(paid).values().stream().reduce(BigInteger.ZERO, BigInteger::add));
        assertThrows(IllegalArgumentException.class,
                () -> settle(BigInteger.TEN, BigInteger.TEN, BigInteger.TEN, BigInteger.ONE, BigInteger.ZERO));
    }

    @Test
    void repeatedResetsLotsMemoAndWorkouts() {
        Purchase p = purchase();
        Reset first = reset(p.segment(), START.plusDays(10), Map.of(), new BigDecimal("0.20"));
        Reset second = reset(first.futureSegment(), START.plusDays(20), Map.of(), new BigDecimal("0.28"));
        assertNotNull(first.lot());
        assertNotNull(second.lot());
        assertEquals(first.lot().amountDueMinor(), first.lot().balanceMinor(START.plusDays(200)));
        assertEquals(BigInteger.ZERO, reset(p.segment(), START.plusDays(400), Map.of(), RATE).deltaMinor());
        BigInteger d = developerMemo(p.grossPurchasePriceMinor(), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        assertEquals(p.grossPurchasePriceMinor(), d);
        Position pos = position(p.segment(), START.plusDays(30), Map.of());
        List<Cashflow> modified = List.of(new Cashflow("a-mod", START.plusDays(365), BigInteger.valueOf(3000000)));
        Modification mod = modify(pos.businessDate(), modified, p.segment().netEir().rate(), pos.amortizedCostMinor());
        assertTrue(mod.modificationGainLossMinor().signum() < 0);
        assertEquals(p.segment().netEir().rate(), mod.originalNetEir());
        assertThrows(IllegalArgumentException.class,
                () -> substitute(pos, List.of(new Cashflow("bad", START.plusDays(365), BigInteger.ONE)), BigInteger.ZERO));
    }

    @Test
    void validationImpairmentAndFundingChanges() {
        assertThrows(IllegalArgumentException.class,
                () -> price(START, List.of(new Cashflow("past", START, BigInteger.ONE)), RATE, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> price(START, flows(), RATE, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> solve(START, flows(), face(flows()).add(BigInteger.ONE)));
        assertEquals(2, stage(30, false, false));
        assertEquals(3, stage(90, false, false));
        assertEquals(3, stage(0, false, true));
        assertEquals(2, stage(0, true, false));
        Position p = position(purchase().segment(), START, Map.of());
        assertThrows(IllegalArgumentException.class, () -> impairment(p, RATE, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> impairment(p, RATE, 1, List.of(new Scenario("bad", new BigDecimal("0.9"), START, List.of()))));
        FundingAccrual split = funding(
                List.of(new FundingInterval(START, START.plusDays(10), BigInteger.valueOf(10000000), new BigDecimal("0.18")),
                        new FundingInterval(START.plusDays(10), START.plusDays(30), BigInteger.valueOf(5000000), new BigDecimal("0.18"))));
        assertEquals(BigInteger.valueOf(100000), split.expenseMinor());
        assertEquals(BigInteger.ZERO, split.capitalizedInterestMinor());
        assertEquals(Map.of("a", BigInteger.ONE, "b", BigInteger.ZERO),
                allocate(BigInteger.ONE, Map.of("a", new BigDecimal("0.005"), "b", new BigDecimal("0.005"))));
    }

    @Test
    void analyticalDerivativeMatchesIndependentSymmetricDifference() {
        BigDecimal epsilon = new BigDecimal("1e-10");
        BigDecimal q = BigDecimal.ONE.add(RATE.divide(new BigDecimal("360"), MC));
        BigDecimal derivative = BigDecimal.ZERO;
        for (Cashflow cf : flows()) {
            int n = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(START, cf.dueDate()));
            derivative = derivative.subtract(new BigDecimal(cf.amountMinor(), 2).multiply(BigDecimal.valueOf(n), MC)
                    .divide(new BigDecimal("360").multiply(q.pow(n + 1, MC), MC), MC), MC);
        }
        BigDecimal finiteDifference = pv(START, flows(), RATE.add(epsilon)).subtract(pv(START, flows(), RATE.subtract(epsilon)), MC)
                .divide(epsilon.multiply(new BigDecimal("2")), MC);
        near(derivative, finiteDifference);
        assertTrue(derivative.signum() < 0);
    }

    @Test
    void partialSettlementPersistsExactRetainedTargetsThroughSameDayEventsAndMaturity() {
        Purchase p = purchase();
        LocalDate date = START.plusDays(30);
        Position before = position(p.segment(), date, Map.of());
        PartialSettlement first = settlePortions(before, Map.of("c", BigInteger.valueOf(100028)), BigInteger.valueOf(100028),
                BigInteger.ZERO);
        MeasurementState state = first.remainingMeasurement(p.segment());
        Position retained = position(state, date);
        assertEquals(BigInteger.valueOf(9617242), before.amortizedCostMinor());
        assertEquals(BigInteger.valueOf(78743), first.settlement().amortizedCostMinor());
        assertEquals(BigInteger.valueOf(9538499), retained.amortizedCostMinor());
        assertEquals(before.amortizedCostMinor(), first.settlement().amortizedCostMinor().add(retained.amortizedCostMinor()));
        assertEquals(BigInteger.ZERO, income(retained, position(state, date), BigInteger.ZERO).interestIncomeMinor());
        Reset immediateReset = reset(state, date, new BigDecimal("0.20"));
        assertEquals(retained.amortizedCostMinor(), immediateReset.oldNetMinor());
        assertEquals(retained.deferredIntegralFeeMinor(),
                immediateReset.futureSegment().grossBasisMinor().subtract(immediateReset.futureSegment().netBasisMinor()));
        PartialSettlement second = settlePortions(retained, Map.of("c", BigInteger.valueOf(100028)), BigInteger.valueOf(100028),
                BigInteger.ZERO);
        MeasurementState afterSecond = second.remainingMeasurement(p.segment());
        assertEquals(retained.amortizedCostMinor(),
                second.settlement().amortizedCostMinor().add(position(afterSecond, date).amortizedCostMinor()));
        assertEquals(p.segment().grossYield(), afterSecond.segment().grossYield());
        assertEquals(p.segment().netEir(), afterSecond.segment().netEir());
        Position maturity = position(afterSecond, START.plusDays(365));
        assertEquals(BigInteger.ZERO, maturity.deferredDiscountMinor());
        assertEquals(BigInteger.ZERO, maturity.deferredIntegralFeeMinor());
        PartialSettlement terminal = settlePortions(maturity, second.remainingFaceMinor(), maturity.contractualOutstandingMinor(),
                BigInteger.ZERO);
        assertEquals(BigInteger.ZERO, terminal.retainedPosition().contractualOutstandingMinor());
        assertEquals(BigInteger.ZERO, terminal.retainedPosition().grossPurchaseBasisMinor());
        assertEquals(BigInteger.ZERO, terminal.retainedPosition().amortizedCostMinor());
    }

    @Test
    void stage3RequiresConditionalObservedDefaultAndUnwindsUnmaturedRecoveries() {
        List<Cashflow> cashflows = List.of(new Cashflow("stage3", START.plusDays(365), BigInteger.valueOf(1000000)));
        Purchase p = price(START, cashflows, RATE, new BigDecimal("0.01"));
        Position opening = position(p.segment(), START, Map.of());
        Scenario noDefault = new Scenario("no-default", new BigDecimal("0.9"), null,
                List.of(new Recovery("stage3", Payer.BORROWER, START.plusDays(365), BigInteger.valueOf(1000000))));
        Scenario observed = new Scenario("observed", new BigDecimal("0.1"), START, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> impairment(opening, p.segment().netEir().rate(), 3, List.of(noDefault, observed)));
        Scenario future = new Scenario("future-default", BigDecimal.ONE, START.plusDays(1), List.of());
        assertThrows(IllegalArgumentException.class, () -> impairment(opening, p.segment().netEir().rate(), 3, List.of(future)));
        assertEquals(opening.amortizedCostMinor(),
                impairment(opening, p.segment().netEir().rate(), 3, List.of(new Scenario("conditional", BigDecimal.ONE, START, List.of())))
                        .lossAllowanceMinor());
        List<Scenario> forecast = List.of(new Scenario("conditional-recovery", BigDecimal.ONE, START,
                List.of(new Recovery("stage3", Payer.BORROWER, START.plusDays(365), BigInteger.valueOf(300000)))));
        Stage3Unwind unwind = stage3Unwind(p.segment(), START, START.plusDays(1), Map.of(), forecast);
        Position closing = position(p.segment(), START.plusDays(1), Map.of());
        BigInteger openingNet = opening.amortizedCostMinor().subtract(unwind.openingAllowanceMinor());
        BigInteger closingNet = closing.amortizedCostMinor().subtract(unwind.closingAllowanceMinor());
        assertEquals(closingNet.subtract(openingNet), unwind.interestIncomeMinor());
        assertTrue(unwind.interestIncomeMinor().signum() > 0);
        assertTrue(unwind.interestIncomeMinor().compareTo(unwind.scheduledEirIncomeMinor()) < 0);
        assertEquals(opening.contractualOutstandingMinor(), closing.contractualOutstandingMinor());
    }

}
