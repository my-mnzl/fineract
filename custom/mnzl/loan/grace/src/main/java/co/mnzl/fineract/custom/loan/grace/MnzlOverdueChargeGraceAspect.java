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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Enforces working-day grace on overdue-penalty candidate selection and application.
 *
 * <p>
 * Both overdue-penalty entry points funnel through that method:
 * <ul>
 * <li>the COB step {@code APPLY_CHARGE_TO_OVERDUE_LOANS}, and</li>
 * <li>the standalone scheduled job {@code APPLY_CHARGE_TO_OVERDUE_LOAN_INSTALLMENT} ("Apply penalty to overdue loans"),
 * whose {@code retrieveAllLoansWithOverdueInstallments} query selects installments using calendar-day
 * {@code penaltyWaitPeriod}.</li>
 * </ul>
 * The write advice drops any installment whose <em>working-day</em> grace (skipping weekends per {@code m_working_days}
 * and active {@code m_holiday} entries for the loan's office) has not yet elapsed. When backdating is disabled, a
 * second advice broadens the standalone job's calendar-day query and retains candidates only on their exact working-day
 * eligibility date. This prevents the upstream one-day query window from permanently losing penalties whose working-day
 * grace ends later than their calendar-day grace.
 */
@Aspect
@Slf4j
@RequiredArgsConstructor
public class MnzlOverdueChargeGraceAspect {

    private final MnzlWorkingDayCalculator workingDayCalculator;
    private final ConfigurationDomainService configurationDomainService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;

    @Around("execution(* org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService+.retrieveAllLoansWithOverdueInstallments(..))")
    public Object retrieveWorkingDayCandidates(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length < 2 || !Boolean.FALSE.equals(args[1])) {
            return proceed(joinPoint, args);
        }

        Object[] broadenedArgs = args.clone();
        broadenedArgs[1] = true;
        Object result = proceed(joinPoint, broadenedArgs);
        if (!(result instanceof Collection<?> rawData) || rawData.isEmpty()) {
            return result;
        }

        @SuppressWarnings("unchecked")
        Collection<OverdueLoanScheduleData> overdueData = (Collection<OverdueLoanScheduleData>) rawData;
        int penaltyWaitPeriod = args[0] instanceof Long value ? Math.toIntExact(value) : 0;
        LocalDate currentDate = DateUtils.getBusinessLocalDate();
        WorkingDays workingDays = workingDayCalculator.getWorkingDays();
        Map<Long, List<OverdueLoanScheduleData>> byLoan = new LinkedHashMap<>();
        for (OverdueLoanScheduleData data : overdueData) {
            byLoan.computeIfAbsent(data.getLoanId(), ignored -> new ArrayList<>()).add(data);
        }

        List<OverdueLoanScheduleData> eligible = new ArrayList<>();
        byLoan.forEach((loanId, candidates) -> {
            Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);
            eligible.addAll(filterCandidates(loan, candidates, penaltyWaitPeriod, currentDate, workingDays, true));
        });
        return eligible;
    }

    @Around("execution(* org.apache.fineract.portfolio.loanaccount.service.LoanChargeWritePlatformService.applyOverdueChargesForLoan(..))")
    public Object enforceWorkingDayGrace(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length < 2 || !(args[0] instanceof Long loanId) || !(args[1] instanceof Collection<?> rawData) || rawData.isEmpty()) {
            return proceed(joinPoint, args);
        }
        @SuppressWarnings("unchecked")
        Collection<OverdueLoanScheduleData> overdueData = (Collection<OverdueLoanScheduleData>) rawData;

        final int penaltyWaitPeriod = penaltyWaitPeriodDays();
        final LocalDate currentDate = DateUtils.getBusinessLocalDate();
        final Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);

        final WorkingDays workingDays = workingDayCalculator.getWorkingDays();
        List<OverdueLoanScheduleData> due = filterCandidates(loan, overdueData, penaltyWaitPeriod, currentDate, workingDays, false);
        if (due.size() != overdueData.size()) {
            log.info(
                    "Working-day grace withheld overdue penalty on loan id [{}] for {} installment(s) still within grace as of [{}] "
                            + "(penaltyWaitPeriod={} working days); {} installment(s) proceed",
                    loanId, overdueData.size() - due.size(), currentDate, penaltyWaitPeriod, due.size());
        }
        if (due.isEmpty()) {
            return null; // applyOverdueChargesForLoan is void; nothing is due yet
        }
        args[1] = due;
        return proceed(joinPoint, args);
    }

    private List<OverdueLoanScheduleData> filterCandidates(Loan loan, Collection<OverdueLoanScheduleData> overdueData,
            int penaltyWaitPeriod, LocalDate currentDate, WorkingDays workingDays, boolean exactDateOnly) {
        // Hoist the holiday lookup once per loan, anchored at the earliest candidate due date.
        Long officeId = loan.getOffice() != null ? loan.getOffice().getId() : null;
        LocalDate earliestDueDate = null;
        for (OverdueLoanScheduleData data : overdueData) {
            LocalDate due = installmentDueDate(loan, data.getPeriodNumber());
            if (due != null && (earliestDueDate == null || due.isBefore(earliestDueDate))) {
                earliestDueDate = due;
            }
        }
        final List<Holiday> holidays = workingDayCalculator.getActiveHolidaysForOffice(officeId,
                earliestDueDate != null ? earliestDueDate : currentDate);

        List<OverdueLoanScheduleData> eligible = new ArrayList<>();
        for (OverdueLoanScheduleData data : overdueData) {
            LocalDate installmentDueDate = installmentDueDate(loan, data.getPeriodNumber());
            if (installmentDueDate == null) {
                // The normal write path preserves upstream's decision. A broadened non-backdating query must not admit
                // an unresolvable older installment because that would silently change its exact-date semantics.
                if (!exactDateOnly) {
                    eligible.add(data);
                }
                continue;
            }
            LocalDate firstPenaltyDate = workingDayCalculator.addWorkingDays(installmentDueDate, penaltyWaitPeriod, workingDays, holidays);
            if (exactDateOnly ? currentDate.equals(firstPenaltyDate) : !currentDate.isBefore(firstPenaltyDate)) {
                eligible.add(data);
            }
        }
        return eligible;
    }

    // Wraps joinPoint.proceed(...) so the advice need not declare `throws Throwable` (checkstyle IllegalThrows).
    private static Object proceed(ProceedingJoinPoint joinPoint, Object[] args) {
        try {
            return joinPoint.proceed(args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Error applying working-day grace to overdue charges", e);
        }
    }

    private int penaltyWaitPeriodDays() {
        Long value = configurationDomainService.retrievePenaltyWaitPeriod();
        return value == null ? 0 : Math.toIntExact(value);
    }

    private static LocalDate installmentDueDate(Loan loan, Integer periodNumber) {
        if (periodNumber == null) {
            return null;
        }
        return loan.getRepaymentScheduleInstallments().stream().filter(i -> periodNumber.equals(i.getInstallmentNumber())).findFirst()
                .map(LoanRepaymentScheduleInstallment::getDueDate).orElse(null);
    }
}
