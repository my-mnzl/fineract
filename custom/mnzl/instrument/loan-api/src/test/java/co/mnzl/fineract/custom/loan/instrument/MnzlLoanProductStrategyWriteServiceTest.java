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
package co.mnzl.fineract.custom.loan.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanProductStrategyWriteServiceTest {

    private static final Long PRODUCT_ID = 42L;

    private static final String FULL_JSON = """
            {
              "instrumentCode": "MNZL_STANDARD_LOAN",
              "scheduleStrategyCode": "MNZL_DECLINING_BALANCE",
              "chargeStrategyCode": "MNZL_INTEREST_AND_PENALTIES",
              "cobStrategyCode": "MNZL_DUE_INSTALLMENTS"
            }
            """;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private LoanProductRepository loanProductRepository;

    // Production parses JSON via a real FromJsonHelper, so we use a real one instead of a mock.
    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private JdbcMnzlLoanProductStrategyService service;

    @BeforeEach
    void setUp() {
        service = new JdbcMnzlLoanProductStrategyService(jdbcTemplate, loanProductRepository, fromJsonHelper);
        when(loanProductRepository.findById(anyLong())).thenReturn(Optional.of(Mockito.mock(LoanProduct.class)));
        // Default: post-update findOne returns whatever the test stubs — not interested in the return value here.
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID)))
                .thenReturn(MnzlLoanProductStrategyData.builder().loanProductId(PRODUCT_ID).build());
    }

    // ---- INSERT path: row absent (UPDATE returns 0) ----------------------------------------------

    @Test
    void create_newRow_insertsAllFields() {
        // First call (UPDATE) returns 0 -> production falls through to INSERT.
        when(jdbcTemplate.update(startsWith("update m_mnzl_loan_product_strategy"), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.update(startsWith("insert into m_mnzl_loan_product_strategy"), any(), any(), any(), any(), any())).thenReturn(1);

        service.update(PRODUCT_ID, FULL_JSON);

        // Both UPDATE and INSERT are invoked (UPDATE first returning 0, then INSERT). Verify the INSERT call.
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> a1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a4 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a5 = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), a1.capture(), a2.capture(), a3.capture(), a4.capture(), a5.capture());

        // Both invocations captured; pick out the INSERT one.
        int insertIdx = -1;
        for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
            if (sqlCaptor.getAllValues().get(i).startsWith("insert into m_mnzl_loan_product_strategy")) {
                insertIdx = i;
                break;
            }
        }
        assertThat(insertIdx).as("insert call captured").isGreaterThanOrEqualTo(0);

        assertThat(sqlCaptor.getAllValues().get(insertIdx)).contains("loan_product_id", "instrument_code", "schedule_strategy_code",
                "charge_strategy_code", "cob_strategy_code");
        // Insert column order: (loan_product_id, instrument_code, schedule_strategy_code, charge_strategy_code, cob_strategy_code).
        assertThat(a1.getAllValues().get(insertIdx)).isEqualTo(PRODUCT_ID);
        assertThat(a2.getAllValues().get(insertIdx)).isEqualTo("MNZL_STANDARD_LOAN");
        assertThat(a3.getAllValues().get(insertIdx)).isEqualTo("MNZL_DECLINING_BALANCE");
        assertThat(a4.getAllValues().get(insertIdx)).isEqualTo("MNZL_INTEREST_AND_PENALTIES");
        assertThat(a5.getAllValues().get(insertIdx)).isEqualTo("MNZL_DUE_INSTALLMENTS");
    }

    // ---- UPDATE path: row exists (UPDATE returns 1) ---------------------------------------------

    @Test
    void update_existingRow_overwrites() {
        when(jdbcTemplate.update(startsWith("update m_mnzl_loan_product_strategy"), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class))).thenReturn(1);

        service.update(PRODUCT_ID, FULL_JSON);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg4 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg5 = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), arg1.capture(), arg2.capture(), arg3.capture(), arg4.capture(), arg5.capture());

        assertThat(sqlCaptor.getValue()).startsWith("update m_mnzl_loan_product_strategy").contains("instrument_code = ?",
                "schedule_strategy_code = ?", "charge_strategy_code = ?", "cob_strategy_code = ?", "loan_product_id = ?");
        // Update order is (instrumentCode, scheduleStrategyCode, chargeStrategyCode, cobStrategyCode, loanProductId).
        assertThat(arg1.getValue()).isEqualTo("MNZL_STANDARD_LOAN");
        assertThat(arg2.getValue()).isEqualTo("MNZL_DECLINING_BALANCE");
        assertThat(arg3.getValue()).isEqualTo("MNZL_INTEREST_AND_PENALTIES");
        assertThat(arg4.getValue()).isEqualTo("MNZL_DUE_INSTALLMENTS");
        assertThat(arg5.getValue()).isEqualTo(PRODUCT_ID);

        // INSERT must NOT be invoked when UPDATE found a row.
        verify(jdbcTemplate, never()).update(contains("insert into m_mnzl_loan_product_strategy"), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class));
    }

    // ---- Partial fields: production overwrites all 4 codes (nullable from JSON) -----------------

    @Test
    void update_partialFields_overwritesEverythingIncludingMissingAsNull() {
        // Production reads each field via extractStringNamed; missing fields become null and are written as null.
        // The validator (validateForUpdate) would catch this in a real flow, but the write service itself does
        // not re-validate. This test pins that behaviour so a future refactor that introduces partial-update
        // semantics will fail loudly.
        when(jdbcTemplate.update(startsWith("update m_mnzl_loan_product_strategy"), any(), any(), any(), any(), any())).thenReturn(1);

        String partialJson = """
                {
                  "scheduleStrategyCode": "MNZL_DECLINING_BALANCE"
                }
                """;
        service.update(PRODUCT_ID, partialJson);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg4 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg5 = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), arg1.capture(), arg2.capture(), arg3.capture(), arg4.capture(), arg5.capture());

        assertThat(sqlCaptor.getValue()).startsWith("update m_mnzl_loan_product_strategy");
        assertThat(arg1.getValue()).isNull(); // instrumentCode missing -> null
        assertThat(arg2.getValue()).isEqualTo("MNZL_DECLINING_BALANCE");
        assertThat(arg3.getValue()).isNull(); // chargeStrategyCode missing -> null
        assertThat(arg4.getValue()).isNull(); // cobStrategyCode missing -> null
        assertThat(arg5.getValue()).isEqualTo(PRODUCT_ID);
    }

    // ---- Delete: production does NOT expose a delete operation ----------------------------------
    // Skipped intentionally. MnzlLoanProductStrategyWriteService only declares update(...). Adding a
    // test here would either compile-fail (no method to call) or mock-only theatre. If a delete is
    // ever added, drop a test alongside it.

    // ---- Cross-cutting: post-write findOne is invoked to return the persisted view --------------

    @Test
    void update_invokesFindOneAfterWrite() {
        when(jdbcTemplate.update(startsWith("update m_mnzl_loan_product_strategy"), any(), any(), any(), any(), any())).thenReturn(1);

        service.update(PRODUCT_ID, FULL_JSON);

        verify(jdbcTemplate, times(1)).queryForObject(contains("where loan_product_id = ?"), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID));
    }
}
