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

import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.ColumnValidator;
import org.apache.fineract.organisation.holiday.domain.HolidayRepository;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.staff.service.StaffReadPlatformService;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.accountdetails.service.AccountDetailsReadPlatformService;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstanceRepository;
import org.apache.fineract.portfolio.calendar.service.CalendarReadPlatformService;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.delinquency.service.DelinquencyReadPlatformService;
import org.apache.fineract.portfolio.floatingrates.service.FloatingRatesReadPlatformService;
import org.apache.fineract.portfolio.fund.service.FundReadPlatformService;
import org.apache.fineract.portfolio.group.service.GroupReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanTransactionMapper;
import org.apache.fineract.portfolio.loanaccount.repository.LoanBuyDownFeeBalanceRepository;
import org.apache.fineract.portfolio.loanaccount.repository.LoanCapitalizedIncomeBalanceRepository;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanForeclosureValidator;
import org.apache.fineract.portfolio.loanaccount.service.InterestRefundServiceDelegate;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargePaidByReadService;
import org.apache.fineract.portfolio.loanaccount.service.LoanMaximumAmountCalculator;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanRepaymentScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionRelationReadService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanproduct.service.LoanDropdownReadPlatformService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "mnzl.loan.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlLoanScheduleApiConfiguration {

    @Bean
    @Primary
    public LoanUtilService mnzlLoanUtilService(ApplicationCurrencyRepositoryWrapper applicationCurrencyRepository,
            CalendarInstanceRepository calendarInstanceRepository, ConfigurationDomainService configurationDomainService,
            HolidayRepository holidayRepository, WorkingDaysRepositoryWrapper workingDaysRepository,
            LoanScheduleGeneratorFactory loanScheduleFactory, FloatingRatesReadPlatformService floatingRatesReadPlatformService,
            CalendarReadPlatformService calendarReadPlatformService, NoteRepository noteRepository) {
        return new MnzlLoanUtilService(applicationCurrencyRepository, calendarInstanceRepository, configurationDomainService,
                holidayRepository, workingDaysRepository, loanScheduleFactory, floatingRatesReadPlatformService,
                calendarReadPlatformService, noteRepository);
    }

    @Bean
    @Primary
    @SuppressWarnings("checkstyle:ParameterNumber")
    public LoanReadPlatformService mnzlLoanReadPlatformService(JdbcTemplate jdbcTemplate, PlatformSecurityContext context,
            LoanRepositoryWrapper loanRepositoryWrapper, ApplicationCurrencyRepositoryWrapper applicationCurrencyRepository,
            LoanProductReadPlatformService loanProductReadPlatformService, ClientReadPlatformService clientReadPlatformService,
            GroupReadPlatformService groupReadPlatformService, LoanDropdownReadPlatformService loanDropdownReadPlatformService,
            FundReadPlatformService fundReadPlatformService, ChargeReadPlatformService chargeReadPlatformService,
            CodeValueReadPlatformService codeValueReadPlatformService, CalendarReadPlatformService calendarReadPlatformService,
            StaffReadPlatformService staffReadPlatformService, PaginationHelper paginationHelper,
            PaymentTypeReadPlatformService paymentTypeReadPlatformService,
            FloatingRatesReadPlatformService floatingRatesReadPlatformService, LoanUtilService loanUtilService,
            ConfigurationDomainService configurationDomainService, AccountDetailsReadPlatformService accountDetailsReadPlatformService,
            ColumnValidator columnValidator, DatabaseSpecificSQLGenerator sqlGenerator,
            DelinquencyReadPlatformService delinquencyReadPlatformService, LoanTransactionRepository loanTransactionRepository,
            LoanChargePaidByReadService loanChargePaidByReadService, LoanTransactionRelationReadService loanTransactionRelationReadService,
            LoanForeclosureValidator loanForeclosureValidator, LoanTransactionMapper loanTransactionMapper,
            LoanTransactionProcessingService loanTransactionProcessingService, LoanBalanceService loanBalanceService,
            LoanCapitalizedIncomeBalanceRepository loanCapitalizedIncomeBalanceRepository,
            LoanBuyDownFeeBalanceRepository loanBuyDownFeeBalanceRepository,
            @Lazy InterestRefundServiceDelegate interestRefundServiceDelegate, LoanMaximumAmountCalculator loanMaximumAmountCalculator,
            LoanRepaymentScheduleService loanRepaymentScheduleService) {
        return new MnzlLoanReadPlatformServiceImpl(jdbcTemplate, context, loanRepositoryWrapper, applicationCurrencyRepository,
                loanProductReadPlatformService, clientReadPlatformService, groupReadPlatformService, loanDropdownReadPlatformService,
                fundReadPlatformService, chargeReadPlatformService, codeValueReadPlatformService, calendarReadPlatformService,
                staffReadPlatformService, paginationHelper, paymentTypeReadPlatformService, floatingRatesReadPlatformService,
                loanUtilService, configurationDomainService, accountDetailsReadPlatformService, columnValidator, sqlGenerator,
                delinquencyReadPlatformService, loanTransactionRepository, loanChargePaidByReadService, loanTransactionRelationReadService,
                loanForeclosureValidator, loanTransactionMapper, loanTransactionProcessingService, loanBalanceService,
                loanCapitalizedIncomeBalanceRepository, loanBuyDownFeeBalanceRepository, interestRefundServiceDelegate,
                loanMaximumAmountCalculator, loanRepaymentScheduleService);
    }
}
