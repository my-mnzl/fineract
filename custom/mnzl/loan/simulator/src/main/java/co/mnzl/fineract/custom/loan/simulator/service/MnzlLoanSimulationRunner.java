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

import co.mnzl.fineract.custom.loan.simulator.data.SchedulePreviewPeriod;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationActionRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationSnapshot;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.service.InlineExecutorService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelPeriod;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleCalculationPlatformService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final IntConsumer NOOP_PROGRESS = progress -> { /* no-op */ };
    private static final String SIMULATED_CLIENT_ACTIVATION_DATE = "2024-08-01";
    private static final BigDecimal INSURANCE_ANNUAL_RATE = new BigDecimal("0.00015");
    private static final BigDecimal INSURANCE_ANNUAL_MINIMUM = new BigDecimal("250");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private final PortfolioCommandSourceWritePlatformService commandService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final InlineExecutorService<Long> inlineLoanCOBExecutorService;
    private final PlatformSecurityContext securityContext;
    private final LoanProductRepository loanProductRepository;
    private final JdbcTemplate jdbcTemplate;
    private final LoanScheduleCalculationPlatformService scheduleCalculationService;
    private final FromJsonHelper fromJsonHelper;

    // Fineract charge id for the monthly insurance premium. When 0 (default),
    // the simulator falls back to looking up a charge by name (see below) so
    // the usual dev DB "just works".
    @Value("${mnzl.loan.simulator.insurance-charge-id:0}")
    private long insuranceChargeId;

    // Name of the insurance charge to auto-discover from m_charge when
    // insuranceChargeId is not explicitly set. Kept configurable for envs
    // that use a different label.
    @Value("${mnzl.loan.simulator.insurance-charge-name:Insurance Fee}")
    private String insuranceChargeName;

    // Savings product id to open the linked savings account against. When 0
    // (default) the runner picks the first active savings product from
    // m_savings_product. A linked savings account is required to attach
    // ACCOUNT_TRANSFER-mode charges such as the insurance fee.
    @Value("${mnzl.loan.simulator.savings-product-id:0}")
    private long savingsProductId;

    public SimulationResult run(SimulationRequest request) {
        return run(request, NOOP_PROGRESS);
    }

    public SimulationResult run(SimulationRequest request, IntConsumer progressCallback) {
        List<SimulationSnapshot> snapshots = new ArrayList<>();
        HashMap<BusinessDateType, LocalDate> originalDates = ThreadLocalContextUtil.getBusinessDates();
        Long clientId = null;
        Long savingsId = null;
        Long loanId = null;

        try {
            // 1. Create a test client
            clientId = createSimulatedClient();
            log.info("Simulation: created test client {}", clientId);

            // 2. Open a linked savings account (needed for ACCOUNT_TRANSFER charges
            // like the insurance fee — mirrors the production origination flow).
            savingsId = createSimulatedSavingsAccount(clientId);
            if (savingsId != null) {
                log.info("Simulation: created linked savings account {}", savingsId);
            }

            // 3. Create the loan application
            loanId = createLoanApplication(request, clientId, savingsId);
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
                progressCallback.accept(i + 1);
                log.info("Simulation: completed action {} ({}) on date {}", i, action.getType(), action.getDate());
            }

            return SimulationResult.builder().uuid(null) // set by the write service
                    .name(request.getName()).status(SimulationStatus.COMPLETED).snapshots(snapshots).build();

        } catch (PlatformApiDataValidationException e) {
            log.error("Simulation validation failed: {}", e.getErrors(), e);
            return SimulationResult.builder().uuid(null).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage(e.getErrors().toString()).snapshots(snapshots).build();
        } catch (Exception e) {
            log.error("Simulation failed", e);
            return SimulationResult.builder().uuid(null).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage(e.getMessage()).snapshots(snapshots).build();
        } finally {
            // Cleanup in reverse creation order: loan → savings → client.
            if (loanId != null) {
                cleanupSimulatedLoan(loanId);
            }
            if (savingsId != null) {
                cleanupSimulatedSavings(savingsId);
            }
            if (clientId != null) {
                cleanupSimulatedClient(clientId);
            }
            ThreadLocalContextUtil.setBusinessDates(originalDates);
        }
    }

    private Long createSimulatedClient() {
        setBusinessDate(LocalDate.parse(SIMULATED_CLIENT_ACTIVATION_DATE, DATE_FORMAT));

        Long officeId = securityContext.authenticatedUser().getOffice().getId();

        JsonObject json = new JsonObject();
        json.addProperty("officeId", officeId);
        json.addProperty("fullname", "Simulation Test Client");
        json.addProperty("legalFormId", 1);
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
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                try (var stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=0");
                }
                try (var ps = con.prepareStatement("DELETE FROM m_note WHERE client_id = ?")) {
                    ps.setLong(1, clientId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_client WHERE id = ?")) {
                    ps.setLong(1, clientId);
                    ps.executeUpdate();
                }
                try (var stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=1");
                }
                return null;
            });
            log.info("Simulation: cleaned up client {}", clientId);
        } catch (Exception e) {
            log.warn("Simulation: failed to cleanup client {} — manual cleanup may be needed", clientId, e);
        }
    }

    /**
     * Open a linked savings account for the simulated client. Mirrors production: create → approve → activate, all
     * dated at the client activation date so the account exists before the loan submission date.
     *
     * Returns null (and logs) when no savings product exists — the simulation continues, but ACCOUNT_TRANSFER-mode
     * charges won't attach.
     */
    private Long createSimulatedSavingsAccount(Long clientId) {
        Long productId = resolveSavingsProductId();
        if (productId == null) {
            log.warn("Simulation: no savings product configured or discoverable — skipping linked savings account");
            return null;
        }

        setBusinessDate(LocalDate.parse(SIMULATED_CLIENT_ACTIVATION_DATE, DATE_FORMAT));

        JsonObject createJson = new JsonObject();
        createJson.addProperty("clientId", clientId);
        createJson.addProperty("productId", productId);
        createJson.addProperty("submittedOnDate", SIMULATED_CLIENT_ACTIVATION_DATE);
        createJson.addProperty("dateFormat", DATETIME_PATTERN);
        createJson.addProperty("locale", LOCALE);

        CommandWrapper create = new CommandWrapperBuilder().createSavingsAccount().withJson(createJson.toString()).build();
        CommandProcessingResult createResult = commandService.logCommandSource(create);
        Long savingsId = createResult.getSavingsId();

        JsonObject approveJson = new JsonObject();
        approveJson.addProperty("approvedOnDate", SIMULATED_CLIENT_ACTIVATION_DATE);
        approveJson.addProperty("dateFormat", DATETIME_PATTERN);
        approveJson.addProperty("locale", LOCALE);
        CommandWrapper approve = new CommandWrapperBuilder().approveSavingsAccountApplication(savingsId).withJson(approveJson.toString())
                .build();
        commandService.logCommandSource(approve);

        JsonObject activateJson = new JsonObject();
        activateJson.addProperty("activatedOnDate", SIMULATED_CLIENT_ACTIVATION_DATE);
        activateJson.addProperty("dateFormat", DATETIME_PATTERN);
        activateJson.addProperty("locale", LOCALE);
        CommandWrapper activate = new CommandWrapperBuilder().savingsAccountActivation(savingsId).withJson(activateJson.toString()).build();
        commandService.logCommandSource(activate);

        return savingsId;
    }

    private Long resolveSavingsProductId() {
        if (savingsProductId > 0) {
            return savingsProductId;
        }
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM m_savings_product ORDER BY id LIMIT 1", Long.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cleanupSimulatedSavings(Long savingsId) {
        try {
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                try (var stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=0");
                }
                String[] children = { "m_note", "m_savings_account_transaction", "m_savings_account_charge",
                        "m_portfolio_account_associations" };
                for (String table : children) {
                    String col = "m_portfolio_account_associations".equals(table) ? "linked_savings_account_id" : "savings_account_id";
                    try (var ps = con.prepareStatement("DELETE FROM " + table + " WHERE " + col + " = ?")) {
                        ps.setLong(1, savingsId);
                        ps.executeUpdate();
                    } catch (Exception ignored) {
                        // table may not exist in this schema — safe to skip
                    }
                }
                try (var ps = con.prepareStatement("DELETE FROM m_savings_account WHERE id = ?")) {
                    ps.setLong(1, savingsId);
                    ps.executeUpdate();
                }
                try (var stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=1");
                }
                return null;
            });
            log.info("Simulation: cleaned up savings account {}", savingsId);
        } catch (Exception e) {
            log.warn("Simulation: failed to cleanup savings {} — manual cleanup may be needed", savingsId, e);
        }
    }

    /**
     * Build the loan-application JSON that Fineract's loan APIs accept. Shared between {@link #createLoanApplication}
     * and schedule preview so both go through the exact same rate / product / date logic.
     *
     * @param clientId
     *            clientId for real applications; null/omitted for previews
     * @param savingsId
     *            linked savings account id; null to skip the linkAccountId field
     */
    JsonObject buildLoanApplicationJson(SimulationRequest request, Long clientId, Long savingsId) {
        LoanProduct loanProduct = loanProductRepository.findById(request.getLoanProductId())
                .orElseThrow(() -> new IllegalArgumentException("Loan product not found: " + request.getLoanProductId()));

        JsonObject json = new JsonObject();
        if (clientId != null) {
            json.addProperty("clientId", clientId);
        }
        json.addProperty("productId", request.getLoanProductId());
        json.addProperty("principal", request.getPrincipal());
        int repaymentEvery = request.getRepaymentEvery() != null ? request.getRepaymentEvery() : 1;
        int frequencyType = request.getRepaymentFrequencyType() != null ? request.getRepaymentFrequencyType() : 2; // default
                                                                                                                   // MONTHS
        json.addProperty("loanTermFrequency", request.getNumberOfRepayments() * repaymentEvery);
        json.addProperty("loanTermFrequencyType", frequencyType);
        json.addProperty("numberOfRepayments", request.getNumberOfRepayments());
        json.addProperty("repaymentEvery", repaymentEvery);
        json.addProperty("repaymentFrequencyType", frequencyType);
        if (loanProduct.isLinkedToFloatingInterestRate()) {
            json.addProperty("isFloatingInterestRate", false);
            BigDecimal differential = request.getInterestRateDifferential() != null ? request.getInterestRateDifferential()
                    : loanProduct.getFloatingRates().getDefaultDifferentialLendingRate();
            json.addProperty("interestRateDifferential", differential);
        } else {
            BigDecimal rate = request.getInterestRatePerPeriod() != null ? request.getInterestRatePerPeriod()
                    : loanProduct.getNominalInterestRatePerPeriod();
            json.addProperty("interestRatePerPeriod", rate);
        }
        json.addProperty("amortizationType", 1); // EQUAL_INSTALLMENTS
        json.addProperty("interestType", 0); // DECLINING_BALANCE
        json.addProperty("interestCalculationPeriodType", 1); // SAME_AS_REPAYMENT_PERIOD
        json.addProperty("transactionProcessingStrategyCode", loanProduct.getTransactionProcessingStrategyCode());
        json.addProperty("expectedDisbursementDate", request.getDisbursementDate());
        json.addProperty("submittedOnDate", request.getEffectiveSubmittedOnDate());
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);
        json.addProperty("loanType", "individual");
        if (request.getInterestChargedFromDate() != null) {
            json.addProperty("interestChargedFromDate", request.getInterestChargedFromDate());
        }
        if (request.getFirstRepaymentOnDate() != null) {
            json.addProperty("repaymentsStartingFromDate", request.getFirstRepaymentOnDate());
        }
        if (savingsId != null) {
            json.addProperty("linkAccountId", savingsId);
        }

        // Bake the insurance charge into the initial schedule so both preview and
        // real loan start life with it. Previously we attached it via a separate
        // command after approval, which worked for the real loan but left the
        // preview (and presets computed from it) short by the monthly premium.
        BigDecimal principal = request.getPrincipal();
        Long insuranceId = resolveInsuranceChargeId();
        if (insuranceId != null && principal != null) {
            BigDecimal annual = principal.multiply(INSURANCE_ANNUAL_RATE).max(INSURANCE_ANNUAL_MINIMUM);
            BigDecimal monthly = annual.divide(TWELVE, 2, RoundingMode.HALF_UP);
            JsonObject charge = new JsonObject();
            charge.addProperty("chargeId", insuranceId);
            charge.addProperty("amount", monthly);
            JsonArray charges = new JsonArray();
            charges.add(charge);
            json.add("charges", charges);
        }
        return json;
    }

    private Long createLoanApplication(SimulationRequest request, Long clientId, Long savingsId) {
        setBusinessDate(LocalDate.parse(request.getEffectiveSubmittedOnDate(), DATE_FORMAT));

        JsonObject json = buildLoanApplicationJson(request, clientId, savingsId);

        log.info("Simulation: loan application JSON = {}", json);
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

        // Insurance charge is now included in the initial loan creation JSON via
        // buildLoanApplicationJson, so no separate attach step is needed here.

        return loanId;
    }

    private Long resolveInsuranceChargeId() {
        if (insuranceChargeId > 0) {
            return insuranceChargeId;
        }
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM m_charge WHERE name = ? AND is_active = 1 LIMIT 1", Long.class,
                    insuranceChargeName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void postLoanCharge(Long loanId, long chargeId, BigDecimal amount, LocalDate dueDate) {
        JsonObject json = new JsonObject();
        json.addProperty("chargeId", chargeId);
        if (amount != null) {
            json.addProperty("amount", amount);
        }
        if (dueDate != null) {
            // OVERDUE_INSTALLMENT, SPECIFIED_DUE_DATE, and LOAN_PERIODIC charge types require a dueDate per
            // LoanChargeService.create. Pass the simulator action's date so the ADD_CHARGE action works for
            // those charge categories instead of failing with "Loan charge is missing due date".
            json.addProperty("dueDate", dueDate.format(DATE_FORMAT));
        }
        json.addProperty("dateFormat", DATETIME_PATTERN);
        json.addProperty("locale", LOCALE);

        CommandWrapper command = new CommandWrapperBuilder().createLoanCharge(loanId).withJson(json.toString()).build();
        commandService.logCommandSource(command);
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
        postLoanCharge(loanId, action.getChargeId(), action.getAmount(), action.getDate());
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
            // Run all deletes on a single connection with FK checks disabled.
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                try (var stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=0");
                }
                try (var ps = con.prepareStatement("DELETE FROM m_loan_transaction WHERE loan_id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_loan_repayment_schedule WHERE loan_id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_loan_charge WHERE loan_id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_loan_disbursement_detail WHERE loan_id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_loan_officer_assignment_history WHERE loan_id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_note WHERE loan_id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var ps = con.prepareStatement("DELETE FROM m_loan WHERE id = ?")) {
                    ps.setLong(1, loanId);
                    ps.executeUpdate();
                }
                try (var stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=1");
                }
                return null;
            });
            log.info("Simulation: cleaned up loan {}", loanId);
        } catch (Exception e) {
            log.warn("Simulation: failed to cleanup loan {} — manual cleanup may be needed", loanId, e);
        }
    }

    /**
     * Calculate the expected repayment schedule for a request without creating a loan. Reuses
     * {@link #buildLoanApplicationJson} so the preview exactly matches what {@link #run} would produce at disbursement
     * time.
     *
     * Used by the frontend to seed presets (Happy Path, etc.) with the exact per-installment amounts the loan will
     * actually bill.
     */
    public List<SchedulePreviewPeriod> previewSchedule(SimulationRequest request) {
        // We have to pass a real clientId here even though preview doesn't persist a loan: upstream
        // LoanScheduleAssembler resolves the loan's office through the client (or group) — when both are
        // absent, officeId stays null and the holiday lookup binds null as a typeless parameter, which
        // MariaDB silently coerces but PostgreSQL rejects with "operator does not exist: bigint = character
        // varying". Creating + cleaning up a short-lived simulated client is the cheapest cross-DB fix.
        HashMap<BusinessDateType, LocalDate> originalDates = ThreadLocalContextUtil.getBusinessDates();
        Long previewClientId = null;
        try {
            previewClientId = createSimulatedClient();
            JsonObject json = buildLoanApplicationJson(request, previewClientId, null);
            String jsonString = json.toString();
            JsonQuery query = JsonQuery.from(jsonString, fromJsonHelper.parse(jsonString), fromJsonHelper);
            LoanScheduleModel model = scheduleCalculationService.calculateLoanSchedule(query, false);

            List<SchedulePreviewPeriod> periods = new ArrayList<>();
            for (LoanScheduleModelPeriod p : model.getPeriods()) {
                if (!p.isRepaymentPeriod()) {
                    continue;
                }
                BigDecimal totalDue = safe(p.principalDue()).add(safe(p.interestDue())).add(safe(p.feeChargesDue()))
                        .add(safe(p.penaltyChargesDue()));
                periods.add(SchedulePreviewPeriod.builder().period(p.periodNumber()).dueDate(p.periodDueDate().format(DATE_FORMAT))
                        .principalDue(safe(p.principalDue())).interestDue(safe(p.interestDue())).feeChargesDue(safe(p.feeChargesDue()))
                        .penaltyChargesDue(safe(p.penaltyChargesDue())).totalDue(totalDue).principalOutstanding(safe(p.principalDue()))
                        .interestOutstanding(safe(p.interestDue())).totalOutstanding(totalDue).build());
            }
            return periods;
        } finally {
            if (previewClientId != null) {
                cleanupSimulatedClient(previewClientId);
            }
            ThreadLocalContextUtil.setBusinessDates(originalDates);
        }
    }
}
