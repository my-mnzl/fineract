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
package co.mnzl.fineract.custom.receivables.service;

import static co.mnzl.fineract.custom.receivables.service.ReceivablesException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual native state frozen at posting business dates, never reconstructed from a later loan balance. */
@Service
@RequiredArgsConstructor
public class ReceivablesHelHistory {

    private final ReceivablesStore store;
    private final EntityManager entityManager;
    private final ReceivablesJson json;
    private final BusinessEventNotifierService events;

    @PostConstruct
    public void register() {
        events.addPostBusinessEventListener(LoanBusinessEvent.class, event -> schedule(event.get()));
        events.addPostBusinessEventListener(LoanTransactionBusinessEvent.class, event -> schedule(event.get().getLoan()));
    }

    private void schedule(Loan loan) {
        if (loan.isPurchasedReceivable() || loan.getId() == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        // A command may emit several events. Store only its final state and include a HEL funded in this transaction.
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Capture capture && capture.loan.getId().equals(loan.getId())) {
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new Capture(loan));
    }

    private final class Capture implements TransactionSynchronization {

        private final Loan loan;

        private Capture(Loan loan) {
            this.loan = loan;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (!readOnly) {
                capture(loan);
            }
        }
    }

    private void capture(Loan loan) {
        var bindings = store.jdbc().queryForList("select distinct scope_key from m_mnzl_r_hel_funding where loan_id=?", loan.getId());
        if (bindings.isEmpty()) {
            return;
        }
        entityManager.flush();
        LocalDate date = DateUtils.getBusinessLocalDate();
        ObjectNode snapshot = snapshot(loan);
        for (var binding : bindings) {
            store.insert("hel_snapshot", UUID.randomUUID().toString(), Map.of("scope_key", binding.get("scope_key"), "loan_id",
                    loan.getId(), "business_date", date, "snapshot_json", json.write(snapshot)));
        }
    }

    public JsonNode read(String scope, String externalId, LocalDate date) {
        require(!date.isAfter(DateUtils.getBusinessLocalDate()), "INVALID_DATA");
        var bindings = store.jdbc().queryForList(
                "select distinct loan_id from m_mnzl_r_hel_funding where scope_key=? and loan_external_id=?", scope, externalId);
        require(bindings.size() == 1, "OWNERSHIP_CONFLICT");
        var snapshots = store.jdbc().queryForList(
                "select * from m_mnzl_r_hel_snapshot where scope_key=? and loan_id=? and business_date<=? order by business_date desc, sequence_id desc limit 1",
                scope, bindings.getFirst().get("loan_id"), date);
        require(!snapshots.isEmpty(), "RECOVERY_REQUIRED");
        var row = snapshots.getFirst();
        ObjectNode result = (ObjectNode) json.read(ReceivablesStore.string(row, "snapshot_json"));
        require(result.path("timeline").path("actualDisbursementDate").isTextual(), "RECOVERY_REQUIRED");
        result.put("snapshotId", ReceivablesStore.string(row, "record_key"));
        result.put("snapshotBusinessDate", ReceivablesStore.string(row, "business_date"));
        result.put("asOfDate", date.toString());
        // Age the frozen contractual rows only; their amounts and payment allocations remain native facts.
        BigDecimal overdue = BigDecimal.ZERO;
        for (var period : result.path("repaymentSchedule").path("periods")) {
            if (LocalDate.parse(period.path("dueDate").asText()).isBefore(date)) {
                overdue = overdue.add(new BigDecimal(period.path("totalOutstandingForPeriod").asText()));
            }
        }
        ((ObjectNode) result.get("summary")).put("totalOverdue", overdue.toPlainString());
        return result;
    }

    private ObjectNode snapshot(Loan loan) {
        var result = json.object();
        result.put("id", loan.getId().toString());
        result.put("externalId", loan.getExternalId().getValue());
        result.put("clientId", loan.getClientId().toString());
        result.put("loanProductId", loan.productId().toString());
        result.set("currency", json.value(Map.of("code", loan.getCurrencyCode())));
        result.put("principal", loan.getPrincipal().getAmount().toPlainString());
        result.set("status", json.value(Map.of("active", loan.getStatus().isActive(), "closedObligationsMet", loan.isClosedObligationsMet(),
                "closedWrittenOff", loan.isClosedWrittenOff(), "closedRescheduled",
                loan.getStatus() == org.apache.fineract.portfolio.loanaccount.domain.LoanStatus.CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT)));
        var timeline = result.putObject("timeline");
        if (loan.getActualDisbursementDate() != null) {
            timeline.put("actualDisbursementDate", loan.getActualDisbursementDate().toString());
        }
        if (loan.getExpectedMaturityDate() != null) {
            timeline.put("expectedMaturityDate", loan.getExpectedMaturityDate().toString());
        }
        if (loan.getWrittenOffOnDate() != null) {
            timeline.put("writtenOffOnDate", loan.getWrittenOffOnDate().toString());
        }
        if (loan.getClosedOnDate() != null) {
            timeline.put("closedOnDate", loan.getClosedOnDate().toString());
        }
        result.putObject("summary").put("totalWrittenOff", loan.getTotalWrittenOff().toPlainString()).put("totalOutstanding",
                loan.getSummary().getTotalOutstanding(loan.getCurrency()).getAmount().toPlainString());
        var periods = result.putObject("repaymentSchedule").putArray("periods");
        for (var period : loan.getRepaymentScheduleInstallments()) {
            var value = periods.addObject();
            value.put("period", period.getInstallmentNumber());
            value.put("dueDate", period.getDueDate().toString());
            value.put("totalOutstandingForPeriod", period.getTotalOutstanding(loan.getCurrency()).getAmount().toPlainString());
            value.put("totalDueForPeriod",
                    period.getPrincipal(loan.getCurrency()).plus(period.getInterestCharged(loan.getCurrency()))
                            .plus(period.getFeeChargesCharged(loan.getCurrency())).plus(period.getPenaltyChargesCharged(loan.getCurrency()))
                            .getAmount().toPlainString());
            value.put("totalPaidForPeriod", period.getTotalPaid(loan.getCurrency()).getAmount().toPlainString());
        }
        var transactions = result.putArray("transactions");
        for (var transaction : loan.getLoanTransactions()) {
            var value = transactions.addObject();
            value.put("id", transaction.getId().toString());
            value.put("date", transaction.getTransactionDate().toString());
            value.put("amount", transaction.getAmount().toPlainString());
            value.put("reversed", transaction.isReversed());
            value.putObject("type").put("id", transaction.getTypeOf().getValue()).put("repayment", transaction.isRepayment())
                    .put("writeOff", transaction.isWriteOff());
        }
        return result;
    }
}
