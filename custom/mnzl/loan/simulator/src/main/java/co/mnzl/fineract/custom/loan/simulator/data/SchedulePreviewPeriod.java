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
package co.mnzl.fineract.custom.loan.simulator.data;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * Wire-format schedule row for the preview endpoint. Uses a {@code String} dueDate so the default serializer emits an
 * ISO date string, matching the frontend's SchedulePeriod type. (Fineract's shared Gson adapter for LocalDate isn't
 * applied to this custom module's responses.)
 */
@Getter
@Builder
public class SchedulePreviewPeriod {

    private final int period;
    private final String dueDate;
    private final BigDecimal principalDue;
    private final BigDecimal interestDue;
    private final BigDecimal feeChargesDue;
    private final BigDecimal penaltyChargesDue;
    private final BigDecimal totalDue;
    private final BigDecimal principalOutstanding;
    private final BigDecimal interestOutstanding;
    private final BigDecimal totalOutstanding;
}
