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
package co.mnzl.fineract.custom.loan.grace;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.delinquency.service.LoanDelinquencyDomainService;
import org.apache.fineract.portfolio.delinquency.validator.LoanDelinquencyActionData;
import org.apache.fineract.portfolio.loanaccount.data.CollectionData;
import org.apache.fineract.portfolio.loanaccount.data.LoanDelinquencyData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

/**
 * Decorator over the upstream {@link LoanDelinquencyDomainService} that reinterprets {@code graceOnArrearsAgeing} as
 * working days. The delegate computes everything assuming calendar-day grace; this wrapper post-corrects
 * {@code delinquentDate} (push later by the extra weekend/holiday days) and {@code delinquentDays} (subtract the same
 * offset, clamped at zero).
 */
@Slf4j
@RequiredArgsConstructor
public class MnzlLoanDelinquencyDomainService implements LoanDelinquencyDomainService {

    private final LoanDelinquencyDomainService delegate;
    private final MnzlWorkingDayCalculator workingDayCalculator;

    @Override
    public CollectionData getOverdueCollectionData(Loan loan, List<LoanDelinquencyActionData> effectiveDelinquencyList) {
        CollectionData base = delegate.getOverdueCollectionData(loan, effectiveDelinquencyList);
        adjustForWorkingDayGrace(loan, base);
        return base;
    }

    @Override
    public LoanDelinquencyData getLoanDelinquencyData(Loan loan, List<LoanDelinquencyActionData> effectiveDelinquencyList) {
        LoanDelinquencyData base = delegate.getLoanDelinquencyData(loan, effectiveDelinquencyList);
        adjustForWorkingDayGrace(loan, base.getLoanCollectionData());
        return base;
    }

    private void adjustForWorkingDayGrace(Loan loan, CollectionData data) {
        if (data == null || data.getDelinquentDate() == null) {
            return;
        }
        Integer graceDays = loan.getLoanProductRelatedDetail().getGraceOnArrearsAgeing();
        if (graceDays == null || graceDays <= 0) {
            return;
        }
        // Reverse-derive overdueSinceDate from the delegate's output. This relies on upstream
        // LoanDelinquencyDomainServiceImpl computing delinquentDate = overdueSinceDate.plusDays(graceDays)
        // (see lines 129 and 208 in that class); if that formula ever changes, this wrapper silently
        // produces wrong dates with no compile-time signal.
        Long officeId = loan.getOffice() != null ? loan.getOffice().getId() : null;
        LocalDate calendarDelinquentDate = data.getDelinquentDate();
        LocalDate overdueSinceDate = calendarDelinquentDate.minusDays(graceDays);
        LocalDate workingDelinquentDate = workingDayCalculator.addWorkingDays(overdueSinceDate, graceDays, officeId);

        long extraGraceDays = ChronoUnit.DAYS.between(calendarDelinquentDate, workingDelinquentDate);
        if (extraGraceDays <= 0) {
            return;
        }
        // Mutates the delegate's CollectionData in place. Safe today because the delegate constructs a fresh
        // CollectionData.template() per call and returns it; if that ever changes (e.g. caching), this wrapper
        // would leak the mutation back to the cache.
        data.setDelinquentDate(workingDelinquentDate);
        Long currentDelinquentDays = data.getDelinquentDays();
        Long adjustedDelinquentDays = currentDelinquentDays;
        if (currentDelinquentDays != null && currentDelinquentDays > 0) {
            adjustedDelinquentDays = Math.max(0L, currentDelinquentDays - extraGraceDays);
            data.setDelinquentDays(adjustedDelinquentDays);
        }
        // Audit trail so ops can explain "why is this loan's delinquentDate later than I expected" without code
        // spelunking.
        log.info(
                "Working-day grace shifted delinquency for loan id [{}]: delinquentDate {} -> {} (+{} calendar days for weekends/holidays), "
                        + "delinquentDays {} -> {} (graceOnArrearsAgeing={} working days)",
                loan.getId(), calendarDelinquentDate, workingDelinquentDate, extraGraceDays, currentDelinquentDays, adjustedDelinquentDays,
                graceDays);
    }
}
