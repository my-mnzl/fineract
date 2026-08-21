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
package org.apache.fineract.portfolio.loanaccount.jobs.updateloanarrearsageing;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.loanaccount.service.LoanArrearsAgingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LoanArrearsAgeingUpdateHandlerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;
    @Mock
    private LoanArrearsAgingService loanArrearsAgingService;

    @Test
    void updateLoanArrearsAgeingDetailsForAllLoansClearsTableTransactionallyAndUsesUpsert() {
        when(sqlGenerator.currentBusinessDate()).thenReturn("DATE('2026-04-06')");
        when(sqlGenerator.subDate(anyString(), anyString(), anyString())).thenReturn("DATE_SUB(...)");
        when(sqlGenerator.insertOnConflictUpdate(anyList(), anyList())).thenReturn(" UPSERT_CLAUSE");
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.batchUpdate(any(String[].class))).thenReturn(new int[0]);

        LoanArrearsAgeingUpdateHandler handler = new LoanArrearsAgeingUpdateHandler(jdbcTemplate, sqlGenerator,
                loanArrearsAgingService);

        handler.updateLoanArrearsAgeingDetailsForAllLoans();

        verify(jdbcTemplate).update("delete from m_loan_arrears_aging");
        ArgumentCaptor<String[]> statementsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(jdbcTemplate).batchUpdate(statementsCaptor.capture());
        assertTrue(statementsCaptor.getValue()[0].contains("UPSERT_CLAUSE"));
    }
}
