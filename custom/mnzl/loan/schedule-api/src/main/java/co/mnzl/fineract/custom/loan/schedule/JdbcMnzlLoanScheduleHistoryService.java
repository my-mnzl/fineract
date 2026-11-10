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

import co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleHistoryAssembler.HistoryRow;
import co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleHistoryAssembler.TermVariationRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "mnzl.loan.scheduleHistory.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JdbcMnzlLoanScheduleHistoryService implements MnzlLoanScheduleHistoryReadService {

    // Same office-hierarchy scoping as LoanReadPlatformServiceImpl.retrieveOne, so a loan GET /loans/{id} hides is
    // hidden here too.
    static final String LOAN_SQL = "select l.currency_digits from m_loan l left join m_client c on c.id = l.client_id"
            + " left join m_group g on g.id = l.group_id join m_office o on (o.id = c.office_id or o.id = g.office_id)"
            + " left join m_office transferToOffice on transferToOffice.id = c.transfer_to_office_id"
            + " where l.id = ? and (o.hierarchy like ? or transferToOffice.hierarchy like ?)";

    static final String HISTORY_SQL = "select h.version, h.installment, h.fromdate, h.duedate, h.principal_amount, h.interest_amount,"
            + " h.fee_charges_amount, h.penalty_charges_amount, h.created_on_utc, h.created_date, r.id as request_id, r.status_enum,"
            + " r.reschedule_from_date, r.reschedule_from_installment, cv.code_value as reason, r.reschedule_reason_comment,"
            + " r.submitted_on_date, su.username as submitted_by_username, r.approved_on_date, au.username as approved_by_username"
            + " from m_loan_repayment_schedule_history h left join m_loan_reschedule_request r on r.id = h.loan_reschedule_request_id"
            + " left join m_code_value cv on cv.id = r.reschedule_reason_cv_id left join m_appuser su on su.id = r.submitted_by_user_id"
            + " left join m_appuser au on au.id = r.approved_by_user_id where h.loan_id = ? order by h.version, h.installment, h.id";

    static final String TERM_VARIATIONS_SQL = "select m.loan_reschedule_request_id, tv.term_type, tv.applicable_date, tv.decimal_value,"
            + " tv.date_value from m_loan_reschedule_request_term_variations_mapping m"
            + " join m_loan_term_variations tv on tv.id = m.loan_term_variations_id"
            + " join m_loan_reschedule_request r on r.id = m.loan_reschedule_request_id"
            + " where r.loan_id = ? order by m.loan_reschedule_request_id, tv.applicable_date, tv.id";

    // A reschedule archive copies created_on_utc from the stored installments, so its approval command gives the
    // archive
    // time; uses the (entity_name, resource_id) index.
    static final String APPROVALS_SQL = "select c.resource_id, max(coalesce(c.checked_on_date_utc, c.made_on_date_utc)) as approved_at"
            + " from m_loan_reschedule_request r join m_portfolio_command_source c on c.entity_name = 'RESCHEDULELOAN'"
            + " and c.resource_id = r.id where r.loan_id = ? and c.action_name = 'APPROVE' and c.status = 1 group by c.resource_id";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformSecurityContext context;

    @Override
    public MnzlLoanScheduleHistoryData retrieveScheduleHistory(Long loanId) {
        final String hierarchySearch = context.authenticatedUser().getOffice().getHierarchy() + "%";
        final List<Integer> currencyDigits = jdbcTemplate.queryForList(LOAN_SQL, Integer.class, loanId, hierarchySearch, hierarchySearch);
        if (currencyDigits.isEmpty()) {
            throw new LoanNotFoundException(loanId);
        }
        final List<HistoryRow> rows = jdbcTemplate.query(HISTORY_SQL, (rs, rowNum) -> historyRow(rs), loanId);
        final boolean rescheduled = rows.stream().anyMatch(row -> row.rescheduleRequestId() != null);
        final List<TermVariationRow> termVariations = rescheduled
                ? jdbcTemplate.query(TERM_VARIATIONS_SQL, (rs, rowNum) -> termVariationRow(rs), loanId)
                : List.of();
        final Map<Long, Instant> approvals = new HashMap<>();
        if (rescheduled) {
            jdbcTemplate.query(APPROVALS_SQL, (RowCallbackHandler) rs -> approvals.put(rs.getLong("resource_id"), utc(rs, "approved_at")),
                    loanId);
        }
        return MnzlLoanScheduleHistoryAssembler.assemble(loanId, currencyDigits.get(0), MoneyHelper.getRoundingMode(),
                DateUtils.getDateTimeZoneOfTenant(), rows, termVariations, approvals);
    }

    private static HistoryRow historyRow(ResultSet rs) throws SQLException {
        return new HistoryRow(rs.getInt("version"), rs.getInt("installment"), rs.getObject("fromdate", LocalDate.class),
                rs.getObject("duedate", LocalDate.class), rs.getBigDecimal("principal_amount"), rs.getBigDecimal("interest_amount"),
                rs.getBigDecimal("fee_charges_amount"), rs.getBigDecimal("penalty_charges_amount"), auditCreatedAt(rs),
                rs.getObject("request_id", Long.class), rs.getObject("status_enum", Integer.class),
                rs.getObject("reschedule_from_date", LocalDate.class), rs.getObject("reschedule_from_installment", Integer.class),
                rs.getString("reason"), rs.getString("reschedule_reason_comment"), rs.getObject("submitted_on_date", LocalDate.class),
                rs.getString("submitted_by_username"), rs.getObject("approved_on_date", LocalDate.class),
                rs.getString("approved_by_username"));
    }

    /**
     * The row's {@code created_on_utc} (UTC), else the pre-UTC {@code created_date} (tenant time). Core copies the
     * stamp of a stored installment and only stamps "now" on freshly generated ones.
     */
    private static Instant auditCreatedAt(ResultSet rs) throws SQLException {
        final Instant createdOnUtc = utc(rs, "created_on_utc");
        if (createdOnUtc != null) {
            return createdOnUtc;
        }
        final LocalDateTime createdDate = rs.getObject("created_date", LocalDateTime.class);
        return createdDate == null ? null
                : createdDate.atZone(DateUtils.getDateTimeZoneOfTenant()).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * The {@code *_utc} audit columns hold UTC wall time (MySQL/MariaDB {@code DATETIME}) or an instant (PostgreSQL
     * {@code TIMESTAMP WITH TIME ZONE}); a UTC calendar reads both correctly whatever the JVM default zone.
     */
    private static Instant utc(ResultSet rs, String column) throws SQLException {
        final Timestamp value = rs.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)));
        return value == null ? null : value.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static TermVariationRow termVariationRow(ResultSet rs) throws SQLException {
        return new TermVariationRow(rs.getLong("loan_reschedule_request_id"), rs.getInt("term_type"),
                rs.getObject("applicable_date", LocalDate.class), rs.getBigDecimal("decimal_value"),
                rs.getObject("date_value", LocalDate.class));
    }
}
