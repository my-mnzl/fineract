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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Pure Actual/360 measurement. Analytical amounts are major EGP; posted amounts are integer piastres. Two accretion
 * rules share pricing, rounding, boundaries and income: the daily-compounded default and the simple rule that
 * capitalises interest only on cheque due dates. A segment pins the rule it was booked under.
 */
public final class ReceivablesMath {

    /** Daily compounding: {@code growth = (1 + r/360)^actualDays}. The default when no version is pinned. */
    public static final String CALCULATION_VERSION = "EG_RECEIVABLES_ACT360_DAILY_V1";
    /** Simple interest between cheques, capitalised at each cheque due date: {@code B = B(1 + r*days/360) - C}. */
    public static final String SIMPLE_CALCULATION_VERSION = "EG_RECEIVABLES_ACT360_SIMPLE_V1";
    public static final List<String> SUPPORTED_VERSIONS = List.of(CALCULATION_VERSION, SIMPLE_CALCULATION_VERSION);
    public static final MathContext MC = new MathContext(60, RoundingMode.HALF_EVEN);
    private static final BigDecimal DAYS = new BigDecimal("360");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal RESIDUAL = new BigDecimal("1e-10");
    private static final BigDecimal WIDTH = new BigDecimal("1e-18");

    private ReceivablesMath() {}

    public record Cashflow(String cashflowId, LocalDate dueDate, BigInteger amountMinor) {

        public Cashflow {
            if (cashflowId == null || cashflowId.isBlank() || dueDate == null || amountMinor == null || amountMinor.signum() <= 0) {
                throw new IllegalArgumentException("Positive identified dated cashflow required");
            }
        }
    }

    public record Yield(BigDecimal rate, BigDecimal residual, int iterations) {
    }

    /** A segment pins its accretion rule; snapshots written before versions existed deserialise as daily. */
    public record Segment(LocalDate startDate, List<Cashflow> cashflows, BigInteger grossBasisMinor, BigInteger netBasisMinor,
            Yield grossYield, Yield netEir, String calculationVersion) {

        public Segment {
            cashflows = List.copyOf(cashflows);
            calculationVersion = version(calculationVersion);
        }

        public Segment(LocalDate startDate, List<Cashflow> cashflows, BigInteger grossBasisMinor, BigInteger netBasisMinor,
                Yield grossYield, Yield netEir) {
            this(startDate, cashflows, grossBasisMinor, netBasisMinor, grossYield, netEir, CALCULATION_VERSION);
        }
    }

    /** Null or blank selects the daily default; anything else must be a supported version id. */
    public static String version(String calculationVersion) {
        if (calculationVersion == null || calculationVersion.isBlank()) {
            return CALCULATION_VERSION;
        }
        require(SUPPORTED_VERSIONS.contains(calculationVersion), "Unsupported calculation version");
        return calculationVersion;
    }

    /** The accretion rule behind a version id. Pricing never depends on it; accretion after purchase does. */
    interface Accretion {

        String id();

        String compounding();

        String formula();

        /** Value growth of one unit over {@code days} with no cheque boundary in between. */
        BigDecimal accrual(BigDecimal rate, long days);

        /**
         * Factor dividing a value at {@code to} to reach its value at {@code from}. The daily rule compounds day by day
         * between the two dates. The simple rule is anchored: a value is carried from the anchor (the segment start or
         * the last cheque date on or before {@code from}) through every cheque date up to {@code to}, and the
         * anchor-to-{@code from} accrual is then taken out, so values at any date are shares of the one walked balance
         * rather than a fresh discounting from that date.
         */
        BigDecimal discount(BigDecimal rate, LocalDate anchor, LocalDate from, LocalDate to, NavigableSet<LocalDate> boundaries);
    }

    private static final Accretion DAILY = new Accretion() {

        @Override
        public String id() {
            return CALCULATION_VERSION;
        }

        @Override
        public String compounding() {
            return "DAILY";
        }

        @Override
        public String formula() {
            return "PV=sum(C/(1+r/360)^actualDays); U=F-G; H=G-N";
        }

        @Override
        public BigDecimal accrual(BigDecimal rate, long days) {
            return growth(rate, days);
        }

        @Override
        public BigDecimal discount(BigDecimal rate, LocalDate anchor, LocalDate from, LocalDate to, NavigableSet<LocalDate> boundaries) {
            days(anchor, from);
            return growth(rate, days(from, to));
        }
    };

