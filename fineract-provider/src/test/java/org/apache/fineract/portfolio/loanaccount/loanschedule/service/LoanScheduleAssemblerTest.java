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
package org.apache.fineract.portfolio.loanaccount.loanschedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashSet;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.holiday.domain.HolidayRepository;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstanceRepository;
import org.apache.fineract.portfolio.calendar.domain.CalendarRepository;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.floatingrates.service.FloatingRatesReadPlatformService;
import org.apache.fineract.portfolio.group.domain.GroupRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.AprCalculator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleSelectionContext;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.serialization.VariableLoanScheduleFromApiJsonValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanaccount.service.LoanDisbursementDetailsAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanDisbursementService;
import org.apache.fineract.portfolio.loanaccount.service.LoanProductRelatedDetailUpdateUtil;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.Test;

class LoanScheduleAssemblerTest {

    @Test
    void assembleLoanScheduleFromUsesContextAwareFactoryForEqualAmortizationRequests() {
        FromJsonHelper fromJsonHelper = mock(FromJsonHelper.class);
        LoanChargeAssembler loanChargeAssembler = mock(LoanChargeAssembler.class);
        LoanScheduleGeneratorFactory loanScheduleFactory = mock(LoanScheduleGeneratorFactory.class);
        LoanScheduleGenerator decliningGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleGenerator flatGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleModel decliningModel = mock(LoanScheduleModel.class);
        LoanScheduleModel finalModel = mock(LoanScheduleModel.class);
        LoanApplicationTerms loanApplicationTerms = mock(LoanApplicationTerms.class);
        CurrencyData currency = mock(CurrencyData.class);
        LoanScheduleAssembler assembler = newAssembler(fromJsonHelper, loanChargeAssembler, loanScheduleFactory);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", 6);

        try {
            when(loanChargeAssembler.fromParsedJson(any(), any())).thenReturn(new HashSet<LoanCharge>());
            when(loanScheduleFactory.create(any(LoanScheduleSelectionContext.class))).thenReturn(decliningGenerator, flatGenerator);
            when(decliningGenerator.generate(any(MathContext.class), same(loanApplicationTerms), anySet(), any(HolidayDetailDTO.class)))
                    .thenReturn(decliningModel);
            when(flatGenerator.generate(any(MathContext.class), same(loanApplicationTerms), anySet(), any(HolidayDetailDTO.class)))
                    .thenReturn(finalModel);
            when(decliningModel.getTotalInterestCharged()).thenReturn(BigDecimal.ONE);
            when(loanApplicationTerms.isEqualAmortization()).thenReturn(true);
            when(loanApplicationTerms.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
            when(loanApplicationTerms.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
            when(loanApplicationTerms.getCurrency()).thenReturn(currency);
            when(fromJsonHelper.extractLongNamed(any(), any())).thenReturn(42L);

            LoanScheduleModel result = assembler.assembleLoanScheduleFrom(loanApplicationTerms, false, List.of(), mock(WorkingDays.class),
                    JsonParser.parseString("{\"productId\":42}"), List.<LoanDisbursementDetails>of());

            assertThat(result).isSameAs(finalModel);
            verify(loanScheduleFactory).create(argThat(context -> Long.valueOf(42L).equals(context.loanProductId())
                    && context.loanScheduleType() == LoanScheduleType.CUMULATIVE
                    && context.interestMethod() == InterestMethod.DECLINING_BALANCE));
            verify(loanScheduleFactory).create(argThat(context -> Long.valueOf(42L).equals(context.loanProductId())
                    && context.loanScheduleType() == LoanScheduleType.CUMULATIVE && context.interestMethod() == InterestMethod.FLAT));
            verify(loanScheduleFactory, times(2)).create(any(LoanScheduleSelectionContext.class));
            verify(loanScheduleFactory, never()).create(any(LoanScheduleType.class), any(InterestMethod.class));
        } finally {
            ThreadLocalContextUtil.clearTenant();
            MoneyHelper.clearCacheForTenant("default");
        }
    }

    private LoanScheduleAssembler newAssembler(FromJsonHelper fromJsonHelper, LoanChargeAssembler loanChargeAssembler,
            LoanScheduleGeneratorFactory loanScheduleFactory) {
        return new LoanScheduleAssembler(fromJsonHelper, mock(LoanProductRepository.class),
                mock(ApplicationCurrencyRepositoryWrapper.class), loanChargeAssembler, loanScheduleFactory, mock(AprCalculator.class),
                mock(CalendarRepository.class), mock(HolidayRepository.class), mock(ConfigurationDomainService.class),
                mock(ClientRepositoryWrapper.class), mock(GroupRepositoryWrapper.class), mock(WorkingDaysRepositoryWrapper.class),
                mock(FloatingRatesReadPlatformService.class), mock(VariableLoanScheduleFromApiJsonValidator.class),
                mock(CalendarInstanceRepository.class), mock(LoanUtilService.class), mock(LoanDisbursementDetailsAssembler.class),
                mock(LoanRepositoryWrapper.class), mock(LoanLifecycleStateMachine.class), mock(LoanAccrualsProcessingService.class),
                mock(LoanDisbursementService.class), mock(LoanChargeService.class), mock(LoanScheduleService.class),
                mock(LoanProductRelatedDetailUpdateUtil.class));
    }
}
