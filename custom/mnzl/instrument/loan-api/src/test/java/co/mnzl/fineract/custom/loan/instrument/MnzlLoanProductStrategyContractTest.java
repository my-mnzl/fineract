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
package co.mnzl.fineract.custom.loan.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

class MnzlLoanProductStrategyContractTest {

    @Test
    void preservesApiPath() {
        assertThat(MnzlLoanProductStrategyApiResource.class.getAnnotation(Path.class).value())
                .isEqualTo("/v1/mnzl/loan-products/{loanProductId}/strategies");
    }

    @Test
    void preservesPersistedStrategyCodes() {
        assertThat(MnzlLoanProductStrategyCodes.INSTRUMENT_STANDARD_LOAN).isEqualTo("MNZL_STANDARD_LOAN");
        assertThat(MnzlLoanProductStrategyCodes.INSTRUMENT_BALLOON_LOAN).isEqualTo("MNZL_BALLOON_LOAN");
        assertThat(MnzlLoanProductStrategyCodes.SCHEDULE_CORE).isEqualTo("CORE_DEFAULT");
        assertThat(MnzlLoanProductStrategyCodes.SCHEDULE_MNZL_DECLINING_BALANCE).isEqualTo("MNZL_DECLINING_BALANCE");
        assertThat(MnzlLoanProductStrategyCodes.CHARGE_CORE).isEqualTo("CORE_DEFAULT");
        assertThat(MnzlLoanProductStrategyCodes.CHARGE_MNZL_INTEREST_AND_PENALTIES).isEqualTo("MNZL_INTEREST_AND_PENALTIES");
        assertThat(MnzlLoanProductStrategyCodes.COB_CORE).isEqualTo("CORE_DEFAULT");
        assertThat(MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS).isEqualTo("MNZL_DUE_INSTALLMENTS");
    }
}
