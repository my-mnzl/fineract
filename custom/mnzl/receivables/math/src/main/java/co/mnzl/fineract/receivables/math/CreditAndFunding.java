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

import static co.mnzl.fineract.receivables.math.ReceivablesMath.Leg;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.MC;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.Segment;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.days;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.growth;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.major;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.minor;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.position;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.require;
import static co.mnzl.fineract.receivables.math.ReceivablesMath.segment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Forecast-driven impairment and separate simple-interest funding. No production forecast defaults. */
public final class CreditAndFunding {

    private CreditAndFunding() {}

    public enum Payer {
        BORROWER, DEVELOPER
    }

    public record Recovery(String cashflowId, Payer payer, LocalDate date, BigInteger amountMinor) {
    }

    public record Scenario(String id, BigDecimal weight, LocalDate defaultDate, List<Recovery> recoveries) {

        public Scenario {
            recoveries = List.copyOf(recoveries);
        }
    }

    public record Impairment(int stage, BigDecimal analyticalAllowance, BigInteger lossAllowanceMinor, BigDecimal expectedRecoveriesPv,
            Map<String, BigDecimal> lossByCashflow) {

        public Impairment {
            lossByCashflow = Map.copyOf(lossByCashflow);
        }
    }

    public record Stage3Unwind(BigInteger openingAllowanceMinor, BigInteger closingAllowanceMinor, BigInteger interestIncomeMinor,
            BigInteger scheduledEirIncomeMinor, BigInteger allowanceUnwindMinor) {
    }

    public record FundingInterval(LocalDate from, LocalDate to, BigInteger principalMinor, BigDecimal nominalRate) {
    }

    public record FundingAccrual(BigDecimal analyticalExpense, BigInteger expenseMinor, BigInteger capitalizedInterestMinor) {
    }

    public static int stage(int dpd, boolean significantIncrease, boolean qualitativeDefault) {
        require(dpd >= 0, "Negative DPD");
        return dpd >= 90 || qualitativeDefault ? 3 : dpd >= 30 || significantIncrease ? 2 : 1;
    }

