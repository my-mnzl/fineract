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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.ChargeAmountCalculatorRegistry;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A reschedule approval or an interest recalculation rebuilds the schedule and then recalculates every charge before
 * replaying payments. For an overdue penalty that re-derives the amount from the whole installment and wipes its paid
 * and waived amounts (a waived 1,408.86 penalty came back as 42.27 overdue; a 1.27 penalty became 1,408.90). Overdue
 * penalties must keep the amount they were charged with, while every other charge is still recalculated.
 */
class MnzlLoanChargeServiceTest {

    private ChargeAmountCalculatorRegistry chargeAmountCalculatorRegistry;
    private MnzlLoanChargeService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        chargeAmountCalculatorRegistry = mock(ChargeAmountCalculatorRegistry.class);
        service = new MnzlLoanChargeService(mock(LoanChargeValidator.class), mock(LoanTransactionProcessingService.class),
                mock(LoanLifecycleStateMachine.class), mock(LoanBalanceService.class), chargeAmountCalculatorRegistry);
        loan = mock(Loan.class);
    }

    @Test
    void overduePenaltyKeepsItsChargedAmountPaymentsAndWaiver() {
        LoanCharge penalty = overduePenalty();

        service.recalculateLoanCharge(loan, penalty, 0);

        assertNotRecalculated(penalty);
    }

    @Test
    void overduePenaltyIsAlsoKeptWhenRecalculatedAfterATransaction() {
        LoanCharge penalty = overduePenalty();

        service.recalculateLoanCharge(loan, penalty, 0, LocalDate.of(2026, 11, 13));

        assertNotRecalculated(penalty);
    }

    @Test
    void otherChargesAreStillRecalculated() {
        LoanCharge insuranceFee = mock(LoanCharge.class);
        when(insuranceFee.isOverdueInstallmentCharge()).thenReturn(false);
        when(insuranceFee.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT);
        when(insuranceFee.amountOrPercentage()).thenReturn(BigDecimal.valueOf(260));

        service.recalculateLoanCharge(loan, insuranceFee, 0);

        verify(insuranceFee).getChargeCalculation();
        verify(insuranceFee).amountOrPercentage();
    }

    private LoanCharge overduePenalty() {
        LoanCharge penalty = mock(LoanCharge.class);
        when(penalty.isOverdueInstallmentCharge()).thenReturn(true);
        when(penalty.getAmount()).thenReturn(BigDecimal.valueOf(1408.86));
        return penalty;
    }

    private void assertNotRecalculated(LoanCharge penalty) {
        verifyNoInteractions(chargeAmountCalculatorRegistry);
        verify(penalty, never()).getChargeCalculation();
        verify(penalty, never()).getOverdueInstallmentCharge();
        verify(penalty, never()).setAmount(any());
        verify(penalty, never()).setAmountPaid(any());
        verify(penalty, never()).setAmountWaived(any());
        verify(penalty, never()).setAmountOutstanding(any());
    }
}
