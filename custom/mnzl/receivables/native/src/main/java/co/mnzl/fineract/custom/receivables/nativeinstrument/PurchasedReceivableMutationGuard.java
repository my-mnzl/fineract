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
package co.mnzl.fineract.custom.receivables.nativeinstrument;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/** Ordinary loan commands cannot bypass the atomic purchased-receivable service. */
@Aspect
@Component
@RequiredArgsConstructor
public class PurchasedReceivableMutationGuard {

    private final LoanRepository loans;

    @Before("execution(* org.apache.fineract.portfolio.loanaccount.service.*WritePlatformService*.*(..))"
            + " || execution(* org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService.make*(..))"
            + " || execution(* org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService.foreCloseLoan(..))")
    public void guard(JoinPoint point) {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] arguments = point.getArgs();
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument instanceof Loan loan) {
                reject(loan);
            } else if (argument instanceof LoanTransaction transaction) {
                reject(transaction.getLoan());
            } else if (argument instanceof JsonCommand command && command.getLoanId() != null) {
                reject(command.getLoanId());
            } else if (argument instanceof Long id && parameters[i].getName().equals("loanId")) {
                reject(id);
            }
        }
    }

    private void reject(long id) {
        loans.findById(id).ifPresent(this::reject);
    }

    private void reject(Loan loan) {
        if (loan != null && loan.isPurchasedReceivable()) {
            throw new IllegalStateException("Purchased receivable financial changes require /v1/mnzl/receivables/commands");
        }
    }
}
