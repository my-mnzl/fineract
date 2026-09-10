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
import static co.mnzl.fineract.receivables.math.CreditAndFunding.Impairment;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.Payer;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.Recovery;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.Scenario;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.Stage3Unwind;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.funding;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.impairment;
import static co.mnzl.fineract.receivables.math.CreditAndFunding.stage3Unwind;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.Reset;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.Settlement;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.Substitution;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.reset;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.settle;
import static co.mnzl.fineract.receivables.math.ReceivableEvents.substitute;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.CALCULATION_VERSION;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Income;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Purchase;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Yield;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.days;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.growth;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.income;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.major;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.minor;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.price;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferenceVectorsTest {

    private static final Map<String, JsonNode> VECTORS = load();

    private static Map<String, JsonNode> load() {
        try {
            JsonNode root = new ObjectMapper().readTree(ReferenceVectorsTest.class.getResourceAsStream("/reference-vectors.json"));
            assertEquals(CALCULATION_VERSION, root.path("calculationVersion").asText());
            Map<String, JsonNode> result = new LinkedHashMap<>();
            root.path("vectors").forEach(v -> result.put(v.path("id").asText(), v));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** References are fixture-local complete objects, never calculator request IDs. */
    private static JsonNode expand(JsonNode node) {
        JsonNode copy = node.deepCopy();
        if (copy.isObject()) {
            ObjectNode object = (ObjectNode) copy;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String key : names) {
                JsonNode value = object.get(key);
                if (List.of("acquisitionVector", "sourceVector", "firstAccountVector").contains(key)) {
                    assertTrue(VECTORS.containsKey(value.asText()));
                    object.set(key, expand(VECTORS.get(value.asText())));
                } else {
                    object.set(key, expand(value));
                }
            }
        } else if (copy.isArray()) {
            for (int i = 0; i < copy.size(); i++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) copy).set(i, expand(copy.get(i)));
            }
        }
        return copy;
    }

    private static JsonNode input(String id) {
        return expand(VECTORS.get(id)).path("input");
    }

    private static JsonNode expected(String id) {
        return VECTORS.get(id).path("expected");
    }

    private static BigDecimal d(JsonNode node, String key) {
        return new BigDecimal(node.path(key).asText());
    }

    private static BigInteger m(JsonNode node, String key) {
        return new BigInteger(node.path(key).asText());
    }

    private static LocalDate date(JsonNode node, String key) {
        return LocalDate.parse(node.path(key).asText());
    }

    private static List<Cashflow> flows(JsonNode node) {
        List<Cashflow> result = new ArrayList<>();
        node.forEach(c -> result.add(new Cashflow(c.path("id").asText(), date(c, "dueDate"), m(c, "amountMinor"))));
        return result;
    }

    private static Purchase purchase(JsonNode in) {
        return price(date(in, "settlementDate"), flows(in.path("cashflows")), d(in, "nominalAnnualRate"), d(in, "feeRate"));
    }

    private static Map<String, BigInteger> collected(JsonNode in) {
        Map<String, BigInteger> result = new LinkedHashMap<>();
        in.path("collectedCashflowIds").forEach(id -> result.put(id.asText(), BigInteger.ZERO));
        return result;
    }

    private static void money(JsonNode e, String key, BigInteger actual) {
        if (e.has(key)) {
            assertEquals(m(e, key), actual, key);
        }
    }

    private static void decimal(JsonNode e, String key, BigDecimal actual) {
        if (e.has(key)) {
            assertTrue(
                    d(e, key).subtract(actual).abs().compareTo(
                            new BigDecimal(key.contains("Yield") || key.equals("netEir") || key.contains("Eir") ? "5e-17" : "1e-10")) <= 0,
                    key + ": " + actual);
        }
    }

    @Test
    void allPriceVectorsAndIndependentExpectedValues() {
        for (String id : List.of("zero-rate", "irregular-integral-fee", "small-cheque", "late-partial", "signed-fee-movement")) {
            JsonNode in = input(id);
            JsonNode e = expected(id);
            Purchase p = purchase(in);
            money(e, "grossPriceMinor", p.grossPurchasePriceMinor());
            money(e, "feeMinor", p.integralFeeMinor());
            money(e, "netPurchaseCashMinor", p.netPurchaseCashMinor());
            money(e, "contractualFaceMinor", p.contractualFaceMinor());
            decimal(e, "grossYield", p.segment().grossYield().rate());
            decimal(e, "netEir", p.segment().netEir().rate());
            decimal(e, "unroundedGrossPrice", p.unroundedGrossPrice());
            assertTrue(p.segment().grossYield().residual().abs().compareTo(new BigDecimal("1e-10")) <= 0);
            assertTrue(p.segment().netEir().residual().abs().compareTo(new BigDecimal("1e-10")) <= 0);
            // Independently evaluate quote with repeated daily discounting at a different precision (no production
            // growth/PV).
            BigDecimal q = BigDecimal.ONE.add(d(in, "nominalAnnualRate").divide(new BigDecimal("360"), new MathContext(75)));
            BigDecimal independent = BigDecimal.ZERO;
            for (Cashflow cf : flows(in.path("cashflows"))) {
                BigDecimal value = new BigDecimal(cf.amountMinor(), 2);
                for (LocalDate day = date(in, "settlementDate"); day.isBefore(cf.dueDate()); day = day.plusDays(1)) {
                    value = value.divide(q, new MathContext(75));
                }
                independent = independent.add(value);
            }
            assertTrue(independent.subtract(p.unroundedGrossPrice()).abs().compareTo(new BigDecimal("1e-10")) < 0);
            Position opening = position(p.segment(), p.segment().startDate(), Map.of());
            if (id.equals("small-cheque")) {
                Position next = position(p.segment(), flows(in.path("cashflows")).getFirst().dueDate(), Map.of());
                money(e, "firstIntervalIncomeMinor", income(opening, next, BigInteger.ZERO).interestIncomeMinor());
                assertTrue(income(opening, next, BigInteger.ZERO).interestIncomeMinor().compareTo(BigInteger.valueOf(100)) > 0);
            }
            if (id.equals("signed-fee-movement")) {
                Position next = position(p.segment(), date(in, "boundaryDate"), Map.of());
                Income inc = income(opening, next, BigInteger.ZERO);
                money(e, "grossIncomeMinor", inc.grossDiscountIncomeMinor());
                money(e, "netEirIncomeMinor", inc.interestIncomeMinor());
                money(e, "feeIncomeMinor", inc.integralFeeIncomeMinor());
                money(e, "closingDeferredFeeMinor", next.deferredIntegralFeeMinor());
            }
        }
    }

    @Test
    void dateGrowthAndNoChequeMonth() {
        JsonNode in = input("daily-leap");
        JsonNode e = expected("daily-leap");
        BigDecimal factor = growth(d(in, "nominalAnnualRate"), days(date(in, "fromDate"), date(in, "toDate")));
        assertEquals(2, days(date(in, "fromDate"), date(in, "toDate")));
        decimal(e, "factor", factor);
        money(e, "closingMinor", minor(major(m(in, "openingMinor")).multiply(factor)));
        in = input("no-cheque-month");
        e = expected("no-cheque-month");
        Purchase p = purchase(in.path("acquisitionVector").path("input"));
        Position next = position(p.segment(), date(in, "boundaryDate"), Map.of());
        Income inc = income(position(p.segment(), p.segment().startDate(), Map.of()), next, BigInteger.ZERO);
        money(e, "grossBasisMinor", next.grossPurchaseBasisMinor());
        money(e, "amortizedCostMinor", next.amortizedCostMinor());
        money(e, "deferredFeeMinor", next.deferredIntegralFeeMinor());
        money(e, "grossIncomeMinor", inc.grossDiscountIncomeMinor());
        money(e, "feeIncomeMinor", inc.integralFeeIncomeMinor());
        money(e, "totalIncomeMinor", inc.interestIncomeMinor());
    }

    @Test
    void resetVectors() {
        for (String id : List.of("reset-fall", "reset-rise")) {
            JsonNode in = input(id);
            JsonNode e = expected(id);
            Purchase p = purchase(in.path("acquisitionVector").path("input"));
            Reset r = reset(p.segment(), date(in, "resetDate"), collected(in), d(in, "newNominalAnnualRate"));
            decimal(e, "oldPvSameDate", r.oldPvSameDate());
            decimal(e, "newPvSameDate", r.newPvSameDate());
            money(e, "oldGrossBasisMinor", r.oldGrossMinor());
            money(e, "oldAmortizedCostMinor", r.oldNetMinor());
            money(e, "newGrossBasisMinor", r.futureSegment().grossBasisMinor());
            money(e, "newAmortizedCostMinor", r.futureSegment().netBasisMinor());
            money(e, "developerAdjustmentMinor", r.deltaMinor());
            money(e, "adjustmentSettlementMinor", r.lot().amountDueMinor());
            money(e, "adjustmentUnwindMinor", r.lot().amountDueMinor().subtract(r.lot().principalMinor()));
            decimal(e, "newGrossYield", r.futureSegment().grossYield().rate());
            decimal(e, "newNetEir", r.futureSegment().netEir().rate());
            assertEquals(e.path("developerDirection").asText(), r.lot().direction().name());
            assertEquals(r.lot().balanceMinor(r.lot().dueDate()), r.lot().balanceMinor(r.lot().dueDate().plusDays(60)));
            assertEquals(r.oldGrossMinor().subtract(r.oldNetMinor()),
                    r.futureSegment().grossBasisMinor().subtract(r.futureSegment().netBasisMinor()));
        }
    }

    @Test
    void settlementAndSubstitution() {
        JsonNode in = input("early-settlement");
        JsonNode e = expected("early-settlement");
        Purchase p = purchase(in.path("acquisitionVector").path("input"));
        Position pos = position(p.segment(), date(in, "settlementDate"), collected(in));
        Settlement s = settle(pos.contractualOutstandingMinor(), pos.grossPurchaseBasisMinor(), pos.amortizedCostMinor(),
                m(in, "agreedPayoffMinor"), BigInteger.ZERO);
        money(e, "developerShareMinor", s.developerShareMinor());
        money(e, "financierIncomeMinor", s.financierIncomeMinor());
        money(e, "deferredDiscountMinor", s.deferredDiscountMinor());
        money(e, "deferredFeeMinor", s.deferredIntegralFeeMinor());
        in = input("cashless-substitution");
        e = expected("cashless-substitution");
        Substitution sub = substitute(pos, flows(in.path("replacementCashflows")), BigInteger.ZERO);
        money(e, "transferredGrossBasisMinor", sub.replacement().grossBasisMinor());
        money(e, "transferredAmortizedCostMinor", sub.replacement().netBasisMinor());
        money(e, "replacementFaceMinor", sub.replacementFaceMinor());
        decimal(e, "replacementGrossYield", sub.replacement().grossYield().rate());
        decimal(e, "replacementNetEir", sub.replacement().netEir().rate());
    }

    private static List<Scenario> scenarios(JsonNode array) {
        List<Scenario> result = new ArrayList<>();
        for (JsonNode s : array) {
            List<Recovery> recoveries = new ArrayList<>();
            for (JsonNode r : s.path("recoveries")) {
                recoveries.add(new Recovery(r.path("cashflowId").asText(), Payer.valueOf(r.path("payer").asText()), date(r, "date"),
                        m(r, "amountMinor")));
            }
            result.add(new Scenario(s.path("id").asText(), d(s, "weight"), s.path("defaultDate").isNull() ? null : date(s, "defaultDate"),
                    recoveries));
        }
        return result;
    }

    @Test
    void eclAndStage3Vectors() {
        JsonNode in = input("ecl-horizons");
        JsonNode e = expected("ecl-horizons");
        List<Cashflow> cf = flows(in.path("contractualCashflows"));
        Segment seg = new Segment(date(in, "asOfDate"), cf, BigInteger.ZERO, BigInteger.ZERO,
                new Yield(d(in, "grossYield"), BigDecimal.ZERO, 0), new Yield(d(in, "netEir"), BigDecimal.ZERO, 0));
        Position pos = position(seg, date(in, "asOfDate"), Map.of());
        decimal(e, "unroundedAmortizedCost", pos.analyticalNetBasis());
        Impairment one = impairment(pos, d(in, "netEir"), 1, scenarios(in.path("scenarios")));
        Impairment two = impairment(pos, d(in, "netEir"), 2, scenarios(in.path("scenarios")));
        money(e, "stage1LossAllowanceMinor", one.lossAllowanceMinor());
        money(e, "stage2LossAllowanceMinor", two.lossAllowanceMinor());
        decimal(e, "stage1UnroundedLoss", one.analyticalAllowance());
        decimal(e, "stage2UnroundedLoss", two.analyticalAllowance());
        in = input("late-partial");
        e = expected("late-partial");
        Purchase p = purchase(in);
        BigInteger remaining = BigInteger.valueOf(700000);
        pos = position(p.segment(), date(in, "asOfDate"), Map.of("cf-01", remaining));
        Impairment late = impairment(pos, p.segment().netEir().rate(), 1, List.of(new Scenario("cure", BigDecimal.ONE, null,
                List.of(new Recovery("cf-01", Payer.BORROWER, date(in, "expectedRemainingRecoveryDate"), remaining)))));
        money(e, "lossAllowanceMinor", late.lossAllowanceMinor());
        money(e, "asOfAmortizedCostMinor", pos.amortizedCostMinor());
        assertEquals(15, pos.daysPastDue());
        in = input("stage3-overdue-unwind");
        e = expected("stage3-overdue-unwind");
        List<Scenario> forecast = List.of(new Scenario("default", BigDecimal.ONE, date(in, "fromDate"),
                List.of(new Recovery("cf-01", Payer.BORROWER, date(in, "recoveryDate"), m(in, "borrowerRecoveryMinor")),
                        new Recovery("cf-01", Payer.DEVELOPER, date(in, "recoveryDate"), m(in, "developerRecoveryMinor")))));
        Stage3Unwind unwind = stage3Unwind(p.segment(), date(in, "fromDate"), date(in, "toDate"), Map.of("cf-01", remaining), forecast);
        money(e, "openingAllowanceMinor", unwind.openingAllowanceMinor());
        money(e, "closingAllowanceMinor", unwind.closingAllowanceMinor());
        money(e, "interestIncomeMinor", unwind.interestIncomeMinor());
        assertEquals(BigInteger.ZERO, unwind.scheduledEirIncomeMinor());
    }

    @Test
    void fundingAndPortfolioVectors() {
        JsonNode in = input("bank-funding");
        JsonNode e = expected("bank-funding");
        FundingAccrual f = funding(List
                .of(new FundingInterval(date(in, "fromDate"), date(in, "toDate"), m(in, "principalMinor"), d(in, "nominalAnnualRate"))));
        money(e, "interestMinor", f.expenseMinor());
        money(e, "capitalizedInterestMinor", f.capitalizedInterestMinor());
        in = input("rounding-aggregation");
        e = expected("rounding-aggregation");
        BigInteger g = BigInteger.ZERO;
        BigInteger h = BigInteger.ZERO;
        BigInteger n = BigInteger.ZERO;
        for (int i = 0; i < 3; i++) {
            BigInteger gi = minor(new BigDecimal(in.path("accountGrossPvMajor").get(i).asText()));
            BigInteger hi = minor(major(gi).multiply(d(in, "feeRate")));
            assertEquals(e.path("grossPriceByAccountMinor").get(i).asText(), gi.toString());
            g = g.add(gi);
            h = h.add(hi);
            n = n.add(gi.subtract(hi));
        }
        money(e, "portfolioGrossPriceMinor", g);
        money(e, "portfolioFeeMinor", h);
        money(e, "portfolioNetCashMinor", n);
        in = input("sum-two-accounts");
        e = expected("sum-two-accounts");
        Purchase a = purchase(in.path("firstAccountVector").path("input"));
        Purchase b = purchase(in.path("secondAccount"));
        money(e, "secondGrossPriceMinor", b.grossPurchasePriceMinor());
        money(e, "secondFeeMinor", b.integralFeeMinor());
        decimal(e, "secondGrossYield", b.segment().grossYield().rate());
        decimal(e, "secondNetEir", b.segment().netEir().rate());
        money(e, "portfolioGrossPriceMinor", a.grossPurchasePriceMinor().add(b.grossPurchasePriceMinor()));
        Position ap = position(a.segment(), date(in, "boundaryDate"), Map.of());
        Position bp = position(b.segment(), date(in, "boundaryDate"), Map.of());
        money(e, "boundaryGrossBasisMinor", ap.grossPurchaseBasisMinor().add(bp.grossPurchaseBasisMinor()));
        money(e, "boundaryAmortizedCostMinor", ap.amortizedCostMinor().add(bp.amortizedCostMinor()));
    }
}
