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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.event.business.BusinessEventListener;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

@Slf4j
@RequiredArgsConstructor
public class MnzlPeriodicChargeProjectionListener {

    private final BusinessEventNotifierService businessEventNotifierService;
    private final MnzlPeriodicChargeProjectionService projectionService;

    @PostConstruct
    public void register() {
        businessEventNotifierService.addPostBusinessEventListener(LoanCreatedBusinessEvent.class, new LoanCreatedListener());
    }

    private final class LoanCreatedListener implements BusinessEventListener<LoanCreatedBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanCreatedBusinessEvent event) {
            final Loan loan = event.get();
            try {
                final int added = projectionService.projectFullTermPeriodicCharges(loan);
                if (added > 0) {
                    log.info("Projected {} periodic charge occurrence(s) for loan id={}", added, loan.getId());
                }
            } catch (RuntimeException exception) {
                log.error("Failed to project periodic charges for loan id={}", loan.getId(), exception);
                throw exception;
            }
        }
    }
}
