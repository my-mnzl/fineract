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
package co.mnzl.fineract.custom.charge.calculation;

import java.time.LocalDate;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculatorRegistry;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An overdue penalty is fixed when the penalty job charges it: a percentage of what was overdue on that installment at
 * that time. Upstream recalculates every charge whenever a schedule is rebuilt (reschedule approval, interest
 * recalculation) before the payments are replayed, so the base becomes the whole installment and the waived and paid
 * amounts are reset. Overdue penalties therefore keep the amount, payments and waivers they were charged with.
 * Registered by {@code CustomChargeAutoConfiguration}.
 */
public class MnzlLoanChargeService extends LoanChargeService {

    private static final Logger LOG = LoggerFactory.getLogger(MnzlLoanChargeService.class);

    public MnzlLoanChargeService(LoanChargeValidator loanChargeValidator, LoanTransactionProcessingService loanTransactionProcessingService,
            LoanLifecycleStateMachine loanLifecycleStateMachine, LoanBalanceService loanBalanceService,
            ChargeAmountCalculatorRegistry chargeAmountCalculatorRegistry) {
        super(loanChargeValidator, loanTransactionProcessingService, loanLifecycleStateMachine, loanBalanceService,
                chargeAmountCalculatorRegistry);
    }

    @Override
    public void recalculateLoanCharge(final Loan loan, final LoanCharge loanCharge, final int penaltyWaitPeriod) {
        if (isChargedOverduePenalty(loan, loanCharge)) {
            return;
        }
        super.recalculateLoanCharge(loan, loanCharge, penaltyWaitPeriod);
    }

    @Override
    public void recalculateLoanCharge(final Loan loan, final LoanCharge loanCharge, final int penaltyWaitPeriod,
            final LocalDate transactionDate) {
        if (isChargedOverduePenalty(loan, loanCharge)) {
            return;
        }
        super.recalculateLoanCharge(loan, loanCharge, penaltyWaitPeriod, transactionDate);
    }

    private boolean isChargedOverduePenalty(final Loan loan, final LoanCharge loanCharge) {
        if (!loanCharge.isOverdueInstallmentCharge()) {
            return false;
        }
        LOG.debug("Keeping overdue penalty {} on loan {} as charged ({})", loanCharge.getId(), loan.getId(), loanCharge.getAmount());
        return true;
    }
}
