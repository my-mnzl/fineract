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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariationType;
import org.apache.fineract.portfolio.loanaccount.rescheduleloan.data.LoanRescheduleRequestEnumerations;

/**
 * Turns flat history rows (one per archived installment, reschedule request columns repeated) into the
 * {@link MnzlLoanScheduleHistoryData} response.
 */
public final class MnzlLoanScheduleHistoryAssembler {

    private static final String TERM_TYPE_CODE_PREFIX = "loanTermType.";

    private MnzlLoanScheduleHistoryAssembler() {}

    /** One {@code m_loan_repayment_schedule_history} row, left-joined to its reschedule request. */
    public record HistoryRow(Integer version, Integer installment, LocalDate fromDate, LocalDate dueDate, BigDecimal principal,
            BigDecimal interest, BigDecimal fees, BigDecimal penalties, Instant auditCreatedAt, Long rescheduleRequestId,
            Integer statusEnum, LocalDate rescheduleFromDate, Integer rescheduleFromInstallment, String reason, String comment,
            LocalDate submittedOnDate, String submittedByUsername, LocalDate approvedOnDate, String approvedByUsername) {
    }

    /** {@code parentId} is set on variations core derives from another one of the same request. */
    public record TermVariationRow(Long rescheduleRequestId, Integer termType, BigDecimal decimalValue, LocalDate dateValue,
            Long parentId) {
    }

    /**
     * @param approvals
     *            reschedule request id to the instant its approval command ran
     * @param tenantZone
     *            zone of the request's business dates, used when no approval command is recorded
     */
    public static MnzlLoanScheduleHistoryData assemble(Long loanId, int currencyDigits, RoundingMode roundingMode, ZoneId tenantZone,
            List<HistoryRow> rows, List<TermVariationRow> termVariationRows, Map<Long, Instant> approvals) {
        final Map<Long, List<TermVariationRow>> variationsByRequest = termVariationRows.stream()
                .collect(Collectors.groupingBy(TermVariationRow::rescheduleRequestId));
        final Map<Integer, List<HistoryRow>> rowsByVersion = rows.stream()
                .collect(Collectors.groupingBy(HistoryRow::version, TreeMap::new, Collectors.toList()));

        final List<MnzlLoanScheduleHistoryData.Version> versions = new ArrayList<>(rowsByVersion.size());
        rowsByVersion.forEach((version, versionRows) -> {
            final HistoryRow requestRow = versionRows.stream().filter(row -> row.rescheduleRequestId() != null).findFirst().orElse(null);
            final Instant archivedAt = archivedAt(versionRows, requestRow, approvals, tenantZone);
            final List<MnzlLoanScheduleHistoryData.Period> periods = versionRows.stream()
                    .sorted(Comparator.comparing(HistoryRow::installment)).map(row -> period(row, currencyDigits, roundingMode)).toList();
            versions.add(new MnzlLoanScheduleHistoryData.Version(version, archivedAt,
                    requestRow == null ? null
                            : rescheduleRequest(requestRow, variationsByRequest.getOrDefault(requestRow.rescheduleRequestId(), List.of())),
                    periods));
        });
        return new MnzlLoanScheduleHistoryData(loanId, versions);
    }

    /**
     * A reschedule archive copies the stored installments' audit stamps but is written while its approval runs, so: the
     * approval command's time, else the start of the approval business day. Without a request, the earliest row stamp:
     * the real archive time for a regenerated schedule, a copied installment stamp for a foreclosure.
     */
    private static Instant archivedAt(List<HistoryRow> versionRows, HistoryRow requestRow, Map<Long, Instant> approvals,
            ZoneId tenantZone) {
        if (requestRow != null) {
            final Instant approvedAt = approvals.get(requestRow.rescheduleRequestId());
            if (approvedAt != null) {
                return approvedAt;
            }
            if (requestRow.approvedOnDate() != null) {
                return requestRow.approvedOnDate().atStartOfDay(tenantZone).toInstant();
            }
        }
        return versionRows.stream().map(HistoryRow::auditCreatedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Lower camel name of {@link LoanTermVariationType} (its code without the {@code loanTermType.} prefix). A type
     * this Fineract does not know passes its numeric code through.
     */
    static String termVariationTypeName(Integer termType) {
        final LoanTermVariationType type = LoanTermVariationType.fromInt(termType);
        if (type == LoanTermVariationType.INVALID) {
            return String.valueOf(termType);
        }
        final String code = type.getCode();
        return code.startsWith(TERM_TYPE_CODE_PREFIX) ? code.substring(TERM_TYPE_CODE_PREFIX.length()) : code;
    }

    private static MnzlLoanScheduleHistoryData.Period period(HistoryRow row, int currencyDigits, RoundingMode roundingMode) {
        return new MnzlLoanScheduleHistoryData.Period(row.installment(), row.fromDate(), row.dueDate(),
                money(row.principal(), currencyDigits, roundingMode), money(row.interest(), currencyDigits, roundingMode),
                money(row.fees(), currencyDigits, roundingMode), money(row.penalties(), currencyDigits, roundingMode));
    }

    /**
     * The terms as approved: in creation order, every variation applies from the request's own from-date (core later
     * shifts the stored dates in place), and derived child rows, such as the repayment-period extension core adds for a
     * grace on principal, are left out.
     */
    private static MnzlLoanScheduleHistoryData.RescheduleRequest rescheduleRequest(HistoryRow row, List<TermVariationRow> variations) {
        final List<MnzlLoanScheduleHistoryData.TermVariation> termVariations = variations.stream()
                .filter(variation -> variation.parentId() == null)
                .map(variation -> new MnzlLoanScheduleHistoryData.TermVariation(termVariationTypeName(variation.termType()),
                        row.rescheduleFromDate(), plain(variation.decimalValue()), variation.dateValue()))
                .toList();
        return new MnzlLoanScheduleHistoryData.RescheduleRequest(row.rescheduleRequestId(), status(row.statusEnum()),
                row.rescheduleFromDate(), row.rescheduleFromInstallment(), row.reason(), StringUtils.trimToNull(row.comment()),
                row.submittedOnDate(), row.submittedByUsername(), row.approvedOnDate(), row.approvedByUsername(), termVariations);
    }

    private static String status(Integer statusEnum) {
        return statusEnum == null ? null : LoanRescheduleRequestEnumerations.status(statusEnum).value();
    }

    private static BigDecimal money(BigDecimal amount, int currencyDigits, RoundingMode roundingMode) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(currencyDigits, roundingMode);
    }

    /** Term values are stored at scale 6; drop the padding so 26.000000 reads as 26. */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        final BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }
}
