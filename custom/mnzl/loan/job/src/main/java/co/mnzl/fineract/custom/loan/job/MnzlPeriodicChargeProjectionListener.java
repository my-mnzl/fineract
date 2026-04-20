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
import org.apache.fineract.infrastructure.event.business.BusinessEventListener;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

/**
 * Registers a {@code BusinessEventListener<LoanCreatedBusinessEvent>} that runs after a loan is submitted and calls
 * {@link MnzlPeriodicChargeProjectionService#projectFullTermPeriodicCharges(org.apache.fineract.portfolio.loanaccount.domain.Loan)}
 * to materialise every product-linked {@code LOAN_PERIODIC} charge across the loan term.
 *
 * <p>
 * The listener fires inside the same transaction as the submission, so the projected
 * {@link org.apache.fineract.portfolio.loanaccount.domain.LoanCharge} rows commit atomically with the new loan.
 * User-supplied periodic charges (added ad-hoc in the create-loan form) are expanded earlier in
 * {@link MnzlLoanChargeAssembler}; this listener only handles charges linked to the loan product.
 * </p>
 */
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
        public void onBusinessEvent(LoanCreatedBusinessEvent event) {
            final Loan loan = event.get();
            projectionService.projectFullTermPeriodicCharges(loan);
        }
    }
}
