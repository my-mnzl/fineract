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

import static org.assertj.core.api.Assertions.assertThat;

import co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleHistoryAssembler.HistoryRow;
import co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleHistoryAssembler.TermVariationRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MnzlLoanScheduleHistoryAssemblerTest {

    private static final Long LOAN_ID = 23297L;
    private static final Instant ARCHIVED = Instant.parse("2026-11-13T10:02:11Z");
    private static final LocalDate REQUEST_DATE = LocalDate.of(2026, 11, 13);
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @Test
    void noRowsGivesEmptyVersions() {
        MnzlLoanScheduleHistoryData data = assemble(List.of(), List.of());

        assertThat(data.loanId()).isEqualTo(LOAN_ID);
        assertThat(data.versions()).isEmpty();
    }

    @Test
    void groupsRowsByVersionAndOrdersVersionsAndInstallments() {
        List<HistoryRow> rows = List.of(row(2, 2, 12L, ARCHIVED.plusSeconds(60)), row(1, 2, null, ARCHIVED),
                row(2, 1, 12L, ARCHIVED.plusSeconds(61)), row(1, 1, null, ARCHIVED.plusSeconds(1)), row(1, 3, null, ARCHIVED));

        MnzlLoanScheduleHistoryData data = assemble(rows, List.of());

        assertThat(data.versions()).extracting(MnzlLoanScheduleHistoryData.Version::version).containsExactly(1, 2);
        assertThat(data.versions().get(0).periods()).extracting(MnzlLoanScheduleHistoryData.Period::installment).containsExactly(1, 2, 3);
        assertThat(data.versions().get(1).periods()).extracting(MnzlLoanScheduleHistoryData.Period::installment).containsExactly(1, 2);
    }

    @Test
    void rescheduleArchiveIsDatedByItsApprovalCommandNotTheCopiedInstallmentStamps() {
        // Two reschedules of the same installments: both archives carry the installments' original creation stamp.
        Instant installmentsCreated = Instant.parse("2026-07-08T13:00:52Z");
        Instant firstApproval = Instant.parse("2026-11-08T14:05:03Z");
        Instant secondApproval = Instant.parse("2026-11-10T23:21:35Z");
        List<HistoryRow> rows = List.of(row(1, 1, 2L, installmentsCreated), row(1, 2, 2L, installmentsCreated),
                row(2, 1, 10L, installmentsCreated), row(2, 2, 10L, installmentsCreated));

        MnzlLoanScheduleHistoryData data = MnzlLoanScheduleHistoryAssembler.assemble(LOAN_ID, 2, RoundingMode.HALF_EVEN, CAIRO, rows,
                List.of(), Map.of(2L, firstApproval, 10L, secondApproval));

        assertThat(data.versions()).extracting(MnzlLoanScheduleHistoryData.Version::archivedAt).containsExactly(firstApproval,
                secondApproval);
    }

    @Test
    void rescheduleWithoutApprovalCommandFallsBackToTheStartOfTheApprovalDayInTenantTime() {
        MnzlLoanScheduleHistoryData data = assemble(List.of(row(1, 1, 12L, ARCHIVED)), List.of());

        // 13 Nov 2026 00:00 in Cairo (UTC+2).
        assertThat(data.versions().get(0).archivedAt()).isEqualTo(Instant.parse("2026-11-12T22:00:00Z"));
    }

    @Test
    void archiveWithoutRescheduleRequestUsesTheEarliestInstallmentStamp() {
        List<HistoryRow> rows = List.of(row(1, 2, null, ARCHIVED.plusSeconds(1)), row(1, 1, null, ARCHIVED));

        MnzlLoanScheduleHistoryData data = assemble(rows, List.of());

        assertThat(data.versions().get(0).archivedAt()).isEqualTo(ARCHIVED);
    }

    @Test
    void archiveWithoutRescheduleRequestHasNullRequest() {
        MnzlLoanScheduleHistoryData data = assemble(List.of(row(1, 1, null, null)), List.of());

        MnzlLoanScheduleHistoryData.Version version = data.versions().get(0);
        assertThat(version.rescheduleRequest()).isNull();
        assertThat(version.archivedAt()).isNull();
    }

    @Test
    void rescheduleRequestCarriesItsFieldsAndOnlyItsOwnTermVariations() {
        List<TermVariationRow> variations = List.of(
                new TermVariationRow(12L, 2, LocalDate.of(2026, 11, 15), new BigDecimal("26.000000"), null),
                new TermVariationRow(99L, 4, LocalDate.of(2026, 12, 1), null, LocalDate.of(2026, 12, 5)));

        MnzlLoanScheduleHistoryData.RescheduleRequest request = assemble(List.of(row(1, 1, 12L, ARCHIVED)), variations).versions().get(0)
                .rescheduleRequest();

        assertThat(request.id()).isEqualTo(12L);
        assertThat(request.status()).isEqualTo("Approved");
        assertThat(request.rescheduleFromDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(request.rescheduleFromInstallment()).isEqualTo(5);
        assertThat(request.reason()).isEqualTo("CBE rate change");
        assertThat(request.comment()).isNull();
        assertThat(request.submittedOnDate()).isEqualTo(REQUEST_DATE);
        assertThat(request.submittedByUsername()).isEqualTo("mifos");
        assertThat(request.approvedOnDate()).isEqualTo(REQUEST_DATE);
        assertThat(request.approvedByUsername()).isEqualTo("approver");
        assertThat(request.termVariations()).containsExactly(
                new MnzlLoanScheduleHistoryData.TermVariation("interestRate", LocalDate.of(2026, 11, 15), new BigDecimal("26"), null));
    }

    @Test
    void termVariationTypesUseLowerCamelNamesAndPassUnknownCodesThrough() {
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(1)).isEqualTo("emiAmount");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(2)).isEqualTo("interestRate");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(4)).isEqualTo("dueDate");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(7)).isEqualTo("graceOnInterest");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(8)).isEqualTo("graceOnPrincipal");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(9)).isEqualTo("extendRepaymentPeriod");
        // Reschedules with a new rate and a from-installment store this type, not interestRate.
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(10)).isEqualTo("interestRateFromInstallment");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(42)).isEqualTo("42");
        assertThat(MnzlLoanScheduleHistoryAssembler.termVariationTypeName(0)).isEqualTo("0");
    }

    @Test
    void termVariationsAreOrderedByApplicableDate() {
        List<TermVariationRow> variations = List.of(
                new TermVariationRow(12L, 9, LocalDate.of(2027, 1, 1), new BigDecimal("2.000000"), null),
                new TermVariationRow(12L, 10, LocalDate.of(2026, 11, 15), new BigDecimal("26.500000"), null));

        List<MnzlLoanScheduleHistoryData.TermVariation> termVariations = assemble(List.of(row(1, 1, 12L, ARCHIVED)), variations).versions()
                .get(0).rescheduleRequest().termVariations();

        assertThat(termVariations).extracting(MnzlLoanScheduleHistoryData.TermVariation::type)
                .containsExactly("interestRateFromInstallment", "extendRepaymentPeriod");
        assertThat(termVariations).extracting(MnzlLoanScheduleHistoryData.TermVariation::decimalValue)
                .containsExactly(new BigDecimal("26.5"), new BigDecimal("2"));
    }

    @Test
    void amountsAreRoundedToCurrencyDigitsWithTheTenantRoundingMode() {
        HistoryRow row = new HistoryRow(1, 1, null, LocalDate.of(2026, 7, 15), new BigDecimal("9999.985000"),
                new BigDecimal("26982.955000"), new BigDecimal("20.834999"), null, ARCHIVED, null, null, null, null, null, null, null, null,
                null, null);

        MnzlLoanScheduleHistoryData.Period halfEven = MnzlLoanScheduleHistoryAssembler
                .assemble(LOAN_ID, 2, RoundingMode.HALF_EVEN, CAIRO, List.of(row), List.of(), Map.of()).versions().get(0).periods().get(0);
        MnzlLoanScheduleHistoryData.Period halfUp = MnzlLoanScheduleHistoryAssembler
                .assemble(LOAN_ID, 2, RoundingMode.HALF_UP, CAIRO, List.of(row), List.of(), Map.of()).versions().get(0).periods().get(0);

        assertThat(halfEven.principal()).isEqualTo(new BigDecimal("9999.98"));
        assertThat(halfEven.interest()).isEqualTo(new BigDecimal("26982.96"));
        assertThat(halfEven.fees()).isEqualTo(new BigDecimal("20.83"));
        // A missing amount reads as zero at the currency's scale.
        assertThat(halfEven.penalties()).isEqualTo(new BigDecimal("0.00"));
        assertThat(halfUp.principal()).isEqualTo(new BigDecimal("9999.99"));
        assertThat(halfEven.fromDate()).isNull();
        assertThat(halfEven.dueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    void currencyWithoutDecimalsRoundsToWholeUnits() {
        HistoryRow row = new HistoryRow(1, 1, null, LocalDate.of(2026, 7, 15), new BigDecimal("1000.500000"), BigDecimal.ZERO, null, null,
                ARCHIVED, null, null, null, null, null, null, null, null, null, null);

        MnzlLoanScheduleHistoryData.Period period = MnzlLoanScheduleHistoryAssembler
                .assemble(LOAN_ID, 0, RoundingMode.HALF_UP, CAIRO, List.of(row), List.of(), Map.of()).versions().get(0).periods().get(0);

        assertThat(period.principal()).isEqualTo(new BigDecimal("1001"));
        assertThat(period.fees()).isEqualTo(new BigDecimal("0"));
    }

    @Test
    void blankCommentIsNullAndRealCommentIsKept() {
        HistoryRow blank = requestRow(12L, "   ");
        HistoryRow written = requestRow(13L, "Customer asked");

        MnzlLoanScheduleHistoryData data = assemble(List.of(blank, withVersion(written, 2)), List.of());

        assertThat(data.versions().get(0).rescheduleRequest().comment()).isNull();
        assertThat(data.versions().get(1).rescheduleRequest().comment()).isEqualTo("Customer asked");
        assertThat(data.versions().get(1).rescheduleRequest().termVariations()).isEmpty();
    }

    private static MnzlLoanScheduleHistoryData assemble(List<HistoryRow> rows, List<TermVariationRow> variations) {
        return MnzlLoanScheduleHistoryAssembler.assemble(LOAN_ID, 2, RoundingMode.HALF_EVEN, CAIRO, rows, variations, Map.of());
    }

    private static HistoryRow row(int version, int installment, Long requestId, Instant createdAt) {
        return new HistoryRow(version, installment, LocalDate.of(2026, 6, 8).plusMonths(installment - 1L),
                LocalDate.of(2026, 7, 15).plusMonths(installment - 1L), new BigDecimal("9999.990000"), new BigDecimal("26982.950000"),
                new BigDecimal("20.830000"), BigDecimal.ZERO, createdAt, requestId, requestId == null ? null : 200,
                requestId == null ? null : LocalDate.of(2026, 11, 15), requestId == null ? null : 5,
                requestId == null ? null : "CBE rate change", requestId == null ? null : "", requestId == null ? null : REQUEST_DATE,
                requestId == null ? null : "mifos", requestId == null ? null : REQUEST_DATE, requestId == null ? null : "approver");
    }

    private static HistoryRow requestRow(Long requestId, String comment) {
        HistoryRow base = row(1, 1, requestId, ARCHIVED);
        return new HistoryRow(base.version(), base.installment(), base.fromDate(), base.dueDate(), base.principal(), base.interest(),
                base.fees(), base.penalties(), base.auditCreatedAt(), base.rescheduleRequestId(), base.statusEnum(),
                base.rescheduleFromDate(), base.rescheduleFromInstallment(), base.reason(), comment, base.submittedOnDate(),
                base.submittedByUsername(), base.approvedOnDate(), base.approvedByUsername());
    }

    private static HistoryRow withVersion(HistoryRow base, int version) {
        return new HistoryRow(version, base.installment(), base.fromDate(), base.dueDate(), base.principal(), base.interest(), base.fees(),
                base.penalties(), base.auditCreatedAt(), base.rescheduleRequestId(), base.statusEnum(), base.rescheduleFromDate(),
                base.rescheduleFromInstallment(), base.reason(), base.comment(), base.submittedOnDate(), base.submittedByUsername(),
                base.approvedOnDate(), base.approvedByUsername());
    }
}
