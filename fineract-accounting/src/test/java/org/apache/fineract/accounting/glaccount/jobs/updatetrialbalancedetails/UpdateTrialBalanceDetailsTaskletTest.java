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
package org.apache.fineract.accounting.glaccount.jobs.updatetrialbalancedetails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.fineract.accounting.glaccount.domain.TrialBalance;
import org.apache.fineract.accounting.glaccount.domain.TrialBalanceRepository;
import org.apache.fineract.accounting.glaccount.domain.TrialBalanceRepositoryWrapper;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSourceService;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSourceServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
class UpdateTrialBalanceDetailsTaskletTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 3);

    @Mock
    private RoutingDataSourceServiceFactory dataSourceServiceFactory;

    @Mock
    private RoutingDataSourceService dataSourceService;

    @Mock
    private DataSource dataSource;

    @Mock
    private TrialBalanceRepositoryWrapper trialBalanceRepositoryWrapper;

    @Mock
    private TrialBalanceRepository trialBalanceRepository;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    private UpdateTrialBalanceDetailsTasklet underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));

        underTest = new UpdateTrialBalanceDetailsTasklet(dataSourceServiceFactory, trialBalanceRepositoryWrapper, trialBalanceRepository,
                journalEntryRepository);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void executeConvertsOffsetCreatedDateToLocalDateWhenSavingTrialBalance() throws Exception {
        LocalDate transactionDate = LocalDate.of(2026, 2, 1);
        OffsetDateTime createdDate = OffsetDateTime.of(2026, 2, 2, 18, 30, 0, 0, ZoneOffset.UTC);

        TrialBalance trialBalance = executeWithCreatedDate(transactionDate, createdDate);

        assertEquals(transactionDate, trialBalance.getEntryDate());
        assertEquals(createdDate.toLocalDate(), trialBalance.getTransactionDate());
    }

    @Test
    void executeConvertsLocalDateTimeCreatedDateToLocalDateWhenSavingTrialBalance() throws Exception {
        LocalDate transactionDate = LocalDate.of(2026, 2, 1);
        LocalDateTime createdDate = LocalDateTime.of(2026, 2, 2, 18, 30, 0);

        TrialBalance trialBalance = executeWithCreatedDate(transactionDate, createdDate);

        assertEquals(transactionDate, trialBalance.getEntryDate());
        assertEquals(createdDate.toLocalDate(), trialBalance.getTransactionDate());
    }

    @Test
    void executeAllowsNullCreatedDateWhenSavingTrialBalance() throws Exception {
        LocalDate transactionDate = LocalDate.of(2026, 2, 1);

        TrialBalance trialBalance = executeWithCreatedDate(transactionDate, null);

        assertEquals(transactionDate, trialBalance.getEntryDate());
        assertNull(trialBalance.getTransactionDate());
    }

    @Test
    void executeRejectsUnsupportedCreatedDateType() {
        LocalDate transactionDate = LocalDate.of(2026, 2, 1);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> executeWithCreatedDate(transactionDate, "2026-02-02", false));

        assertEquals("Expected date-like value for createdDate but got java.lang.String", exception.getMessage());
    }

    private TrialBalance executeWithCreatedDate(LocalDate transactionDate, Object createdDate) throws Exception {
        return executeWithCreatedDate(transactionDate, createdDate, true);
    }

    private TrialBalance executeWithCreatedDate(LocalDate transactionDate, Object createdDate, boolean expectSuccess) throws Exception {
        Object[] trialBalanceRow = { 10L, 20L, new BigDecimal("12.34"), transactionDate, createdDate, new BigDecimal("56.78") };
        ArgumentCaptor<List<TrialBalance>> trialBalancesCaptor = ArgumentCaptor.captor();

        when(dataSourceServiceFactory.determineDataSourceService()).thenReturn(dataSourceService);
        when(dataSourceService.retrieveDataSource()).thenReturn(dataSource);
        when(trialBalanceRepository.findMaxCreatedDate()).thenReturn(LocalDate.of(2026, 1, 31));
        when(journalEntryRepository.findTransactionDatesAfter(LocalDate.of(2026, 1, 31))).thenReturn(List.of(transactionDate));
        when(journalEntryRepository.findTrialBalanceLinesForDate(transactionDate)).thenReturn(List.<Object[]>of(trialBalanceRow));
        if (expectSuccess) {
            when(trialBalanceRepository.findDistinctOfficeIdsWithNullClosingBalance()).thenReturn(List.of());
        }

        assertEquals(RepeatStatus.FINISHED, underTest.execute(stepContribution, chunkContext));

        verify(trialBalanceRepositoryWrapper).save(trialBalancesCaptor.capture());
        List<TrialBalance> trialBalances = trialBalancesCaptor.getValue();
        assertEquals(1, trialBalances.size());
        return trialBalances.getFirst();
    }
}
