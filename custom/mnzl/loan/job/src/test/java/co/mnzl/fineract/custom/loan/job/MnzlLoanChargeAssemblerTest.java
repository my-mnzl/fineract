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
package co.mnzl.fineract.custom.loan.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculatorRegistry;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanChargeAssemblerTest {

    private static final Long PERIODIC_CHARGE_ID = 9L;
    private static final Long SPECIFIED_DUE_CHARGE_ID = 13L;

    @Mock
    private ChargeRepositoryWrapper chargeRepository;

    @Mock
    private LoanChargeRepository loanChargeRepository;

    @Mock
    private LoanProductRepository loanProductRepository;

    @Mock
    private ExternalIdFactory externalIdFactory;

    @Mock
    private LoanChargeService loanChargeService;

    @Mock
    private ChargeAmountCalculatorRegistry chargeAmountCalculatorRegistry;

    @Mock
    private MnzlPeriodicChargeProjectionService projectionService;

    @Mock
    private Charge periodicCharge;

    @Mock
    private Charge specifiedDueCharge;

    private MnzlLoanChargeAssembler assembler;

    @BeforeEach
    void setUp() {
        final FromJsonHelper fromJsonHelper = new FromJsonHelper();
        assembler = new MnzlLoanChargeAssembler(fromJsonHelper, chargeRepository, loanChargeRepository, loanProductRepository,
                externalIdFactory, loanChargeService, chargeAmountCalculatorRegistry, projectionService);

        lenient().when(chargeRepository.findOneWithNotFoundDetection(PERIODIC_CHARGE_ID)).thenReturn(periodicCharge);
        lenient().when(periodicCharge.isLoanPeriodic()).thenReturn(true);
        lenient().when(chargeRepository.findOneWithNotFoundDetection(SPECIFIED_DUE_CHARGE_ID)).thenReturn(specifiedDueCharge);
        lenient().when(specifiedDueCharge.isLoanPeriodic()).thenReturn(false);
    }

    @Test
    void expandsPeriodicEntryWithoutDueDateIntoOccurrencesAcrossLoanTerm() {
        final LocalDate anchor = LocalDate.of(2026, 5, 20);
        final LocalDate second = LocalDate.of(2027, 5, 20);
        final LocalDate third = LocalDate.of(2028, 5, 20);
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(anchor, second, third));

        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "loanTermFrequency": 36,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [
                    {"chargeId": 9, "amount": 0.01}
                  ]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        final List<JsonObject> entries = chargeEntries(root);
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(e -> e.get("chargeId").getAsLong()).containsOnly(9L);
        assertThat(entries).extracting(e -> e.get("amount").getAsBigDecimal().toPlainString()).containsOnly("0.01");
        assertThat(entries).extracting(e -> e.get("dueDate").getAsString()).containsExactly("20 May 2026", "20 May 2027", "20 May 2028");
    }

    @Test
    void anchorFallsBackToDisbursementPlusFirstRepaymentPeriod() {
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(LocalDate.of(2026, 5, 20)));

        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [{"chargeId": 9, "amount": 0.01}]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        verify(projectionService, times(1)).occurrencesBetween(eq(periodicCharge), eq(LocalDate.of(2026, 5, 20)),
                eq(LocalDate.of(2027, 4, 20)));
    }

    @Test
    void anchorUsesRepaymentsStartingFromDateWhenPresent() {
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(LocalDate.of(2026, 6, 1)));

        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "repaymentsStartingFromDate": "01 June 2026",
                  "loanTermFrequency": 24,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [{"chargeId": 9, "amount": 0.01}]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        verify(projectionService, times(1)).occurrencesBetween(eq(periodicCharge), eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2028, 4, 20)));
    }

    @Test
    void leavesNonPeriodicEntriesUnchanged() {
        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [
                    {"chargeId": 13, "amount": 1.99}
                  ]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        final List<JsonObject> entries = chargeEntries(root);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("chargeId").getAsLong()).isEqualTo(13L);
        assertThat(entries.get(0).has("dueDate")).isFalse();
    }

    @Test
    void leavesEntriesWithExplicitDueDateUnchanged() {
        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [
                    {"chargeId": 9, "amount": 0.01, "dueDate": "20 May 2026"}
                  ]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        final List<JsonObject> entries = chargeEntries(root);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("dueDate").getAsString()).isEqualTo("20 May 2026");
    }

    @Test
    void isNoOpWhenNoChargesArrayPresent() {
        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026"
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        assertThat(root.has("charges")).isFalse();
    }

    @Test
    void looksUpChargeDefinitionOnlyOncePerEntry() {
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(LocalDate.of(2026, 5, 20)));

        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [{"chargeId": 9, "amount": 0.01}]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        verify(chargeRepository, times(1)).findOneWithNotFoundDetection(PERIODIC_CHARGE_ID);
    }

    private JsonObject parse(final String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private List<JsonObject> chargeEntries(final JsonObject root) {
        final List<JsonObject> entries = new java.util.ArrayList<>();
        root.getAsJsonArray("charges").forEach(e -> entries.add(e.getAsJsonObject()));
        return entries;
    }

    // ---------------- C.8 expansions ----------------
    //
    // Skipped (already covered by existing methods above):
    // * expandPeriodicChargesWithoutDueDate_singleCharge_expandsToN -> see
    // expandsPeriodicEntryWithoutDueDateIntoOccurrencesAcrossLoanTerm
    // * expandPeriodicChargesWithoutDueDate_existingDueDateNotExpanded -> see leavesEntriesWithExplicitDueDateUnchanged
    // * expandPeriodicChargesWithoutDueDate_anchorFromRepaymentsStartingFromDate -> see
    // anchorUsesRepaymentsStartingFromDateWhenPresent
    //
    // Skipped (not applicable at this layer):
    // * expandPeriodicChargesWithoutDueDate_dedupeByChargeIdAndDueDate -> the assembler does not dedupe at the
    // JSON-entry
    // level; dedupe is enforced downstream (decorator + projection service). Verified against production source.

    @Test
    void expandPeriodicChargesWithoutDueDate_mixedPeriodicAndOneTime_onlyExpandsPeriodic() {
        when(projectionService.occurrencesBetween(eq(periodicCharge), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 6, 20), LocalDate.of(2026, 7, 20)));

        final JsonObject root = parse("""
                {
                  "productId": 2,
                  "expectedDisbursementDate": "20 April 2026",
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "dateFormat": "dd MMMM yyyy",
                  "locale": "en",
                  "charges": [
                    {"chargeId": 9, "amount": 0.01},
                    {"chargeId": 13, "amount": 100.00, "dueDate": "01 May 2026"}
                  ]
                }
                """);

        assembler.expandPeriodicChargesWithoutDueDate(root);

        final List<JsonObject> entries = chargeEntries(root);
        // 3 expanded periodic occurrences + 1 untouched one-time entry = 4 total entries.
        assertThat(entries).hasSize(4);
        // Periodic entries (chargeId=9) keep their amount and gain dueDate.
        assertThat(entries).filteredOn(e -> e.get("chargeId").getAsLong() == 9L).hasSize(3).extracting(e -> e.get("dueDate").getAsString())
                .containsExactly("20 May 2026", "20 June 2026", "20 July 2026");
        // One-time entry (chargeId=13) is preserved verbatim with its original dueDate.
        assertThat(entries).filteredOn(e -> e.get("chargeId").getAsLong() == 13L).hasSize(1).first()
                .satisfies(e -> assertThat(e.get("dueDate").getAsString()).isEqualTo("01 May 2026"));
    }
}