    private static final Accretion SIMPLE = new Accretion() {

        @Override
        public String id() {
            return SIMPLE_CALCULATION_VERSION;
        }

        @Override
        public String compounding() {
            return "SIMPLE_AT_CHEQUES";
        }

        @Override
        public String formula() {
            return "B(d)=B(prev)*(1+r*actualDays/360)-C at each cheque date; PV=sum(C/prod(1+r*actualDays_k/360)); U=F-G; H=G-N";
        }

        @Override
        public BigDecimal accrual(BigDecimal rate, long days) {
            return simpleAccrual(rate, days);
        }

        @Override
        public BigDecimal discount(BigDecimal rate, LocalDate anchor, LocalDate from, LocalDate to, NavigableSet<LocalDate> boundaries) {
            days(anchor, from);
            days(from, to);
            BigDecimal factor = BigDecimal.ONE;
            LocalDate previous = anchor;
            for (LocalDate boundary : boundaries.subSet(anchor, false, to, false)) {
                factor = factor.multiply(simpleAccrual(rate, days(previous, boundary)), MC);
                previous = boundary;
            }
            factor = factor.multiply(simpleAccrual(rate, days(previous, to)), MC);
            return from.equals(anchor) ? factor : factor.divide(simpleAccrual(rate, days(anchor, from)), MC);
        }
    };

    static Accretion accretion(String calculationVersion) {
        return version(calculationVersion).equals(SIMPLE_CALCULATION_VERSION) ? SIMPLE : DAILY;
    }

    private static NavigableSet<LocalDate> boundaries(List<Cashflow> flows) {
        NavigableSet<LocalDate> result = new TreeSet<>();
        flows.forEach(cf -> result.add(cf.dueDate()));
        return result;
    }

    /** The segment start or the last cheque date on or before {@code date}: where the simple walk last capitalised. */
    public static LocalDate anchor(LocalDate startDate, LocalDate date, java.util.Collection<LocalDate> boundaries) {
        days(startDate, date);
        LocalDate anchor = startDate;
        for (LocalDate boundary : boundaries) {
            if (boundary.isAfter(anchor) && !boundary.isAfter(date)) {
                anchor = boundary;
            }
        }
        return anchor;
    }

    /** Persist the reconciled event-boundary targets alongside unchanged analytical yields/remaining face. */
    public record MeasurementState(Segment segment, Position boundaryPosition, Map<String, BigInteger> outstandingMinor) {

        public MeasurementState {
            outstandingMinor = Map.copyOf(outstandingMinor);
            validateMeasurementState(segment, boundaryPosition, outstandingMinor);
        }
    }

    private static void validateMeasurementState(Segment segment, Position boundary, Map<String, BigInteger> outstanding) {
        Map<String, Leg> storedLegs = new LinkedHashMap<>();
        for (Leg leg : boundary.legs()) {
            require(storedLegs.put(leg.cashflow().cashflowId(), leg) == null, "Duplicate measurement cashflow");
        }
        require(outstanding.keySet().equals(storedLegs.keySet()), "Measurement requires complete remaining face");
        Position analytical = position(segment, boundary.businessDate(), outstanding);
        require(analytical.legs().size() == storedLegs.size(), "Measurement segment cashflows changed");
        for (Leg measured : analytical.legs()) {
            Leg stored = storedLegs.get(measured.cashflow().cashflowId());
            require(stored != null && measured.cashflow().equals(stored.cashflow())
                    && measured.outstandingMinor().equals(stored.outstandingMinor()) && measured.discountDays() == stored.discountDays(),
                    "Measurement segment cashflows or remaining face changed");
            require(measured.grossBasis().subtract(stored.grossBasis()).abs().compareTo(RESIDUAL) <= 0
                    && measured.netBasis().subtract(stored.netBasis()).abs().compareTo(RESIDUAL) <= 0,
                    "Measurement segment yields changed");
        }
        require(analytical.contractualOutstandingMinor().equals(boundary.contractualOutstandingMinor())
                && analytical.notYetDueMinor().equals(boundary.notYetDueMinor())
                && analytical.pastDueMinor().equals(boundary.pastDueMinor())
                && analytical.analyticalGrossBasis().subtract(boundary.analyticalGrossBasis()).abs().compareTo(RESIDUAL) <= 0
                && analytical.analyticalNetBasis().subtract(boundary.analyticalNetBasis()).abs().compareTo(RESIDUAL) <= 0,
                "Measurement boundary does not match segment");
    }

