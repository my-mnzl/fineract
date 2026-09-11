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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.receivables.nativeinstrument.NativeHelBridge.JournalLine;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class NativeHelBridgeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 10);
    private final LoanRepository loans = mock(LoanRepository.class);
    private final LoanWritePlatformService writer = mock(LoanWritePlatformService.class);
    private final ProductToGLAccountMappingRepository mappings = mock(ProductToGLAccountMappingRepository.class);
    private final PaymentTypeRepository paymentTypes = mock(PaymentTypeRepository.class);
    private final ConfigurationDomainService config = mock(ConfigurationDomainService.class);
    private final Loan loan = mock(Loan.class);
    private final List<LoanTransaction> transactions = new ArrayList<>();
    private final AtomicReference<LoanStatus> status = new AtomicReference<>(LoanStatus.APPROVED);
    private NativeHelBridge bridge;
    private MonetaryCurrency currency;

    @BeforeEach
    void setup() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "hel-test", "HEL Test", "UTC", null));
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, DATE)));
        MoneyHelper.initializeTenantRoundingMode("hel-test", 6);
        currency = new MonetaryCurrency("EGP", 2, 1);
        when(loan.getId()).thenReturn(1L);
        when(loan.getTermPeriodFrequencyType()).thenReturn(org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.MONTHS);
        when(loan.getTermFrequency()).thenReturn(12);
        when(loan.getClientId()).thenReturn(2L);
        when(loan.productId()).thenReturn(3L);
        when(loan.getCurrencyCode()).thenReturn("EGP");
        when(loan.getCurrency()).thenReturn(currency);
        when(loan.getLoanProduct()).thenReturn(mock(LoanProduct.class));
        when(loan.getPrincipal()).thenReturn(Money.of(currency, new BigDecimal("1000")));
        when(loan.getLoanProductRelatedDetail()).thenReturn(LoanProductRelatedDetail.fixedReceivable(currency, new BigDecimal("1000"), 12));
        when(loan.getLoanTransactions()).thenReturn(transactions);
        when(loan.getStatus()).thenAnswer(ignored -> status.get());
        when(loans.findByExternalId(any())).thenReturn(Optional.of(loan));
        when(loans.findById(1L)).thenReturn(Optional.of(loan));
        GLAccount clearing = mock(GLAccount.class);
        when(clearing.getId()).thenReturn(90L);
        when(mappings.findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(3L, 1, 1, 8L))
                .thenReturn(new ProductToGLAccountMapping().setGlAccount(clearing));
        when(paymentTypes.findById(8L)).thenReturn(Optional.of(mock(PaymentType.class)));
        bridge = spy(new NativeHelBridge(loans, writer, mappings, paymentTypes, config, mock(FromJsonHelper.class),
                mock(EntityManager.class), mock(JdbcTemplate.class)));
    }

    @AfterEach
    void cleanup() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCacheForTenant("hel-test");
    }

    private LoanTransaction transaction(long id, LoanTransactionType type, String amount) {
        LoanTransaction tx = mock(LoanTransaction.class);
        when(tx.getId()).thenReturn(id);
        when(tx.getTypeOf()).thenReturn(type);
        when(tx.getAmount()).thenReturn(new BigDecimal(amount));
        return tx;
    }

    private List<JournalLine> journals(boolean fees) {
        ArrayList<JournalLine> lines = new ArrayList<>(
                List.of(new JournalLine(11, 80, 10, "DEBIT", BigInteger.valueOf(100000), "EGP", DATE, "HEL_PRINCIPAL"),
                        new JournalLine(12, 90, 10, "CREDIT", BigInteger.valueOf(100000), "EGP", DATE, "CLEARING")));
        if (fees) {
            lines.add(new JournalLine(13, 90, 20, "DEBIT", BigInteger.valueOf(1000), "EGP", DATE, "CLEARING"));
            lines.add(new JournalLine(14, 81, 20, "CREDIT", BigInteger.valueOf(1000), "EGP", DATE, "FINANCED_FEE"));
        }
        return lines;
    }

    private void complete(boolean fees) {
        doAnswer(ignored -> {
            status.set(LoanStatus.ACTIVE);
            transactions.add(transaction(10, LoanTransactionType.DISBURSEMENT, "1000"));
            if (fees) {
                transactions.add(transaction(20, LoanTransactionType.REPAYMENT_AT_DISBURSEMENT, "10"));
            }
            return null;
        }).when(writer).disburseLoan(eq(1L), any(), eq(false), eq(true));
        doReturn(journals(fees)).when(bridge).readJournals(eq(1L), any(), eq(90L));
    }

    @Test
    void ordinaryFundingUsesPaymentChannelAndActualNativeReadback() {
        complete(false);
        assertThat(bridge.readTerms("application").status()).isEqualTo("APPROVED");
        assertThat(bridge.readTerms("application").tenure()).isEqualTo(12);
        var result = bridge.fund("application", DATE, BigInteger.valueOf(100000), BigInteger.ZERO, List.of(), 8, 90, "hel-operation");
        assertThat(result.clearingAmountMinor()).isEqualTo(100000);
        assertThat(result.nativeTransactionIds()).containsExactly(10L);
        ArgumentCaptor<JsonCommand> command = ArgumentCaptor.forClass(JsonCommand.class);
        verify(writer).disburseLoan(eq(1L), command.capture(), eq(false), eq(true));
        assertThat(command.getValue().parsedJson().getAsJsonObject().get("paymentTypeId").getAsLong()).isEqualTo(8);
        assertThat(command.getValue().parsedJson().getAsJsonObject().get("externalId").getAsString()).isEqualTo("hel-operation");
    }

    @Test
    void financedFeeIsWithheldFromClearingUsingNativeFeeJournal() {
        LoanCharge fee = mock(LoanCharge.class);
        when(fee.getId()).thenReturn(4L);
        var definition = mock(org.apache.fineract.portfolio.charge.domain.Charge.class);
        when(definition.getId()).thenReturn(14L);
        when(fee.getCharge()).thenReturn(definition);
        when(fee.isDueAtDisbursement()).thenReturn(true);
        when(fee.amount()).thenReturn(new BigDecimal("10"));
        when(fee.getChargePaymentMode()).thenReturn(ChargePaymentMode.REGULAR);
        when(fee.getAmountPaid(currency)).thenReturn(Money.zero(currency));
        when(loan.getActiveCharges()).thenReturn(Set.of(fee));
        when(config.isPaymentTypeApplicableForDisbursementCharge()).thenReturn(true);
        complete(true);
        var result = bridge.fund("application", DATE, BigInteger.valueOf(100000), BigInteger.valueOf(1000), List.of(4L), 8, 90,
                "hel-operation");
        assertThat(result.clearingAmountMinor()).isEqualTo(99000);
        assertThat(result.journals()).hasSize(4);
    }

    @Test
    void changedTermsCannotInvokeDisbursement() {
        assertThatThrownBy(
                () -> bridge.fund("application", DATE, BigInteger.valueOf(100001), BigInteger.ZERO, List.of(), 8, 90, "hel-operation"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void approvedLoanCannotUseUnmappedClearingAccount() {
        assertThatThrownBy(
                () -> bridge.fund("application", DATE, BigInteger.valueOf(100000), BigInteger.ZERO, List.of(), 8, 91, "hel-operation"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void missingOrUnbalancedNativeJournalsFailTheOuterTransaction() {
        complete(false);
        doReturn(List.of(journals(false).getFirst())).when(bridge).readJournals(eq(1L), any(), eq(90L));
        assertThatThrownBy(
                () -> bridge.fund("application", DATE, BigInteger.valueOf(100000), BigInteger.ZERO, List.of(), 8, 90, "hel-operation"))
                .isInstanceOf(IllegalStateException.class);
    }
}
