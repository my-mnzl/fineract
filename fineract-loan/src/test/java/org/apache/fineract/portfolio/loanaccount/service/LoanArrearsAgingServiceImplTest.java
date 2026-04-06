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
package org.apache.fineract.portfolio.loanaccount.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanSchedulePeriodData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class LoanArrearsAgingServiceImplTest {

    private final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
    private final BusinessEventNotifierService businessEventNotifierService = Mockito.mock(BusinessEventNotifierService.class);
    private final DatabaseSpecificSQLGenerator sqlGenerator = Mockito.mock(DatabaseSpecificSQLGenerator.class);

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCache();
    }

    @Test
    void createInsertStatementsUsesUpsertForOriginalScheduleRows() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(new EnumMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 6)))));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
        when(sqlGenerator.insertOnConflictUpdate(anyList(), anyList())).thenReturn(" UPSERT_CLAUSE");
        LoanArrearsAgingServiceImpl service = new LoanArrearsAgingServiceImpl(jdbcTemplate, businessEventNotifierService, sqlGenerator);

        LoanSchedulePeriodData period = LoanSchedulePeriodData.repaymentOnlyPeriod(1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                new BigDecimal("100"), null, new BigDecimal("25"), BigDecimal.ZERO, BigDecimal.ZERO);
        LoanSchedulePeriodData updatedPeriod = LoanSchedulePeriodData.withPaidDetail(period, false, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        Map<Long, List<LoanSchedulePeriodData>> scheduleDate = new HashMap<>();
        scheduleDate.put(99L, new ArrayList<>(List.of(updatedPeriod)));

        List<String> insertStatements = new ArrayList<>();
        service.createInsertStatements(insertStatements, scheduleDate, true);

        assertEquals(1, insertStatements.size());
        assertTrue(insertStatements.get(0).contains("UPSERT_CLAUSE"));
    }
}