    public static Position position(MeasurementState state, LocalDate date) {
        days(state.boundaryPosition().businessDate(), date);
        if (date.equals(state.boundaryPosition().businessDate())) {
            return state.boundaryPosition();
        }
        return position(state.segment(), date, state.outstandingMinor());
    }

    public record Purchase(BigDecimal quotedRate, BigDecimal feeRate, BigDecimal unroundedGrossPrice, BigInteger contractualFaceMinor,
            BigInteger grossPurchasePriceMinor, BigInteger integralFeeMinor, BigInteger netPurchaseCashMinor, Segment segment) {
    }

    public record PortfolioPurchase(List<Purchase> accounts, BigInteger contractualFaceMinor, BigInteger grossPurchasePriceMinor,
            BigInteger integralFeeMinor, BigInteger netPurchaseCashMinor) {

        public PortfolioPurchase {
            accounts = List.copyOf(accounts);
        }
    }

    public static PortfolioPurchase portfolio(List<Purchase> accounts) {
        require(!accounts.isEmpty(), "Portfolio accounts required");
        BigInteger face = BigInteger.ZERO;
        BigInteger gross = BigInteger.ZERO;
        BigInteger fee = BigInteger.ZERO;
        BigInteger cash = BigInteger.ZERO;
        for (Purchase account : accounts) {
            face = face.add(account.contractualFaceMinor());
            gross = gross.add(account.grossPurchasePriceMinor());
            fee = fee.add(account.integralFeeMinor());
            cash = cash.add(account.netPurchaseCashMinor());
        }
        return new PortfolioPurchase(accounts, face, gross, fee, cash);
    }

    public record Leg(Cashflow cashflow, long discountDays, BigInteger outstandingMinor, BigDecimal grossBasis, BigDecimal netBasis) {
    }

    public record Position(LocalDate businessDate, BigInteger contractualOutstandingMinor, BigInteger notYetDueMinor,
            BigInteger pastDueMinor, BigInteger grossPurchaseBasisMinor, BigInteger amortizedCostMinor, BigInteger deferredDiscountMinor,
            BigInteger deferredIntegralFeeMinor, int daysPastDue, BigDecimal analyticalGrossBasis, BigDecimal analyticalNetBasis,
            List<Leg> legs) {

        public Position {
            legs = List.copyOf(legs);
        }

        public BigInteger netCarryingMinor(BigInteger allowance) {
            require(allowance.signum() >= 0 && allowance.compareTo(amortizedCostMinor) <= 0, "Invalid allowance");
            return amortizedCostMinor.subtract(allowance);
        }
    }

    public record Income(BigInteger grossDiscountIncomeMinor, BigInteger integralFeeIncomeMinor, BigInteger interestIncomeMinor) {
    }

    public record Explanation(String calculationVersion, String currency, String dayCount, String compounding, String rounding,
            String boundary, String formula, LocalDate segmentStartDate, BigInteger openingGrossMinor, BigInteger openingNetMinor,
            Yield grossYield, Yield netEir, Position position) {
    }

    public static BigDecimal major(BigInteger minor) {
        return new BigDecimal(minor, 2);
    }

    public static BigInteger minor(BigDecimal major) {
        return major.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
    }

    public static long days(LocalDate from, LocalDate to) {
        long result = ChronoUnit.DAYS.between(from, to);
        require(result >= 0, "Dates must be ordered");
        return result;
    }

    public static BigDecimal growth(BigDecimal rate, long days) {
        require(rate != null && rate.signum() >= 0 && days >= 0 && days <= Integer.MAX_VALUE, "Invalid rate or day count");
        return BigDecimal.ONE.add(rate.divide(DAYS, MC), MC).pow((int) days, MC);
    }

