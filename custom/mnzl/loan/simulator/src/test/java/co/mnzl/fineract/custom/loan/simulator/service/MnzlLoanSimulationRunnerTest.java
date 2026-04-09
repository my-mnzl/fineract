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
package co.mnzl.fineract.custom.loan.simulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.simulator.data.SimulationActionRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationActionType;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.service.InlineExecutorService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MnzlLoanSimulationRunnerTest {

    private static final Long LOAN_ID = 42L;
    private static final Long CLIENT_ID = 99L;

    @Mock
    private PortfolioCommandSourceWritePlatformService commandService;
    @Mock
    private LoanRepositoryWrapper loanRepositoryWrapper;
    @Mock
    private InlineExecutorService<Long> inlineLoanCOBExecutorService;
    @Mock
    private PlatformSecurityContext securityContext;
    @Mock
    private AppUser appUser;
    @Mock
    private Office office;
    @Mock
    private Loan loan;
    @Mock
    private LoanSummary loanSummary;
    @Mock
    private CommandProcessingResult commandResult;

    private MnzlLoanSimulationRunner runner;
    private HashMap<BusinessDateType, LocalDate> originalDates;

    @BeforeEach
    void setUp() {
        runner = new MnzlLoanSimulationRunner(commandService, loanRepositoryWrapper, inlineLoanCOBExecutorService, securityContext);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        originalDates = new HashMap<>();
        originalDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 1));
        originalDates.put(BusinessDateType.COB_DATE, LocalDate.of(2025, 12, 31));
        ThreadLocalContextUtil.setBusinessDates(originalDates);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void successfulSimulationCleansUpLoan() {
        setupCommandService();
        setupLoanMock(false, true);

        SimulationResult result = runner.run(buildSimpleRequest());

        assertThat(result.getStatus()).isEqualTo(SimulationStatus.COMPLETED);
        assertThat(result.getSnapshots()).hasSize(1);
    }

    @Test
    void simulationWithWriteOffUndoesWriteOffDuringCleanup() {
        setupCommandService();
        when(loanRepositoryWrapper.findOneWithNotFoundDetection(LOAN_ID)).thenReturn(loan);
        setupLoanSummaryMock();
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(Collections.emptyList());
        lenient().when(loan.getLoanTransactions()).thenReturn(Collections.emptyList());
        lenient().when(loan.getApprovedPrincipal()).thenReturn(BigDecimal.valueOf(100000));

        // During cleanup: first call -> written-off (triggers undo), after undo -> not written-off
        when(loan.isClosedWrittenOff()).thenReturn(true, false);
        when(loan.isOpen()).thenReturn(true);
        lenient().when(loan.isApproved()).thenReturn(true);

        SimulationRequest request = SimulationRequest.builder().name("Write-off test").loanProductId(1L)
                .principal(BigDecimal.valueOf(100000)).interestRatePerPeriod(BigDecimal.valueOf(12)).numberOfRepayments(12)
                .disbursementDate("2026-01-01")
                .actions(List.of(
                        SimulationActionRequest.builder().type(SimulationActionType.DISBURSE).date(LocalDate.of(2026, 1, 1)).build(),
                        SimulationActionRequest.builder().type(SimulationActionType.WRITE_OFF).date(LocalDate.of(2026, 12, 1)).build()))
                .build();

        SimulationResult result = runner.run(request);

        assertThat(result.getStatus()).isEqualTo(SimulationStatus.COMPLETED);
        assertThat(result.getSnapshots()).hasSize(2);
    }

    @Test
    void businessDatesRestoredOnFailure() {
        setupSecurityContext();
        when(commandService.logCommandSource(any(CommandWrapper.class))).thenReturn(commandResult) // client creation
                                                                                                   // succeeds
                .thenThrow(new RuntimeException("Loan creation failed"));
        lenient().when(commandResult.getClientId()).thenReturn(CLIENT_ID);

        SimulationResult result = runner.run(buildSimpleRequest());

        assertThat(result.getStatus()).isEqualTo(SimulationStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("Loan creation failed");

        HashMap<BusinessDateType, LocalDate> currentDates = ThreadLocalContextUtil.getBusinessDates();
        assertThat(currentDates.get(BusinessDateType.BUSINESS_DATE)).isEqualTo(originalDates.get(BusinessDateType.BUSINESS_DATE));
    }

    private SimulationRequest buildSimpleRequest() {
        return SimulationRequest.builder().name("Test simulation").loanProductId(1L).principal(BigDecimal.valueOf(100000))
                .interestRatePerPeriod(BigDecimal.valueOf(12)).numberOfRepayments(12).disbursementDate("2026-01-01")
                .actions(List
                        .of(SimulationActionRequest.builder().type(SimulationActionType.DISBURSE).date(LocalDate.of(2026, 1, 1)).build()))
                .build();
    }

    private void setupSecurityContext() {
        lenient().when(securityContext.authenticatedUser()).thenReturn(appUser);
        lenient().when(appUser.getOffice()).thenReturn(office);
        lenient().when(office.getId()).thenReturn(1L);
    }

    private void setupCommandService() {
        setupSecurityContext();
        lenient().when(commandResult.getClientId()).thenReturn(CLIENT_ID);
        lenient().when(commandResult.getLoanId()).thenReturn(LOAN_ID);
        lenient().when(commandService.logCommandSource(any(CommandWrapper.class))).thenReturn(commandResult);
    }

    private void setupLoanMock(boolean closedWrittenOff, boolean open) {
        when(loanRepositoryWrapper.findOneWithNotFoundDetection(LOAN_ID)).thenReturn(loan);
        lenient().when(loan.isClosedWrittenOff()).thenReturn(closedWrittenOff);
        lenient().when(loan.isOpen()).thenReturn(open);
        lenient().when(loan.isApproved()).thenReturn(true);
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(Collections.emptyList());
        lenient().when(loan.getLoanTransactions()).thenReturn(Collections.emptyList());
        lenient().when(loan.getApprovedPrincipal()).thenReturn(BigDecimal.valueOf(100000));
        setupLoanSummaryMock();
    }

    private void setupLoanSummaryMock() {
        lenient().when(loan.getSummary()).thenReturn(loanSummary);
        lenient().when(loanSummary.getTotalPrincipalOutstanding()).thenReturn(BigDecimal.valueOf(100000));
        lenient().when(loanSummary.getTotalInterestOutstanding()).thenReturn(BigDecimal.valueOf(14400));
        lenient().when(loanSummary.getTotalFeeChargesOutstanding()).thenReturn(BigDecimal.ZERO);
        lenient().when(loanSummary.getTotalPenaltyChargesOutstanding()).thenReturn(BigDecimal.ZERO);
        lenient().when(loanSummary.getTotalOutstanding()).thenReturn(BigDecimal.valueOf(114400));
    }
}
