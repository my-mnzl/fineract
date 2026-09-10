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

import static co.mnzl.fineract.receivables.math.ReceivablesMath.Cashflow;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Leg;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.MC;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.MeasurementState;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.allocate;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.days;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.face;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.growth;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.major;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.minor;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.pv;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.require;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.segment;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.validateIds;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mathematical effects only; authorization, reconciliation cutoffs, versions and immutable event storage belong to the
 * caller.
 */
public final class ReceivableEvents {

    private ReceivableEvents() {}

    public enum Direction {
        PAYABLE, RECEIVABLE
    }

    public record AdjustmentLot(LocalDate effectiveDate, LocalDate dueDate, BigDecimal rate, Direction direction, BigInteger principalMinor,
            BigInteger amountDueMinor) {

        public BigInteger balanceMinor(LocalDate date) {
            days(effectiveDate, date);
            LocalDate end = date.isAfter(dueDate) ? dueDate : date;
            return minor(major(principalMinor).multiply(growth(rate, days(effectiveDate, end)), MC));
        }

        public BigInteger unwindMinor(LocalDate from, LocalDate to) {
            return balanceMinor(to).subtract(balanceMinor(from));
        }
    }

    public record Reset(BigDecimal oldPvSameDate, BigDecimal newPvSameDate, BigInteger oldGrossMinor, BigInteger oldNetMinor,
            BigInteger deltaMinor, Segment futureSegment, AdjustmentLot lot, BigInteger overdueMinor) {
    }

    public record Settlement(BigInteger faceExtinguishedMinor, BigInteger grossBasisMinor, BigInteger amortizedCostMinor,
            BigInteger deferredDiscountMinor, BigInteger deferredIntegralFeeMinor, BigInteger payoffMinor, BigInteger developerShareMinor,
            BigInteger financierIncomeMinor, BigInteger allowanceReleasedMinor) {
    }

    public record PartialSettlement(Settlement settlement, Position retainedPosition, Map<String, BigInteger> remainingFaceMinor,
            Map<String, BigInteger> grossAllocationMinor, Map<String, BigInteger> netAllocationMinor) {

        public PartialSettlement {
            remainingFaceMinor = Map.copyOf(remainingFaceMinor);
            grossAllocationMinor = Map.copyOf(grossAllocationMinor);
            netAllocationMinor = Map.copyOf(netAllocationMinor);
        }

        public MeasurementState remainingMeasurement(Segment segment) {
            return new MeasurementState(segment, retainedPosition, remainingFaceMinor);
        }
    }

    public record Substitution(Segment replacement, BigInteger oldFaceMinor, BigInteger replacementFaceMinor,
            BigInteger allowanceReleasedMinor) {
    }

    public record Modification(List<Cashflow> modifiedCashflows, BigDecimal originalNetEir, BigDecimal modifiedNetBasis,
            BigInteger modifiedNetBasisMinor, BigInteger modificationGainLossMinor) {

        public Modification {
            modifiedCashflows = List.copyOf(modifiedCashflows);
        }
    }

    public static Reset reset(Segment segment, LocalDate date, Map<String, BigInteger> outstanding, BigDecimal newRate) {
        return reset(position(segment, date, outstanding), newRate);
    }

    public static Reset reset(MeasurementState state, LocalDate date, BigDecimal newRate) {
        return reset(position(state, date), newRate);
    }

