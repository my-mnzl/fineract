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

import java.util.Objects;
import java.util.function.IntFunction;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;

public final class ChargeCalculationOptionDataLookup {

    private static volatile IntFunction<EnumOptionData> optionDataProvider = ChargeCalculationOptionDataLookup::fallbackOptionData;

    private ChargeCalculationOptionDataLookup() {}

    public static EnumOptionData optionData(int chargeCalculationId) {
        try {
            return optionDataProvider.apply(chargeCalculationId);
        } catch (IllegalArgumentException ex) {
            return invalidOptionData();
        }
    }

    public static void register(IntFunction<EnumOptionData> optionDataProvider) {
        ChargeCalculationOptionDataLookup.optionDataProvider = Objects.requireNonNull(optionDataProvider);
    }

    static void reset() {
        optionDataProvider = ChargeCalculationOptionDataLookup::fallbackOptionData;
    }

    private static EnumOptionData fallbackOptionData(int chargeCalculationId) {
        return switch (ChargeCalculationType.fromInt(chargeCalculationId)) {
            case FLAT ->
                new EnumOptionData(ChargeCalculationType.FLAT.getValue().longValue(), ChargeCalculationType.FLAT.getCode(), "Flat");
            case PERCENT_OF_AMOUNT -> new EnumOptionData(ChargeCalculationType.PERCENT_OF_AMOUNT.getValue().longValue(),
                    ChargeCalculationType.PERCENT_OF_AMOUNT.getCode(), "% Amount");
            case PERCENT_OF_AMOUNT_AND_INTEREST ->
                new EnumOptionData(ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST.getValue().longValue(),
                        ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST.getCode(), "% Loan Amount + Interest");
            case PERCENT_OF_INTEREST -> new EnumOptionData(ChargeCalculationType.PERCENT_OF_INTEREST.getValue().longValue(),
                    ChargeCalculationType.PERCENT_OF_INTEREST.getCode(), "% Interest");
            case PERCENT_OF_DISBURSEMENT_AMOUNT ->
                new EnumOptionData(ChargeCalculationType.PERCENT_OF_DISBURSEMENT_AMOUNT.getValue().longValue(),
                        ChargeCalculationType.PERCENT_OF_DISBURSEMENT_AMOUNT.getCode(), "% Disbursement Amount");
            default -> invalidOptionData();
        };
    }

    private static EnumOptionData invalidOptionData() {
        return new EnumOptionData(ChargeCalculationType.INVALID.getValue().longValue(), ChargeCalculationType.INVALID.getCode(), "Invalid");
    }
}
