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
package org.apache.fineract.cob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.cob.data.BusinessStepNameAndOrder;
import org.apache.fineract.cob.domain.BatchBusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.cob.service.ReloaderService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;

class COBBusinessStepServicePrimarySelectionTest {

    @Test
    void getCOBBusinessStepsPrefersPrimaryBeanForSameEnumStyledName() {
        BatchBusinessStepRepository repository = mock(BatchBusinessStepRepository.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);

        when(repository.findAllByJobName("JOB")).thenReturn(List.of(stepConfig("DUPLICATE_STEP", 1L)));
        when(beanFactory.getBeanNamesForType(TestBusinessStep.class)).thenReturn(new String[] { "defaultStep", "primaryStep" });

        TestBusinessStep defaultStep = new TestBusinessStep("DUPLICATE_STEP");
        TestPrimaryBusinessStep primaryStep = new TestPrimaryBusinessStep("DUPLICATE_STEP");

        when(applicationContext.getBean("defaultStep")).thenReturn(defaultStep);
        when(applicationContext.getBean("primaryStep")).thenReturn(primaryStep);
        when(applicationContext.findAnnotationOnBean("defaultStep", Primary.class)).thenReturn(null);
        when(applicationContext.findAnnotationOnBean("primaryStep", Primary.class)).thenReturn(mock(Primary.class));

        COBBusinessStepServiceImpl service = new COBBusinessStepServiceImpl(repository, applicationContext, beanFactory,
                mock(BusinessEventNotifierService.class), mock(ConfigurationDomainService.class), mock(ReloaderService.class));

        var result = service.getCOBBusinessSteps(TestBusinessStep.class, "JOB");

        assertThat(result).singleElement().extracting(BusinessStepNameAndOrder::getStepName).isEqualTo("primaryStep");
    }

    private BatchBusinessStep stepConfig(String stepName, Long order) {
        BatchBusinessStep step = new BatchBusinessStep();
        step.setStepName(stepName);
        step.setStepOrder(order);
        return step;
    }

    static class TestEntity extends AbstractPersistableCustom<Long> {}

    static class TestBusinessStep implements COBBusinessStep<TestEntity> {

        private final String enumStyledName;

        TestBusinessStep(String enumStyledName) {
            this.enumStyledName = enumStyledName;
        }

        @Override
        public TestEntity execute(TestEntity item) {
            return item;
        }

        @Override
        public String getEnumStyledName() {
            return enumStyledName;
        }

        @Override
        public String getHumanReadableName() {
            return enumStyledName;
        }
    }

    @Primary
    static class TestPrimaryBusinessStep extends TestBusinessStep {

        TestPrimaryBusinessStep(String enumStyledName) {
            super(enumStyledName);
        }
    }
}