    private static Reset reset(Position p, BigDecimal newRate) {
        LocalDate date = p.businessDate();
        List<Cashflow> future = new ArrayList<>();
        BigDecimal oldPv = BigDecimal.ZERO;
        for (Leg leg : p.legs()) {
            if (leg.discountDays() > 0 && leg.outstandingMinor().signum() > 0) {
                future.add(new Cashflow(leg.cashflow().cashflowId(), leg.cashflow().dueDate(), leg.outstandingMinor()));
                oldPv = oldPv.add(leg.grossBasis(), MC);
            }
        }
        if (future.isEmpty()) {
            return new Reset(BigDecimal.ZERO, BigDecimal.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, null, null,
                    p.pastDueMinor());
        }
        BigInteger oldG = p.grossPurchaseBasisMinor().subtract(p.pastDueMinor());
        BigInteger oldN = p.amortizedCostMinor().subtract(p.pastDueMinor());
        BigDecimal newPv = pv(date, future, newRate);
        BigInteger newG = minor(newPv);
        BigInteger delta = newG.subtract(oldG);
        BigInteger newN = oldN.add(delta);
        Segment next = segment(date, future, newG, newN);
        LocalDate nextDate = future.stream().map(Cashflow::dueDate).min(LocalDate::compareTo).orElseThrow();
        AdjustmentLot lot = delta.signum() == 0 ? null
                : new AdjustmentLot(date, nextDate, newRate, delta.signum() > 0 ? Direction.PAYABLE : Direction.RECEIVABLE, delta.abs(),
                        minor(major(delta.abs()).multiply(growth(newRate, days(date, nextDate)), MC)));
        return new Reset(oldPv, newPv, oldG, oldN, delta, next, lot, p.pastDueMinor());
    }

    /** Full or preallocated partial monetary bases; allowance release is reported separately from settlement income. */
    public static Settlement settle(BigInteger face, BigInteger gross, BigInteger net, BigInteger payoff, BigInteger allowance) {
        require(face.signum() > 0 && net.signum() >= 0 && net.compareTo(gross) <= 0 && gross.compareTo(face) <= 0,
                "Invalid settlement bases");
        require(payoff.compareTo(gross) >= 0 && payoff.compareTo(face) <= 0, "Payoff requires workout");
        require(allowance.signum() >= 0 && allowance.compareTo(net) <= 0, "Invalid allowance release");
        BigInteger surplus = payoff.subtract(gross);
        BigInteger share = minor(major(surplus).multiply(new BigDecimal("0.5")));
        BigInteger fee = gross.subtract(net);
        return new Settlement(face, gross, net, face.subtract(gross), fee, payoff, share, surplus.subtract(share).add(fee), allowance);
    }

