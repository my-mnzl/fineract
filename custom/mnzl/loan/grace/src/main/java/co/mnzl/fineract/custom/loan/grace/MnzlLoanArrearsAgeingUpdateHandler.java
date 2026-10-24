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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.jobs.updateloanarrearsageing.LoanArrearsAgeingUpdateHandler;
import org.apache.fineract.portfolio.loanaccount.service.LoanArrearsAgingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Counts {@code graceOnArrearsAgeing} in working days for the "Update Loan Arrears Ageing" job and the
 * {@code UPDATE_LOAN_ARREARS_AGING} COB step, which both go through this handler.
 *
 * <p>
 * Upstream rebuilds {@code m_loan_arrears_aging} in one statement that counts the grace in calendar days
 * ({@code duedate < businessDate - grace}). That stays as is; this subclass then recomputes the loans whose answer can
 * change. Working-day grace is never shorter than calendar grace, so it can only take installments <em>out</em> of
 * arrears, and only those due in {@code [businessDate - (7 * grace + 14), businessDate - grace)}: the lower bound is
 * the {@link MnzlWorkingDayCalculator#addWorkingDays} search cap, and anything older is past the grace under either
 * rule. One query loads the calendar-overdue installments of loans with an unpaid installment in that window; their
 * rows are rewritten from the installments still past the working-day grace.
 * </p>
 *
 * <p>
 * Purchased receivables and products with {@code arrears_based_on_original_schedule} keep upstream's result.
 * </p>
 */
@Slf4j
public class MnzlLoanArrearsAgeingUpdateHandler extends LoanArrearsAgeingUpdateHandler {

    private static final String DELETE_ARREARS = "DELETE FROM m_loan_arrears_aging WHERE loan_id = ?";
    private static final String INSERT_ARREARS = "INSERT INTO m_loan_arrears_aging(loan_id, principal_overdue_derived, "
            + "interest_overdue_derived, fee_charges_overdue_derived, penalty_charges_overdue_derived, total_overdue_derived, "
            + "overdue_since_date_derived) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final MnzlWorkingDayCalculator workingDayCalculator;

    public MnzlLoanArrearsAgeingUpdateHandler(JdbcTemplate jdbcTemplate, DatabaseSpecificSQLGenerator sqlGenerator,
            LoanArrearsAgingService loanArrearsAgingService, MnzlWorkingDayCalculator workingDayCalculator) {
        super(jdbcTemplate, sqlGenerator, loanArrearsAgingService);
        this.jdbcTemplate = jdbcTemplate;
        this.sqlGenerator = sqlGenerator;
        this.workingDayCalculator = workingDayCalculator;
    }

    @Override
    public void updateLoanArrearsAgeingDetailsForAllLoans() {
        super.updateLoanArrearsAgeingDetailsForAllLoans();
        applyWorkingDayGrace(null);
    }

    @Override
    public void updateLoanArrearsAgeingDetails(List<Long> loanIdsForUpdate) {
        super.updateLoanArrearsAgeingDetails(loanIdsForUpdate);
        if (!loanIdsForUpdate.isEmpty()) {
            applyWorkingDayGrace(loanIdsForUpdate);
        }
    }

    /**
     * @param loanIds
     *            the loans to correct, or {@code null} for every active loan
     */
    private void applyWorkingDayGrace(List<Long> loanIds) {
        final Map<Long, List<CalendarOverdueInstallment>> installmentsByLoan = loadCandidates(loanIds);
        if (installmentsByLoan.isEmpty()) {
            return;
        }
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        final WorkingDays workingDays = workingDayCalculator.getWorkingDays();
        final int maxGraceDays = installmentsByLoan.values().stream().mapToInt(installments -> installments.get(0).graceDays()).max()
                .orElse(0);
        // Every installment the working-day rule decides on is due on or after this, so it anchors the holiday lookup.
        final LocalDate holidayAnchor = windowStart(businessDate, maxGraceDays).minusDays(1);
        final Map<Long, List<Holiday>> holidaysByOffice = new HashMap<>();

        final List<Object[]> deletes = new ArrayList<>(installmentsByLoan.size());
        final List<Object[]> inserts = new ArrayList<>(installmentsByLoan.size());
        int clearedLoans = 0;
        for (Map.Entry<Long, List<CalendarOverdueInstallment>> entry : installmentsByLoan.entrySet()) {
            final List<CalendarOverdueInstallment> installments = entry.getValue();
            final int graceDays = installments.get(0).graceDays();
            final LocalDate windowStart = windowStart(businessDate, graceDays);
            final List<Holiday> holidays = holidaysByOffice.computeIfAbsent(installments.get(0).officeId(),
                    officeId -> workingDayCalculator.getActiveHolidaysForOffice(officeId, holidayAnchor));

            BigDecimal principal = BigDecimal.ZERO;
            BigDecimal interest = BigDecimal.ZERO;
            BigDecimal fee = BigDecimal.ZERO;
            BigDecimal penalty = BigDecimal.ZERO;
            LocalDate overdueSince = null;
            for (CalendarOverdueInstallment installment : installments) {
                final boolean inArrears = installment.dueDate().isBefore(windowStart) || workingDayCalculator
                        .isPastWorkingDayGrace(installment.dueDate(), graceDays, businessDate, workingDays, holidays);
                if (!inArrears) {
                    continue;
                }
                principal = principal.add(installment.principal());
                interest = interest.add(installment.interest());
                fee = fee.add(installment.fee());
                penalty = penalty.add(installment.penalty());
                if (overdueSince == null) {
                    overdueSince = installment.dueDate(); // rows arrive ordered by due date
                }
            }

            deletes.add(new Object[] { entry.getKey() });
            if (overdueSince == null) {
                clearedLoans++;
            } else {
                inserts.add(new Object[] { entry.getKey(), principal, interest, fee, penalty, principal.add(interest).add(fee).add(penalty),
                        overdueSince });
            }
        }

        jdbcTemplate.batchUpdate(DELETE_ARREARS, deletes);
        if (!inserts.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_ARREARS, inserts);
        }
        log.info("Working-day grace recomputed arrears for {} loan(s) as of [{}]; {} no longer in arrears", deletes.size(), businessDate,
                clearedLoans);
    }

    private static LocalDate windowStart(LocalDate businessDate, int graceDays) {
        return businessDate.minusDays(7L * graceDays + 14);
    }

    /**
     * Calendar-overdue unpaid installments (upstream's selection and outstanding formulas) of active, graced loans that
     * have at least one unpaid installment inside the window where the working-day rule can disagree. Ordered by loan,
     * then due date.
     */
    private Map<Long, List<CalendarOverdueInstallment>> loadCandidates(List<Long> loanIds) {
        final String businessDate = sqlGenerator.currentBusinessDate();
        final String calendarCutoff = sqlGenerator.subDate(businessDate, "ml.grace_on_arrears_ageing", "day");
        final StringBuilder sql = new StringBuilder(1500);
        sql.append("select ml.id as loanId, ml.grace_on_arrears_ageing as graceDays, ")
                .append("coalesce(mc.office_id, mg.office_id) as officeId, mr.duedate as dueDate, ")
                .append("coalesce(mr.principal_amount, 0) - coalesce(mr.principal_completed_derived, 0) ")
                .append("- coalesce(mr.principal_writtenoff_derived, 0) as principal, ")
                .append("coalesce(mr.interest_amount, 0) - coalesce(mr.interest_writtenoff_derived, 0) ")
                .append("- coalesce(mr.interest_waived_derived, 0) - coalesce(mr.interest_completed_derived, 0) as interest, ")
                .append("coalesce(mr.fee_charges_amount, 0) - coalesce(mr.fee_charges_writtenoff_derived, 0) ")
                .append("- coalesce(mr.fee_charges_waived_derived, 0) - coalesce(mr.fee_charges_completed_derived, 0) as fee, ")
                .append("coalesce(mr.penalty_charges_amount, 0) - coalesce(mr.penalty_charges_writtenoff_derived, 0) ")
                .append("- coalesce(mr.penalty_charges_waived_derived, 0) - coalesce(mr.penalty_charges_completed_derived, 0) as penalty ")
                .append("from m_loan ml ").append("inner join m_loan_repayment_schedule mr on mr.loan_id = ml.id ")
                .append("left join m_client mc on mc.id = ml.client_id ").append("left join m_group mg on mg.id = ml.group_id ")
                .append("left join m_product_loan_recalculation_details prd on prd.product_id = ml.product_id ")
                .append("where ml.loan_status_id = 300 ").append("and ml.grace_on_arrears_ageing > 0 ")
                .append("and ml.loan_transaction_strategy_code <> :purchasedReceivableStrategy ")
                .append("and (prd.arrears_based_on_original_schedule = false or prd.arrears_based_on_original_schedule is null) ")
                .append("and mr.completed_derived is false ").append("and mr.duedate < ").append(calendarCutoff).append(' ')
                .append("and exists (select 1 from m_loan_repayment_schedule w where w.loan_id = ml.id ")
                .append("and w.completed_derived is false and w.duedate < ").append(calendarCutoff).append(" and w.duedate >= ")
                .append(sqlGenerator.subDate(businessDate, "(ml.grace_on_arrears_ageing * 7 + 14)", "day")).append(") ");
        final MapSqlParameterSource parameters = new MapSqlParameterSource("purchasedReceivableStrategy",
                Loan.FIXED_RECEIVABLE_TRANSACTION_STRATEGY);
        if (loanIds != null) {
            sql.append("and ml.id in (:loanIds) ");
            parameters.addValue("loanIds", loanIds);
        }
        sql.append("order by ml.id, mr.duedate");

        final Map<Long, List<CalendarOverdueInstallment>> installmentsByLoan = new LinkedHashMap<>();
        new NamedParameterJdbcTemplate(jdbcTemplate).query(sql.toString(), parameters, rs -> {
            installmentsByLoan.computeIfAbsent(rs.getLong("loanId"), loanId -> new ArrayList<>())
                    .add(new CalendarOverdueInstallment(JdbcSupport.getLong(rs, "officeId"), rs.getInt("graceDays"),
                            JdbcSupport.getLocalDate(rs, "dueDate"), JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "principal"),
                            JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "interest"),
                            JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "fee"),
                            JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "penalty")));
        });
        return installmentsByLoan;
    }

    private record CalendarOverdueInstallment(Long officeId, int graceDays, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
            BigDecimal fee, BigDecimal penalty) {
    }
}
