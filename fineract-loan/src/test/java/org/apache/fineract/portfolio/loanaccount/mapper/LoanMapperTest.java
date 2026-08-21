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
package org.apache.fineract.portfolio.loanaccount.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Set;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleSelectionContext;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanMapperTest {

    @BeforeEach
    void setUpTenantContext() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @AfterEach
    void clearTenantContext() {
        ThreadLocalContextUtil.clearTenant();
        MoneyHelper.clearCacheForTenant("default");
    }

    @Test
    void regenerateScheduleModelUsesContextAwareFactoryForStandardRegeneration() {
        LoanTermVariationsMapper loanTermVariationsMapper = mock(LoanTermVariationsMapper.class);
        LoanMapper loanMapper = new LoanMapper(loanTermVariationsMapper);
        ScheduleGeneratorDTO scheduleGeneratorDTO = mock(ScheduleGeneratorDTO.class);
        LoanScheduleGeneratorFactory loanScheduleFactory = mock(LoanScheduleGeneratorFactory.class);
        LoanScheduleGenerator loanScheduleGenerator = mock(LoanScheduleGenerator.class);
        LoanApplicationTerms loanApplicationTerms = mock(LoanApplicationTerms.class);
        Loan loan = mock(Loan.class);
        LoanScheduleModel loanScheduleModel = mock(LoanScheduleModel.class);

        when(loanTermVariationsMapper.constructLoanApplicationTerms(scheduleGeneratorDTO, loan)).thenReturn(loanApplicationTerms);
        when(scheduleGeneratorDTO.getLoanScheduleFactory()).thenReturn(loanScheduleFactory);
        when(scheduleGeneratorDTO.getHolidayDetailDTO()).thenReturn(mock(HolidayDetailDTO.class));
        when(loanScheduleFactory.create(any(LoanScheduleSelectionContext.class))).thenReturn(loanScheduleGenerator);
        when(loanScheduleGenerator.generate(any(MathContext.class), same(loanApplicationTerms), anySet(), any(HolidayDetailDTO.class)))
                .thenReturn(loanScheduleModel);
        when(loanApplicationTerms.isEqualAmortization()).thenReturn(false);
        when(loanApplicationTerms.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        when(loanApplicationTerms.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
        when(loan.getId()).thenReturn(7L);
        when(loan.productId()).thenReturn(42L);
        when(loan.getActiveCharges()).thenReturn(Set.<LoanCharge>of());

        LoanScheduleModel result = loanMapper.regenerateScheduleModel(scheduleGeneratorDTO, loan);

        assertThat(result).isSameAs(loanScheduleModel);
        verify(loanScheduleFactory).create(argThat(context -> Long.valueOf(7L).equals(context.loanId())
                && Long.valueOf(42L).equals(context.loanProductId()) && context.loanScheduleType() == LoanScheduleType.CUMULATIVE
                && context.interestMethod() == InterestMethod.DECLINING_BALANCE));
        verify(loanScheduleFactory, never()).create(any(LoanScheduleType.class), any(InterestMethod.class));
    }

    @Test
    void regenerateScheduleModelUsesContextAwareFactoryForEqualAmortization() {
        LoanTermVariationsMapper loanTermVariationsMapper = mock(LoanTermVariationsMapper.class);
        LoanMapper loanMapper = new LoanMapper(loanTermVariationsMapper);
        ScheduleGeneratorDTO scheduleGeneratorDTO = mock(ScheduleGeneratorDTO.class);
        LoanScheduleGeneratorFactory loanScheduleFactory = mock(LoanScheduleGeneratorFactory.class);
        LoanScheduleGenerator decliningLoanScheduleGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleGenerator flatLoanScheduleGenerator = mock(LoanScheduleGenerator.class);
        LoanApplicationTerms loanApplicationTerms = mock(LoanApplicationTerms.class);
        Loan loan = mock(Loan.class);
        LoanScheduleModel decliningLoanScheduleModel = mock(LoanScheduleModel.class);
        LoanScheduleModel finalLoanScheduleModel = mock(LoanScheduleModel.class);

        when(loanTermVariationsMapper.constructLoanApplicationTerms(scheduleGeneratorDTO, loan)).thenReturn(loanApplicationTerms);
        when(scheduleGeneratorDTO.getLoanScheduleFactory()).thenReturn(loanScheduleFactory);
        when(scheduleGeneratorDTO.getHolidayDetailDTO()).thenReturn(mock(HolidayDetailDTO.class));
        when(loanScheduleFactory.create(any(LoanScheduleSelectionContext.class))).thenReturn(decliningLoanScheduleGenerator,
                flatLoanScheduleGenerator);
        when(decliningLoanScheduleGenerator.generate(any(MathContext.class), same(loanApplicationTerms), anySet(),
                any(HolidayDetailDTO.class))).thenReturn(decliningLoanScheduleModel);
        when(flatLoanScheduleGenerator.generate(any(MathContext.class), same(loanApplicationTerms), anySet(), any(HolidayDetailDTO.class)))
                .thenReturn(finalLoanScheduleModel);
        when(decliningLoanScheduleModel.getTotalInterestCharged()).thenReturn(BigDecimal.ONE);
        when(loanApplicationTerms.isEqualAmortization()).thenReturn(true);
        when(loanApplicationTerms.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
        when(loanApplicationTerms.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        when(loanApplicationTerms.getCurrency()).thenReturn(new CurrencyData("USD", "US Dollar", 2, 0, "$", "USD"));
        when(loan.getId()).thenReturn(7L);
        when(loan.productId()).thenReturn(42L);
        when(loan.getActiveCharges()).thenReturn(Set.<LoanCharge>of());

        LoanScheduleModel result = loanMapper.regenerateScheduleModel(scheduleGeneratorDTO, loan);

        assertThat(result).isSameAs(finalLoanScheduleModel);
        verify(loanScheduleFactory).create(argThat(context -> Long.valueOf(7L).equals(context.loanId())
                && Long.valueOf(42L).equals(context.loanProductId()) && context.loanScheduleType() == LoanScheduleType.CUMULATIVE
                && context.interestMethod() == InterestMethod.DECLINING_BALANCE));
        verify(loanScheduleFactory)
                .create(argThat(context -> Long.valueOf(7L).equals(context.loanId()) && Long.valueOf(42L).equals(context.loanProductId())
                        && context.loanScheduleType() == LoanScheduleType.CUMULATIVE && context.interestMethod() == InterestMethod.FLAT));
        verify(loanScheduleFactory, never()).create(any(LoanScheduleType.class), any(InterestMethod.class));
    }
}
