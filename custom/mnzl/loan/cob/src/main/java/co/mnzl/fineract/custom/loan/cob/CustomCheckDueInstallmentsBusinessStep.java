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
package co.mnzl.fineract.custom.loan.cob;

import static org.apache.fineract.infrastructure.core.diagnostics.performance.MeasuringUtil.measure;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.cob.loan.CheckDueInstallmentsBusinessStep;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAccountCustomSnapshotBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CustomCheckDueInstallmentsBusinessStep implements LoanCOBBusinessStep {

    private final BusinessEventNotifierService businessEventNotifierService;
    private final CheckDueInstallmentsBusinessStep defaultBusinessStep;
    private final MnzlLoanProductStrategyReadService loanProductStrategyReadService;

    @Override
    public Loan execute(Loan loan) {
        if (loan == null) {
            log.debug("Ignoring custom snapshot event processing for null loan.");
            return null;
        }
        if (!usesCustomCobStrategy(loan.productId())) {
            return defaultBusinessStep.execute(loan);
        }

        String externalId = Optional.ofNullable(loan.getExternalId()).map(ExternalId::getValue).orElse(null);
        measure(new Runnable() {

            @Override
            public void run() {
                try {
                    log.debug("Starting custom snapshot event processing for loan with id [{}], account number [{}], external Id [{}].",
                            loan.getId(), loan.getAccountNumber(), externalId);

                    if (loan.getRepaymentScheduleInstallments() != null && !loan.getRepaymentScheduleInstallments().isEmpty()) {
                        final LocalDate currentDate = DateUtils.getBusinessLocalDate();
                        boolean shouldPostCustomSnapshotBusinessEvent = false;
                        for (int i = 0; i < loan.getRepaymentScheduleInstallments().size(); i++) {
                            if (loan.getRepaymentScheduleInstallments().get(i).getDueDate().equals(currentDate)
                                    && loan.getRepaymentScheduleInstallments().get(i).isNotFullyPaidOff()) {
                                shouldPostCustomSnapshotBusinessEvent = true;
                            }
                        }

                        if (shouldPostCustomSnapshotBusinessEvent) {
                            ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
                            businessEventNotifierService.notifyPostBusinessEvent(new LoanAccountCustomSnapshotBusinessEvent(loan));
                        }
                    }
                } catch (RuntimeException re) {
                    log.error(
                            "Exception while processing custom snapshot event for loan with Id [{}], account number [{}], external Id [{}].",
                            loan.getId(), loan.getAccountNumber(), externalId, re);
                    throw re;
                } finally {
                    ThreadLocalContextUtil.setActionContext(ActionContext.COB);
                }
            }
        }, duration -> log.debug(
                "Ending custom snapshot event processing for loan with Id [{}], account number [{}], external Id [{}], finished in [{}]ms.",
                loan.getId(), loan.getAccountNumber(), externalId, duration.toMillis()));

        return loan;
    }

    @Override
    public String getEnumStyledName() {
        return "CHECK_DUE_INSTALLMENTS";
    }

    @Override
    public String getHumanReadableName() {
        return "Check Due Installments";
    }

    private boolean usesCustomCobStrategy(Long loanProductId) {
        final String strategyCode = loanProductStrategyReadService.findCobStrategyCode(loanProductId).orElse(null);
        return strategyCode == null || MnzlLoanProductStrategyCodes.COB_MNZL_DUE_INSTALLMENTS.equals(strategyCode);
    }
}