    /** Allocate both the selected and retained portions together, preserving the existing posted bases exactly. */
    public static PartialSettlement settlePortions(Position p, Map<String, BigInteger> selectedFace, BigInteger payoff,
            BigInteger allowance) {
        require(!selectedFace.isEmpty(), "Selected portions required");
        Map<String, BigDecimal> gWeights = new LinkedHashMap<>();
        Map<String, BigDecimal> nWeights = new LinkedHashMap<>();
        BigInteger selected = BigInteger.ZERO;
        for (Leg leg : p.legs()) {
            String id = leg.cashflow().cashflowId();
            BigInteger amount = selectedFace.getOrDefault(id, BigInteger.ZERO);
            require(amount.signum() >= 0 && amount.compareTo(leg.outstandingMinor()) <= 0, "Invalid selected face");
            selected = selected.add(amount);
            BigDecimal fraction = leg.outstandingMinor().signum() == 0 ? BigDecimal.ZERO
                    : new BigDecimal(amount).divide(new BigDecimal(leg.outstandingMinor()), MC);
            BigDecimal sg = leg.grossBasis().multiply(fraction, MC);
            BigDecimal sn = leg.netBasis().multiply(fraction, MC);
            gWeights.put(id + ":selected", sg);
            gWeights.put(id + ":retained", leg.grossBasis().subtract(sg, MC));
            nWeights.put(id + ":selected", sn);
            nWeights.put(id + ":retained", leg.netBasis().subtract(sn, MC));
        }
        require(p.legs().stream().map(l -> l.cashflow().cashflowId()).toList().containsAll(selectedFace.keySet()),
                "Unknown selected cashflow");
        Map<String, BigInteger> ga = allocate(p.grossPurchaseBasisMinor(), gWeights);
        Map<String, BigInteger> na = allocate(p.amortizedCostMinor(), nWeights);
        BigInteger g = BigInteger.ZERO;
        BigInteger n = BigInteger.ZERO;
        for (String id : selectedFace.keySet()) {
            g = g.add(ga.get(id + ":selected"));
            n = n.add(na.get(id + ":selected"));
        }
        Settlement settlement = settle(selected, g, n, payoff, allowance);
        List<Leg> retainedLegs = new ArrayList<>();
        Map<String, BigInteger> remaining = new LinkedHashMap<>();
        BigInteger future = BigInteger.ZERO;
        BigInteger due = BigInteger.ZERO;
        BigDecimal analyticalGross = BigDecimal.ZERO;
        BigDecimal analyticalNet = BigDecimal.ZERO;
        int dpd = 0;
        for (Leg leg : p.legs()) {
            String id = leg.cashflow().cashflowId();
            BigInteger amount = leg.outstandingMinor().subtract(selectedFace.getOrDefault(id, BigInteger.ZERO));
            remaining.put(id, amount);
            BigDecimal rg = gWeights.get(id + ":retained");
            BigDecimal rn = nWeights.get(id + ":retained");
            retainedLegs.add(new Leg(leg.cashflow(), leg.discountDays(), amount, rg, rn));
            analyticalGross = analyticalGross.add(rg, MC);
            analyticalNet = analyticalNet.add(rn, MC);
            if (leg.discountDays() > 0) {
                future = future.add(amount);
            } else {
                due = due.add(amount);
                if (amount.signum() > 0) {
                    dpd = Math.max(dpd, Math.toIntExact(days(leg.cashflow().dueDate(), p.businessDate())));
                }
            }
        }
        BigInteger retainedGross = p.grossPurchaseBasisMinor().subtract(g);
        BigInteger retainedNet = p.amortizedCostMinor().subtract(n);
        BigInteger retainedFace = future.add(due);
        Position retained = new Position(p.businessDate(), retainedFace, future, due, retainedGross, retainedNet,
                retainedFace.subtract(retainedGross), retainedGross.subtract(retainedNet), dpd, analyticalGross, analyticalNet,
                retainedLegs);
        return new PartialSettlement(settlement, retained, remaining, ga, na);
    }

    public static Substitution substitute(Position old, List<Cashflow> replacement, BigInteger oldAllowance) {
        require(oldAllowance.signum() >= 0 && oldAllowance.compareTo(old.amortizedCostMinor()) <= 0, "Invalid old allowance");
        return new Substitution(segment(old.businessDate(), replacement, old.grossPurchaseBasisMinor(), old.amortizedCostMinor()),
                old.contractualOutstandingMinor(), face(replacement), oldAllowance);
    }

    public static Modification modify(LocalDate date, List<Cashflow> legallyModified, BigDecimal originalNetEir, BigInteger beforeNet) {
        validateIds(legallyModified);
        BigDecimal net = pv(date, legallyModified, originalNetEir);
        return new Modification(legallyModified, originalNetEir, net, minor(net), minor(net).subtract(beforeNet));
    }

    /** Independent nonposting developer memo recurrence. Reset principal is deliberately absent. */
    public static BigInteger developerMemo(BigInteger opening, BigInteger grossIncome, BigInteger payableUnwind,
            BigInteger receivableUnwind, BigInteger customerCollections, BigInteger developerPayments, BigInteger developerReceipts,
            BigInteger extinguishedGross, BigInteger newSettlementShare, BigInteger grossWriteOff, BigInteger modificationGrossDelta) {
        return opening.add(grossIncome).subtract(payableUnwind).add(receivableUnwind).subtract(customerCollections).add(developerPayments)
                .subtract(developerReceipts).subtract(extinguishedGross).subtract(newSettlementShare).subtract(grossWriteOff)
                .add(modificationGrossDelta);
    }
}
