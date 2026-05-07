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
package co.mnzl.fineract.custom.loan.simulator.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SimulationSnapshot {

    private final int actionIndex;
    private final SimulationActionRequest action;
    private final List<SchedulePeriod> schedule;
    private final Summary summary;
    private final List<Transaction> transactions;

    @Getter
    @Builder
    public static class SchedulePeriod {

        private final int period;
        private final LocalDate dueDate;
        private final BigDecimal principalDue;
        private final BigDecimal interestDue;
        private final BigDecimal feeChargesDue;
        private final BigDecimal penaltyChargesDue;
        private final BigDecimal totalDue;
        private final BigDecimal principalOutstanding;
        private final BigDecimal interestOutstanding;
        private final BigDecimal totalOutstanding;
    }

    @Getter
    @Builder
    public static class Summary {

        private final BigDecimal principalDisbursed;
        private final BigDecimal principalOutstanding;
        private final BigDecimal interestOutstanding;
        private final BigDecimal feeChargesOutstanding;
        private final BigDecimal penaltyChargesOutstanding;
        private final BigDecimal totalOutstanding;
    }

    @Getter
    @Builder
    public static class Transaction {

        private final String type;
        private final LocalDate date;
        private final BigDecimal amount;
        private final BigDecimal principalPortion;
        private final BigDecimal interestPortion;
        private final BigDecimal feePortion;
        private final BigDecimal penaltyPortion;
    }
}
