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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.Booking;
import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeReceivableBridge.NativeState;
import co.mnzl.fineract.receivables.math.ReceivablesMath;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The purchase journal under each fee treatment, for both purchase commands (the irregular admin fee vector). */
class ReceivablesPurchaseJournalTest {

    private static final LocalDate DATE = LocalDate.of(2030, 1, 1);
    private final ReceivablesJson json = new ReceivablesJson();
    private final ReceivablesStore store = mock(ReceivablesStore.class);
    private final NativeReceivableBridge nativeBridge = mock(NativeReceivableBridge.class);
    private final ReceivablesConfiguration configuration = mock(ReceivablesConfiguration.class);
    private final ReceivablesAcquisitions acquisitions = mock(ReceivablesAcquisitions.class);
    private final ReceivablesAccountCommands accounts = new ReceivablesAccountCommands(store, json,
            new ReceivablesMeasurement(store, json, configuration), nativeBridge, mock(ReceivablesCashCommands.class), configuration,
            acquisitions);

    ReceivablesPurchaseJournalTest() throws Exception {}

    @Test
    void upfrontAdminFeeIsIncomeAtPurchaseAndNothingIsDeferred() {
        var booked = book(ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION);
        assertThat(lines(booked.execution())).containsExactly("contractualReceivable DEBIT 15000000 FACE",
                "deferredDiscount CREDIT 2259162 DISCOUNT", "acquisitionClearing CREDIT 12613430 CASH",
                "adminFeeIncome CREDIT 127408 ADMIN_FEE");
        assertBalanced(booked.execution());
        assertThat(booked.account()).containsEntry("purchase_fee_minor", "127408").containsEntry("purchase_gross_minor", "12740838")
                .containsEntry("gross_minor", "12740838").containsEntry("net_minor", "12740838")
                .containsEntry("purchase_cash_minor", "12613430");
        verify(configuration).requireCalculation(booked.execution(), ReceivablesMath.UPFRONT_FEE_CALCULATION_VERSION);
    }

    @Test
    void deferredFeeVersionsBookExactlyAsBeforeUnderTheCurrentKey() {
        var booked = book(ReceivablesMath.CALCULATION_VERSION);
        assertThat(lines(booked.execution())).containsExactly("contractualReceivable DEBIT 15000000 FACE",
                "deferredDiscount CREDIT 2259162 DISCOUNT", "deferredAdminFee CREDIT 127408 ADMIN_FEE",
                "acquisitionClearing CREDIT 12613430 CASH");
        assertBalanced(booked.execution());
        assertThat(booked.account()).containsEntry("purchase_fee_minor", "127408").containsEntry("gross_minor", "12740838")
                .containsEntry("net_minor", "12613430");
    }

    private record Booked(ReceivablesExecution execution, Map<String, Object> account) {
    }

    @SuppressWarnings("unchecked")
    private Booked book(String version) {
        ObjectNode basis = json.object().put("calculationVersion", version).put("productPolicyCode", "EG_RECEIVABLES_V1")
                .put("schemaVersion", "1").put("policyRevisionId", "policy").put("calculatorBuild", "build")
                .put("settlementDate", DATE.toString()).put("corridorObservationId", "rate").put("corridorRate", "0.24").put("spread", "0")
                .put("feeRate", "0.01").put("sourceVersion", "1").put("sourceHash", "0".repeat(64));
        basis.putArray("acceptedAccountPrices").addObject().put("accountId", "account").put("grossPurchasePriceMinor", "12740838")
                .put("adminFeeMinor", "127408").put("netPurchaseCashMinor", "12613430");
        var flows = basis.putArray("cashflows");
        String[][] legs = { { "cf-01", "2030-04-01", "4000000" }, { "cf-02", "2030-07-30", "5000000" },
                { "cf-03", "2031-01-26", "6000000" } };
        for (String[] leg : legs) {
            flows.addObject().put("cashflowId", leg[0]).put("receivableId", "account").put("installmentId", leg[0]).put("dueDate", leg[1])
                    .put("amountMinor", leg[2]).put("currency", "EGP").put("scheduleVersionId", "schedule");
        }
        ObjectNode command = json.object().put("commandType", "BOOK_HISTORICAL_PURCHASE").put("operationId", "book")
                .put("businessDate", DATE.toString()).put("subjectId", "account").put("accountId", "account").put("dealId", "deal")
                .put("customerReferenceId", "customer").put("basisHash", json.hash(json.normalizedBasis(basis)));
        command.set("basis", basis);
        var e = new ReceivablesExecution(command, "scope", Map.of("policy_revision", "policy", "calculator_build", "build", "office_id", 1L,
                "product_id", 2L, "mapping_revision", "map"));
        when(store.find(anyString(), anyString())).thenReturn(null);
        when(acquisitions.register(e)).thenReturn("acquisition");
        when(nativeBridge.createClient(any())).thenReturn(3L);
        when(nativeBridge.bookExactFace(anyLong(), anyLong(), anyString(), eq(DATE), any()))
                .thenReturn(new Booking(4L, 5L, Map.of(), null));
        when(nativeBridge.readIndependentState(4L, DATE, "AFTER_EVENTS"))
                .thenReturn(new NativeState(4L, "ACTIVE", DATE, "AFTER_EVENTS", BigInteger.valueOf(15_000_000), List.of(), List.of()));
        when(store.require("account", e.accountKey("account")))
                .thenReturn(Map.of("record_key", e.accountKey("account"), "native_loan_id", 4L));
        accounts.book(e);
        ArgumentCaptor<Map<String, Object>> account = ArgumentCaptor.forClass(Map.class);
        verify(store).insert(eq("account"), eq(e.accountKey("account")), account.capture());
        verify(store).insert(eq("segment"), anyString(), anyMap());
        return new Booked(e, account.getValue());
    }

    private static List<String> lines(ReceivablesExecution e) {
        return e.lines.stream().map(l -> l.accountKey() + " " + l.side() + " " + l.amountMinor() + " " + l.component()).toList();
    }

    private static void assertBalanced(ReceivablesExecution e) {
        assertThat(e.lines.stream().map(l -> l.side().equals("DEBIT") ? l.amountMinor() : l.amountMinor().negate()).reduce(BigInteger.ZERO,
                BigInteger::add)).isZero();
    }
}
