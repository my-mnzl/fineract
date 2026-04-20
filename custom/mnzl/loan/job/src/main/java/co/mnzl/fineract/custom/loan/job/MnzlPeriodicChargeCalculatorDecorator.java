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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanScheduleData;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelPeriod;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformServiceImpl;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;

/**
 * {@code @Primary} decorator over {@link LoanScheduleCalculationPlatformServiceImpl} for the calculator endpoint
 * ({@code POST /loans?command=calculateLoanSchedule}).
 *
 * <p>
 * Runs the real calculator once, then — if the loan product has active {@code LOAN_PERIODIC} charges — derives anchor
 * (first non-downpayment repayment date) and maturity (last repayment date) from the returned schedule, projects
 * occurrences across that window, appends them as JSON charge entries on the request (skipping duplicates by
 * {@code chargeId|dueDate}), and re-runs the delegate so the preview reflects them.
 * </p>
 *
 * <p>
 * User-supplied periodic charges without a {@code dueDate} are handled earlier in
 * {@link MnzlLoanChargeAssembler#fromParsedJson}; that expansion happens before the first delegate call, so by the time
 * this decorator inspects the response those entries are already materialised.
 * </p>
 */
@Slf4j
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
        final Long productId = root.get("productId").getAsLong();
        final LoanProduct product = loanProductRepository.findById(productId).orElse(null);
        if (product == null || product.getCharges() == null) {
            return initial;
        }
        final List<Charge> periodicCharges = product.getCharges().stream().filter(Charge::isActive).filter(Charge::isLoanCharge)
                .filter(Charge::isLoanPeriodic).toList();
        if (periodicCharges.isEmpty()) {
            return initial;
        }

        final LocalDate anchor = earliestRepaymentDueDate(initial);
        final LocalDate maturity = latestRepaymentDueDate(initial);
        if (anchor == null || maturity == null) {
            return initial;
        }

        final JsonArray charges = root.has("charges") && root.get("charges").isJsonArray() ? root.getAsJsonArray("charges")
                : new JsonArray();

        final String dateFormat = root.has("dateFormat") ? root.get("dateFormat").getAsString() : DEFAULT_DATE_FORMAT;
        final String localeTag = root.has("locale") ? root.get("locale").getAsString() : DEFAULT_LOCALE;
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, Locale.forLanguageTag(localeTag));

        final Set<String> existingEntries = new HashSet<>();
        charges.forEach(e -> {
            if (e.isJsonObject()) {
                final JsonObject o = e.getAsJsonObject();
                if (o.has("chargeId") && o.has("dueDate")) {
                    existingEntries.add(o.get("chargeId").getAsString() + "|" + o.get("dueDate").getAsString());
                }
            }
        });

        boolean added = false;
        for (final Charge c : periodicCharges) {
            for (final LocalDate date : projectionService.occurrencesBetween(c, anchor, maturity)) {
                final String formatted = date.format(formatter);
                final String key = c.getId() + "|" + formatted;
                if (existingEntries.contains(key)) {
                    continue;
                }
                final JsonObject entry = new JsonObject();
                entry.addProperty("chargeId", c.getId());
                entry.addProperty("amount", c.getAmount());
                entry.addProperty("dueDate", formatted);
                entry.addProperty("dateFormat", dateFormat);
                entry.addProperty("locale", localeTag);
                charges.add(entry);
                existingEntries.add(key);
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

    @Override
    public void updateFutureSchedule(final LoanScheduleData loanScheduleData, final Long loanId) {
        delegate.updateFutureSchedule(loanScheduleData, loanId);
    }

    @Override
    public LoanScheduleData generateLoanScheduleForVariableInstallmentRequest(final Long loanId, final String json) {
        return delegate.generateLoanScheduleForVariableInstallmentRequest(loanId, json);
    }

    private LocalDate earliestRepaymentDueDate(final LoanScheduleModel model) {
        LocalDate earliest = null;
        for (LoanScheduleModelPeriod p : model.getPeriods()) {
            if (!p.isRepaymentPeriod() || p.isDownPaymentPeriod()) {
                continue;
            }
            final LocalDate due = p.periodDueDate();
            if (due == null) {
                continue;
            }
            if (earliest == null || due.isBefore(earliest)) {
                earliest = due;
            }
        }
        return earliest;
    }

    private LocalDate latestRepaymentDueDate(final LoanScheduleModel model) {
        LocalDate latest = null;
        for (LoanScheduleModelPeriod p : model.getPeriods()) {
            if (!p.isRepaymentPeriod() || p.isDownPaymentPeriod()) {
                continue;
            }
            final LocalDate due = p.periodDueDate();
            if (due == null) {
                continue;
            }
            if (latest == null || due.isAfter(latest)) {
                latest = due;
            }
        }
        return latest;
    }
}
