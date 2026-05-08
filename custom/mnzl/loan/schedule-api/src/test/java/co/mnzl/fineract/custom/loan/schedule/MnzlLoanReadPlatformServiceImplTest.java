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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
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
import org.apache.fineract.portfolio.loanaccount.service.LoanRepaymentScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionRelationReadService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanproduct.service.LoanDropdownReadPlatformService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 unit tests for {@link MnzlLoanReadPlatformServiceImpl} (Task C.12).
 *
 * <p>
 * The two overrides ({@code fetchLoansForInterestRecalculation()} and the paged variant) execute hand-rolled SQL via
 * {@link JdbcTemplate#queryForList(String, Class, Object...)}. We mock the JdbcTemplate, capture the positional
 * parameters with an {@link ArgumentCaptor}, and assert the production code forwards the right values (status codes,
 * business date, pagination cursor, office hierarchy) without re-asserting the SQL text byte-for-byte.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanReadPlatformServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MnzlLoanReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, TODAY);
        dates.put(BusinessDateType.COB_DATE, TODAY.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);

        service = newService();
    }

    @Test
    void fetchLoansForInterestRecalculation_eligibleLoanReturned() {
        // queryForList(String, Class, Object...) — the varargs collapse into a single Object[] argument.
        when(jdbcTemplate.queryForList(any(String.class), eq(Long.class), any(Object[].class))).thenReturn(List.of(101L, 102L));

        Collection<Long> result = service.fetchLoansForInterestRecalculation();

        assertThat(result).containsExactly(101L, 102L);
    }

    @Test
    void fetchLoansForInterestRecalculation_noLoansEligible_emptyList() {
        when(jdbcTemplate.queryForList(any(String.class), eq(Long.class), any(Object[].class))).thenReturn(List.of());

        Collection<Long> result = service.fetchLoansForInterestRecalculation();

        assertThat(result).isEmpty();
    }

    @Test
    void fetchLoansForInterestRecalculation_passesBusinessDateAndStatusCodes() {
        // The unpaged variant binds: today, yesterday, three loan-status codes (submitted/approved/active),
        // then today three more times for due-date / disburse-date / interest-recalc-on comparisons.
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForList(sqlCaptor.capture(), eq(Long.class), argsCaptor.capture())).thenReturn(List.of());

        service.fetchLoansForInterestRecalculation();

        // Spot-check the SQL hits the recalculation joins.
        assertThat(sqlCaptor.getValue()).contains("m_loan_repayment_schedule").contains("m_floating_rates_periods")
                .contains("interest_recalculation_enabled");
        Object[] args = argsCaptor.getValue();
        assertThat(args).hasSize(8);
        // First two params: today, yesterday.
        assertThat(args[0]).isEqualTo(TODAY);
        assertThat(args[1]).isEqualTo(TODAY.minusDays(1));
        // Status codes: submitted-and-pending-approval, approved, active.
        assertThat(args[2]).isEqualTo(LoanStatus.SUBMITTED_AND_PENDING_APPROVAL.getValue());
        assertThat(args[3]).isEqualTo(LoanStatus.APPROVED.getValue());
        assertThat(args[4]).isEqualTo(LoanStatus.ACTIVE.getValue());
        // Trailing three are today repeated for the comparison clauses.
        assertThat(args[5]).isEqualTo(TODAY);
        assertThat(args[6]).isEqualTo(TODAY);
        assertThat(args[7]).isEqualTo(TODAY);
    }

    @Test
    void fetchLoansForInterestRecalculation_paginationBoundaries_pagedVariant() {
        // Paged variant adds three trailing params: maxLoanIdInList, officeHierarchy, pageSize.
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForList(sqlCaptor.capture(), eq(Long.class), argsCaptor.capture())).thenReturn(List.of(501L));

        List<Long> result = service.fetchLoansForInterestRecalculation(50, 500L, ".%.");

        assertThat(result).containsExactly(501L);
        assertThat(sqlCaptor.getValue()).contains("LIMIT").contains("o.hierarchy like").contains("l.id >= ?");
        Object[] args = argsCaptor.getValue();
        // Tail of the param list: maxLoanIdInList, officeHierarchy, pageSize.
        assertThat(args[args.length - 3]).isEqualTo(500L);
        assertThat(args[args.length - 2]).isEqualTo(".%.");
        assertThat(args[args.length - 1]).isEqualTo(50);
    }

    @Test
    void fetchLoansForInterestRecalculation_officeHierarchyFilter_pagedVariant() {
        // Different hierarchy strings must propagate verbatim to the JdbcTemplate call.
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForList(any(String.class), eq(Long.class), argsCaptor.capture())).thenReturn(List.of());

        service.fetchLoansForInterestRecalculation(25, 0L, ".branch-7.%");

        Object[] args = argsCaptor.getValue();
        assertThat(args[args.length - 2]).isEqualTo(".branch-7.%");
    }

    @Test
    void fetchLoansForInterestRecalculation_pagedVariantStatusCodesAndBusinessDate() {
        // Independent verification that the paged query forwards the same status / date params as the unpaged one.
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForList(any(String.class), eq(Long.class), argsCaptor.capture())).thenReturn(List.of());

        service.fetchLoansForInterestRecalculation(10, 0L, ".%");

        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo(TODAY);
        assertThat(args[1]).isEqualTo(TODAY.minusDays(1));
        assertThat(args[2]).isEqualTo(LoanStatus.SUBMITTED_AND_PENDING_APPROVAL.getValue());
        assertThat(args[3]).isEqualTo(LoanStatus.APPROVED.getValue());
        assertThat(args[4]).isEqualTo(LoanStatus.ACTIVE.getValue());
    }

    private MnzlLoanReadPlatformServiceImpl newService() {
        return new MnzlLoanReadPlatformServiceImpl(jdbcTemplate, mock(PlatformSecurityContext.class), mock(LoanRepositoryWrapper.class),
                mock(ApplicationCurrencyRepositoryWrapper.class), mock(LoanProductReadPlatformService.class),
                mock(ClientReadPlatformService.class), mock(GroupReadPlatformService.class), mock(LoanDropdownReadPlatformService.class),
                mock(FundReadPlatformService.class), mock(ChargeReadPlatformService.class), mock(CodeValueReadPlatformService.class),
                mock(CalendarReadPlatformService.class), mock(StaffReadPlatformService.class), mock(PaginationHelper.class),
                mock(PaymentTypeReadPlatformService.class), mock(FloatingRatesReadPlatformService.class), mock(LoanUtilService.class),
                mock(ConfigurationDomainService.class), mock(AccountDetailsReadPlatformService.class), mock(ColumnValidator.class),
                mock(DatabaseSpecificSQLGenerator.class), mock(DelinquencyReadPlatformService.class), mock(LoanTransactionRepository.class),
                mock(LoanChargePaidByReadService.class), mock(LoanTransactionRelationReadService.class),
                mock(LoanForeclosureValidator.class), mock(LoanTransactionMapper.class), mock(LoanTransactionProcessingService.class),
                mock(LoanBalanceService.class), mock(LoanCapitalizedIncomeBalanceRepository.class),
                mock(LoanBuyDownFeeBalanceRepository.class), mock(InterestRefundServiceDelegate.class),
                mock(LoanMaximumAmountCalculator.class), mock(LoanRepaymentScheduleService.class));
    }
}
