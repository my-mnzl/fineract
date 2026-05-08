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
package co.mnzl.fineract.custom.loan.schedule.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * L2 Spring slice for {@link co.mnzl.fineract.custom.loan.schedule.MnzlLoanScheduleApiConfiguration}.
 *
 * <p>
 * Deferred to L3. The {@code mnzlLoanReadPlatformService} bean alone constructs through 30+ collaborators
 * (LoanRepositoryWrapper, ApplicationCurrencyRepositoryWrapper, LoanProductReadPlatformService,
 * ClientReadPlatformService, GroupReadPlatformService, ChargeReadPlatformService, PaginationHelper,
 * LoanTransactionMapper, LoanForeclosureValidator, LoanCapitalizedIncomeBalanceRepository, ...). Wiring all of these as
 * {@code @Bean} mocks here would duplicate the full provider config and provide no incremental signal over a true
 * context-loaded test. Phase F (L3 scenario suite) brings up the full Spring Boot context and exercises this
 * configuration end-to-end.
 * </p>
 */
class MnzlLoanReadPlatformServiceSpringIT {

    @Test
    @Disabled("L2 slice deferred: requires 30+ collaborator mocks (LoanRepositoryWrapper, "
            + "ApplicationCurrencyRepositoryWrapper, LoanProductReadPlatformService, ClientReadPlatformService, "
            + "GroupReadPlatformService, FundReadPlatformService, ChargeReadPlatformService, PaginationHelper, "
            + "LoanTransactionMapper, LoanForeclosureValidator, ...). Wiring is covered at L3 (Phase F) where "
            + "the full Spring context is up.")
    void mnzlScheduleEnabled_readPlatformServiceWired() {
        // intentionally empty — see @Disabled reason
    }

    @Test
    @Disabled("L2 slice deferred: same collaborator chain as the read-service test; covered at L3 (Phase F).")
    void mnzlScheduleDisabled_readPlatformServiceAbsent() {
        // intentionally empty — see @Disabled reason
    }
}
