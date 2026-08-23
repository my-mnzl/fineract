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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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

    @Test
    void removesTrackedSpringBatchExecutionAndCustomParameters() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        MnzlSimulationCleanupService service = new MnzlSimulationCleanupService(jdbcTemplate);
        when(jdbcTemplate.queryForList("SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", Long.class, 501L))
                .thenReturn(List.of(601L));
        when(jdbcTemplate.queryForList("""
                SELECT PARAMETER_VALUE
                FROM BATCH_JOB_EXECUTION_PARAMS
                WHERE JOB_EXECUTION_ID = ? AND PARAMETER_NAME IN (?, ?)
                """, String.class, 501L, "CUSTOM_JOB_PARAMETER_ID", "BusinessDate")).thenReturn(List.of("701", "702"));

        service.cleanup(null, null, null, null, List.of(501L));

        verify(jdbcTemplate).update("DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?", 501L);
        verify(jdbcTemplate).update("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID = ?", 501L);
        verify(jdbcTemplate).update("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = ?", 501L);
        verify(jdbcTemplate).update("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", 501L);
        verify(jdbcTemplate).update("""
                DELETE FROM BATCH_JOB_INSTANCE
                WHERE JOB_INSTANCE_ID = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = ?
                  )
                """, 601L, 601L);
        verify(jdbcTemplate).update("""
                DELETE FROM batch_custom_job_parameters
                WHERE id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM BATCH_JOB_EXECUTION_PARAMS
                      WHERE PARAMETER_NAME IN (?, ?) AND PARAMETER_VALUE = ?
                  )
                """, 701L, "CUSTOM_JOB_PARAMETER_ID", "BusinessDate", "701");
        verify(jdbcTemplate).update("""
                DELETE FROM batch_custom_job_parameters
                WHERE id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM BATCH_JOB_EXECUTION_PARAMS
                      WHERE PARAMETER_NAME IN (?, ?) AND PARAMETER_VALUE = ?
                  )
                """, 702L, "CUSTOM_JOB_PARAMETER_ID", "BusinessDate", "702");
    }
}
