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
package co.mnzl.fineract.custom.loan.schedule;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.CumulativeFlatInterestLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultLoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleSelectionContext;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ProgressiveLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnProperty(name = "mnzl.loan.schedule.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class CustomLoanScheduleGeneratorFactory implements LoanScheduleGeneratorFactory {

    private final ProgressiveLoanScheduleGenerator progressiveLoanScheduleGenerator;
    private final CumulativeFlatInterestLoanScheduleGenerator cumulativeFlatInterestLoanScheduleGenerator;
    private final CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator customCumulativeDecliningBalanceInterestLoanScheduleGenerator;
    private final DefaultLoanScheduleGeneratorFactory defaultLoanScheduleGeneratorFactory;
    private final MnzlLoanProductStrategyReadService loanProductStrategyReadService;

    @Override
    public LoanScheduleGenerator create(final LoanScheduleType loanScheduleType, final InterestMethod interestMethod) {
        return switch (loanScheduleType) {
            case CUMULATIVE -> cumulativeLoanScheduleGenerator(interestMethod);
            case PROGRESSIVE -> progressiveLoanScheduleGenerator;
        };
    }

    @Override
    public LoanScheduleGenerator create(final LoanScheduleSelectionContext selectionContext) {
        if (!usesCustomStrategy(selectionContext)) {
            return defaultLoanScheduleGeneratorFactory.create(selectionContext);
        }
        return create(selectionContext.loanScheduleType(), selectionContext.interestMethod());
    }

    private LoanScheduleGenerator cumulativeLoanScheduleGenerator(final InterestMethod interestMethod) {
        return switch (interestMethod) {
            case FLAT -> cumulativeFlatInterestLoanScheduleGenerator;
            case DECLINING_BALANCE -> customCumulativeDecliningBalanceInterestLoanScheduleGenerator;
            case INVALID -> null;
        };
    }

    private boolean usesCustomStrategy(final LoanScheduleSelectionContext selectionContext) {
        if (selectionContext == null) {
            return false;
        }
        String scheduleStrategyCode = selectionContext.scheduleStrategyCode();
        if (scheduleStrategyCode == null && selectionContext.loanProductId() != null) {
            scheduleStrategyCode = loanProductStrategyReadService.findScheduleStrategyCode(selectionContext.loanProductId()).orElse(null);
        }
        return MnzlLoanProductStrategyCodes.SCHEDULE_MNZL_DECLINING_BALANCE.equals(scheduleStrategyCode);
    }
}
