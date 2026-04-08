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
package org.apache.fineract.portfolio.loanaccount.jobs.transferfeechargeforloans;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargeData;
import org.apache.fineract.portfolio.loanaccount.data.LoanInstallmentChargeData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeReadPlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;

class TransferFeeChargeForLoansTaskletTest {

    @Test
    void execute_skipsZeroOutstandingInstallmentCharges() throws Exception {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 8))));

        LoanChargeReadPlatformService loanChargeReadPlatformService = mock(LoanChargeReadPlatformService.class);
        AccountAssociationsReadPlatformService accountAssociationsReadPlatformService = mock(AccountAssociationsReadPlatformService.class);
        AccountTransfersWritePlatformService accountTransfersWritePlatformService = mock(AccountTransfersWritePlatformService.class);
        TransferFeeChargeForLoansTasklet tasklet = new TransferFeeChargeForLoansTasklet(loanChargeReadPlatformService,
                accountAssociationsReadPlatformService, accountTransfersWritePlatformService);

        LoanChargeData chargeData = LoanChargeData.builder().id(11L).loanId(22L)
                .chargeTimeType(
                        new EnumOptionData((long) ChargeTimeType.INSTALMENT_FEE.getValue(), "chargeTime.installmentFee", "Installment Fee"))
                .build();
        LoanInstallmentChargeData installmentChargeData = LoanInstallmentChargeData.builder().installmentNumber(1)
                .dueDate(LocalDate.of(2026, 4, 7)).amountOutstanding(BigDecimal.ZERO).paid(false).waived(false).build();

        when(loanChargeReadPlatformService.retrieveLoanChargesForFeePayment(ChargePaymentMode.ACCOUNT_TRANSFER.getValue(),
                LoanStatus.ACTIVE.getValue())).thenReturn(List.of(chargeData));
        when(loanChargeReadPlatformService.retrieveInstallmentLoanCharges(11L, true)).thenReturn(List.of(installmentChargeData));

        tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

        verify(accountAssociationsReadPlatformService, never()).retriveLoanLinkedAssociation(anyLong());
        verify(accountTransfersWritePlatformService, never()).transferFunds(any());
    }
}