    public static Impairment impairment(Position position, BigDecimal originalNetEir, int stage, List<Scenario> scenarios) {
        require(stage >= 1 && stage <= 3 && !scenarios.isEmpty(), "Current Credit forecast required");
        BigDecimal weights = BigDecimal.ZERO;
        HashSet<String> ids = new HashSet<>();
        Map<String, Leg> legs = new LinkedHashMap<>();
        position.legs().forEach(l -> legs.put(l.cashflow().cashflowId(), l));
        Map<String, BigDecimal> losses = new LinkedHashMap<>();
        BigDecimal expected = BigDecimal.ZERO;
        LocalDate date = position.businessDate();
        for (Scenario scenario : scenarios) {
            require(scenario.id() != null && ids.add(scenario.id()) && scenario.weight().signum() >= 0, "Invalid scenario");
            weights = weights.add(scenario.weight());
            Map<String, BigInteger> recovered = new LinkedHashMap<>();
            Map<String, BigDecimal> recoveriesPv = new LinkedHashMap<>();
            HashSet<String> sources = new HashSet<>();
            for (Recovery r : scenario.recoveries()) {
                require(legs.containsKey(r.cashflowId()) && r.payer() != null && r.amountMinor().signum() >= 0,
                        "Invalid recovery exposure");
                require(sources.add(r.cashflowId() + "/" + r.payer() + "/" + r.date()), "Duplicate recovery source/date");
                recovered.merge(r.cashflowId(), r.amountMinor(), BigInteger::add);
                recoveriesPv.merge(r.cashflowId(), major(r.amountMinor()).divide(growth(originalNetEir, days(date, r.date())), MC),
                        (a, b) -> a.add(b, MC));
            }
            for (Leg leg : position.legs()) {
                String id = leg.cashflow().cashflowId();
                BigInteger recovery = recovered.getOrDefault(id, BigInteger.ZERO);
                require(recovery.compareTo(leg.outstandingMinor()) <= 0, "Recovery double counts/exceeds exposure");
                BigDecimal rp = recoveriesPv.getOrDefault(id, BigDecimal.ZERO);
                if (scenario.defaultDate() == null && leg.discountDays() > 0) {
                    require(recovery.equals(leg.outstandingMinor())
                            && rp.subtract(leg.netBasis()).abs().compareTo(new BigDecimal("1e-10")) <= 0,
                            "No-default forecast must reproduce contractual future cashflows");
                }
                expected = expected.add(rp.min(leg.netBasis()).multiply(scenario.weight(), MC), MC);
                boolean include = leg.discountDays() == 0 || stage != 1
                        || (scenario.defaultDate() != null && !scenario.defaultDate().isAfter(date.plusMonths(12)));
                if (include) {
                    losses.merge(id, leg.netBasis().subtract(rp, MC).max(BigDecimal.ZERO).multiply(scenario.weight(), MC),
                            (a, b) -> a.add(b, MC));
                }
            }
        }
        require(weights.compareTo(BigDecimal.ONE) == 0, "Scenario weights must sum exactly to one");
        BigDecimal allowance = losses.values().stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC)).min(position.analyticalNetBasis());
        return new Impairment(stage, allowance, minor(allowance).min(position.amortizedCostMinor()), expected, losses);
    }

    /** Same forecast on both boundaries isolates time passage; changes to estimates are a separate impairment event. */
    public static Stage3Unwind stage3Unwind(Segment segment, LocalDate from, LocalDate to, Map<String, BigInteger> outstanding,
            List<Scenario> unchangedForecast) {
        days(from, to);
        Position opening = position(segment, from, outstanding);
        Position closing = position(segment, to, outstanding);
        Impairment a = impairment(opening, segment.netEir().rate(), 3, unchangedForecast);
        Impairment b = impairment(closing, segment.netEir().rate(), 3, unchangedForecast);
        BigInteger scheduled = closing.amortizedCostMinor().subtract(opening.amortizedCostMinor());
        BigInteger allowanceUnwind = a.lossAllowanceMinor().subtract(b.lossAllowanceMinor());
        return new Stage3Unwind(a.lossAllowanceMinor(), b.lossAllowanceMinor(), scheduled.add(allowanceUnwind), scheduled, allowanceUnwind);
    }

    /**
     * Non-overlapping intervals are split by actual principal/rate changes; posted expense is cumulative target
     * rounding.
     */
    public static FundingAccrual funding(List<FundingInterval> intervals) {
        BigDecimal expense = BigDecimal.ZERO;
        LocalDate previous = null;
        for (FundingInterval interval : intervals) {
            require(interval.principalMinor().signum() >= 0 && interval.nominalRate().signum() >= 0, "Invalid funding principal/rate");
            require(previous == null || !interval.from().isBefore(previous), "Overlapping funding intervals");
            expense = expense.add(major(interval.principalMinor()).multiply(interval.nominalRate(), MC)
                    .multiply(BigDecimal.valueOf(days(interval.from(), interval.to())), MC).divide(new BigDecimal("360"), MC), MC);
            previous = interval.to();
        }
        return new FundingAccrual(expense, minor(expense), BigInteger.ZERO);
    }

    /**
     * Disclosed report annualization: weightedAssetDays is sum(net earning assets * actual days), in major EGP days.
     */
    public static BigDecimal annualizedMargin(BigDecimal actualNetInterestIncome, BigDecimal weightedAssetDays, int annualizationDays) {
        require(weightedAssetDays.signum() > 0 && annualizationDays > 0, "Invalid margin denominator/convention");
        return actualNetInterestIncome.multiply(BigDecimal.valueOf(annualizationDays), MC).divide(weightedAssetDays, MC);
    }
}
