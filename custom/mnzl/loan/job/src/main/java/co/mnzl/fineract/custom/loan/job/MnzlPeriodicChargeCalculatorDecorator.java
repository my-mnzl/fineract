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
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanScheduleData;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelPeriod;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformServiceImpl;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;

/** Adds product-linked periodic charges to loan-schedule previews. */
@RequiredArgsConstructor
public class MnzlPeriodicChargeCalculatorDecorator implements LoanScheduleCalculationPlatformService {

    private static final String DEFAULT_DATE_FORMAT = "dd MMMM yyyy";
    private static final String DEFAULT_LOCALE = "en";

    private final LoanScheduleCalculationPlatformServiceImpl delegate;
    private final LoanProductRepository loanProductRepository;
    private final MnzlPeriodicChargeProjectionService projectionService;

    @Override
    public LoanScheduleModel calculateLoanSchedule(final JsonQuery query, final Boolean validateParams) {
        final LoanScheduleModel initial = delegate.calculateLoanSchedule(query, validateParams);
        final JsonObject root = query.parsedJson().isJsonObject() ? query.parsedJson().getAsJsonObject() : null;
        if (root == null || !root.has("productId")) {
            return initial;
        }
        final LoanProduct product = loanProductRepository.findById(root.get("productId").getAsLong()).orElse(null);
        if (product == null || product.getCharges() == null) {
            return initial;
        }
        final List<Charge> periodicCharges = product.getCharges().stream().filter(Charge::isActive).filter(Charge::isLoanCharge)
                .filter(Charge::isLoanPeriodic).toList();
        final LocalDate anchor = earliestRepaymentDueDate(initial);
        final LocalDate maturity = latestRepaymentDueDate(initial);
        if (periodicCharges.isEmpty() || anchor == null || maturity == null) {
            return initial;
        }

        final JsonArray charges = root.has("charges") && root.get("charges").isJsonArray() ? root.getAsJsonArray("charges")
                : new JsonArray();
        final String dateFormat = root.has("dateFormat") ? root.get("dateFormat").getAsString() : DEFAULT_DATE_FORMAT;
        final String localeTag = root.has("locale") ? root.get("locale").getAsString() : DEFAULT_LOCALE;
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, Locale.forLanguageTag(localeTag));

        boolean added = false;
        for (final Charge charge : periodicCharges) {
            final List<LocalDate> desiredOccurrences = projectionService.occurrencesBetween(charge, anchor, maturity);
            final List<LocalDate> existingDueDates = existingDueDates(charges, charge.getId(), formatter);
            for (final LocalDate date : MnzlPeriodicChargeProjectionService.missingOccurrences(desiredOccurrences, existingDueDates)) {
                final String formattedDate = date.format(formatter);
                final JsonObject entry = new JsonObject();
                entry.addProperty("chargeId", charge.getId());
                entry.addProperty("amount", charge.getAmount());
                entry.addProperty("dueDate", formattedDate);
                entry.addProperty("dateFormat", dateFormat);
                entry.addProperty("locale", localeTag);
                charges.add(entry);
                added = true;
            }
        }
        if (!added) {
            return initial;
        }
        if (!root.has("charges")) {
            root.add("charges", charges);
        }
        return delegate.calculateLoanSchedule(query, false);
    }

    private List<LocalDate> existingDueDates(final JsonArray charges, final Long chargeId, final DateTimeFormatter formatter) {
        final List<LocalDate> dates = new ArrayList<>();
        charges.forEach(element -> {
            if (!element.isJsonObject()) {
                return;
            }
            final JsonObject entry = element.getAsJsonObject();
            if (!entry.has("chargeId") || !entry.has("dueDate") || !chargeId.equals(entry.get("chargeId").getAsLong())) {
                return;
            }
            try {
                dates.add(LocalDate.parse(entry.get("dueDate").getAsString(), formatter));
            } catch (DateTimeParseException ignored) {
                // The delegate already validated the entry. Leave differently formatted dates unmatched here.
            }
        });
        return dates;
    }

    @Override
    public void updateFutureSchedule(final LoanScheduleData loanScheduleData, final Long loanId) {
        delegate.updateFutureSchedule(loanScheduleData, loanId);
    }

    @Override
    public LoanScheduleData generateLoanScheduleForVariableInstallmentRequest(final Long loanId, final String json) {
        return delegate.generateLoanScheduleForVariableInstallmentRequest(loanId, json);
    }

    private LocalDate earliestRepaymentDueDate(final LoanScheduleModel model) {
        return model.getPeriods().stream().filter(LoanScheduleModelPeriod::isRepaymentPeriod)
                .filter(period -> !period.isDownPaymentPeriod()).map(LoanScheduleModelPeriod::periodDueDate).filter(date -> date != null)
                .min(LocalDate::compareTo).orElse(null);
    }

    private LocalDate latestRepaymentDueDate(final LoanScheduleModel model) {
        return model.getPeriods().stream().filter(LoanScheduleModelPeriod::isRepaymentPeriod)
                .filter(period -> !period.isDownPaymentPeriod()).map(LoanScheduleModelPeriod::periodDueDate).filter(date -> date != null)
                .max(LocalDate::compareTo).orElse(null);
    }
}
