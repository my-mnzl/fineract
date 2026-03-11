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

import com.google.gson.JsonElement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.loanproduct.exception.LoanProductNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "mnzl.loan.instrument.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JdbcMnzlLoanProductStrategyService implements MnzlLoanProductStrategyReadService, MnzlLoanProductStrategyWriteService {

    private final JdbcTemplate jdbcTemplate;
    private final LoanProductRepository loanProductRepository;
    private final FromJsonHelper fromJsonHelper;

    @Override
    public MnzlLoanProductStrategyData findOne(Long loanProductId) {
        requireLoanProduct(loanProductId);
        try {
            return jdbcTemplate.queryForObject(
                    "select loan_product_id, instrument_code, schedule_strategy_code, charge_strategy_code, cob_strategy_code "
                            + "from m_mnzl_loan_product_strategy where loan_product_id = ?",
                    new MnzlLoanProductStrategyMapper(), loanProductId);
        } catch (EmptyResultDataAccessException e) {
            return MnzlLoanProductStrategyData.builder().loanProductId(loanProductId).build();
        }
    }

    @Override
    @Transactional
    public MnzlLoanProductStrategyData update(Long loanProductId, String json) {
        requireLoanProduct(loanProductId);
        final JsonElement element = fromJsonHelper.parse(json);
        final String instrumentCode = fromJsonHelper.extractStringNamed("instrumentCode", element);
        final String scheduleStrategyCode = fromJsonHelper.extractStringNamed("scheduleStrategyCode", element);
        final String chargeStrategyCode = fromJsonHelper.extractStringNamed("chargeStrategyCode", element);
        final String cobStrategyCode = fromJsonHelper.extractStringNamed("cobStrategyCode", element);

        int updated = jdbcTemplate.update(
                "update m_mnzl_loan_product_strategy set instrument_code = ?, schedule_strategy_code = ?, charge_strategy_code = ?, "
                        + "cob_strategy_code = ?, last_modified_date = CURRENT_TIMESTAMP where loan_product_id = ?",
                instrumentCode, scheduleStrategyCode, chargeStrategyCode, cobStrategyCode, loanProductId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "insert into m_mnzl_loan_product_strategy (loan_product_id, instrument_code, schedule_strategy_code, "
                            + "charge_strategy_code, cob_strategy_code, created_date, last_modified_date) values (?, ?, ?, ?, ?, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    loanProductId, instrumentCode, scheduleStrategyCode, chargeStrategyCode, cobStrategyCode);
        }
        return findOne(loanProductId);
    }

    private void requireLoanProduct(Long loanProductId) {
        loanProductRepository.findById(loanProductId).orElseThrow(() -> new LoanProductNotFoundException(loanProductId));
    }

    private static final class MnzlLoanProductStrategyMapper implements RowMapper<MnzlLoanProductStrategyData> {

        @Override
        public MnzlLoanProductStrategyData mapRow(ResultSet rs, int rowNum) throws SQLException {
            return MnzlLoanProductStrategyData.builder().loanProductId(rs.getLong("loan_product_id"))
                    .instrumentCode(rs.getString("instrument_code")).scheduleStrategyCode(rs.getString("schedule_strategy_code"))
                    .chargeStrategyCode(rs.getString("charge_strategy_code")).cobStrategyCode(rs.getString("cob_strategy_code")).build();
        }
    }
}