    /** Simple Actual/360 accrual factor {@code 1 + r*days/360}; no compounding inside the interval. */
    public static BigDecimal simpleAccrual(BigDecimal rate, long days) {
        require(rate != null && rate.signum() >= 0 && days >= 0 && days <= Integer.MAX_VALUE, "Invalid rate or day count");
        return BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(days), MC).divide(DAYS, MC), MC);
    }

    /** Growth of one unit over {@code days} with no cheque boundary in between, under the given version. */
    public static BigDecimal accrual(String calculationVersion, BigDecimal rate, long days) {
        return accretion(calculationVersion).accrual(rate, days);
    }

    /**
     * Factor that discounts a value at {@code to} back to {@code from}. Daily ignores the anchor and boundaries; simple
     * carries the value from the anchor through each cheque boundary, then removes the anchor-to-{@code from} accrual
     * (see {@link #anchor}).
     */
    public static BigDecimal discount(String calculationVersion, BigDecimal rate, LocalDate anchor, LocalDate from, LocalDate to,
            java.util.Collection<LocalDate> boundaries) {
        return accretion(calculationVersion).discount(rate, anchor, from, to, new TreeSet<>(boundaries));
    }

    public static BigDecimal pv(LocalDate date, List<Cashflow> flows, BigDecimal rate) {
        BigDecimal value = BigDecimal.ZERO;
        for (Cashflow cf : flows) {
            value = value.add(major(cf.amountMinor()).divide(growth(rate, days(date, cf.dueDate())), MC), MC);
        }
        return value;
    }

    /** Present value under the version's accretion rule; the daily rule is {@link #pv(LocalDate, List, BigDecimal)}. */
    public static BigDecimal pv(String calculationVersion, LocalDate date, List<Cashflow> flows, BigDecimal rate) {
        Accretion accretion = accretion(calculationVersion);
        if (accretion == DAILY) {
            return pv(date, flows, rate);
        }
        NavigableSet<LocalDate> boundaries = boundaries(flows);
        BigDecimal value = BigDecimal.ZERO;
        for (Cashflow cf : flows) {
            value = value.add(major(cf.amountMinor()).divide(accretion.discount(rate, date, date, cf.dueDate(), boundaries), MC), MC);
        }
        return value;
    }

    public static Yield solve(LocalDate date, List<Cashflow> flows, BigInteger basisMinor) {
        return solve(CALCULATION_VERSION, date, flows, basisMinor);
    }

    /** Same bisection, bracket and acceptance rules for every version; only the PV under test changes. */
    public static Yield solve(String calculationVersion, LocalDate date, List<Cashflow> flows, BigInteger basisMinor) {
        String version = version(calculationVersion);
        validateFuture(date, flows);
        BigInteger face = face(flows);
        require(basisMinor.signum() > 0 && basisMinor.compareTo(face) <= 0, "Basis has no nonnegative yield");
        if (basisMinor.equals(face)) {
            return new Yield(BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        BigDecimal basis = major(basisMinor);
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = BigDecimal.ONE;
        int iterations = 0;
        while (pv(version, date, flows, high).compareTo(basis) > 0) {
            high = high.multiply(TWO, MC);
            require(++iterations < 4096, "Cannot bracket yield");
        }
        for (; iterations < 16384; iterations++) {
            BigDecimal rate = low.add(high, MC).divide(TWO, MC);
            BigDecimal residual = pv(version, date, flows, rate).subtract(basis, MC);
            if (residual.abs().compareTo(RESIDUAL) <= 0 && high.subtract(low).compareTo(WIDTH) <= 0) {
                return new Yield(rate, residual, iterations + 1);
            }
            if (residual.signum() > 0) {
                low = rate;
            } else {
                high = rate;
            }
        }
        throw new IllegalArgumentException("Yield precision unavailable");
    }

    public record PriceAmounts(BigDecimal unroundedGrossPrice, BigInteger contractualFaceMinor, BigInteger grossPurchasePriceMinor,
            BigInteger integralFeeMinor, BigInteger netPurchaseCashMinor) {
    }

    /** Shared monetary pricing; estimates do not need analytical yield solvers. */
    public static PriceAmounts priceAmounts(LocalDate date, List<Cashflow> flows, BigDecimal quotedRate, BigDecimal feeRate) {
        validateFuture(date, flows);
        require(feeRate.signum() >= 0 && feeRate.compareTo(BigDecimal.ONE) < 0, "Invalid integral fee rate");
        BigDecimal raw = pv(date, flows, quotedRate);
        BigInteger gross = minor(raw);
        BigInteger fee = minor(major(gross).multiply(feeRate, MC));
        BigInteger net = gross.subtract(fee);
        require(net.signum() > 0 && net.compareTo(gross) <= 0, "Invalid gross/net segment bases");
        return new PriceAmounts(raw, face(flows), gross, fee, net);
    }

    public static Purchase price(LocalDate date, List<Cashflow> flows, BigDecimal quotedRate, BigDecimal feeRate) {
        return price(CALCULATION_VERSION, date, flows, quotedRate, feeRate);
    }

    /**
     * The price is the daily PV at the quoted rate under every version; the version only decides how the paid amount
     * accretes afterwards, so the same cash buys different solved yields.
     */
    public static Purchase price(String calculationVersion, LocalDate date, List<Cashflow> flows, BigDecimal quotedRate,
            BigDecimal feeRate) {
        var amounts = priceAmounts(date, flows, quotedRate, feeRate);
        Segment segment = segment(calculationVersion, date, flows, amounts.grossPurchasePriceMinor(), amounts.netPurchaseCashMinor());
        return new Purchase(quotedRate, feeRate, amounts.unroundedGrossPrice(), amounts.contractualFaceMinor(),
                amounts.grossPurchasePriceMinor(), amounts.integralFeeMinor(), amounts.netPurchaseCashMinor(), segment);
    }

    public static Segment segment(LocalDate date, List<Cashflow> flows, BigInteger gross, BigInteger net) {
        return segment(CALCULATION_VERSION, date, flows, gross, net);
    }

    public static Segment segment(String calculationVersion, LocalDate date, List<Cashflow> flows, BigInteger gross, BigInteger net) {
        String version = version(calculationVersion);
        require(net.signum() > 0 && net.compareTo(gross) <= 0, "Invalid gross/net segment bases");
        return new Segment(date, flows, gross, net, solve(version, date, flows, gross), solve(version, date, flows, net), version);
    }

    /** Outstanding is the remaining face after committed events; omitted IDs retain full face. */
    public static Position position(Segment segment, LocalDate date, Map<String, BigInteger> outstanding) {
        days(segment.startDate(), date);
        validateIds(segment.cashflows());
        require(segment.cashflows().stream().map(Cashflow::cashflowId).toList().containsAll(outstanding.keySet()), "Unknown cashflow");
        if (accretion(segment.calculationVersion()) == SIMPLE) {
            return simplePosition(segment, date, outstanding);
        }
        BigInteger future = BigInteger.ZERO;
        BigInteger due = BigInteger.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        List<Leg> legs = new ArrayList<>();
        int dpd = 0;
        for (Cashflow cf : segment.cashflows()) {
            BigInteger amount = outstanding.getOrDefault(cf.cashflowId(), cf.amountMinor());
            require(amount.signum() >= 0 && amount.compareTo(cf.amountMinor()) <= 0, "Invalid remaining face");
            long n = cf.dueDate().isAfter(date) ? days(date, cf.dueDate()) : 0;
            BigDecimal g = major(amount).divide(growth(segment.grossYield().rate(), n), MC);
            BigDecimal e = major(amount).divide(growth(segment.netEir().rate(), n), MC);
            gross = gross.add(g, MC);
            net = net.add(e, MC);
            if (n > 0) {
                future = future.add(amount);
            } else {
                due = due.add(amount);
                if (amount.signum() > 0) {
                    dpd = Math.max(dpd, Math.toIntExact(days(cf.dueDate(), date)));
                }
            }
            legs.add(new Leg(cf, n, amount, g, e));
        }
        BigInteger f = future.add(due);
        BigInteger g = minor(gross);
        BigInteger n = minor(net);
        return new Position(date, f, future, due, g, n, f.subtract(g), g.subtract(n), dpd, gross, net, legs);
    }

    /**
     * Simple accretion walks one pooled balance forward: capitalise at every cheque date up to the measurement date,
     * then accrue simply from the last cheque date (or the segment start) to it. Each future leg carries its share of
     * that balance: its face discounted through the cheque dates back to the anchor, grown by the anchor-to-date
     * accrual. On a cheque date the shares equal the closed-form discounting exactly; due legs sit at face and stop
     * accreting, with partial payments reducing remaining face and reversals restoring it. Legs that were settled early
     * simply drop out of the balance.
     */
    private static Position simplePosition(Segment segment, LocalDate date, Map<String, BigInteger> outstanding) {
        NavigableSet<LocalDate> boundaries = boundaries(segment.cashflows());
        LocalDate anchor = anchor(segment.startDate(), date, boundaries);
        BigInteger future = BigInteger.ZERO;
        BigInteger due = BigInteger.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        List<Leg> legs = new ArrayList<>();
        int dpd = 0;
        for (Cashflow cf : segment.cashflows()) {
            BigInteger amount = outstanding.getOrDefault(cf.cashflowId(), cf.amountMinor());
            require(amount.signum() >= 0 && amount.compareTo(cf.amountMinor()) <= 0, "Invalid remaining face");
            long n = cf.dueDate().isAfter(date) ? days(date, cf.dueDate()) : 0;
            BigDecimal g;
            BigDecimal e;
            if (n > 0) {
                g = major(amount).divide(SIMPLE.discount(segment.grossYield().rate(), anchor, date, cf.dueDate(), boundaries), MC);
                e = major(amount).divide(SIMPLE.discount(segment.netEir().rate(), anchor, date, cf.dueDate(), boundaries), MC);
                future = future.add(amount);
            } else {
                g = major(amount);
                e = g;
                due = due.add(amount);
                if (amount.signum() > 0) {
                    dpd = Math.max(dpd, Math.toIntExact(days(cf.dueDate(), date)));
                }
            }
            gross = gross.add(g, MC);
            net = net.add(e, MC);
            legs.add(new Leg(cf, n, amount, g, e));
        }
        BigInteger f = future.add(due);
        BigInteger g = minor(gross);
        BigInteger n = minor(net);
        return new Position(date, f, future, due, g, n, f.subtract(g), g.subtract(n), dpd, gross, net, legs);
    }

    /** Targets, rather than independently rounded daily accruals, determine each posting. */
    public static Income income(Position opening, Position closing, BigInteger cashReceivedMinor) {
        days(opening.businessDate(), closing.businessDate());
        BigInteger gross = closing.grossPurchaseBasisMinor().subtract(opening.grossPurchaseBasisMinor()).add(cashReceivedMinor);
        BigInteger net = closing.amortizedCostMinor().subtract(opening.amortizedCostMinor()).add(cashReceivedMinor);
        return new Income(gross, net.subtract(gross), net);
    }

    public static Explanation explain(Segment segment, Position position) {
        Accretion accretion = accretion(segment.calculationVersion());
        return new Explanation(accretion.id(), "EGP", "Actual/360", accretion.compounding(), "HALF_UP", "START_OF_DAY", accretion.formula(),
                segment.startDate(), segment.grossBasisMinor(), segment.netBasisMinor(), segment.grossYield(), segment.netEir(), position);
    }

    /** Largest remainders allocate an existing monetary target; IDs provide deterministic ties. */
    public static Map<String, BigInteger> allocate(BigInteger target, Map<String, BigDecimal> weights) {
        require(target.signum() >= 0 && !weights.isEmpty(), "Invalid allocation");
        BigDecimal sum = weights.values().stream().peek(w -> require(w.signum() >= 0, "Negative allocation weight")).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        require(sum.signum() > 0 || target.signum() == 0, "Zero allocation weight");
        Map<String, BigInteger> result = new LinkedHashMap<>();
        Map<String, BigDecimal> remainders = new LinkedHashMap<>();
        BigInteger allocated = BigInteger.ZERO;
        for (String id : weights.keySet().stream().sorted().toList()) {
            BigDecimal share = weights.get(id).movePointRight(2);
            BigInteger floor = share.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
            result.put(id, floor);
            allocated = allocated.add(floor);
            remainders.put(id, share.subtract(new BigDecimal(floor)));
        }
        int residual = target.subtract(allocated).intValueExact();
        List<String> order = result.keySet().stream()
                .sorted(Comparator.<String, BigDecimal>comparing(remainders::get).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        require(residual >= 0 && residual <= order.size(), "Allocation precision insufficient");
        for (int i = 0; i < residual; i++) {
            result.compute(order.get(i), (id, value) -> value.add(BigInteger.ONE));
        }
        return Map.copyOf(result);
    }

    public static Map<String, BigInteger> allocateGross(Position p) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        p.legs().forEach(l -> weights.put(l.cashflow().cashflowId(), l.grossBasis()));
        return allocate(p.grossPurchaseBasisMinor(), weights);
    }

    public static Map<String, BigInteger> allocateNet(Position p) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        p.legs().forEach(l -> weights.put(l.cashflow().cashflowId(), l.netBasis()));
        return allocate(p.amortizedCostMinor(), weights);
    }

    static BigInteger face(List<Cashflow> flows) {
        return flows.stream().map(Cashflow::amountMinor).reduce(BigInteger.ZERO, BigInteger::add);
    }

    static void validateFuture(LocalDate date, List<Cashflow> flows) {
        require(!flows.isEmpty(), "Cashflows required");
        validateIds(flows);
        flows.forEach(cf -> require(cf.dueDate().isAfter(date), "Acquisition/segment requires future cashflows"));
    }

    static void validateIds(List<Cashflow> flows) {
        HashSet<String> ids = new HashSet<>();
        flows.forEach(cf -> require(ids.add(cf.cashflowId()), "Duplicate cashflow ID"));
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
