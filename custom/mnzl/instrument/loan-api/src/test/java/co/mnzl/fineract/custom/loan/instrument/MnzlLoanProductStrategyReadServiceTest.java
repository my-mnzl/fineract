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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanProductStrategyReadServiceTest {

    private static final Long PRODUCT_ID = 42L;
    private static final Long OTHER_PRODUCT_ID = 99L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private LoanProductRepository loanProductRepository;

    @Mock
    private FromJsonHelper fromJsonHelper;

    private JdbcMnzlLoanProductStrategyService service;

    @BeforeEach
    void setUp() {
        service = new JdbcMnzlLoanProductStrategyService(jdbcTemplate, loanProductRepository, fromJsonHelper);
        when(loanProductRepository.findById(anyLong())).thenReturn(Optional.of(mockLoanProduct()));
    }

    private static LoanProduct mockLoanProduct() {
        return org.mockito.Mockito.mock(LoanProduct.class);
    }

    private static MnzlLoanProductStrategyData fullData(Long productId) {
        return MnzlLoanProductStrategyData.builder().loanProductId(productId).instrumentCode("MNZL_STANDARD_LOAN")
                .scheduleStrategyCode("MNZL_DECLINING_BALANCE").chargeStrategyCode("MNZL_INTEREST_AND_PENALTIES")
                .cobStrategyCode("MNZL_DUE_INSTALLMENTS").build();
    }

    // ---- Instrument code -------------------------------------------------------------------------

    @Test
    void findInstrumentCode_present_returnsOptionalOf() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(fullData(PRODUCT_ID));

        MnzlLoanProductStrategyData data = service.findOne(PRODUCT_ID);

        assertThat(Optional.ofNullable(data.getInstrumentCode())).contains("MNZL_STANDARD_LOAN");
    }

    @Test
    void findInstrumentCode_absent_returnsEmpty() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID)))
                .thenThrow(new EmptyResultDataAccessException(1));

        MnzlLoanProductStrategyData data = service.findOne(PRODUCT_ID);

        assertThat(Optional.ofNullable(data.getInstrumentCode())).isEmpty();
        assertThat(data.getLoanProductId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void findInstrumentCode_multipleProductsConfigured_returnsCorrectOne() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID)))
                .thenReturn(MnzlLoanProductStrategyData.builder().loanProductId(PRODUCT_ID).instrumentCode("MNZL_STANDARD_LOAN").build());
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(OTHER_PRODUCT_ID))).thenReturn(
                MnzlLoanProductStrategyData.builder().loanProductId(OTHER_PRODUCT_ID).instrumentCode("MNZL_BALLOON_LOAN").build());

        MnzlLoanProductStrategyData primary = service.findOne(PRODUCT_ID);
        MnzlLoanProductStrategyData other = service.findOne(OTHER_PRODUCT_ID);

        assertThat(primary.getInstrumentCode()).isEqualTo("MNZL_STANDARD_LOAN");
        assertThat(other.getInstrumentCode()).isEqualTo("MNZL_BALLOON_LOAN");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForObject(sqlCaptor.capture(), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), idCaptor.capture());
        assertThat(sqlCaptor.getAllValues()).allSatisfy(sql -> assertThat(sql).contains("loan_product_id = ?"));
        assertThat(idCaptor.getAllValues()).containsExactly(PRODUCT_ID, OTHER_PRODUCT_ID);
    }

    // ---- Schedule strategy code -----------------------------------------------------------------

    @Test
    void findScheduleStrategyCode_present_returnsOptionalOf() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(fullData(PRODUCT_ID));

        Optional<String> result = service.findScheduleStrategyCode(PRODUCT_ID);

        assertThat(result).contains("MNZL_DECLINING_BALANCE");
    }

    @Test
    void findScheduleStrategyCode_absent_returnsEmpty() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<String> result = service.findScheduleStrategyCode(PRODUCT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findScheduleStrategyCode_multipleProductsConfigured_returnsCorrectOne() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(MnzlLoanProductStrategyData
                .builder().loanProductId(PRODUCT_ID).scheduleStrategyCode("MNZL_DECLINING_BALANCE").build());
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(OTHER_PRODUCT_ID))).thenReturn(MnzlLoanProductStrategyData
                .builder().loanProductId(OTHER_PRODUCT_ID).scheduleStrategyCode("CORE_DEFAULT").build());

        assertThat(service.findScheduleStrategyCode(PRODUCT_ID)).contains("MNZL_DECLINING_BALANCE");
        assertThat(service.findScheduleStrategyCode(OTHER_PRODUCT_ID)).contains("CORE_DEFAULT");
    }

    // ---- Charge strategy code -------------------------------------------------------------------

    @Test
    void findChargeStrategyCode_present_returnsOptionalOf() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(fullData(PRODUCT_ID));

        Optional<String> result = service.findChargeStrategyCode(PRODUCT_ID);

        assertThat(result).contains("MNZL_INTEREST_AND_PENALTIES");
    }

    @Test
    void findChargeStrategyCode_absent_returnsEmpty() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<String> result = service.findChargeStrategyCode(PRODUCT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findChargeStrategyCode_multipleProductsConfigured_returnsCorrectOne() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(MnzlLoanProductStrategyData
                .builder().loanProductId(PRODUCT_ID).chargeStrategyCode("MNZL_INTEREST_AND_PENALTIES").build());
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(OTHER_PRODUCT_ID))).thenReturn(
                MnzlLoanProductStrategyData.builder().loanProductId(OTHER_PRODUCT_ID).chargeStrategyCode("CORE_DEFAULT").build());

        assertThat(service.findChargeStrategyCode(PRODUCT_ID)).contains("MNZL_INTEREST_AND_PENALTIES");
        assertThat(service.findChargeStrategyCode(OTHER_PRODUCT_ID)).contains("CORE_DEFAULT");
    }

    // ---- COB strategy code ----------------------------------------------------------------------

    @Test
    void findCobStrategyCode_present_returnsOptionalOf() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(fullData(PRODUCT_ID));

        Optional<String> result = service.findCobStrategyCode(PRODUCT_ID);

        assertThat(result).contains("MNZL_DUE_INSTALLMENTS");
    }

    @Test
    void findCobStrategyCode_absent_returnsEmpty() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<String> result = service.findCobStrategyCode(PRODUCT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findCobStrategyCode_multipleProductsConfigured_returnsCorrectOne() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(MnzlLoanProductStrategyData
                .builder().loanProductId(PRODUCT_ID).cobStrategyCode("MNZL_DUE_INSTALLMENTS").build());
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(OTHER_PRODUCT_ID))).thenReturn(
                MnzlLoanProductStrategyData.builder().loanProductId(OTHER_PRODUCT_ID).cobStrategyCode("CORE_DEFAULT").build());

        assertThat(service.findCobStrategyCode(PRODUCT_ID)).contains("MNZL_DUE_INSTALLMENTS");
        assertThat(service.findCobStrategyCode(OTHER_PRODUCT_ID)).contains("CORE_DEFAULT");
    }

    // ---- SQL shape sanity (filter by productId) -------------------------------------------------

    @Test
    void findOne_queriesByLoanProductId() {
        when(jdbcTemplate.queryForObject(any(String.class), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID))).thenReturn(fullData(PRODUCT_ID));

        service.findOne(PRODUCT_ID);

        verify(jdbcTemplate).queryForObject(contains("where loan_product_id = ?"), ArgumentMatchers.<RowMapper<MnzlLoanProductStrategyData>>any(), eq(PRODUCT_ID));
    }
}
