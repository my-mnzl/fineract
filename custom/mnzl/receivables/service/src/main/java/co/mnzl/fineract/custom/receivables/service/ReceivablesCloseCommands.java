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

import static co.mnzl.fineract.custom.receivables.service.ReceivablesException.require;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesJson.text;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.number;
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceivablesCloseCommands {

    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesAccountCommands accounts;
    private final ReceivablesCashCommands cash;
    private final ReceivablesReadService reads;

    public void close(ReceivablesExecution e) {
        require(!e.command.get("scope").has("developerOrganizationId"), "INVALID_DATA");
        LocalDate boundary = LocalDate.parse(text(e.command, "boundaryDate"));
        require(boundary.getDayOfMonth() == 1 && text(e.command, "periodId").equals(boundary.minusDays(1).toString().substring(0, 7)),
                "INVALID_DATA");
        String key = ReceivablesStore.key(e.scope, "period", text(e.command, "periodId"));
        var existing = store.find("period", key);
        if (text(e.command, "phase").equals("FINALIZE")) {
            require(existing != null && string(existing, "status").equals("PREPARING")
                    && number(existing, "event_watermark") == Long.parseLong(text(e.command, "eventWatermark"))
                    && string(existing, "cutoff_hash").equals(text(e.command, "sourceCutoffHash")), "SOURCE_CHANGED");
            require(e.command.get("approverIds").size() > 0, "APPROVAL_SCOPE_CHANGED");
            store.update("period", key, Map.of("status", "CLOSED", "reconciliation_id", text(e.command, "reconciliationId"), "locked_by",
                    text(e.command, "actorId"), "version", number(existing, "version") + 1));
            return;
        }
        require(existing == null, "PERIOD_CLOSED");
        require(Long.parseLong(text(e.command, "eventWatermark")) == reads.watermark(e.command.get("scope")), "SOURCE_CHANGED");
        e.date = boundary;
        e.postingDate = boundary.minusDays(1);
        e.boundarySide = "BEFORE_EVENTS";
        Set<String> forecastIds = new HashSet<>();
        e.command.get("forecastIds").forEach(f -> forecastIds.add(f.asText()));
        for (var account : store.scoped("account", e.scope)) {
            require(!LocalDate.parse(string(account, "last_effective_date")).isAfter(boundary), "SOURCE_CHANGED");
            if (LocalDate.parse(string(account, "activation_date")).isBefore(boundary)) {
                accounts.closeAccrue(e, account, forecastIds);
            }
        }
        for (var facility : store.scoped("funding_facility", e.scope)) {
            cash.accrueFunding(e, facility);
        }
        store.insert("period", key,
                Map.of("scope_key", e.scope, "period_id", text(e.command, "periodId"), "boundary_date", boundary, "status", "PREPARING",
                        "version", 1L, "event_watermark", 0L, "cutoff_hash", text(e.command, "sourceCutoffHash"), "reconciliation_id",
                        "PENDING", "snapshot_json", json.write(e.command)));
    }

    public void afterEvent(ReceivablesExecution e, long watermark) {
        if (!text(e.command, "commandType").equals("CLOSE_PERIOD") || !text(e.command, "phase").equals("PREPARE")) {
            return;
        }
        var controls = reads.controls(e.command.get("scope"), new ReceivablesReadService.Boundary(e.date, "BEFORE_EVENTS", watermark), null,
                null);
        for (var balance : controls.get("balances")) {
            require(text(balance, "differenceMinor").equals("0"), "JOURNAL_MISMATCH");
        }
        store.update("period", ReceivablesStore.key(e.scope, "period", text(e.command, "periodId")),
                Map.of("event_watermark", watermark, "snapshot_json", json.write(controls)));
    }

    public void correct(ReceivablesExecution e) {
        require(text(e.command, "executionMode").equals("CORRECTION"), "APPROVAL_SCOPE_CHANGED");
        // A correction must be tied to a supported native reversal and replacement; arbitrary GL edits are never
        // accepted.
        throw new ReceivablesException("RECOVERY_REQUIRED");
    }
}
