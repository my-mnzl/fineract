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
import static co.mnzl.fineract.custom.receivables.service.ReceivablesStore.string;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReceivablesInvestmentReadService {

    private final PlatformSecurityContext security;
    private final LoanReadPlatformService loans;
    private final ReceivablesStore store;
    private final ReceivablesJson json;
    private final ReceivablesReadService reads;

    public JsonNode read(long loanId, boolean includeProjections) {
        var user = security.authenticatedUser();
        user.validateHasReadPermission("LOAN");
        user.validateHasPermissionTo("READ_MNZL_RECEIVABLES");
        // Reuse standard loan visibility, including current/transfer office hierarchy, before any native lookup.
        loans.retrieveOne(loanId);
        var accounts = store.jdbc().queryForList("select scope_json,external_id from m_mnzl_r_account where native_loan_id=?", loanId);
        require(accounts.size() <= 1, "RECOVERY_REQUIRED");
        if (accounts.isEmpty()) {
            return json.object().put("applicable", false);
        }
        var account = accounts.getFirst();
        return reads.investment(json.read(string(account, "scope_json")), string(account, "external_id"), includeProjections);
    }
}
