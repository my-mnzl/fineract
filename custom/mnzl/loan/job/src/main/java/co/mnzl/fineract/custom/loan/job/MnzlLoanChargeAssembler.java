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

/** Expands an undated periodic charge submitted with a loan into dated occurrences. */
public class MnzlLoanChargeAssembler extends LoanChargeAssembler {

    private static final String DEFAULT_DATE_FORMAT = "dd MMMM yyyy";
    private static final String DEFAULT_LOCALE = "en";

    private final FromJsonHelper fromApiJsonHelper;
    private final ChargeRepositoryWrapper chargeRepository;
    private final MnzlPeriodicChargeProjectionService projectionService;

    public MnzlLoanChargeAssembler(final FromJsonHelper fromApiJsonHelper, final ChargeRepositoryWrapper chargeRepository,
            final LoanChargeRepository loanChargeRepository, final LoanProductRepository loanProductRepository,
            final ExternalIdFactory externalIdFactory, final LoanChargeService loanChargeService,
            final ChargeAmountCalculatorRegistry chargeAmountCalculatorRegistry,
            final MnzlPeriodicChargeProjectionService projectionService) {
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

    void expandPeriodicChargesWithoutDueDate(final JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }
        final JsonObject root = element.getAsJsonObject();
        if (!root.has("charges") || !root.get("charges").isJsonArray()) {
            return;
        }
        final JsonArray charges = root.getAsJsonArray("charges");
        final Map<Long, Charge> chargeCache = new HashMap<>();
        if (!containsUndatedPeriodicCharge(charges, chargeCache)) {
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
        for (JsonElement elementEntry : charges) {
            if (!elementEntry.isJsonObject()) {
                expanded.add(elementEntry);
                continue;
            }
            final JsonObject entry = elementEntry.getAsJsonObject();
            if (!isPeriodicWithoutDueDate(entry, chargeCache)) {
                expanded.add(entry);
                continue;
            }
            final Charge definition = chargeCache.get(entry.get("chargeId").getAsLong());
            for (LocalDate date : projectionService.occurrencesBetween(definition, anchor, maturity)) {
                final JsonObject occurrence = entry.deepCopy();
                occurrence.addProperty("dueDate", date.format(formatter));
                occurrence.addProperty("dateFormat", dateFormat);
                occurrence.addProperty("locale", localeTag);
                expanded.add(occurrence);
            }
        }
        root.add("charges", expanded);
    }

    private boolean containsUndatedPeriodicCharge(final JsonArray charges, final Map<Long, Charge> chargeCache) {
        for (JsonElement element : charges) {
            if (element.isJsonObject() && isPeriodicWithoutDueDate(element.getAsJsonObject(), chargeCache)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPeriodicWithoutDueDate(final JsonObject entry, final Map<Long, Charge> chargeCache) {
        if (!entry.has("chargeId") || entry.get("chargeId").isJsonNull() || (entry.has("dueDate") && !entry.get("dueDate").isJsonNull())) {
            return false;
        }
        final Long chargeId = entry.get("chargeId").getAsLong();
        final Charge definition = chargeCache.computeIfAbsent(chargeId, chargeRepository::findOneWithNotFoundDetection);
        return definition != null && definition.isLoanPeriodic();
    }

    private LocalDate computeAnchor(final JsonObject root) {
        if (root.has("repaymentsStartingFromDate") && !root.get("repaymentsStartingFromDate").isJsonNull()) {
            final LocalDate date = fromApiJsonHelper.extractLocalDateNamed("repaymentsStartingFromDate", root);
            if (date != null) {
                return date;
            }
        }
        final LocalDate disbursement = fromApiJsonHelper.extractLocalDateNamed("expectedDisbursementDate", root);
        if (disbursement == null) {
            return null;
        }
        final int every = root.has("repaymentEvery") && !root.get("repaymentEvery").isJsonNull() ? root.get("repaymentEvery").getAsInt()
                : 1;
        final int frequencyType = root.has("repaymentFrequencyType") && !root.get("repaymentFrequencyType").isJsonNull()
                ? root.get("repaymentFrequencyType").getAsInt()
                : 2;
        return addPeriod(disbursement, every, frequencyType);
    }

    private LocalDate computeMaturity(final JsonObject root) {
        final LocalDate disbursement = fromApiJsonHelper.extractLocalDateNamed("expectedDisbursementDate", root);
        if (disbursement == null || !root.has("loanTermFrequency") || root.get("loanTermFrequency").isJsonNull()
                || !root.has("loanTermFrequencyType") || root.get("loanTermFrequencyType").isJsonNull()) {
            return null;
        }
        return addPeriod(disbursement, root.get("loanTermFrequency").getAsInt(), root.get("loanTermFrequencyType").getAsInt());
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
