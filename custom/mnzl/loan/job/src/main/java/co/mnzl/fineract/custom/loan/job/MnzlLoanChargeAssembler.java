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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculatorRegistry;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;

/**
 * {@code @Primary} subclass of the stock {@link LoanChargeAssembler} that pre-processes the submitted {@code charges}
 * JSON array before the base class turns it into {@link LoanCharge} entities.
 *
 * <p>
 * For each entry that has a {@code chargeId} but no {@code dueDate} and resolves to a {@code LOAN_PERIODIC} charge,
 * this class replaces the single entry with N entries — one per projected occurrence between the derived loan anchor
 * and maturity. Without this, the stock {@code LoanChargeService.create} would throw "Loan charge is missing due date"
 * for LOAN_PERIODIC charges added from the create-loan form.
 * </p>
 *
 * <p>
 * Anchor/maturity are derived from the request's {@code repaymentsStartingFromDate}/{@code expectedDisbursementDate}
 * and {@code loanTermFrequency}/{@code loanTermFrequencyType} using simple period arithmetic. This matches the
 * scheduler for the common case (fixed-interval repayments without holiday shifts); edge cases involving holidays or
 * explicit day-of-month/day-of-week selectors may produce dates that do not exactly align with the generated schedule,
 * in which case the schedule generator still attaches the charge to whichever installment period contains the computed
 * due date.
 * </p>
 */
public class MnzlLoanChargeAssembler extends LoanChargeAssembler {

    private static final String DEFAULT_DATE_FORMAT = "dd MMMM yyyy";
    private static final String DEFAULT_LOCALE = "en";

    private final FromJsonHelper fromApiJsonHelper;
    private final ChargeRepositoryWrapper chargeRepository;
    private final MnzlPeriodicChargeProjectionService projectionService;

    public MnzlLoanChargeAssembler(FromJsonHelper fromApiJsonHelper, ChargeRepositoryWrapper chargeRepository,
            LoanChargeRepository loanChargeRepository, LoanProductRepository loanProductRepository, ExternalIdFactory externalIdFactory,
            LoanChargeService loanChargeService, ChargeAmountCalculatorRegistry chargeAmountCalculatorRegistry,
            MnzlPeriodicChargeProjectionService projectionService) {
        super(fromApiJsonHelper, chargeRepository, loanChargeRepository, loanProductRepository, externalIdFactory, loanChargeService,
                chargeAmountCalculatorRegistry);
        this.fromApiJsonHelper = fromApiJsonHelper;
        this.chargeRepository = chargeRepository;
        this.projectionService = projectionService;
    }

    @Override
    public Set<LoanCharge> fromParsedJson(final JsonElement element, final List<LoanDisbursementDetails> disbursementDetails) {
        expandPeriodicChargesWithoutDueDate(element);
        return super.fromParsedJson(element, disbursementDetails);
    }

    // Package-private for unit testing; invoked by fromParsedJson above.
    void expandPeriodicChargesWithoutDueDate(final JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }
        final JsonObject root = element.getAsJsonObject();
        if (!root.has("charges") || !root.get("charges").isJsonArray()) {
            return;
        }
        final JsonArray charges = root.getAsJsonArray("charges");

        // Memoize Charge lookups so we don't hit the repository twice per entry (scan + expansion).
        final Map<Long, Charge> chargeCache = new HashMap<>();

        boolean hasPeriodicWithoutDueDate = false;
        for (JsonElement e : charges) {
            if (e.isJsonObject() && isPeriodicWithoutDueDate(e.getAsJsonObject(), chargeCache)) {
                hasPeriodicWithoutDueDate = true;
                break;
            }
        }
        if (!hasPeriodicWithoutDueDate) {
            return;
        }

        final LocalDate anchor = computeAnchor(root);
        final LocalDate maturity = computeMaturity(root);
        if (anchor == null || maturity == null) {
            return;
        }

        final String dateFormat = root.has("dateFormat") && !root.get("dateFormat").isJsonNull() ? root.get("dateFormat").getAsString()
                : DEFAULT_DATE_FORMAT;
        final String localeTag = root.has("locale") && !root.get("locale").isJsonNull() ? root.get("locale").getAsString() : DEFAULT_LOCALE;
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, Locale.forLanguageTag(localeTag));

        final JsonArray expanded = new JsonArray();
        for (JsonElement e : charges) {
            if (!e.isJsonObject()) {
                expanded.add(e);
                continue;
            }
            final JsonObject entry = e.getAsJsonObject();
            if (!isPeriodicWithoutDueDate(entry, chargeCache)) {
                expanded.add(entry);
                continue;
            }
            final Charge def = chargeCache.get(entry.get("chargeId").getAsLong());
            for (LocalDate date : projectionService.occurrencesBetween(def, anchor, maturity)) {
                final JsonObject occurrence = entry.deepCopy();
                occurrence.addProperty("dueDate", date.format(formatter));
                occurrence.addProperty("dateFormat", dateFormat);
                occurrence.addProperty("locale", localeTag);
                expanded.add(occurrence);
            }
        }
        root.add("charges", expanded);
    }

    private boolean isPeriodicWithoutDueDate(final JsonObject entry, final Map<Long, Charge> chargeCache) {
        if (!entry.has("chargeId") || entry.has("dueDate")) {
            return false;
        }
        final Long chargeId = entry.get("chargeId").getAsLong();
        final Charge def = chargeCache.computeIfAbsent(chargeId, chargeRepository::findOneWithNotFoundDetection);
        return def != null && def.isLoanPeriodic();
    }

    private LocalDate computeAnchor(final JsonObject root) {
        if (root.has("repaymentsStartingFromDate") && !root.get("repaymentsStartingFromDate").isJsonNull()) {
            final LocalDate d = fromApiJsonHelper.extractLocalDateNamed("repaymentsStartingFromDate", root);
            if (d != null) {
                return d;
            }
        }
        final LocalDate disbursement = fromApiJsonHelper.extractLocalDateNamed("expectedDisbursementDate", root);
        if (disbursement == null) {
            return null;
        }
        final int every = root.has("repaymentEvery") && !root.get("repaymentEvery").isJsonNull() ? root.get("repaymentEvery").getAsInt()
                : 1;
        final int freqType = root.has("repaymentFrequencyType") && !root.get("repaymentFrequencyType").isJsonNull()
                ? root.get("repaymentFrequencyType").getAsInt()
                : 2;
        return addPeriod(disbursement, every, freqType);
    }

    private LocalDate computeMaturity(final JsonObject root) {
        final LocalDate disbursement = fromApiJsonHelper.extractLocalDateNamed("expectedDisbursementDate", root);
        if (disbursement == null) {
            return null;
        }
        if (!root.has("loanTermFrequency") || root.get("loanTermFrequency").isJsonNull() || !root.has("loanTermFrequencyType")
                || root.get("loanTermFrequencyType").isJsonNull()) {
            return null;
        }
        final int term = root.get("loanTermFrequency").getAsInt();
        final int termType = root.get("loanTermFrequencyType").getAsInt();
        return addPeriod(disbursement, term, termType);
    }

    private LocalDate addPeriod(final LocalDate date, final int amount, final int frequencyType) {
        return switch (frequencyType) {
            case 0 -> date.plusDays(amount);
            case 1 -> date.plusWeeks(amount);
            case 2 -> date.plusMonths(amount);
            case 3 -> date.plusYears(amount);
            default -> date.plusMonths(amount);
        };
    }
}
