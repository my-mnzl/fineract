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
package co.mnzl.fineract.custom.receivables.nativeinstrument;

import com.google.gson.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Funds an approved ordinary HEL through its existing native engine into an explicitly configured clearing channel. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NativeHelBridge {

    private final LoanRepository loans;
    private final LoanWritePlatformService loanWriter;
    private final ProductToGLAccountMappingRepository mappings;
    private final PaymentTypeRepository paymentTypes;
    private final ConfigurationDomainService configuration;
    private final FromJsonHelper json;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public record Fee(long feeId, BigInteger amountMinor, String currency) {
    }

    public record ScheduleRow(int installmentNumber, LocalDate dueDate, BigInteger principalMinor, BigInteger interestMinor,
            BigInteger feesMinor, BigInteger penaltiesMinor, BigInteger totalMinor, String currency) {
    }

    public record Terms(long nativeLoanId, long clientId, String status, BigInteger principalMinor, String annualNominalRate, int tenure,
            List<ScheduleRow> repaymentSchedule, List<Fee> financedFees, LocalDate businessDate) {
    }

    public record JournalLine(long journalId, long nativeGlAccountId, long transactionId, String side, BigInteger amountMinor,
            String currency, LocalDate businessDate, String role) {
    }

    public record Funding(long nativeLoanId, BigInteger clearingAmountMinor, List<Long> nativeTransactionIds, List<JournalLine> journals,
            Terms terms) {
    }

    @Transactional(readOnly = true)
    public Terms readTerms(String externalId) {
        return terms(requireOrdinaryLoan(externalId));
    }

    public Funding fund(String externalId, LocalDate date, BigInteger expectedPrincipalMinor, BigInteger expectedFeesMinor,
            List<Long> chargeIds, long paymentTypeId, long clearingGlId, String externalTransactionId) {
        Loan loan = requireOrdinaryLoan(externalId);
        entityManager.lock(loan, LockModeType.PESSIMISTIC_WRITE);
        if (!date.equals(DateUtils.getBusinessLocalDate()) || loan.getStatus() != LoanStatus.APPROVED || loan.isMultiDisburmentLoan()
                || loan.isTopup() || loan.getLoanProductRelatedDetail().isEnableDownPayment()) {
            throw new IllegalStateException(
                    "HEL clearing requires a current-date approved single-disbursal loan without topup or down payment");
        }
        if (externalTransactionId == null || externalTransactionId.isBlank() || externalTransactionId.length() > 100) {
            throw new IllegalArgumentException("A stable HEL funding transaction external ID is required");
        }
        Terms accepted = terms(loan);
        BigInteger fees = accepted.financedFees().stream().map(Fee::amountMinor).reduce(BigInteger.ZERO, BigInteger::add);
        Set<Long> expectedFeeIds = new HashSet<>(chargeIds);
        Set<Long> actualFeeIds = new HashSet<>(accepted.financedFees().stream().map(Fee::feeId).toList());
        if (chargeIds.size() != expectedFeeIds.size() || !expectedFeeIds.equals(actualFeeIds)
                || !accepted.principalMinor().equals(expectedPrincipalMinor) || !fees.equals(expectedFeesMinor)
                || expectedPrincipalMinor.signum() <= 0 || fees.signum() < 0 || fees.compareTo(expectedPrincipalMinor) >= 0) {
            throw new IllegalArgumentException("HEL principal or financed fees changed since acceptance");
        }
        for (LoanCharge charge : loan.getActiveCharges()) {
            if (charge.isDueAtDisbursement() && !charge.isWaived() && !charge.isFullyPaid()
                    && (charge.getChargePaymentMode().isPaymentModeAccountTransfer()
                            || charge.getAmountPaid(loan.getCurrency()).isGreaterThanZero())) {
                throw new IllegalArgumentException("Financed HEL fees cannot use savings transfer or partial upfront payment");
            }
        }
        if (fees.signum() > 0 && !configuration.isPaymentTypeApplicableForDisbursementCharge()) {
            throw new IllegalStateException("HEL financed-fee payment channel configuration is missing");
        }
        ProductToGLAccountMapping fundMapping = mappings
                .findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(loan.productId(), 1, 1, paymentTypeId);
        if (fundMapping == null || fundMapping.getGlAccount() == null || !fundMapping.getGlAccount().getId().equals(clearingGlId)
                || Boolean.TRUE.equals(paymentTypes.findById(paymentTypeId).orElseThrow().getIsCashPayment())) {
            throw new IllegalStateException("HEL payment type must map explicitly to the configured noncash settlement clearing account");
        }
        Set<Long> existingTransactions = new HashSet<>(loan.getLoanTransactions().stream().map(LoanTransaction::getId).toList());
        JsonObject command = new JsonObject();
        command.addProperty("actualDisbursementDate", date.toString());
        command.addProperty("dateFormat", "yyyy-MM-dd");
        command.addProperty("locale", "en");
        command.addProperty("transactionAmount", new BigDecimal(expectedPrincipalMinor, 2));
        command.addProperty("paymentTypeId", paymentTypeId);
        command.addProperty("externalId", externalTransactionId);
        // false: native payment-channel disbursement, never transfer to or withdrawal from a savings account.
        loanWriter.disburseLoan(loan.getId(), JsonCommand.fromJsonElement(loan.getId(), command, json), false, true);
        entityManager.flush();
        Loan funded = loans.findById(loan.getId()).orElseThrow();
        List<LoanTransaction> fundingTransactions = funded.getLoanTransactions().stream().filter(
                tx -> !existingTransactions.contains(tx.getId()) && !tx.isReversed() && (tx.getTypeOf() == LoanTransactionType.DISBURSEMENT
                        || tx.getTypeOf() == LoanTransactionType.REPAYMENT_AT_DISBURSEMENT))
                .toList();
        List<Long> transactionIds = fundingTransactions.stream().map(LoanTransaction::getId).sorted().toList();
        List<JournalLine> journals = readJournals(funded.getId(), transactionIds, clearingGlId);
        BigInteger principal = fundingTransactions.stream().filter(tx -> tx.getTypeOf() == LoanTransactionType.DISBURSEMENT)
                .map(tx -> minor(tx.getAmount())).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger paidFees = fundingTransactions.stream().filter(tx -> tx.getTypeOf() == LoanTransactionType.REPAYMENT_AT_DISBURSEMENT)
                .map(tx -> minor(tx.getAmount())).reduce(BigInteger.ZERO, BigInteger::add);
        if (funded.getStatus() != LoanStatus.ACTIVE || !principal.equals(expectedPrincipalMinor) || !paidFees.equals(fees)) {
            throw new IllegalStateException("HEL native funding transaction readback does not match accepted terms");
        }
        verifyFundingJournals(journals, expectedPrincipalMinor, fees, clearingGlId, date);
        Terms fundedTerms = terms(funded);
        if (!accepted.annualNominalRate().equals(fundedTerms.annualNominalRate()) || accepted.tenure() != fundedTerms.tenure()
                || !accepted.repaymentSchedule().equals(fundedTerms.repaymentSchedule())) {
            throw new IllegalStateException("HEL native disbursement changed the accepted contractual schedule");
        }
        return new Funding(funded.getId(), expectedPrincipalMinor.subtract(fees), transactionIds, journals, fundedTerms);
    }

    @Transactional(readOnly = true)
    public List<JournalLine> readJournals(long loanId, List<Long> transactionIds, long clearingGlId) {
        if (transactionIds.isEmpty() || new HashSet<>(transactionIds).size() != transactionIds.size()) {
            throw new IllegalArgumentException("Unique native funding transaction IDs required");
        }
        String placeholders = String.join(",", transactionIds.stream().map(ignored -> "?").toList());
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
        parameters.add(loanId);
        parameters.addAll(transactionIds);
        return jdbc.query(
                "select j.id, j.account_id, j.loan_transaction_id, j.type_enum, j.amount, j.currency_code, j.entry_date, t.transaction_type_enum "
                        + "from acc_gl_journal_entry j join m_loan_transaction t on t.id = j.loan_transaction_id "
                        + "where t.loan_id = ? and t.is_reversed = false and j.reversed = false and t.id in (" + placeholders
                        + ") order by j.id",
                (rs, index) -> {
                    int type = rs.getInt("transaction_type_enum");
                    if (type != LoanTransactionType.DISBURSEMENT.getValue()
                            && type != LoanTransactionType.REPAYMENT_AT_DISBURSEMENT.getValue()) {
                        throw new IllegalArgumentException("Not a HEL funding transaction");
                    }
                    long account = rs.getLong("account_id");
                    int side = rs.getInt("type_enum");
                    if (side != 1 && side != 2) {
                        throw new IllegalStateException("Invalid native journal side");
                    }
                    return new JournalLine(rs.getLong("id"), account, rs.getLong("loan_transaction_id"), side == 2 ? "DEBIT" : "CREDIT",
                            minor(rs.getBigDecimal("amount")), rs.getString("currency_code"), rs.getDate("entry_date").toLocalDate(),
                            account == clearingGlId ? "CLEARING"
                                    : type == LoanTransactionType.DISBURSEMENT.getValue() ? "HEL_PRINCIPAL" : "FINANCED_FEE");
                }, parameters.toArray());
    }

    static void verifyFundingJournals(List<JournalLine> journals, BigInteger principal, BigInteger fees, long clearing, LocalDate date) {
        if (journals.isEmpty() || journals.stream()
                .anyMatch(line -> !"EGP".equals(line.currency()) || !date.equals(line.businessDate()) || line.amountMinor().signum() < 0)) {
            throw new IllegalStateException("HEL funding journals missing or invalid");
        }
        BigInteger debit = sum(journals, "DEBIT", null);
        BigInteger credit = sum(journals, "CREDIT", null);
        if (!debit.equals(credit) || !sum(journals, "DEBIT", "HEL_PRINCIPAL").equals(principal)
                || !sum(journals, "CREDIT", "HEL_PRINCIPAL").equals(BigInteger.ZERO)
                || !sum(journals, "CREDIT", "FINANCED_FEE").equals(fees) || !sum(journals, "DEBIT", "FINANCED_FEE").equals(BigInteger.ZERO)
                || !sum(journals, "CREDIT", "CLEARING").equals(principal) || !sum(journals, "DEBIT", "CLEARING").equals(fees)
                || journals.stream().anyMatch(line -> line.role().equals("CLEARING") && line.nativeGlAccountId() != clearing)) {
            throw new IllegalStateException("HEL native clearing/principal/fee journals do not reconcile");
        }
    }

    private static BigInteger sum(List<JournalLine> lines, String side, String role) {
        return lines.stream().filter(line -> side.equals(line.side()) && (role == null || role.equals(line.role())))
                .map(JournalLine::amountMinor).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private Loan requireOrdinaryLoan(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("HEL loan external ID required");
        }
        Loan loan = loans.findByExternalId(new ExternalId(externalId)).orElseThrow();
        if (loan.isPurchasedReceivable() || !"EGP".equals(loan.getCurrencyCode()) || loan.getLoanProduct().isAccountingDisabled()
                || loan.getClientId() == null) {
            throw new IllegalArgumentException("An ordinary EGP HEL with native accounting is required");
        }
        return loan;
    }

    private static Terms terms(Loan loan) {
        Set<Long> financedPaid = new HashSet<>();
        loan.getLoanTransactions().stream()
                .filter(tx -> !tx.isReversed() && tx.getTypeOf() == LoanTransactionType.REPAYMENT_AT_DISBURSEMENT)
                .forEach(tx -> tx.getLoanChargesPaid().forEach(paid -> financedPaid.add(paid.getLoanCharge().getId())));
        List<Fee> fees = loan.getActiveCharges().stream()
                .filter(charge -> charge.isDueAtDisbursement() && !charge.isWaived()
                        && (!charge.isFullyPaid() || financedPaid.contains(charge.getId())))
                .map(charge -> new Fee(charge.getId(), minor(charge.amount()), "EGP")).sorted(Comparator.comparingLong(Fee::feeId))
                .toList();
        List<ScheduleRow> schedule = loan.getRepaymentScheduleInstallments().stream()
                .map(period -> new ScheduleRow(period.getInstallmentNumber(), period.getDueDate(),
                        minor(period.getPrincipal(loan.getCurrency()).getAmount()),
                        minor(period.getInterestCharged(loan.getCurrency()).getAmount()),
                        minor(period.getFeeChargesCharged(loan.getCurrency()).getAmount()),
                        minor(period.getPenaltyChargesCharged(loan.getCurrency()).getAmount()),
                        minor(period.getPrincipal(loan.getCurrency()).plus(period.getInterestCharged(loan.getCurrency()))
                                .plus(period.getFeeChargesCharged(loan.getCurrency()))
                                .plus(period.getPenaltyChargesCharged(loan.getCurrency())).getAmount()),
                        "EGP"))
                .toList();
        if (loan.getTermPeriodFrequencyType() != org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.MONTHS
                || loan.getTermFrequency() == null || loan.getTermFrequency() <= 0) {
            throw new IllegalArgumentException("HEL requires a positive term in months");
        }
        String status = loan.getStatus() == LoanStatus.ACTIVE ? "ACTIVE"
                : loan.getStatus() == LoanStatus.APPROVED ? "APPROVED" : loan.isClosed() ? "CLOSED" : "PENDING";
        return new Terms(loan.getId(), loan.getClientId(), status, minor(loan.getPrincipal().getAmount()),
                loan.getLoanProductRelatedDetail().getAnnualNominalInterestRate().movePointLeft(2).stripTrailingZeros().toPlainString(),
                loan.getTermFrequency(), schedule, fees, DateUtils.getBusinessLocalDate());
    }

    private static BigInteger minor(BigDecimal amount) {
        return amount.movePointRight(2).toBigIntegerExact();
    }
}
