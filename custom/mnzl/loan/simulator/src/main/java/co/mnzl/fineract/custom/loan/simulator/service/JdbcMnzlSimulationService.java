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
package co.mnzl.fineract.custom.loan.simulator.service;

import co.mnzl.fineract.custom.loan.simulator.api.MnzlSimulationApiJsonValidator;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationActionRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationRequest;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationActionType;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationStatus;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mnzl.loan.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcMnzlSimulationService implements MnzlSimulationReadService, MnzlSimulationWriteService {

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;
    private final FromJsonHelper fromJsonHelper;
    private final PlatformSecurityContext securityContext;
    private final MnzlSimulationApiJsonValidator validator;
    private final MnzlLoanSimulationRunner runner;

    @Override
    public SimulationResult findByUuid(String uuid) {
        List<SimulationResult> results = jdbcTemplate.query(
                "SELECT * FROM m_mnzl_simulation WHERE uuid = ?",
                new SimulationRowMapper(), uuid);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Simulation not found: " + uuid);
        }
        return results.get(0);
    }

    @Override
    public List<SimulationResult> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM m_mnzl_simulation ORDER BY created_date DESC",
                new SimulationRowMapper());
    }

    @Override
    @Transactional
    public SimulationResult runSimulation(String json) {
        validator.validateForCreate(json);

        SimulationRequest request = parseRequest(json);
        String uuid = UUID.randomUUID().toString();
        Long userId = securityContext.authenticatedUser().getId();

        // Insert initial record
        jdbcTemplate.update("""
                INSERT INTO m_mnzl_simulation (uuid, name, status, progress, total_actions,
                    loan_product_id, client_id, principal, interest_rate, number_of_repayments,
                    scenario_json, created_by, created_date)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                uuid, request.getName(), SimulationStatus.RUNNING.name(),
                request.getActions().size(), request.getLoanProductId(),
                request.getClientId(), request.getPrincipal(),
                request.getInterestRatePerPeriod(), request.getNumberOfRepayments(),
                json, userId);

        // Run the simulation
        SimulationResult result = runner.run(request);

        // Update with results
        jdbcTemplate.update("""
                UPDATE m_mnzl_simulation
                SET status = ?, result_json = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE uuid = ?
                """,
                result.getStatus().name(), GSON.toJson(result.getSnapshots()),
                result.getErrorMessage(), uuid);

        return SimulationResult.builder()
                .uuid(uuid)
                .name(result.getName())
                .status(result.getStatus())
                .errorMessage(result.getErrorMessage())
                .snapshots(result.getSnapshots())
                .build();
    }

    @Override
    @Transactional
    public SimulationResult rerunSimulation(String uuid) {
        SimulationResult existing = findByUuid(uuid);
        String scenarioJson = jdbcTemplate.queryForObject(
                "SELECT scenario_json FROM m_mnzl_simulation WHERE uuid = ?",
                String.class, uuid);

        SimulationRequest request = parseRequest(scenarioJson);

        // Update status to RUNNING
        jdbcTemplate.update("UPDATE m_mnzl_simulation SET status = ?, started_at = CURRENT_TIMESTAMP WHERE uuid = ?",
                SimulationStatus.RUNNING.name(), uuid);

        SimulationResult result = runner.run(request);

        jdbcTemplate.update("""
                UPDATE m_mnzl_simulation
                SET status = ?, result_json = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE uuid = ?
                """,
                result.getStatus().name(), GSON.toJson(result.getSnapshots()),
                result.getErrorMessage(), uuid);

        return SimulationResult.builder()
                .uuid(uuid)
                .name(result.getName())
                .status(result.getStatus())
                .errorMessage(result.getErrorMessage())
                .snapshots(result.getSnapshots())
                .build();
    }

    @Override
    @Transactional
    public void deleteSimulation(String uuid) {
        int rows = jdbcTemplate.update("DELETE FROM m_mnzl_simulation WHERE uuid = ?", uuid);
        if (rows == 0) {
            throw new IllegalArgumentException("Simulation not found: " + uuid);
        }
    }

    private SimulationRequest parseRequest(String json) {
        JsonObject root = fromJsonHelper.parse(json).getAsJsonObject();

        List<SimulationActionRequest> actions = new ArrayList<>();
        JsonArray actionsArray = root.getAsJsonArray("actions");
        if (actionsArray != null) {
            for (JsonElement element : actionsArray) {
                JsonObject actionObj = element.getAsJsonObject();
                SimulationActionRequest.SimulationActionRequestBuilder builder = SimulationActionRequest.builder()
                        .type(SimulationActionType.fromString(actionObj.get("type").getAsString()))
                        .date(LocalDate.parse(actionObj.get("date").getAsString(), DATE_FORMAT));
                if (actionObj.has("amount") && !actionObj.get("amount").isJsonNull()) {
                    builder.amount(actionObj.get("amount").getAsBigDecimal());
                }
                if (actionObj.has("chargeId") && !actionObj.get("chargeId").isJsonNull()) {
                    builder.chargeId(actionObj.get("chargeId").getAsLong());
                }
                actions.add(builder.build());
            }
        }

        return SimulationRequest.builder()
                .name(fromJsonHelper.extractStringNamed("name", root))
                .loanProductId(fromJsonHelper.extractLongNamed("loanProductId", root))
                .clientId(fromJsonHelper.extractLongNamed("clientId", root))
                .principal(fromJsonHelper.extractBigDecimalWithLocaleNamed("principal", root))
                .interestRatePerPeriod(fromJsonHelper.extractBigDecimalWithLocaleNamed("interestRatePerPeriod", root))
                .numberOfRepayments(fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", root))
                .disbursementDate(fromJsonHelper.extractStringNamed("disbursementDate", root))
                .actions(actions)
                .build();
    }

    private static class SimulationRowMapper implements RowMapper<SimulationResult> {

        @Override
        public SimulationResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SimulationResult.builder()
                    .uuid(rs.getString("uuid"))
                    .name(rs.getString("name"))
                    .status(SimulationStatus.fromString(rs.getString("status")))
                    .errorMessage(rs.getString("error_message"))
                    .build();
        }
    }
}
