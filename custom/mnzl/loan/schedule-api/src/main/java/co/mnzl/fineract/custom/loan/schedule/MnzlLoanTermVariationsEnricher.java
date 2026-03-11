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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateDTO;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanTermVariationsEnricher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mnzl.loan.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlLoanTermVariationsEnricher implements LoanTermVariationsEnricher {

    @Override
    public BigDecimal enrich(FloatingRateDTO floatingRateDTO, BigDecimal annualNominalInterestRate,
            List<LoanTermVariationsData> loanTermVariations, Loan loan) {
        if (floatingRateDTO == null || !loan.getLoanProduct().isLinkedToFloatingInterestRate()) {
            return annualNominalInterestRate;
        }

        floatingRateDTO.resetInterestRateDiff();
        Collection<FloatingRatePeriodData> applicableRates = loan.getLoanProduct().fetchInterestRates(floatingRateDTO);
        LocalDate today = DateUtils.getBusinessLocalDate();
        LocalDate latestStartDate = LocalDate.MIN;
        BigDecimal interestRate = annualNominalInterestRate;
        for (FloatingRatePeriodData periodData : applicableRates) {
            boolean beforeToday = DateUtils.isBefore(periodData.getFromDateAsLocalDate(), today);
            boolean afterLatest = DateUtils.isAfter(periodData.getFromDateAsLocalDate(), latestStartDate);
            if (beforeToday && afterLatest) {
                latestStartDate = periodData.getFromDateAsLocalDate();
                interestRate = periodData.getInterestRate();
            }
        }
        return interestRate;
    }
}
