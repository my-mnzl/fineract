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

/**
 * Pure Actual/360 daily-compounded measurement. Analytical amounts are major EGP; posted amounts are integer piastres.
 */
public final class ReceivablesMath {

    public static final String CALCULATION_VERSION = "EG_RECEIVABLES_ACT360_DAILY_V1";
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

    public record Segment(LocalDate startDate, List<Cashflow> cashflows, BigInteger grossBasisMinor, BigInteger netBasisMinor,
            Yield grossYield, Yield netEir) {

        public Segment {
            cashflows = List.copyOf(cashflows);
        }
    }

    /** Persist the reconciled event-boundary targets alongside unchanged analytical yields/remaining face. */
    public record MeasurementState(Segment segment, Position boundaryPosition, Map<String, BigInteger> outstandingMinor) {

        public MeasurementState {
            outstandingMinor = Map.copyOf(outstandingMinor);
        }
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

    public static BigDecimal pv(LocalDate date, List<Cashflow> flows, BigDecimal rate) {
        BigDecimal value = BigDecimal.ZERO;
        for (Cashflow cf : flows) {
            value = value.add(major(cf.amountMinor()).divide(growth(rate, days(date, cf.dueDate())), MC), MC);
        }
        return value;
    }

    public static Yield solve(LocalDate date, List<Cashflow> flows, BigInteger basisMinor) {
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
        while (pv(date, flows, high).compareTo(basis) > 0) {
            high = high.multiply(TWO, MC);
            require(++iterations < 4096, "Cannot bracket yield");
        }
        for (; iterations < 16384; iterations++) {
            BigDecimal rate = low.add(high, MC).divide(TWO, MC);
            BigDecimal residual = pv(date, flows, rate).subtract(basis, MC);
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

    public static Purchase price(LocalDate date, List<Cashflow> flows, BigDecimal quotedRate, BigDecimal feeRate) {
        validateFuture(date, flows);
        require(feeRate.signum() >= 0 && feeRate.compareTo(BigDecimal.ONE) < 0, "Invalid integral fee rate");
        BigDecimal raw = pv(date, flows, quotedRate);
        BigInteger gross = minor(raw);
        BigInteger fee = minor(major(gross).multiply(feeRate, MC));
        BigInteger net = gross.subtract(fee);
        Segment segment = segment(date, flows, gross, net);
        return new Purchase(quotedRate, feeRate, raw, face(flows), gross, fee, net, segment);
    }

    public static Segment segment(LocalDate date, List<Cashflow> flows, BigInteger gross, BigInteger net) {
        require(net.signum() > 0 && net.compareTo(gross) <= 0, "Invalid gross/net segment bases");
        return new Segment(date, flows, gross, net, solve(date, flows, gross), solve(date, flows, net));
    }

    /** Outstanding is the remaining face after committed events; omitted IDs retain full face. */
    public static Position position(Segment segment, LocalDate date, Map<String, BigInteger> outstanding) {
        days(segment.startDate(), date);
        validateIds(segment.cashflows());
        require(segment.cashflows().stream().map(Cashflow::cashflowId).toList().containsAll(outstanding.keySet()), "Unknown cashflow");
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

    /** Targets, rather than independently rounded daily accruals, determine each posting. */
    public static Income income(Position opening, Position closing, BigInteger cashReceivedMinor) {
        days(opening.businessDate(), closing.businessDate());
        BigInteger gross = closing.grossPurchaseBasisMinor().subtract(opening.grossPurchaseBasisMinor()).add(cashReceivedMinor);
        BigInteger net = closing.amortizedCostMinor().subtract(opening.amortizedCostMinor()).add(cashReceivedMinor);
        return new Income(gross, net.subtract(gross), net);
    }

    public static Explanation explain(Segment segment, Position position) {
        return new Explanation(CALCULATION_VERSION, "EGP", "Actual/360", "DAILY", "HALF_UP", "START_OF_DAY",
                "PV=sum(C/(1+r/360)^actualDays); U=F-G; H=G-N", segment.startDate(), segment.grossBasisMinor(), segment.netBasisMinor(),
                segment.grossYield(), segment.netEir(), position);
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
