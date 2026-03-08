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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.OutstandingAmountsDTO;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleTransactionProcessorFactory;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.LoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanScheduleDTO;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleSelectionContext;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanTermVariationsMapper;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanTransactionProcessingServiceImplTest {

    @Mock
    private LoanRepaymentScheduleTransactionProcessorFactory transactionProcessorFactory;
    @Mock
    private LoanTermVariationsMapper loanMapper;
    @Mock
    private InterestScheduleModelRepositoryWrapper modelRepository;
    @Mock
    private LoanTransactionService loanTransactionService;
    @Mock
    private ScheduleGeneratorDTO scheduleGeneratorDTO;
    @Mock
    private LoanScheduleGeneratorFactory loanScheduleFactory;
    @Mock
    private LoanScheduleGenerator loanScheduleGenerator;
    @Mock
    private Loan loan;
    @Mock
    private LoanProductRelatedDetail loanRepaymentScheduleDetail;
    @Mock
    private LoanApplicationTerms loanApplicationTerms;
    @Mock
    private LoanRepaymentScheduleTransactionProcessor loanRepaymentScheduleTransactionProcessor;
    @Mock
    private HolidayDetailDTO holidayDetailDTO;
    @Mock
    private OutstandingAmountsDTO outstandingAmounts;
    @Mock
    private LoanScheduleDTO loanScheduleDTO;
    @Mock
    private MonetaryCurrency currency;

    @InjectMocks
    private LoanTransactionProcessingServiceImpl service;

    @BeforeEach
    void setUpTenantContext() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "test", "Test Tenant", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("test", 6);
    }

    @Test
    void getRecalculatedScheduleUsesSelectionContext() {
        when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(true);
        when(loan.isNpa()).thenReturn(false);
        when(loan.isChargedOff()).thenReturn(false);
        when(loan.getLoanRepaymentScheduleDetail()).thenReturn(loanRepaymentScheduleDetail);
        when(loanRepaymentScheduleDetail.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
        when(loanRepaymentScheduleDetail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        when(loan.getId()).thenReturn(42L);
        when(loan.productId()).thenReturn(314L);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn("strategy");
        when(scheduleGeneratorDTO.getLoanScheduleFactory()).thenReturn(loanScheduleFactory);
        when(scheduleGeneratorDTO.getHolidayDetailDTO()).thenReturn(holidayDetailDTO);
        when(scheduleGeneratorDTO.getRecalculateFrom()).thenReturn(LocalDate.of(2025, 1, 1));
        when(scheduleGeneratorDTO.getRecalculateTill()).thenReturn(LocalDate.of(2025, 1, 31));
        when(loanMapper.constructLoanApplicationTerms(scheduleGeneratorDTO, loan)).thenReturn(loanApplicationTerms);
        when(transactionProcessorFactory.determineProcessor("strategy")).thenReturn(loanRepaymentScheduleTransactionProcessor);
        when(loanScheduleFactory.create(any(LoanScheduleSelectionContext.class))).thenReturn(loanScheduleGenerator);
        when(loanScheduleGenerator.rescheduleNextInstallments(any(), any(), any(), any(), any(), any(), any())).thenReturn(loanScheduleDTO);

        LoanScheduleDTO result = service.getRecalculatedSchedule(scheduleGeneratorDTO, loan);

        assertSame(loanScheduleDTO, result);
        LoanScheduleSelectionContext selectionContext = captureSelectionContext();
        assertEquals(LoanScheduleType.CUMULATIVE, selectionContext.loanScheduleType());
        assertEquals(InterestMethod.DECLINING_BALANCE, selectionContext.interestMethod());
        assertEquals(Long.valueOf(42L), selectionContext.loanId());
        assertEquals(Long.valueOf(314L), selectionContext.loanProductId());
    }

    @Test
    void fetchPrepaymentDetailUsesSelectionContext() {
        LocalDate onDate = LocalDate.of(2025, 2, 15);

        when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(true);
        when(loan.isChargeOffOnDate(onDate)).thenReturn(false);
        when(loan.isContractTermination()).thenReturn(false);
        when(loan.getLoanRepaymentScheduleDetail()).thenReturn(loanRepaymentScheduleDetail);
        when(loanRepaymentScheduleDetail.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
        when(loanRepaymentScheduleDetail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        when(loan.getId()).thenReturn(42L);
        when(loan.productId()).thenReturn(314L);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn("strategy");
        when(loan.getCurrency()).thenReturn(currency);
        when(scheduleGeneratorDTO.getLoanScheduleFactory()).thenReturn(loanScheduleFactory);
        when(scheduleGeneratorDTO.getHolidayDetailDTO()).thenReturn(holidayDetailDTO);
        when(loanMapper.constructLoanApplicationTerms(scheduleGeneratorDTO, loan)).thenReturn(loanApplicationTerms);
        when(transactionProcessorFactory.determineProcessor(anyString())).thenReturn(loanRepaymentScheduleTransactionProcessor);
        when(loanScheduleFactory.create(any(LoanScheduleSelectionContext.class))).thenReturn(loanScheduleGenerator);
        when(loanScheduleGenerator.calculatePrepaymentAmount(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(outstandingAmounts);

        OutstandingAmountsDTO result = service.fetchPrepaymentDetail(scheduleGeneratorDTO, onDate, loan);

        assertSame(outstandingAmounts, result);
        LoanScheduleSelectionContext selectionContext = captureSelectionContext();
        assertEquals(LoanScheduleType.CUMULATIVE, selectionContext.loanScheduleType());
        assertEquals(InterestMethod.DECLINING_BALANCE, selectionContext.interestMethod());
        assertEquals(Long.valueOf(42L), selectionContext.loanId());
        assertEquals(Long.valueOf(314L), selectionContext.loanProductId());
    }

    private LoanScheduleSelectionContext captureSelectionContext() {
        ArgumentCaptor<LoanScheduleSelectionContext> captor = ArgumentCaptor.forClass(LoanScheduleSelectionContext.class);
        verify(loanScheduleFactory).create(captor.capture());
        verify(loanScheduleFactory, never()).create(any(LoanScheduleType.class), any(InterestMethod.class));
        return captor.getValue();
    }
}
