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

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyData;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import co.mnzl.fineract.custom.loan.instrument.PurchasedReceivableProductValidator;
import co.mnzl.fineract.custom.loan.schedule.FixedReceivableScheduleGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepository;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Native state only. The caller commits measurement, GL, command result and outbox in this same tenant transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NativeReceivableBridge {

    private static final BigInteger MAX_MINOR = new BigInteger("999999999999999");
    private final ClientRepository clients;
    private final OfficeRepository offices;
    private final LoanRepository loans;
    private final LoanProductRepository products;
    private final MnzlLoanProductStrategyReadService strategies;
    private final PlatformSecurityContext security;
    private final EntityManager entityManager;

    public record ClientIdentity(String externalId, String displayName, long officeId) {
    }

    public record FaceLeg(String sourceInstallmentId, LocalDate dueDate, BigInteger amountMinor) {
    }

    public record Allocation(long nativePeriodId, BigInteger amountMinor) {
    }

    public record Period(long nativePeriodId, int periodNumber, LocalDate dueDate, BigInteger faceMinor, BigInteger collectedMinor,
            BigInteger adjustedMinor, BigInteger outstandingMinor) {
    }

    public record NativeTransaction(long id, String type, LocalDate date, BigInteger amountMinor, boolean reversed,
            LocalDate reversedOnDate, List<Allocation> allocations) {
    }

    public record NativeState(long loanId, String status, LocalDate businessDate, String boundarySide, BigInteger outstandingMinor,
            List<Period> periods, List<NativeTransaction> transactions) {
    }

    public record Booking(long loanId, long activationTransactionId, Map<String, Long> sourcePeriodIds, NativeState state) {
    }

    public record Effect(long transactionId, NativeState state) {
    }

    public enum AdjustmentType {
        COMMERCIAL_SETTLEMENT_ADJUSTMENT, ASSIGNMENT_OUT, MODIFICATION, WRITE_OFF
    }

    public long createClient(ClientIdentity identity) {
        requireText(identity.externalId());
        requireText(identity.displayName());
        Long found = clients.findIdByExternalId(new ExternalId(identity.externalId()));
        if (found != null) {
            Client client = clients.findById(found).orElseThrow();
            if (!Objects.equals(client.getOffice().getId(), identity.officeId()) || !client.isActive()) {
                throw new IllegalArgumentException("Client identity is not active in the requested office");
            }
            return found;
        }
        LocalDate date = DateUtils.getBusinessLocalDate();
        Client client = Client.instance(security.authenticatedUser(), ClientStatus.ACTIVE,
                offices.findById(identity.officeId()).orElseThrow(), null, null, null, null, null, identity.displayName(), date, date,
                new ExternalId(identity.externalId()), null, null, null, date, null, null, null, null, null, null, 1, false);
        return clients.saveAndFlush(client).getId();
    }

    public Booking bookExactFace(long clientId, long productId, String externalId, LocalDate activationDate, List<FaceLeg> input) {
        requireText(externalId);
        if (loans.findByExternalId(new ExternalId(externalId)).isPresent()) {
            throw new IllegalStateException("Native loan already exists; recover the original financial operation");
        }
        LoanProduct product = requireProduct(productId);
        List<FaceLeg> legs = validateLegs(activationDate, input);
        BigInteger face = legs.stream().map(FaceLeg::amountMinor).reduce(BigInteger.ZERO, BigInteger::add);
        Client client = clients.findById(clientId).orElseThrow();
        if (!client.isActive()) {
            throw new IllegalArgumentException("Active tenant client required");
        }
        Loan loan = Loan.acquiredReceivable(client, product, new ExternalId(externalId), activationDate, major(face), legs.size(),
                legs.getLast().dueDate());
        new FixedReceivableScheduleGenerator().construct(
                loan, activationDate, legs.stream()
                        .map(leg -> new FixedReceivableScheduleGenerator.PrincipalLeg(leg.dueDate(), major(leg.amountMinor()))).toList(),
                major(face)).forEach(loan::addLoanRepaymentScheduleInstallment);
        LoanTransaction transaction = LoanTransaction.purchasedReceivableEffect(loan, LoanTransactionType.PURCHASED_RECEIVABLE_ACTIVATION,
                major(face), activationDate, new ExternalId(externalId + ":activation"));
        for (LoanRepaymentScheduleInstallment period : loan.getRepaymentScheduleInstallments()) {
            map(transaction, period, period.getPrincipal(loan.getCurrency()).getAmount());
        }
        loan.addLoanTransaction(transaction);
        loan.refreshPurchasedReceivableSummary(activationDate);
        loan = loans.saveAndFlush(loan);
        Map<String, Long> ids = new HashMap<>();
        for (int i = 0; i < legs.size(); i++) {
            ids.put(legs.get(i).sourceInstallmentId(), loan.getRepaymentScheduleInstallments().get(i).getId());
        }
        return new Booking(loan.getId(), savedTransactionId(loan, transaction.getExternalId()), Map.copyOf(ids),
                state(loan, activationDate, "AFTER_EVENTS"));
    }

    public Effect collect(long loanId, LocalDate date, List<Allocation> allocations, String externalTransactionId) {
        return apply(locked(loanId), date, allocations, externalTransactionId, LoanTransactionType.REPAYMENT);
    }

    public Effect adjustFace(long loanId, LocalDate date, AdjustmentType type, List<Allocation> allocations, String externalTransactionId) {
        if (type == AdjustmentType.MODIFICATION) {
            throw new IllegalArgumentException("Use replaceSchedule for legal modifications");
        }
        LoanTransactionType nativeType = switch (type) {
            case COMMERCIAL_SETTLEMENT_ADJUSTMENT -> LoanTransactionType.COMMERCIAL_SETTLEMENT_ADJUSTMENT;
            case ASSIGNMENT_OUT -> LoanTransactionType.ASSIGNMENT_OUT;
            case WRITE_OFF -> LoanTransactionType.WRITEOFF;
            case MODIFICATION -> throw new IllegalArgumentException("Use replaceSchedule");
        };
        return apply(locked(loanId), date, allocations, externalTransactionId, nativeType);
    }

    private Effect apply(Loan loan, LocalDate date, List<Allocation> allocations, String externalId, LoanTransactionType type) {
        validateDate(loan, date);
        requireTransactionId(loan, externalId);
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("Explicit native period allocation required");
        }
        Set<Long> used = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Allocation allocation : allocations) {
            if (!used.add(allocation.nativePeriodId()) || allocation.amountMinor().signum() <= 0) {
                throw new IllegalArgumentException("Positive unique period allocations required");
            }
            BigDecimal amount = major(allocation.amountMinor());
            LoanRepaymentScheduleInstallment period = period(loan, allocation.nativePeriodId());
            if (period.getPrincipalOutstanding(loan.getCurrency()).getAmount().compareTo(amount) < 0) {
                throw new IllegalArgumentException("Allocation exceeds native period face");
            }
            total = total.add(amount);
        }
        LoanTransaction transaction = LoanTransaction.purchasedReceivableEffect(loan, type, total, date, new ExternalId(externalId));
        for (Allocation allocation : allocations) {
            LoanRepaymentScheduleInstallment period = period(loan, allocation.nativePeriodId());
            BigDecimal amount = major(allocation.amountMinor());
            if (type == LoanTransactionType.REPAYMENT) {
                period.setPrincipalCompleted(period.getPrincipalCompleted(loan.getCurrency()).getAmount().add(amount));
            } else if (type == LoanTransactionType.WRITEOFF) {
                period.setPrincipalWrittenOff(period.getPrincipalWrittenOff(loan.getCurrency()).getAmount().add(amount));
            } else {
                period.setPrincipal(period.getPrincipal(loan.getCurrency()).getAmount().subtract(amount));
                period.setCreditedPrincipal(period.getCreditedPrincipal(loan.getCurrency()).getAmount().subtract(amount));
            }
            period.updateObligationsMet(loan.getCurrency(), date);
            map(transaction, period, amount);
        }
        loan.addLoanTransaction(transaction);
        loan.refreshPurchasedReceivableSummary(date);
        transaction.updateOutstandingLoanBalance(loan.getSummary().getTotalOutstanding());
        loan = loans.saveAndFlush(loan);
        return new Effect(savedTransactionId(loan, transaction.getExternalId()), state(loan, date, "AFTER_EVENTS"));
    }

    public Effect reverse(long loanId, long transactionId, LocalDate date) {
        Loan loan = locked(loanId);
        validateDate(loan, date);
        LoanTransaction transaction = loan.getLoanTransactions().stream().filter(tx -> tx.getId().equals(transactionId)).findFirst()
                .orElseThrow();
        if (transaction.getTypeOf() != LoanTransactionType.REPAYMENT || transaction.isReversed()) {
            throw new IllegalArgumentException("Only a live identified collection can be reversed");
        }
        for (LoanTransactionToRepaymentScheduleMapping mapping : transaction.getLoanTransactionToRepaymentScheduleMappings()) {
            LoanRepaymentScheduleInstallment period = mapping.getLoanRepaymentScheduleInstallment();
            period.setPrincipalCompleted(
                    period.getPrincipalCompleted(loan.getCurrency()).getAmount().subtract(mapping.getPrincipalPortion()));
            period.updateObligationsMet(loan.getCurrency(), date);
        }
        transaction.reversePurchasedReceivable(date);
        loan.refreshPurchasedReceivableSummary(date);
        loan = loans.saveAndFlush(loan);
        return new Effect(savedTransactionId(loan, transaction.getExternalId()), state(loan, date, "AFTER_EVENTS"));
    }

    /** Retains old native periods and mappings; a signed modification maps old exposure out and new exact legs in. */
    public Booking replaceSchedule(long loanId, LocalDate date, List<FaceLeg> input, String externalTransactionId) {
        Loan loan = locked(loanId);
        validateDate(loan, date);
        requireTransactionId(loan, externalTransactionId);
        List<FaceLeg> legs = validateLegs(date, input);
        BigDecimal replacement = major(legs.stream().map(FaceLeg::amountMinor).reduce(BigInteger.ZERO, BigInteger::add));
        BigDecimal prior = loan.getSummary().getTotalOutstanding();
        LoanTransaction tx = LoanTransaction.purchasedReceivableEffect(loan, LoanTransactionType.RECEIVABLE_MODIFICATION,
                replacement.subtract(prior), date, new ExternalId(externalTransactionId));
        for (LoanRepaymentScheduleInstallment period : loan.getRepaymentScheduleInstallments()) {
            BigDecimal outstanding = period.getPrincipalOutstanding(loan.getCurrency()).getAmount();
            if (outstanding.signum() != 0) {
                period.setPrincipal(period.getPrincipal(loan.getCurrency()).getAmount().subtract(outstanding));
                period.setCreditedPrincipal(period.getCreditedPrincipal(loan.getCurrency()).getAmount().subtract(outstanding));
                period.updateObligationsMet(loan.getCurrency(), date);
                map(tx, period, outstanding.negate());
            }
        }
        List<LoanRepaymentScheduleInstallment> added = new FixedReceivableScheduleGenerator().construct(
                loan, date, legs.stream()
                        .map(leg -> new FixedReceivableScheduleGenerator.PrincipalLeg(leg.dueDate(), major(leg.amountMinor()))).toList(),
                replacement);
        int offset = loan.getRepaymentScheduleInstallments().size();
        for (int i = 0; i < added.size(); i++) {
            LoanRepaymentScheduleInstallment period = added.get(i);
            period.setInstallmentNumber(offset + i + 1);
            period.setCreditedPrincipal(period.getPrincipal(loan.getCurrency()).getAmount());
            loan.addLoanRepaymentScheduleInstallment(period);
            map(tx, period, period.getPrincipal(loan.getCurrency()).getAmount());
        }
        loan.addLoanTransaction(tx);
        loan.setExpectedMaturityDate(legs.getLast().dueDate());
        loan.getLoanProductRelatedDetail().setNumberOfRepayments(offset + legs.size());
        loan.refreshPurchasedReceivableSummary(date);
        loan = loans.saveAndFlush(loan);
        Map<String, Long> ids = new HashMap<>();
        for (int i = 0; i < legs.size(); i++) {
            int installmentNumber = offset + i + 1;
            ids.put(legs.get(i).sourceInstallmentId(), loan.getRepaymentScheduleInstallments().stream()
                    .filter(period -> period.getInstallmentNumber() == installmentNumber).findFirst().orElseThrow().getId());
        }
        return new Booking(loan.getId(), savedTransactionId(loan, tx.getExternalId()), Map.copyOf(ids), state(loan, date, "AFTER_EVENTS"));
    }

    private long savedTransactionId(Loan loan, ExternalId externalId) {
        return loan.getLoanTransactions().stream().filter(transaction -> externalId.equals(transaction.getExternalId())).findFirst()
                .orElseThrow().getId();
    }

    @Transactional(readOnly = true)
    public NativeState readIndependentState(long loanId, LocalDate date, String boundarySide) {
        Loan loan = loans.findById(loanId).orElseThrow();
        requirePurchased(loan);
        return state(loan, date, boundarySide);
    }

    @Transactional(readOnly = true)
    public NativeState readIndependentState(long loanId, LocalDate date, String side, Set<Long> transactionIds, Set<Long> reversalIds) {
        Loan loan = loans.findById(loanId).orElseThrow();
        requirePurchased(loan);
        return state(loan, date, side, transactionIds, reversalIds);
    }

    private NativeState state(Loan loan, LocalDate date, String side) {
        return state(loan, date, side, null, null);
    }

    private NativeState state(Loan loan, LocalDate date, String side, Set<Long> transactionIds, Set<Long> reversalIds) {
        if (!Set.of("BEFORE_EVENTS", "AFTER_EVENTS").contains(side)) {
            throw new IllegalArgumentException("Unknown boundary side");
        }
        Map<Long, BigInteger[]> amounts = new HashMap<>();
        List<NativeTransaction> transactions = new ArrayList<>();
        for (LoanTransaction tx : loan.getLoanTransactions()) {
            if ((transactionIds != null && !transactionIds.contains(tx.getId())) || !included(tx.getTransactionDate(), date, side)) {
                continue;
            }
            List<Allocation> allocations = tx.getLoanTransactionToRepaymentScheduleMappings().stream().map(
                    mapping -> new Allocation(mapping.getLoanRepaymentScheduleInstallment().getId(), minor(mapping.getPrincipalPortion())))
                    .toList();
            transactions.add(new NativeTransaction(tx.getId(), tx.getTypeOf().name(), tx.getTransactionDate(), minor(tx.getAmount()),
                    tx.isReversed(), tx.getReversedOnDate(), allocations));
            if (!included(tx.getTransactionDate(), date, side) || (tx.isReversed()
                    && (reversalIds == null || reversalIds.contains(tx.getId())) && included(tx.getReversedOnDate(), date, side))) {
                continue;
            }
            for (Allocation allocation : allocations) {
                BigInteger[] row = amounts.computeIfAbsent(allocation.nativePeriodId(),
                        ignored -> new BigInteger[] { BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO });
                if (tx.getTypeOf() == LoanTransactionType.PURCHASED_RECEIVABLE_ACTIVATION) {
                    row[0] = row[0].add(allocation.amountMinor());
                } else if (tx.getTypeOf() == LoanTransactionType.REPAYMENT) {
                    row[1] = row[1].add(allocation.amountMinor());
                } else if (tx.getTypeOf() == LoanTransactionType.RECEIVABLE_MODIFICATION) {
                    row[2] = row[2].subtract(allocation.amountMinor());
                } else {
                    row[2] = row[2].add(allocation.amountMinor());
                }
            }
        }
        List<Period> periods = loan.getRepaymentScheduleInstallments().stream().filter(p -> amounts.containsKey(p.getId())).map(p -> {
            BigInteger[] a = amounts.get(p.getId());
            return new Period(p.getId(), p.getInstallmentNumber(), p.getDueDate(), a[0], a[1], a[2], a[0].subtract(a[1]).subtract(a[2]));
        }).toList();
        BigInteger outstanding = periods.stream().map(Period::outstandingMinor).reduce(BigInteger.ZERO, BigInteger::add);
        return new NativeState(loan.getId(), outstanding.signum() == 0 ? "CLOSED" : "ACTIVE", date, side, outstanding, periods,
                List.copyOf(transactions));
    }

    private static boolean included(LocalDate effective, LocalDate date, String side) {
        return effective != null && (effective.isBefore(date) || (effective.equals(date) && side.equals("AFTER_EVENTS")));
    }

    private Loan locked(long id) {
        Loan loan = loans.findById(id).orElseThrow();
        entityManager.lock(loan, LockModeType.PESSIMISTIC_WRITE);
        requirePurchased(loan);
        requireProduct(loan.productId());
        return loan;
    }

    private LoanProduct requireProduct(long id) {
        LoanProduct product = products.findById(id).orElseThrow();
        MnzlLoanProductStrategyData strategy = strategies.findOne(id);
        if (!MnzlLoanProductStrategyCodes.INSTRUMENT_PURCHASED_RECEIVABLE.equals(strategy.getInstrumentCode())) {
            throw new IllegalArgumentException("Purchased receivable product required");
        }
        PurchasedReceivableProductValidator.validateStrategies(strategy.getInstrumentCode(), strategy.getScheduleStrategyCode(),
                strategy.getChargeStrategyCode(), strategy.getCobStrategyCode());
        PurchasedReceivableProductValidator.validate(product);
        return product;
    }

    private static void requirePurchased(Loan loan) {
        if (!loan.isPurchasedReceivable()) {
            throw new IllegalArgumentException("Not a purchased receivable");
        }
    }

    private static List<FaceLeg> validateLegs(LocalDate date, List<FaceLeg> input) {
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Positive fixed cashflows required");
        }
        Set<String> ids = new HashSet<>();
        for (FaceLeg leg : input) {
            requireText(leg.sourceInstallmentId());
            if (!ids.add(leg.sourceInstallmentId()) || !leg.dueDate().isAfter(date) || leg.amountMinor().signum() <= 0) {
                throw new IllegalArgumentException("Invalid acquired cashflow");
            }
            major(leg.amountMinor());
        }
        return input.stream().sorted(Comparator.comparing(FaceLeg::dueDate).thenComparing(FaceLeg::sourceInstallmentId)).toList();
    }

    private static void validateDate(Loan loan, LocalDate date) {
        if (date.isBefore(loan.getActualDisbursementDate()) || loan.getLoanTransactions().stream().anyMatch(
                tx -> tx.getTransactionDate().isAfter(date) || (tx.getReversedOnDate() != null && tx.getReversedOnDate().isAfter(date)))) {
            throw new IllegalArgumentException("Native effects must follow the account effective-date sequence");
        }
    }

    private static void requireTransactionId(Loan loan, String externalId) {
        requireText(externalId);
        if (loan.getLoanTransactions().stream()
                .anyMatch(tx -> tx.getExternalId() != null && externalId.equals(tx.getExternalId().getValue()))) {
            throw new IllegalStateException("Native transaction exists; recover original operation");
        }
    }

    private static LoanRepaymentScheduleInstallment period(Loan loan, long id) {
        return loan.getRepaymentScheduleInstallments().stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
    }

    private static void map(LoanTransaction tx, LoanRepaymentScheduleInstallment period, BigDecimal amount) {
        Money zero = Money.zero(period.getLoan().getCurrency());
        tx.getLoanTransactionToRepaymentScheduleMappings().add(LoanTransactionToRepaymentScheduleMapping.createFrom(tx, period,
                Money.of(period.getLoan().getCurrency(), amount), zero, zero, zero));
    }

    private static BigDecimal major(BigInteger minor) {
        if (minor.abs().compareTo(MAX_MINOR) > 0) {
            throw new IllegalArgumentException("Amount exceeds native DECIMAL(19,6) capacity");
        }
        return new BigDecimal(minor, 2);
    }

    private static BigInteger minor(BigDecimal major) {
        return major == null ? BigInteger.ZERO : major.movePointRight(2).toBigIntegerExact();
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Stable external identity required");
        }
    }
}
