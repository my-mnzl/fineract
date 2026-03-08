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
package org.apache.fineract.infrastructure.jobs.service.jobparameterprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.cob.loan.LoanCOBConstant;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.data.JobParameterDTO;
import org.apache.fineract.infrastructure.jobs.domain.CustomJobParameterRepository;
import org.apache.fineract.infrastructure.springbatch.SpringBatchJobConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.batch.core.JobParameter;

class LoanCOBJobParameterProviderTest {

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void providePreservesIncomingBusinessDate() {
        CustomJobParameterRepository repository = mock(CustomJobParameterRepository.class);
        when(repository.save(anySet())).thenReturn(99L);

        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.COB_DATE, LocalDate.of(2023, 1, 31))));

        LoanCOBJobParameterProvider provider = new LoanCOBJobParameterProvider(repository);
        Set<JobParameterDTO> input = new LinkedHashSet<>();
        input.add(new JobParameterDTO(LoanCOBConstant.BUSINESS_DATE_PARAMETER_NAME, "2023-02-01"));
        input.add(new JobParameterDTO("OTHER", "value"));

        Map<String, JobParameter<Long>> result = provider.provide(input);

        ArgumentCaptor<Set<JobParameterDTO>> captor = ArgumentCaptor.forClass(Set.class);
        Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue()).contains(new JobParameterDTO(LoanCOBConstant.BUSINESS_DATE_PARAMETER_NAME, "2023-02-01"));
        assertThat(captor.getValue()).doesNotContain(new JobParameterDTO(LoanCOBConstant.BUSINESS_DATE_PARAMETER_NAME, "2023-01-31"));
        assertThat(result.get(SpringBatchJobConstants.CUSTOM_JOB_PARAMETER_ID_KEY).getValue()).isEqualTo(99L);
    }

    @Test
    void provideAddsCobDateWhenBusinessDateIsMissing() {
        CustomJobParameterRepository repository = mock(CustomJobParameterRepository.class);
        when(repository.save(anySet())).thenReturn(99L);

        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.COB_DATE, LocalDate.of(2023, 1, 31))));

        LoanCOBJobParameterProvider provider = new LoanCOBJobParameterProvider(repository);
        Set<JobParameterDTO> input = new LinkedHashSet<>();
        input.add(new JobParameterDTO("OTHER", "value"));

        provider.provide(input);

        ArgumentCaptor<Set<JobParameterDTO>> captor = ArgumentCaptor.forClass(Set.class);
        Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue()).contains(new JobParameterDTO(LoanCOBConstant.BUSINESS_DATE_PARAMETER_NAME, "2023-01-31"));
    }
}
