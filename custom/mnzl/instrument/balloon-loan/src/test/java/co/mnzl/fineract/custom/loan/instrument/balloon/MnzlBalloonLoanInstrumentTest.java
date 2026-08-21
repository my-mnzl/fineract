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
package co.mnzl.fineract.custom.loan.instrument.balloon;

import static org.assertj.core.api.Assertions.assertThat;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import org.junit.jupiter.api.Test;

class MnzlBalloonLoanInstrumentTest {

    @Test
    void supportsOnlyBalloonInstrumentCode() {
        MnzlBalloonLoanInstrument instrument = new MnzlBalloonLoanInstrument();

        assertThat(instrument.supports(MnzlLoanProductStrategyCodes.INSTRUMENT_BALLOON_LOAN)).isTrue();
        assertThat(instrument.supports(MnzlLoanProductStrategyCodes.INSTRUMENT_STANDARD_LOAN)).isFalse();
    }

    @Test
    void supports_mnzlBalloon_returnsTrue() {
        MnzlBalloonLoanInstrument instrument = new MnzlBalloonLoanInstrument();

        assertThat(instrument.supports("MNZL_BALLOON_LOAN")).isTrue();
    }

    @Test
    void supports_otherCode_returnsFalse() {
        MnzlBalloonLoanInstrument instrument = new MnzlBalloonLoanInstrument();

        assertThat(instrument.supports("MNZL_STANDARD_LOAN")).isFalse();
        assertThat(instrument.supports("WHATEVER")).isFalse();
    }

    @Test
    void supports_null_returnsFalse() {
        MnzlBalloonLoanInstrument instrument = new MnzlBalloonLoanInstrument();

        assertThat(instrument.supports(null)).isFalse();
    }

    @Test
    void supports_emptyString_returnsFalse() {
        MnzlBalloonLoanInstrument instrument = new MnzlBalloonLoanInstrument();

        assertThat(instrument.supports("")).isFalse();
    }
}
