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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class SimpleChargeAmountCalculatorRegistryTest {

    @Test
    void findsCalculatorByPersistedCalculationType() {
        final ChargeAmountCalculator calculator = calculator(6);
        final SimpleChargeAmountCalculatorRegistry registry = new SimpleChargeAmountCalculatorRegistry(List.of(calculator));

        assertThat(registry.find(6)).containsSame(calculator);
        assertThat(registry.find(1)).isEmpty();
    }

    @Test
    void rejectsDuplicateCalculationTypes() {
        assertThatThrownBy(() -> new SimpleChargeAmountCalculatorRegistry(List.of(calculator(6), calculator(6))))
                .isInstanceOf(IllegalStateException.class);
    }

    private ChargeAmountCalculator calculator(final int calculationType) {
        final ChargeAmountCalculator calculator = mock(ChargeAmountCalculator.class);
        when(calculator.calculationType()).thenReturn(calculationType);
        return calculator;
    }
}
