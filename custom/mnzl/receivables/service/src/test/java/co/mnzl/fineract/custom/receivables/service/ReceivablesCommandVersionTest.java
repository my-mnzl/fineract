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
package co.mnzl.fineract.custom.receivables.service;

import static org.assertj.core.api.Assertions.assertThat;

import co.mnzl.fineract.receivables.math.ReceivablesMath;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An event names a version only when every position shares it; positions always name their own. */
class ReceivablesCommandVersionTest {

    @Test
    void eventVersionIsTheSharedPinnedVersionTheDefaultOrAbsent() {
        assertThat(ReceivablesCommandService.eventVersion(Set.of())).isEqualTo(ReceivablesConfiguration.CALCULATION);
        assertThat(ReceivablesCommandService.eventVersion(Set.of(ReceivablesMath.SIMPLE_CALCULATION_VERSION)))
                .isEqualTo(ReceivablesMath.SIMPLE_CALCULATION_VERSION);
        assertThat(ReceivablesCommandService.eventVersion(Set.of(ReceivablesMath.CALCULATION_VERSION)))
                .isEqualTo(ReceivablesMath.CALCULATION_VERSION);
        assertThat(ReceivablesCommandService
                .eventVersion(Set.of(ReceivablesMath.CALCULATION_VERSION, ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION))).isNull();
        assertThat(ReceivablesCommandService
                .eventVersion(Set.of(ReceivablesMath.CALCULATION_VERSION, ReceivablesMath.SIMPLE_CALCULATION_VERSION))).isNull();
    }
}
