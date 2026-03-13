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

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.charge.domain.ChargeAppliesTo;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
public class MnzlPeriodicLoanChargeCandidateReadService {

    private final JdbcTemplate jdbcTemplate;

    public Collection<Long> retrieveLoanIdsWithDuePeriodicCharges(final LocalDate businessDate) {
        final String sql = """
                SELECT DISTINCT l.id
                FROM m_loan l
                INNER JOIN m_product_loan_charge plc ON plc.product_loan_id = l.product_id
                INNER JOIN m_charge c ON c.id = plc.charge_id
                WHERE l.loan_status_id IN (?, ?)
                  AND l.is_charged_off = FALSE
                  AND c.is_deleted = FALSE
                  AND c.is_active = TRUE
                  AND c.charge_applies_to_enum = ?
                  AND c.charge_time_enum = ?
                  AND COALESCE(l.expected_firstrepaymenton_date, (
                      SELECT MIN(rps.duedate)
                      FROM m_loan_repayment_schedule rps
                      WHERE rps.loan_id = l.id
                        AND COALESCE(rps.is_down_payment, FALSE) = FALSE
                  )) <= ?
                """;
        try {
            return this.jdbcTemplate.queryForList(sql, Long.class, LoanStatus.ACTIVE.getValue(), LoanStatus.OVERPAID.getValue(),
                    ChargeAppliesTo.LOAN.getValue(), ChargeTimeType.LOAN_PERIODIC.getValue(), businessDate);
        } catch (final EmptyResultDataAccessException e) {
            return List.of();
        }
    }
}
