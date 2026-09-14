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

import co.mnzl.fineract.receivables.math.ReceivablesMath;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/** On-time future collections at unchanged yields, without forecast losses or financial effects. */
final class ReceivablesInvestmentProjection {

    private ReceivablesInvestmentProjection() {}

    record Row(LocalDate date, String openingCarryingMinor, String cashReceiptMinor, String investmentRecoveryMinor, String eirIncomeMinor,
            String closingCarryingMinor) {
    }

    record Projection(LocalDate fromDate, List<Row> rows, String unavailableReason) {
    }

    static Projection project(ReceivablesMath.MeasurementState state, LocalDate date, String status) {
        if (!"ACTIVE".equals(status)) {
            return new Projection(date, List.of(), "ACCOUNT_NOT_ACTIVE");
        }
        var opening = ReceivablesMath.position(state, date);
        var remaining = new LinkedHashMap<>(state.outstandingMinor());
        var dueDates = new TreeMap<LocalDate, List<String>>();
        for (var leg : opening.legs()) {
            if (leg.outstandingMinor().signum() == 0) {
                continue;
            }
            if (leg.cashflow().dueDate().isBefore(date)) {
                return new Projection(date, List.of(), "OVERDUE_CASHFLOWS");
            }
            dueDates.computeIfAbsent(leg.cashflow().dueDate(), ignored -> new ArrayList<>()).add(leg.cashflow().cashflowId());
        }
        List<Row> rows = new ArrayList<>();
        for (var entry : dueDates.entrySet()) {
            BigInteger cash = BigInteger.ZERO;
            for (String id : entry.getValue()) {
                cash = cash.add(remaining.put(id, BigInteger.ZERO));
            }
            var closing = ReceivablesMath.position(state.segment(), entry.getKey(), remaining);
            var income = ReceivablesMath.income(opening, closing, cash);
            rows.add(new Row(entry.getKey(), opening.amortizedCostMinor().toString(), cash.toString(),
                    cash.subtract(income.interestIncomeMinor()).toString(), income.interestIncomeMinor().toString(),
                    closing.amortizedCostMinor().toString()));
            opening = closing;
        }
        return new Projection(date, List.copyOf(rows), null);
    }
}
