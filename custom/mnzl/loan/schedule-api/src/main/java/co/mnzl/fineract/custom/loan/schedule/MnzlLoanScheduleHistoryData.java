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
package co.mnzl.fineract.custom.loan.schedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response of {@code GET /v1/mnzl/loans/{loanId}/schedule-history}: every schedule Fineract archived into
 * {@code m_loan_repayment_schedule_history}, ordered by {@code version} (never by {@code archivedAt}).
 *
 * <p>
 * A reschedule archives the schedule as it was just before its approval took effect. Archives without a reschedule
 * request come from two other core paths: a foreclosure archives the stored installments as they were, while a
 * regenerated original schedule (disbursement edits, down payment, charge changes on interest-recalculating loans)
 * archives a freshly generated schedule, often the one that applies after the operation.
 * </p>
 */
public record MnzlLoanScheduleHistoryData(Long loanId, List<Version> versions) {

    /**
     * @param rescheduleRequest
     *            the reschedule whose approval archived (replaced) this version, so its term variations describe the
     *            <em>next</em> version, not this one; null for archives without a reschedule request
     * @param archivedAt
     *            when the archive was written: the approval command's time for a reschedule (else the start of its
     *            approval business day, tenant time); for archives without a request, the rows' {@code created_on_utc}.
     *            That is the real archive time for a regenerated schedule, but a foreclosure copies the installments'
     *            original creation stamps, so {@code archivedAt} is not monotonic in {@code version}
     */
    public record Version(Integer version, Instant archivedAt, RescheduleRequest rescheduleRequest, List<Period> periods) {
    }

    public record RescheduleRequest(Long id, String status, LocalDate rescheduleFromDate, Integer rescheduleFromInstallment, String reason,
            String comment, LocalDate submittedOnDate, String submittedByUsername, LocalDate approvedOnDate, String approvedByUsername,
            List<TermVariation> termVariations) {
    }

    public record TermVariation(String type, LocalDate applicableFrom, BigDecimal decimalValue, LocalDate dateValue) {
    }

    public record Period(Integer installment, LocalDate fromDate, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
            BigDecimal fees, BigDecimal penalties) {
    }
}
