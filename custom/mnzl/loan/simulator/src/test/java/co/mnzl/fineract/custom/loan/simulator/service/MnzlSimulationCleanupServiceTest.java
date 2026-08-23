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

import static org.mockito.Mockito.inOrder;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class MnzlSimulationCleanupServiceTest {

    @Test
    void removesRecalculationDetailsBeforeLoanAndDeletesTaggedCommands() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        MnzlSimulationCleanupService service = new MnzlSimulationCleanupService(jdbcTemplate);

        service.cleanup(42L, null, null, "mnzlsim-run");

        InOrder ordered = inOrder(jdbcTemplate);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_loan_repayment_schedule_history WHERE loan_id = ?", 42L);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_loan_payment_allocation_rule WHERE loan_id = ?", 42L);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_loan_credit_allocation_rule WHERE loan_id = ?", 42L);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_loan_progressive_model WHERE loan_id = ?", 42L);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_loan_recalculation_details WHERE loan_id = ?", 42L);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_loan WHERE id = ?", 42L);
        ordered.verify(jdbcTemplate).update("DELETE FROM m_portfolio_command_source WHERE idempotency_key LIKE ?", "mnzlsim-run%");
    }
}
