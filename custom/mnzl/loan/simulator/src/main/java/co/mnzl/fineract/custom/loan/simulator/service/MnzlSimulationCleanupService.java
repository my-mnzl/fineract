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
package co.mnzl.fineract.custom.loan.simulator.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the temporary portfolio graph created by a simulation.
 *
 * <p>
 * Fineract does not expose hard-delete commands for active loans and savings accounts. The simulator therefore removes
 * its short-lived data directly, in foreign-key order and in one transaction. The statements intentionally use only
 * portable SQL so the same cleanup is enforced by MySQL and PostgreSQL rather than relying on a database-specific
 * foreign-key bypass.
 */
@Service
@RequiredArgsConstructor
public class MnzlSimulationCleanupService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void cleanup(Long loanId, Long savingsId, Long clientId) {
        cleanup(loanId, savingsId, clientId, null);
    }

    @Transactional
    public void cleanup(Long loanId, Long savingsId, Long clientId, String commandKeyPrefix) {
        if (loanId != null) {
            cleanupLoan(loanId);
        }
        if (savingsId != null) {
            cleanupSavings(savingsId);
        }
        if (clientId != null) {
            cleanupClient(clientId);
        }
        if (commandKeyPrefix != null) {
            jdbcTemplate.update("DELETE FROM m_portfolio_command_source WHERE idempotency_key LIKE ?", commandKeyPrefix + "%");
        }
    }

    private void cleanupLoan(Long loanId) {
        jdbcTemplate.update("""
                DELETE FROM m_portfolio_account_associations
                WHERE loan_account_id = ? OR linked_loan_account_id = ?
                """, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_transaction_repayment_schedule_mapping
                WHERE loan_transaction_id IN (SELECT id FROM m_loan_transaction WHERE loan_id = ?)
                   OR loan_repayment_schedule_id IN (SELECT id FROM m_loan_repayment_schedule WHERE loan_id = ?)
                """, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_charge_paid_by
                WHERE loan_transaction_id IN (SELECT id FROM m_loan_transaction WHERE loan_id = ?)
                   OR loan_charge_id IN (SELECT id FROM m_loan_charge WHERE loan_id = ?)
                """, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_transaction_relation
                WHERE from_loan_transaction_id IN (SELECT id FROM m_loan_transaction WHERE loan_id = ?)
                   OR to_loan_transaction_id IN (SELECT id FROM m_loan_transaction WHERE loan_id = ?)
                   OR to_loan_charge_id IN (SELECT id FROM m_loan_charge WHERE loan_id = ?)
                """, loanId, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_installment_charge
                WHERE loan_schedule_id IN (SELECT id FROM m_loan_repayment_schedule WHERE loan_id = ?)
                   OR loan_charge_id IN (SELECT id FROM m_loan_charge WHERE loan_id = ?)
                """, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_overdue_installment_charge
                WHERE loan_schedule_id IN (SELECT id FROM m_loan_repayment_schedule WHERE loan_id = ?)
                   OR loan_charge_id IN (SELECT id FROM m_loan_charge WHERE loan_id = ?)
                """, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_charge_tax_details
                WHERE loan_charge_id IN (SELECT id FROM m_loan_charge WHERE loan_id = ?)
                """, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_loan_tranche_disbursement_charge
                WHERE loan_charge_id IN (SELECT id FROM m_loan_charge WHERE loan_id = ?)
                   OR disbursement_detail_id IN (SELECT id FROM m_loan_disbursement_detail WHERE loan_id = ?)
                """, loanId, loanId);
        jdbcTemplate.update("""
                DELETE FROM acc_gl_journal_entry
                WHERE loan_transaction_id IN (SELECT id FROM m_loan_transaction WHERE loan_id = ?)
                """, loanId);
        jdbcTemplate.update("""
                DELETE FROM m_note
                WHERE loan_transaction_id IN (SELECT id FROM m_loan_transaction WHERE loan_id = ?)
                """, loanId);
        jdbcTemplate.update("DELETE FROM m_loan_transaction WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_repayment_schedule_history WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_repayment_schedule WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_charge WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_disbursement_detail WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_account_locks WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_arrears_aging WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_delinquency_action WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_delinquency_tag_history WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_installment_delinquency_tag WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_approved_amount_history WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_officer_assignment_history WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_rate WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_status_change_history WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_term_variations WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan_recalculation_details WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_note WHERE loan_id = ?", loanId);
        jdbcTemplate.update("DELETE FROM m_loan WHERE id = ?", loanId);
    }

    private void cleanupSavings(Long savingsId) {
        jdbcTemplate.update("""
                DELETE FROM m_portfolio_account_associations
                WHERE savings_account_id = ? OR linked_savings_account_id = ?
                """, savingsId, savingsId);
        jdbcTemplate.update("""
                DELETE FROM m_savings_account_charge_paid_by
                WHERE savings_account_transaction_id IN
                        (SELECT id FROM m_savings_account_transaction WHERE savings_account_id = ?)
                   OR savings_account_charge_id IN
                        (SELECT id FROM m_savings_account_charge WHERE savings_account_id = ?)
                """, savingsId, savingsId);
        jdbcTemplate.update("""
                DELETE FROM m_savings_account_transaction_tax_details
                WHERE savings_transaction_id IN
                        (SELECT id FROM m_savings_account_transaction WHERE savings_account_id = ?)
                """, savingsId);
        jdbcTemplate.update("""
                DELETE FROM acc_gl_journal_entry
                WHERE savings_transaction_id IN
                        (SELECT id FROM m_savings_account_transaction WHERE savings_account_id = ?)
                """, savingsId);
        jdbcTemplate.update("DELETE FROM m_savings_account_transaction WHERE savings_account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_savings_account_charge WHERE savings_account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_deposit_account_on_hold_transaction WHERE savings_account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_mandatory_savings_schedule WHERE savings_account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_savings_account_interest_rate_chart WHERE savings_account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_savings_officer_assignment_history WHERE account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_note WHERE savings_account_id = ?", savingsId);
        jdbcTemplate.update("DELETE FROM m_savings_account WHERE id = ?", savingsId);
    }

    private void cleanupClient(Long clientId) {
        jdbcTemplate.update("DELETE FROM m_note WHERE client_id = ?", clientId);
        jdbcTemplate.update("DELETE FROM m_client WHERE id = ?", clientId);
    }
}
