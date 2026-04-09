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
package co.mnzl.fineract.custom.loan.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulationActionTypeTest {

    @ParameterizedTest
    @ValueSource(strings = { "DISBURSE", "PAY", "SKIP", "RUN_COB", "ADD_CHARGE", "WRITE_OFF", "CHANGE_INTEREST_RATE" })
    void fromStringParsesAllValidTypes(String type) {
        assertThat(SimulationActionType.fromString(type)).isNotNull();
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertThat(SimulationActionType.fromString("disburse")).isEqualTo(SimulationActionType.DISBURSE);
        assertThat(SimulationActionType.fromString("pay")).isEqualTo(SimulationActionType.PAY);
    }

    @Test
    void fromStringThrowsForInvalidType() {
        assertThatThrownBy(() -> SimulationActionType.fromString("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
