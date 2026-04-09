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

import co.mnzl.fineract.custom.loan.simulator.data.SimulationActionRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationSnapshot;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.service.InlineExecutorService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a loan simulation by stepping through a list of actions (disburse, pay, run COB, add charge, write off),
 * overriding the ThreadLocal business date for each step, and capturing a snapshot of the loan state after each action.
 *
 * The runner creates a real loan via the Fineract command pipeline, executes all actions, captures results, then
 * reverses and deletes the loan so nothing persists except the simulation record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mnzl.loan.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class MnzlLoanSimulationRunner {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DATETIME_PATTERN = "yyyy-MM-dd";
    private static final String LOCALE = "en";
    private static final String SIMULATED_CLIENT_ACTIVATION_DATE = "2020-01-01";

    private final PortfolioCommandSourceWritePlatformService commandService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final InlineExecutorService<Long> inlineLoanCOBExecutorService;
    private final PlatformSecurityContext securityContext;

    public SimulationResult run(SimulationRequest request) {
        List<SimulationSnapshot> snapshots = new ArrayList<>();
        HashMap<BusinessDateType, LocalDate> originalDates = ThreadLocalContextUtil.getBusinessDates();
        Long clientId = null;
        Long loanId = null;

        try {
            // 1. Create a test client
            clientId = createSimulatedClient();
            log.info("Simulation: created test client {}", clientId);

            // 2. Create the loan application
            loanId = createLoanApplication(request, clientId);
            log.info("Simulation: created loan application {}", loanId);

            // 3. Execute each action
            for (int i = 0; i < request.getActions().size(); i++) {
                SimulationActionRequest action = request.getActions().get(i);
                setBusinessDate(action.getDate());

                switch (action.getType()) {
                    case DISBURSE -> disburseLoan(loanId, action);
                    case PAY -> makeRepayment(loanId, action);
                    case SKIP -> log.info("Simulation: skipping to {}", action.getDate());
                    case RUN_COB -> runCob(loanId);
                    case ADD_CHARGE -> addCharge(loanId, action);
                    case WRITE_OFF -> writeOff(loanId, action);
                    case CHANGE_INTEREST_RATE -> changeInterestRate(loanId, action);
                }

                snapshots.add(captureSnapshot(loanId, i, action));
                log.info("Simulation: completed action {} ({}) on date {}", i, action.getType(), action.getDate());
            }

            return SimulationResult.builder().uuid(null) // set by the write service
                    .name(request.getName()).status(SimulationStatus.COMPLETED).snapshots(snapshots).build();

        } catch (Exception e) {
            log.error("Simulation failed", e);
            return SimulationResult.builder().uuid(null).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage(e.getMessage()).snapshots(snapshots).build();
        } finally {
            // 4. Cleanup: reverse and delete the simulated loan, then the client
            if (loanId != null) {
                cleanupSimulatedLoan(loanId);
            }
            if (clientId != null) {
                cleanupSimulatedClient(clientId);
            }
            // 5. Restore original business dates
            ThreadLocalContextUtil.setBusinessDates(originalDates);
        }
    }

    private Long createSimulatedClient() {
        setBusinessDate(LocalDate.parse(SIMULATED_CLIENT_ACTIVATION_DATE, DATE_FORMAT));

        Long officeId = securityContext.authenticatedUser().getOffice().getId();

        JsonObject json = new JsonObject();
        json.addProperty("officeId", officeId);
        json.addProperty("fullname", "Simulation Test Client");
        json.addProperty("active", true);
        json.addProperty("activationDate", SIMULATED_CLIENT_ACTIVATION_DATE);
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);

        CommandWrapper command = new CommandWrapperBuilder().createClient().withJson(json.toString()).build();
        CommandProcessingResult result = commandService.logCommandSource(command);
        return result.getClientId();
    }

    private void cleanupSimulatedClient(Long clientId) {
        try {
            CommandWrapper command = new CommandWrapperBuilder().deleteClient(clientId).build();
            commandService.logCommandSource(command);
            log.info("Simulation: cleaned up client {}", clientId);
        } catch (Exception e) {
            log.warn("Simulation: failed to cleanup client {} — manual cleanup may be needed", clientId, e);
        }
    }

    private Long createLoanApplication(SimulationRequest request, Long clientId) {
        setBusinessDate(LocalDate.parse(request.getEffectiveSubmittedOnDate(), DATE_FORMAT));

        JsonObject json = new JsonObject();
        json.addProperty("clientId", clientId);
        json.addProperty("productId", request.getLoanProductId());
        json.addProperty("principal", request.getPrincipal());
        json.addProperty("loanTermFrequency", request.getNumberOfRepayments());
        json.addProperty("loanTermFrequencyType", 2); // MONTHS
        json.addProperty("numberOfRepayments", request.getNumberOfRepayments());
        json.addProperty("repaymentEvery", 1);
        json.addProperty("repaymentFrequencyType", 2); // MONTHS
        json.addProperty("interestRatePerPeriod", request.getInterestRatePerPeriod());
        json.addProperty("amortizationType", 1); // EQUAL_INSTALLMENTS
        json.addProperty("interestType", 0); // DECLINING_BALANCE
        json.addProperty("interestCalculationPeriodType", 1); // SAME_AS_REPAYMENT_PERIOD
        json.addProperty("expectedDisbursementDate", request.getDisbursementDate());
        json.addProperty("submittedOnDate", request.getEffectiveSubmittedOnDate());
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);
        json.addProperty("loanType", "individual");
        if (request.getInterestChargedFromDate() != null) {
            json.addProperty("interestChargedFromDate", request.getInterestChargedFromDate());
        }

        CommandWrapper command = new CommandWrapperBuilder().createLoanApplication().withJson(json.toString()).build();
        CommandProcessingResult result = commandService.logCommandSource(command);
        Long loanId = result.getLoanId();

        // Approve
        setBusinessDate(LocalDate.parse(request.getEffectiveApprovedOnDate(), DATE_FORMAT));

        JsonObject approveJson = new JsonObject();
        approveJson.addProperty("approvedOnDate", request.getEffectiveApprovedOnDate());
        approveJson.addProperty("approvedLoanAmount", request.getPrincipal());
        approveJson.addProperty("dateFormat", DATETIME_PATTERN);
        approveJson.addProperty("locale", LOCALE);

        CommandWrapper approveCommand = new CommandWrapperBuilder().approveLoanApplication(loanId).withJson(approveJson.toString()).build();
        commandService.logCommandSource(approveCommand);

        return loanId;
    }

    private void disburseLoan(Long loanId, SimulationActionRequest action) {
        JsonObject json = new JsonObject();
        json.addProperty("actualDisbursementDate", action.getDate().format(DATE_FORMAT));
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);

        CommandWrapper command = new CommandWrapperBuilder().disburseLoanApplication(loanId).withJson(json.toString()).build();
        commandService.logCommandSource(command);
    }

    private void makeRepayment(Long loanId, SimulationActionRequest action) {
        JsonObject json = new JsonObject();
        json.addProperty("transactionDate", action.getDate().format(DATE_FORMAT));
        json.addProperty("transactionAmount", action.getAmount());
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);
        json.addProperty("paymentTypeId", 1);

        CommandWrapper command = new CommandWrapperBuilder().loanRepaymentTransaction(loanId).withJson(json.toString()).build();
        commandService.logCommandSource(command);
    }

    private void runCob(Long loanId) {
        inlineLoanCOBExecutorService.execute(List.of(loanId), "INLINE_LOAN_COB");
    }

    private void addCharge(Long loanId, SimulationActionRequest action) {
        JsonObject json = new JsonObject();
        json.addProperty("chargeId", action.getChargeId());
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);
        if (action.getAmount() != null) {
            json.addProperty("amount", action.getAmount());
        }

        CommandWrapper command = new CommandWrapperBuilder().createLoanCharge(loanId).withJson(json.toString()).build();
        commandService.logCommandSource(command);
    }

    private void writeOff(Long loanId, SimulationActionRequest action) {
        JsonObject json = new JsonObject();
        json.addProperty("transactionDate", action.getDate().format(DATE_FORMAT));
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);

        CommandWrapper command = new CommandWrapperBuilder().writeOffLoanTransaction(loanId).withJson(json.toString()).build();
        commandService.logCommandSource(command);
    }

    private void changeInterestRate(Long loanId, SimulationActionRequest action) {
        JsonObject exception = new JsonObject();
        exception.addProperty("termVariationType", 2); // INTEREST_RATE
        exception.addProperty("termVariationApplicableFrom", action.getDate().format(DATE_FORMAT));
        exception.addProperty("decimalValue", action.getRate());
        exception.addProperty("isSpecificToInstallment", false);

        JsonArray exceptions = new JsonArray();
        exceptions.add(exception);

        JsonObject json = new JsonObject();
        json.add("exceptions", exceptions);
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);

        CommandWrapper command = new CommandWrapperBuilder().createScheduleExceptions(loanId).withJson(json.toString()).build();
        commandService.logCommandSource(command);
        log.info("Simulation: changed interest rate to {} on {}", action.getRate(), action.getDate());
    }

    private SimulationSnapshot captureSnapshot(Long loanId, int actionIndex, SimulationActionRequest action) {
        Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);

        List<SimulationSnapshot.SchedulePeriod> schedulePeriods = new ArrayList<>();
        for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (installment.isDownPayment() || installment.isAdditional()) {
                continue;
            }
            BigDecimal principalOut = safeSubtract(installment.getPrincipal(), installment.getPrincipalCompleted(),
                    installment.getPrincipalWrittenOff());
            BigDecimal interestOut = safeSubtract(installment.getInterestCharged(), installment.getInterestPaid(),
                    installment.getInterestWaived(), installment.getInterestWrittenOff());
            BigDecimal totalDue = safe(installment.getPrincipal()).add(safe(installment.getInterestCharged()))
                    .add(safe(installment.getFeeChargesCharged())).add(safe(installment.getPenaltyCharges()));
            BigDecimal totalOut = principalOut.add(interestOut)
                    .add(safeSubtract(installment.getFeeChargesCharged(), installment.getFeeChargesPaid(),
                            installment.getFeeChargesWaived(), installment.getFeeChargesWrittenOff()))
                    .add(safeSubtract(installment.getPenaltyCharges(), installment.getPenaltyChargesPaid(),
                            installment.getPenaltyChargesWaived(), installment.getPenaltyChargesWrittenOff()));

            schedulePeriods.add(SimulationSnapshot.SchedulePeriod.builder().period(installment.getInstallmentNumber())
                    .dueDate(installment.getDueDate()).principalDue(installment.getPrincipal())
                    .interestDue(installment.getInterestCharged()).feeChargesDue(installment.getFeeChargesCharged())
                    .penaltyChargesDue(installment.getPenaltyCharges()).totalDue(totalDue).principalOutstanding(principalOut)
                    .interestOutstanding(interestOut).totalOutstanding(totalOut).build());
        }

        SimulationSnapshot.Summary summary = SimulationSnapshot.Summary.builder().principalDisbursed(loan.getApprovedPrincipal())
                .principalOutstanding(loan.getSummary().getTotalPrincipalOutstanding())
                .interestOutstanding(loan.getSummary().getTotalInterestOutstanding())
                .feeChargesOutstanding(loan.getSummary().getTotalFeeChargesOutstanding())
                .penaltyChargesOutstanding(loan.getSummary().getTotalPenaltyChargesOutstanding())
                .totalOutstanding(loan.getSummary().getTotalOutstanding()).build();

        List<SimulationSnapshot.Transaction> transactions = new ArrayList<>();
        List<LoanTransaction> loanTransactions = loan.getLoanTransactions();
        if (loanTransactions != null) {
            for (LoanTransaction tx : loanTransactions) {
                if (tx.isReversed()) {
                    continue;
                }
                transactions.add(SimulationSnapshot.Transaction.builder().type(tx.getTypeOf().name()).date(tx.getTransactionDate())
                        .amount(tx.getAmount()).principalPortion(tx.getPrincipalPortion()).interestPortion(tx.getInterestPortion())
                        .feePortion(tx.getFeeChargesPortion()).penaltyPortion(tx.getPenaltyChargesPortion()).build());
            }
        }

        return SimulationSnapshot.builder().actionIndex(actionIndex).action(action).schedule(schedulePeriods).summary(summary)
                .transactions(transactions).build();
    }

    private static BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal safeSubtract(BigDecimal total, BigDecimal... deductions) {
        BigDecimal result = safe(total);
        for (BigDecimal d : deductions) {
            result = result.subtract(safe(d));
        }
        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
    }

    private void setBusinessDate(LocalDate date) {
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, date);
        dates.put(BusinessDateType.COB_DATE, date.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);
    }

    private void cleanupSimulatedLoan(Long loanId) {
        try {
            Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);

            // Undo write-off first if the loan was written off
            if (loan.isClosedWrittenOff()) {
                CommandWrapper undoWriteOff = new CommandWrapperBuilder().undoWriteOffLoanTransaction(loanId).withJson("{}").build();
                commandService.logCommandSource(undoWriteOff);
                loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);
            }

            // Undo disbursement if disbursed
            if (loan.isOpen()) {
                JsonObject undoJson = new JsonObject();
                undoJson.addProperty("dateFormat", DATETIME_PATTERN);
                undoJson.addProperty("locale", LOCALE);
                undoJson.addProperty("note", "Simulation cleanup");
                CommandWrapper undoDisburse = new CommandWrapperBuilder().undoLoanApplicationDisbursal(loanId).withJson(undoJson.toString())
                        .build();
                commandService.logCommandSource(undoDisburse);
                loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);
            }

            // Undo approval
            if (loan.isApproved()) {
                JsonObject undoJson = new JsonObject();
                undoJson.addProperty("dateFormat", DATETIME_PATTERN);
                undoJson.addProperty("locale", LOCALE);
                undoJson.addProperty("note", "Simulation cleanup");
                CommandWrapper undoApprove = new CommandWrapperBuilder().undoLoanApplicationApproval(loanId).withJson(undoJson.toString())
                        .build();
                commandService.logCommandSource(undoApprove);
            }

            // Delete the application
            CommandWrapper delete = new CommandWrapperBuilder().deleteLoanApplication(loanId).build();
            commandService.logCommandSource(delete);

            log.info("Simulation: cleaned up loan {}", loanId);
        } catch (Exception e) {
            log.warn("Simulation: failed to cleanup loan {} — manual cleanup may be needed", loanId, e);
        }
    }
}
