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
package co.mnzl.fineract.custom.loan.job;

import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.exception.TruncatedJobExecutionException;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.jobs.executestandinginstructions.ExecuteStandingInstructionsTasklet;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

public class MnzlExecuteStandingInstructionsTasklet extends ExecuteStandingInstructionsTasklet {

    private static final Logger LOG = LoggerFactory.getLogger(MnzlExecuteStandingInstructionsTasklet.class);
    private static final int MAX_TRANSFER_ATTEMPTS = 6;
    private static final int MAX_ERROR_LOG_LENGTH = 500;

    private final StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final TransactionTemplate transactionTemplate;
    private final ScheduledDateGenerator scheduledDateGenerator;

    public MnzlExecuteStandingInstructionsTasklet(StandingInstructionReadPlatformService standingInstructionReadPlatformService,
            JdbcTemplate jdbcTemplate, DatabaseSpecificSQLGenerator sqlGenerator,
            AccountTransfersWritePlatformService accountTransfersWritePlatformService, TransactionTemplate transactionTemplate,
            ScheduledDateGenerator scheduledDateGenerator) {
        super(standingInstructionReadPlatformService, jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService,
                scheduledDateGenerator);
        this.standingInstructionReadPlatformService = standingInstructionReadPlatformService;
        this.jdbcTemplate = jdbcTemplate;
        this.sqlGenerator = sqlGenerator;
        this.accountTransfersWritePlatformService = accountTransfersWritePlatformService;
        this.transactionTemplate = transactionTemplate;
        this.scheduledDateGenerator = scheduledDateGenerator;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Collection<StandingInstructionData> instructionData = standingInstructionReadPlatformService
                .retrieveAll(StandingInstructionStatus.ACTIVE.getValue());
        List<Throwable> errors = new ArrayList<>();
        for (StandingInstructionData data : instructionData) {
            boolean isDueForTransfer = false;
            AccountTransferRecurrenceType recurrenceType = data.getRecurrenceType();
            StandingInstructionType instructionType = data.getInstructionType();
            LocalDate transactionDate = DateUtils.getBusinessLocalDate();
            if (recurrenceType.isPeriodicRecurrence()) {
                PeriodFrequencyType frequencyType = data.getRecurrenceFrequency();
                LocalDate startDate = data.getValidFrom();
                if (frequencyType.isMonthly()) {
                    startDate = startDate.withDayOfMonth(data.getRecurrenceOnDay());
                    if (DateUtils.isBefore(startDate, data.getValidFrom())) {
                        startDate = startDate.plusMonths(1);
                    }
                } else if (frequencyType.isYearly()) {
                    startDate = startDate.withDayOfMonth(data.getRecurrenceOnDay()).withMonth(data.getRecurrenceOnMonth());
                    if (DateUtils.isBefore(startDate, data.getValidFrom())) {
                        startDate = startDate.plusYears(1);
                    }
                }
                isDueForTransfer = scheduledDateGenerator.isDateFallsInSchedule(frequencyType, data.getRecurrenceInterval(), startDate,
                        transactionDate);
            }
            BigDecimal transactionAmount = data.getAmount();
            if (data.getToAccountType().isLoanAccount()
                    && (recurrenceType.isDuesRecurrence() || (isDueForTransfer && instructionType.isDuesAmoutTransfer()))) {
                StandingInstructionDuesData standingInstructionDuesData = standingInstructionReadPlatformService
                        .retriveLoanDuesData(data.getToAccount().getId());
                if (data.getInstructionType().isDuesAmoutTransfer()) {
                    transactionAmount = standingInstructionDuesData.totalDueAmount();
                }
                if (recurrenceType.isDuesRecurrence()) {
                    isDueForTransfer = isDueForTransfer(standingInstructionDuesData);
                }
            }

            if (isDueForTransfer && transactionAmount != null && transactionAmount.compareTo(BigDecimal.ZERO) > 0) {
                final SavingsAccount fromSavingsAccount = null;
                final boolean isRegularTransaction = true;
                final boolean isExceptionForBalanceCheck = false;
                AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, transactionAmount,
                        data.getFromAccountType(), data.getToAccountType(), data.getFromAccount().getId(), data.getToAccount().getId(),
                        data.getName() + " Standing instruction trasfer ", null, null, null, null, data.toTransferType(), null, null,
                        data.getTransferType().getValue(), null, null, ExternalId.empty(), null, null, fromSavingsAccount,
                        isRegularTransaction, isExceptionForBalanceCheck);
                transferAmount(errors, accountTransferDTO, data.getId());
            }
        }
        if (!errors.isEmpty()) {
            LOG.error("ExecuteStandingInstructions job completed with {} errors out of {} total instructions", errors.size(),
                    instructionData != null ? instructionData.size() : 0);
            throw new TruncatedJobExecutionException(errors);
        }
        return RepeatStatus.FINISHED;
    }

    private boolean transferAmount(final List<Throwable> errors, final AccountTransferDTO accountTransferDTO, final Long instructionId) {
        for (int attempt = 1; attempt <= MAX_TRANSFER_ATTEMPTS; attempt++) {
            try {
                executeInNewTransaction(() -> {
                    accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
                    updateLastRunDate(accountTransferDTO.getTransactionDate(), instructionId);
                    logTransferHistory(instructionId, accountTransferDTO.getTransactionAmount(), true, "");
                    LOG.debug("Successfully transferred standing instruction {} for amount {}", instructionId,
                            accountTransferDTO.getTransactionAmount());
                });
                return true;
            } catch (Exception e) {
                if (isRetryableOptimisticLock(e) && attempt < MAX_TRANSFER_ATTEMPTS) {
                    long retryDelayMillis = calculateRetryDelayMillis(attempt);
                    LOG.warn("Retrying standing instruction {} after optimistic lock on attempt {}/{} in {} ms: {}", instructionId, attempt,
                            MAX_TRANSFER_ATTEMPTS, retryDelayMillis, e.getMessage());
                    try {
                        sleepBeforeRetry(attempt, instructionId);
                    } catch (RuntimeException retryException) {
                        e.addSuppressed(retryException);
                        return recordTerminalFailure(errors, accountTransferDTO, instructionId, attempt, e);
                    }
                    continue;
                }
                return recordTerminalFailure(errors, accountTransferDTO, instructionId, attempt, e);
            }
        }
        return false;
    }

    void sleepBeforeRetry(int attempt, Long instructionId) {
        try {
            Thread.sleep(calculateRetryDelayMillis(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying standing instruction " + instructionId, e);
        }
    }

    private long calculateRetryDelayMillis(int attempt) {
        return attempt * 1000L;
    }

    private boolean recordTerminalFailure(final List<Throwable> errors, final AccountTransferDTO accountTransferDTO,
            final Long instructionId, final int attempts, final Exception cause) {
        Exception transferFailure = wrapTransferException(accountTransferDTO, instructionId, attempts, cause);
        errors.add(transferFailure);

        try {
            executeInNewTransaction(
                    () -> logTransferHistory(instructionId, accountTransferDTO.getTransactionAmount(), false, buildErrorLog(cause)));
        } catch (RuntimeException historyFailure) {
            transferFailure.addSuppressed(historyFailure);
            LOG.error("Failed to persist standing instruction failure history for {}", instructionId, historyFailure);
        }
        return false;
    }

    private Exception wrapTransferException(final AccountTransferDTO accountTransferDTO, final Long instructionId, final int attempts,
            final Exception cause) {
        String attemptSuffix = attempts > 1 ? " after " + attempts + " attempts" : "";
        if (cause instanceof PlatformApiDataValidationException e) {
            return new Exception("Validation exception while transfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId() + attemptSuffix, e);
        } else if (cause instanceof InsufficientAccountBalanceException e) {
            return new Exception("InsufficientAccountBalance Exception while trasfering funds for standing Instruction id" + instructionId
                    + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId() + attemptSuffix, e);
        } else if (cause instanceof AbstractPlatformServiceUnavailableException e) {
            return new Exception("Platform exception while trasfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId() + attemptSuffix, e);
        }
        return new Exception("Unhandled System Exception while trasfering funds for standing Instruction id" + instructionId + " from "
                + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId() + attemptSuffix, cause);
    }

    private String buildErrorLog(final Exception cause) {
        StringBuilder errorLog = new StringBuilder();
        if (cause instanceof PlatformApiDataValidationException e) {
            errorLog.append("Validation exception while trasfering funds ").append(e.getDefaultUserMessage());
        } else if (cause instanceof InsufficientAccountBalanceException) {
            errorLog.append("InsufficientAccountBalance Exception ");
        } else if (cause instanceof AbstractPlatformServiceUnavailableException e) {
            errorLog.append("Platform exception while trasfering funds ").append(e.getDefaultUserMessage());
        } else {
            errorLog.append("Exception while trasfering funds ").append(cause.getMessage());
        }
        if (errorLog.length() > MAX_ERROR_LOG_LENGTH) {
            return errorLog.substring(0, MAX_ERROR_LOG_LENGTH);
        }
        return errorLog.toString();
    }

    private boolean isRetryableOptimisticLock(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof OptimisticLockException || current instanceof OptimisticLockingFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void executeInNewTransaction(final Runnable action) {
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {

            @Override
            protected void doInTransactionWithoutResult(@NonNull TransactionStatus status) {
                action.run();
            }
        });
    }

    private void updateLastRunDate(LocalDate transactionDate, Long instructionId) {
        final String updateQuery = "UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?";
        jdbcTemplate.update(updateQuery, transactionDate, instructionId);
    }

    private void logTransferHistory(Long instructionId, BigDecimal amount, boolean success, String errorLog) {
        String updateQuery = "INSERT INTO m_account_transfer_standing_instructions_history (standing_instruction_id, "
                + sqlGenerator.escape("status") + ", amount, execution_time, error_log) VALUES (?, ?, ?, now(), ?)";
        jdbcTemplate.update(updateQuery, instructionId, success ? "success" : "failed", amount, errorLog);
    }

    @Override
    public boolean isDueForTransfer(StandingInstructionDuesData standingInstructionDuesData) {
        return standingInstructionDuesData.dueDate() != null
                && !standingInstructionDuesData.dueDate().isAfter(LocalDate.now(DateUtils.getDateTimeZoneOfTenant()));
    }
}
