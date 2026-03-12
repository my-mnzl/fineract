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
package co.mnzl.fineract.custom.loan.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.junit.jupiter.api.Test;

class MnzlJobNameConfigTest {

    @Test
    void providerExposesMnzlManagedLoanJobs() {
        var provider = new MnzlJobNameConfig().mnzlJobNameProvider();

        assertThat(provider.provide()).extracting("enumStyleName").contains(JobName.EXECUTE_STANDING_INSTRUCTIONS.name(),
                JobName.TRANSFER_FEE_CHARGE_FOR_LOANS.name(), MnzlJobName.APPLY_PERIODIC_LOAN_CHARGES.name());
    }

    @Test
    void changelogContainsPeriodicLoanChargeJobMetadata() throws IOException {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream("db/custom-changelog/0001_mnzl_loan_job.xml")) {
            assertThat(inputStream).isNotNull();
            final String changelog = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(changelog).contains("Apply Periodic Loan Charges");
            assertThat(changelog).contains("LA_PLC");
        }
    }
}
