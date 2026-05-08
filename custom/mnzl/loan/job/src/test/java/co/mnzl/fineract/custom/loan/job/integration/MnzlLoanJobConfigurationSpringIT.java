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
package co.mnzl.fineract.custom.loan.job.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * L2 Spring slice for {@link co.mnzl.fineract.custom.loan.job.MnzlLoanJobConfiguration}.
 *
 * <p>
 * Deferred to L3. The configuration exposes 6 {@code @Primary @Bean} methods (executeStandingInstructionsTasklet,
 * transferFeeChargeForLoansTasklet, periodicChargeProjectionService, periodicChargeProjectionListener,
 * loanScheduleCalculationPlatformService, loanChargeAssembler) whose union of constructor parameters spans 20+
 * collaborators (StandingInstructionReadPlatformService, JdbcTemplate, DatabaseSpecificSQLGenerator,
 * AccountTransfersWritePlatformService, TransactionTemplate, ScheduledDateGenerator, LoanChargeReadPlatformService,
 * AccountAssociationsReadPlatformService, LoanChargeAssembler, LoanChargeService,
 * LoanScheduleCalculationPlatformServiceImpl, LoanProductRepository, FromJsonHelper, ChargeRepositoryWrapper,
 * LoanChargeRepository, ExternalIdFactory, ChargeAmountCalculatorRegistry, BusinessEventNotifierService, ...). Wiring
 * this many mocks at the slice tier provides no signal beyond what the L1 unit tests already cover. Phase F (L3
 * scenario suite) brings up the full Spring Boot context and exercises this configuration end-to-end.
 * </p>
 */
class MnzlLoanJobConfigurationSpringIT {

    @Test
    @Disabled("L2 slice deferred: requires 20+ collaborator mocks. Wiring is covered at L3 (Phase F) "
            + "where the full Spring context is up.")
    void mnzlJobEnabled_taskletsRegistered() {
        // intentionally empty — see @Disabled reason
    }

    @Test
    @Disabled("L2 slice deferred: same collaborator chain as the enabled-flag test; covered at L3 (Phase F).")
    void mnzlJobDisabled_taskletsAbsent() {
        // intentionally empty — see @Disabled reason
    }
}
