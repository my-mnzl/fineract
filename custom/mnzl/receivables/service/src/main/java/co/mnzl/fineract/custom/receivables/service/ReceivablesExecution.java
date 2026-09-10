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

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReceivablesExecution {

    public final JsonNode command;
    public final String scope;
    public final String operationKey;
    public final String eventKey;
    public LocalDate date;
    public LocalDate postingDate;
    public String boundarySide = "AFTER_EVENTS";
    public final Map<String, Object> configuration;
    public final List<ReceivablesLedger.Line> lines = new ArrayList<>();
    public final List<String> nativeTransactions = new ArrayList<>();
    public final List<JsonNode> bankMovements = new ArrayList<>();
    public final Set<String> accountKeys = new LinkedHashSet<>();
    public final List<String> allocationIds = new ArrayList<>();
    public final Set<String> lotKeys = new LinkedHashSet<>();
    public final Set<String> facilityKeys = new LinkedHashSet<>();
    public co.mnzl.fineract.custom.receivables.nativeinstrument.NativeHelBridge.Funding helFunding;
    public String reversalOf;
    public String correctionOf;

    public ReceivablesExecution(JsonNode command, String scope, Map<String, Object> configuration) {
        this.command = command;
        this.scope = scope;
        this.configuration = configuration;
        operationKey = ReceivablesStore.key(scope, "operation", ReceivablesJson.text(command, "operationId"));
        eventKey = ReceivablesStore.key(scope, "event", operationKey);
        date = LocalDate.parse(ReceivablesJson.text(command, "businessDate"));
        postingDate = date;
    }

    public String accountKey(String id) {
        return ReceivablesStore.key(scope, "account", id);
    }

    public String subjectId() {
        return ReceivablesJson.text(command, "subjectId");
    }

    public String subjectKey() {
        return ReceivablesStore.key(scope, ReceivablesJson.text(command, "subjectKind"), subjectId());
    }
}
