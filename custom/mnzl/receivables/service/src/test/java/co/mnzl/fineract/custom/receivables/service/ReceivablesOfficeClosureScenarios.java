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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.springframework.aop.framework.Advised;

/** Schedules real HTTP transactions around the accounting lock, without replacing any repository operation. */
final class ReceivablesOfficeClosureScenarios {

    private final ReceivablesDatabaseIntegrationTest harness;
    private final OfficeRepository offices;
    private final AtomicReference<Barrier> next = new AtomicReference<>();

    ReceivablesOfficeClosureScenarios(ReceivablesDatabaseIntegrationTest harness, OfficeRepository offices) {
        this.harness = harness;
        this.offices = offices;
    }

    void verify() throws Exception {
        LocalDate initialDate = harness.today;
        verifyBoundaries();
        Advised proxy = (Advised) offices;
        MethodInterceptor interceptor = invocation -> {
            if (!invocation.getMethod().getName().equals("findForAccountingLockById")) {
                return invocation.proceed();
            }
            Barrier barrier = next.getAndSet(null);
            if (barrier != null && !barrier.after) {
                barrier.arrive();
            }
            Object result = invocation.proceed();
            if (barrier != null && barrier.after) {
                barrier.arrive();
            }
            return result;
        };
        proxy.addAdvice(0, interceptor);
        try {
            harness.moveDate(harness.today.plusDays(1));
            nativeFirst();
            harness.moveDate(harness.today.plusDays(1));
            readBeforeClosure();
        } finally {
            Barrier pending = next.getAndSet(null);
            if (pending != null) {
                pending.release.countDown();
            }
            proxy.removeAdvice(interceptor);
            harness.moveDate(initialDate);
        }
    }

    private void verifyBoundaries() throws Exception {
        long initialEpoch = harness.queryLong("select accounting_closure_version from m_office where id=1");
        ObjectNode original = receipt("office-original");
        JsonNode result = post(original, 200);
        long closure = close(harness.today).path("resourceId").asLong();
        var before = harness.counts();
        assertThat(post(original, 200)).isEqualTo(result);
        assertThat(harness.request("GET", ReceivablesDatabaseIntegrationTest.PREFIX + "/operations/office-original", null, 200))
                .isEqualTo(result);
        assertThat(post(receipt("office-equal-date"), 409).path("code").asText()).isEqualTo("PERIOD_CLOSED");
        ObjectNode earlier = receipt("office-before-date");
        String date = harness.today.minusDays(1).toString();
        earlier.put("businessDate", date);
        ((ObjectNode) earlier.get("source")).put("valueDate", date);
        earlier.put("executionMode", "CORRECTION");
        earlier.set("executionAuthorization", harness.json.value(Map.of("authorizationId", "office-before-date-grant", "scopeHash",
                "0".repeat(64), "approvedBy", "1", "effectiveFrom", date, "effectiveThrough", date)));
        harness.issueHistory(earlier);
        assertThat(post(earlier, 409).path("code").asText()).isEqualTo("PERIOD_CLOSED");
        assertThat(harness.counts()).isEqualTo(before);
        harness.moveDate(harness.today.plusDays(1));
        JsonNode open = post(receipt("office-after-date"), 200);
        assertThat(open.path("businessDate").asText()).isEqualTo(harness.today.toString());
        removeClosure(closure);
        assertThat(harness.queryLong("select accounting_closure_version from m_office where id=1")).isEqualTo(initialEpoch + 2);
    }

    private void nativeFirst() throws Exception {
        Barrier nativeHeld = new Barrier(true, true);
        Barrier closureAttempt = new Barrier(false, false);
        ObjectNode command = receipt("office-native-first");
        long loanTransactions = harness.queryLong("select count(*) from m_loan_transaction");
        next.set(nativeHeld);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var nativeResult = workers.submit(() -> post(command, 200));
            try {
                nativeHeld.awaitArrival();
                next.set(closureAttempt);
                var closureResult = workers.submit(() -> close(harness.today));
                closureAttempt.awaitArrival();
                assertThat(closureResult.isDone()).isFalse();
                nativeHeld.release.countDown();
                JsonNode result = nativeResult.get(60, TimeUnit.SECONDS);
                long closure = closureResult.get(60, TimeUnit.SECONDS).path("resourceId").asLong();
                assertThat(result.path("payloadHash").asText()).isEqualTo(harness.json.hash(command));
                assertThat(post(command, 200)).isEqualTo(result);
                assertPostedOnce();
                assertThat(harness.queryLong("select count(*) from m_loan_transaction")).isEqualTo(loanTransactions);
                removeClosure(closure);
            } finally {
                nativeHeld.release.countDown();
                closureAttempt.release.countDown();
            }
        }
    }

    private void readBeforeClosure() throws Exception {
        // execute has already read/locked configuration and read idempotency before this repository method is invoked.
        Barrier beforeLock = new Barrier(false, true);
        ObjectNode command = receipt("office-stale-snapshot");
        var before = harness.counts();
        next.set(beforeLock);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var nativeResult = workers.submit(() -> post(command, 409));
            long closure = 0;
            try {
                beforeLock.awaitArrival();
                closure = close(harness.today).path("resourceId").asLong();
                beforeLock.release.countDown();
                JsonNode rejected = nativeResult.get(60, TimeUnit.SECONDS);
                // The next statement sees the closure committed while the command waited for the office lock.
                assertThat(rejected.path("code").asText()).isEqualTo("PERIOD_CLOSED");
                assertThat(harness.counts()).isEqualTo(before);
                assertThat(post(command, 409).path("code").asText()).isEqualTo("PERIOD_CLOSED");
                assertThat(harness.counts()).isEqualTo(before);
            } finally {
                beforeLock.release.countDown();
                if (closure != 0) {
                    removeClosure(closure);
                }
            }
        }
    }

    private void assertPostedOnce() throws Exception {
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_command where operation_id='office-native-first'")).isEqualTo(1);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_cash_source where bank_source_id='office-native-first-bank'"))
                .isEqualTo(1);
        assertThat(harness.queryLong("select count(*) from m_mnzl_r_event e join m_mnzl_r_command c on c.record_key=e.operation_key "
                + "where c.operation_id='office-native-first'")).isEqualTo(1);
        assertThat(harness.queryLong("select sum(cast(l.amount_minor as decimal(19,0))) from m_mnzl_r_journal_line l "
                + "join m_mnzl_r_event e on e.record_key=l.event_key join m_mnzl_r_command c on c.record_key=e.operation_key "
                + "where c.operation_id='office-native-first'")).isEqualTo(2);
    }

    private ObjectNode receipt(String operation) {
        return harness.receiptCommand(operation, "account-1", "1");
    }

    private JsonNode post(ObjectNode command, int status) throws Exception {
        return harness.request("POST", ReceivablesDatabaseIntegrationTest.PREFIX + "/commands", command, status);
    }

    private JsonNode close(LocalDate date) throws Exception {
        return harness.request("POST", "/glclosures", harness.json.value(Map.of("officeId", 1, "closingDate", date.toString(), "dateFormat",
                "yyyy-MM-dd", "locale", "en", "comments", "Native accounting serialization")), 200);
    }

    private void removeClosure(long id) throws Exception {
        harness.request("DELETE", "/glclosures/" + id, null, 200);
    }

    private static final class Barrier {

        private final boolean after;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;

        private Barrier(boolean after, boolean pause) {
            this.after = after;
            this.release = new CountDownLatch(pause ? 1 : 0);
        }

        private void arrive() throws InterruptedException {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Accounting race barrier was not released");
            }
        }

        private void awaitArrival() throws InterruptedException {
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
