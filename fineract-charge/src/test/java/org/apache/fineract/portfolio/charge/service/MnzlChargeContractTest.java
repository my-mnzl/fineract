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
package org.apache.fineract.portfolio.charge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.junit.jupiter.api.Test;

class MnzlChargeContractTest {

    private final ChargeDropdownReadPlatformServiceImpl dropdowns = new ChargeDropdownReadPlatformServiceImpl();

    @Test
    void preservesDeployedEnumValuesAndCodes() {
        assertThat(ChargeCalculationType.CUSTOM.getValue()).isEqualTo(6);
        assertThat(ChargeCalculationType.CUSTOM.getCode()).isEqualTo("chargeCalculationType.percent.of.amount.interest.and.penalties");
        assertThat(ChargeTimeType.LOAN_PERIODIC.getValue()).isEqualTo(17);
        assertThat(ChargeTimeType.LOAN_PERIODIC.getCode()).isEqualTo("chargeTimeType.loanPeriodic");
    }

    @Test
    void exposesCustomCalculationAndPeriodicTimeInLoanDropdowns() {
        assertThat(dropdowns.retrieveLoanCalculationTypes()).extracting("id").contains(6L);
        assertThat(dropdowns.retrieveLoanCollectionTimeTypes()).extracting("id").contains(17L);
    }
}
