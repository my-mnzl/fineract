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

import static org.apache.fineract.infrastructure.core.service.DateUtils.getBusinessLocalDate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.ColumnValidator;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.staff.service.StaffReadPlatformService;
import org.apache.fineract.portfolio.accountdetails.service.AccountDetailsReadPlatformService;
import org.apache.fineract.portfolio.calendar.service.CalendarReadPlatformService;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.delinquency.service.DelinquencyReadPlatformService;
import org.apache.fineract.portfolio.floatingrates.service.FloatingRatesReadPlatformService;
import org.apache.fineract.portfolio.fund.service.FundReadPlatformService;
import org.apache.fineract.portfolio.group.service.GroupReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanTransactionMapper;
import org.apache.fineract.portfolio.loanaccount.repository.LoanBuyDownFeeBalanceRepository;
import org.apache.fineract.portfolio.loanaccount.repository.LoanCapitalizedIncomeBalanceRepository;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanForeclosureValidator;
import org.apache.fineract.portfolio.loanaccount.service.InterestRefundServiceDelegate;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargePaidByReadService;
import org.apache.fineract.portfolio.loanaccount.service.LoanMaximumAmountCalculator;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformServiceImpl;
import org.apache.fineract.portfolio.loanaccount.service.LoanRepaymentScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionRelationReadService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanproduct.service.LoanDropdownReadPlatformService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class MnzlLoanReadPlatformServiceImpl extends LoanReadPlatformServiceImpl {

    private final JdbcTemplate jdbcTemplate;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public MnzlLoanReadPlatformServiceImpl(JdbcTemplate jdbcTemplate, PlatformSecurityContext context,
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
            LoanBuyDownFeeBalanceRepository loanBuyDownFeeBalanceRepository, InterestRefundServiceDelegate interestRefundServiceDelegate,
            LoanMaximumAmountCalculator loanMaximumAmountCalculator, LoanRepaymentScheduleService loanRepaymentScheduleService) {
        super(jdbcTemplate, context, loanRepositoryWrapper, applicationCurrencyRepository, loanProductReadPlatformService,
                clientReadPlatformService, groupReadPlatformService, loanDropdownReadPlatformService, fundReadPlatformService,
                chargeReadPlatformService, codeValueReadPlatformService, calendarReadPlatformService, staffReadPlatformService,
                paginationHelper, paymentTypeReadPlatformService, floatingRatesReadPlatformService, loanUtilService,
                configurationDomainService, accountDetailsReadPlatformService, columnValidator, sqlGenerator,
                delinquencyReadPlatformService, loanTransactionRepository, loanChargePaidByReadService, loanTransactionRelationReadService,
                loanForeclosureValidator, loanTransactionMapper, loanTransactionProcessingService, loanBalanceService,
                loanCapitalizedIncomeBalanceRepository, loanBuyDownFeeBalanceRepository, interestRefundServiceDelegate,
                loanMaximumAmountCalculator, loanRepaymentScheduleService);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Collection<Long> fetchLoansForInterestRecalculation() {
        final String sql = """
                SELECT l.id
                FROM m_loan l
                INNER JOIN m_loan_repayment_schedule mr ON mr.loan_id = l.id
                LEFT JOIN m_loan_disbursement_detail dd ON dd.loan_id=l.id AND dd.disbursedon_date IS NULL
                -- for past due interest recalculation
                LEFT JOIN m_loan_recalculation_details rcd ON rcd.loan_id = l.id
                -- For Floating rate changes
                LEFT JOIN m_product_loan_floating_rates pfr ON l.product_id = pfr.loan_product_id
                LEFT JOIN m_floating_rates fr ON pfr.floating_rates_id = fr.id
                LEFT JOIN m_floating_rates_periods frp
                    ON fr.id = frp.floating_rates_id AND frp.from_date <= ?
                    AND ((l.interest_recalcualated_on IS NULL AND l.disbursedon_date < frp.from_date
                        OR l.interest_recalcualated_on < frp.from_date))
                LEFT JOIN m_loan_reschedule_request lrr ON lrr.loan_id = l.id
                -- this is to identify the applicable rates when base rate is changed
                LEFT JOIN m_floating_rates bfr ON bfr.is_base_lending_rate = TRUE
                LEFT JOIN m_floating_rates_periods bfrp ON bfr.id = bfrp.floating_rates_id AND bfrp.created_date >= ?
                WHERE l.loan_status_id IN (?, ?, ?)
                  AND l.is_npa = FALSE
                  AND l.is_charged_off = FALSE
                  AND (dd.is_reversed = FALSE OR dd.is_reversed IS NULL)
                  AND (
                        (l.interest_recalculation_enabled = TRUE
                            AND (l.interest_recalcualated_on IS NULL OR l.interest_recalcualated_on <> ?)
                            AND ((mr.completed_derived IS FALSE AND mr.duedate < ?) OR dd.expected_disburse_date < ?)
                            AND rcd.disallow_interest_calc_on_past_due = FALSE)
                       OR
                        (fr.is_active = TRUE
                            AND frp.is_active = TRUE
                            AND ((l.interest_recalcualated_on IS NULL AND l.disbursedon_date < frp.from_date)
                                OR l.interest_recalcualated_on < frp.from_date
                                OR (bfrp.id IS NOT NULL
                                     AND frp.is_differential_to_base_lending_rate = TRUE
                                     AND frp.from_date >= bfrp.from_date))
                            AND lrr.loan_id IS NULL)
                  )
                GROUP BY l.id
                """;
        try {
            LocalDate currentdate = getBusinessLocalDate();
            LocalDate yesterday = getBusinessLocalDate().minusDays(1);
            return this.jdbcTemplate.queryForList(sql, Long.class, currentdate, yesterday,
                    LoanStatus.SUBMITTED_AND_PENDING_APPROVAL.getValue(), LoanStatus.APPROVED.getValue(), LoanStatus.ACTIVE.getValue(),
                    currentdate, currentdate, currentdate);
        } catch (final EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<Long> fetchLoansForInterestRecalculation(Integer pageSize, Long maxLoanIdInList, String officeHierarchy) {
        LocalDate currentdate = getBusinessLocalDate();
        LocalDate yesterday = getBusinessLocalDate().minusDays(1);
        final String sql = """
                SELECT l.id
                FROM m_loan l
                LEFT JOIN m_client c ON c.id = l.client_id
                LEFT JOIN m_office o ON c.office_id = o.id
                INNER JOIN m_loan_repayment_schedule rps ON rps.loan_id = l.id
                LEFT JOIN m_loan_disbursement_detail dd
                    ON dd.loan_id=l.id AND dd.disbursedon_date IS NULL AND dd.is_reversed = FALSE
                -- for past due interest recalculation
                LEFT JOIN m_loan_recalculation_details rcd ON rcd.loan_id = l.id
                -- For Floating rate changes
                LEFT JOIN m_product_loan_floating_rates pfr ON l.product_id = pfr.loan_product_id
                LEFT JOIN m_floating_rates fr ON pfr.floating_rates_id = fr.id
                LEFT JOIN m_floating_rates_periods frp
                    ON fr.id = frp.floating_rates_id AND frp.from_date <= ?
                    AND ((l.interest_recalcualated_on IS NULL AND l.disbursedon_date < frp.from_date
                        OR l.interest_recalcualated_on < frp.from_date))
                LEFT JOIN m_loan_reschedule_request lrr ON lrr.loan_id = l.id
                -- this is to identify the applicable rates when base rate is changed
                LEFT JOIN m_floating_rates bfr ON bfr.is_base_lending_rate = TRUE
                LEFT JOIN m_floating_rates_periods bfrp ON bfr.id = bfrp.floating_rates_id AND bfrp.created_date >= ?
                WHERE l.loan_status_id IN (?, ?, ?)
                    AND l.is_npa = FALSE
                    AND l.is_charged_off = FALSE
                    AND (
                         (l.interest_recalculation_enabled = TRUE
                             AND (l.interest_recalcualated_on IS NULL OR l.interest_recalcualated_on <> ?)
                             AND ((rps.completed_derived IS FALSE AND rps.duedate < ?) OR dd.expected_disburse_date < ?)
                             AND rcd.disallow_interest_calc_on_past_due = FALSE)
                        OR
                         (fr.is_active = TRUE
                             AND frp.is_active = TRUE
                             AND ((l.interest_recalcualated_on IS NULL AND l.disbursedon_date < frp.from_date)
                                  OR l.interest_recalcualated_on < frp.from_date
                                  OR (bfrp.id IS NOT NULL
                                      AND frp.is_differential_to_base_lending_rate = TRUE
                                      AND frp.from_date >= bfrp.from_date))
                             AND lrr.loan_id IS NULL)
                    )
                    AND l.id >= ?
                    AND o.hierarchy like ?
                GROUP BY l.id
                LIMIT ?
                """;
        try {
            return Collections.synchronizedList(this.jdbcTemplate.queryForList(sql, Long.class, currentdate, yesterday,
                    LoanStatus.SUBMITTED_AND_PENDING_APPROVAL.getValue(), LoanStatus.APPROVED.getValue(), LoanStatus.ACTIVE.getValue(),
                    currentdate, currentdate, currentdate, maxLoanIdInList, officeHierarchy, pageSize));
        } catch (final EmptyResultDataAccessException e) {
            return null;
        }
    }
}
