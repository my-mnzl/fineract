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
package org.apache.fineract.integrationtests.mnzl.helpers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.integrationtests.common.Utils;

/** Mnzl-specific custom assertions shared across L3 tests. */
public final class MnzlAssertions {

    private MnzlAssertions() {}

    /** Assert each period's interestDue == outstandingPrincipal × (annualRate × 30/360). */
    public static void assertSchedule30_360DecliningInterest(GetLoansLoanIdResponse loan, double principal, double annualRatePct) {
        List<GetLoansLoanIdRepaymentPeriod> periods = loan.getRepaymentSchedule().getPeriods().stream()
                .filter(p -> p.getPeriod() != null && p.getPeriod() > 0).toList();
        double monthlyRate = annualRatePct / 100.0 / 12.0;
        double outstanding = principal;
        for (int i = 0; i < periods.size(); i++) {
            double expected = Math.round(outstanding * monthlyRate * 100.0) / 100.0;
            double actual = Utils.getDoubleValue(periods.get(i).getInterestDue());
            assertThat(actual).as("Period %d 30/360 interest", i + 1).isEqualTo(expected, within(0.01));
            outstanding -= Utils.getDoubleValue(periods.get(i).getPrincipalDue());
        }
        assertThat(outstanding).as("Total principal allocated").isEqualTo(0.0, within(0.01));
    }

    /** Assert simulator response is COMPLETED with N snapshots. */
    @SuppressWarnings("unchecked")
    public static void assertSimulationCompleted(Map<String, Object> response, int expectedSnapshotCount) {
        assertThat(response.get("status")).isEqualTo("COMPLETED");
        List<Object> snapshots = (List<Object>) response.get("snapshots");
        assertThat(snapshots).hasSize(expectedSnapshotCount);
    }

    /** Assert simulator snapshot N has expected schedule period values. */
    @SuppressWarnings("unchecked")
    public static void assertSnapshotSchedulePeriod(Map<String, Object> response, int snapshotIndex, int periodNumber, double principalDue,
            double interestDue) {
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) response.get("snapshots");
        Map<String, Object> snap = snapshots.get(snapshotIndex);
        List<Map<String, Object>> schedule = (List<Map<String, Object>>) snap.get("schedule");
        Map<String, Object> period = schedule.stream().filter(p -> ((Number) p.get("period")).intValue() == periodNumber).findFirst()
                .orElseThrow();
        assertThat(((Number) period.get("principalDue")).doubleValue()).isEqualTo(principalDue, within(0.01));
        assertThat(((Number) period.get("interestDue")).doubleValue()).isEqualTo(interestDue, within(0.01));
    }

    /** Assert installment N is fully paid (zero outstanding). */
    public static void assertInstallmentPaid(GetLoansLoanIdResponse loan, int periodNumber) {
        GetLoansLoanIdRepaymentPeriod p = loan.getRepaymentSchedule().getPeriods().stream()
                .filter(x -> x.getPeriod() != null && x.getPeriod() == periodNumber).findFirst().orElseThrow();
        assertThat(Utils.getDoubleValue(p.getPrincipalOutstanding())).as("Period %d principal", periodNumber).isEqualTo(0.0, within(0.01));
        assertThat(Utils.getDoubleValue(p.getInterestOutstanding())).as("Period %d interest", periodNumber).isEqualTo(0.0, within(0.01));
    }

    /** Assert two schedules are identical (used for the "no drift" rehab loan check). */
    public static void assertSchedulesEqual(List<GetLoansLoanIdRepaymentPeriod> a, List<GetLoansLoanIdRepaymentPeriod> b) {
        assertThat(b).as("Schedule installment count").hasSize(a.size());
        for (int i = 0; i < a.size(); i++) {
            int n = i + 1;
            assertThat(b.get(i).getDueDate()).as("Installment %d dueDate", n).isEqualTo(a.get(i).getDueDate());
            assertThat(Utils.getDoubleValue(b.get(i).getPrincipalDue())).as("Installment %d principalDue", n)
                    .isEqualTo(Utils.getDoubleValue(a.get(i).getPrincipalDue()), within(0.01));
            assertThat(Utils.getDoubleValue(b.get(i).getInterestDue())).as("Installment %d interestDue", n)
                    .isEqualTo(Utils.getDoubleValue(a.get(i).getInterestDue()), within(0.01));
        }
    }
}
