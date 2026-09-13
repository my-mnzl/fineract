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

import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

public final class ReceivablesDeveloperState {

    private ReceivablesDeveloperState() {}

    public static ObjectNode lot(ReceivablesJson json, Map<String, Object> lot, Map<String, Object> account) {
        var value = json.object();
        value.put("lotId", string(lot, "lot_id"));
        value.put("accountId", string(account, "external_id"));
        value.put("dealId", string(account, "deal_id"));
        value.put("sourceEventId", ReceivablesStore.key(string(lot, "scope_key"), "event", string(lot, "operation_key")));
        value.put("effectiveDate", string(lot, "effective_date"));
        value.put("dueDate", string(lot, "due_date"));
        value.put("direction", string(lot, "direction"));
        value.put("originalAmountMinor", string(lot, "initial_minor"));
        value.put("settledMinor", string(lot, "settled_minor"));
        value.put("accruedUnwindMinor", ReceivablesMeasurement.amount(lot, "carrying_minor")
                .subtract(ReceivablesMeasurement.amount(lot, "initial_minor")).toString());
        value.put("outstandingMinor", ReceivablesMeasurement.amount(lot, "carrying_minor")
                .subtract(ReceivablesMeasurement.amount(lot, "settled_minor")).toString());
        value.put("allowanceMinor", string(lot, "allowance_minor"));
        value.put("annualNominalRate", string(lot, "rate"));
        return value;
    }
}
