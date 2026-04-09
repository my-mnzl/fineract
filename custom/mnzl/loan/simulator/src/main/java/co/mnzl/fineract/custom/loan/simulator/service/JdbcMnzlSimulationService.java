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
import co.mnzl.fineract.custom.loan.simulator.data.SimulationSnapshot;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationActionType;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationStatus;
import co.mnzl.fineract.custom.loan.simulator.exception.SimulationNotFoundException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mnzl.loan.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcMnzlSimulationService implements MnzlSimulationReadService, MnzlSimulationWriteService {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class,
                    (JsonSerializer<LocalDate>) (src, t, ctx) -> new JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE)))
            .registerTypeAdapter(LocalDate.class,
                    (JsonDeserializer<LocalDate>) (json, t, ctx) -> LocalDate.parse(json.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE))
            .create();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;
    private final FromJsonHelper fromJsonHelper;
    private final PlatformSecurityContext securityContext;
    private final MnzlSimulationApiJsonValidator validator;
    private final MnzlLoanSimulationRunner runner;
    private final TaskExecutor simulationExecutor;

    @Override
    public SimulationResult findByUuid(String uuid) {
        List<SimulationResult> results = jdbcTemplate.query("SELECT * FROM m_mnzl_simulation WHERE uuid = ?", new SimulationRowMapper(),
                uuid);
        if (results.isEmpty()) {
            throw new SimulationNotFoundException(uuid);
        }
        return results.get(0);
    }

    @Override
    public List<SimulationResult> findAll(int offset, int limit) {
        return jdbcTemplate.query("SELECT * FROM m_mnzl_simulation ORDER BY created_date DESC LIMIT ? OFFSET ?", new SimulationRowMapper(),
                limit, offset);
    }

    @Override
    public SimulationResult runSimulation(String json) {
        validator.validateForCreate(json);

        SimulationRequest request = parseRequest(json);
        String uuid = UUID.randomUUID().toString();
        Long userId = securityContext.authenticatedUser().getId();

        // Insert initial record
        jdbcTemplate.update("""
                INSERT INTO m_mnzl_simulation (uuid, name, status, progress, total_actions,
                    loan_product_id, principal, interest_rate, number_of_repayments,
                    scenario_json, created_by, created_date, started_at)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, uuid, request.getName(), SimulationStatus.RUNNING.name(), request.getActions().size(), request.getLoanProductId(),
                request.getPrincipal(), request.getInterestRatePerPeriod(), request.getNumberOfRepayments(), json, userId);

        // Capture context for async execution
        FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>(ThreadLocalContextUtil.getBusinessDates());
        SecurityContext secCtx = SecurityContextHolder.getContext();

        try {
            simulationExecutor.execute(() -> executeSimulation(uuid, request, tenant, businessDates, secCtx));
        } catch (TaskRejectedException e) {
            log.error("Simulation {} rejected — executor queue full", uuid, e);
            jdbcTemplate.update("UPDATE m_mnzl_simulation SET status = ?, error_message = ? WHERE uuid = ?", SimulationStatus.FAILED.name(),
                    "Executor queue full, try again later", uuid);
            return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage("Executor queue full, try again later").build();
        }

        return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.RUNNING).build();
    }

    @Override
    public SimulationResult rerunSimulation(String uuid) {
        // Atomic check-and-set: allow transition if not RUNNING, or if RUNNING but stale (>30 min)
        Timestamp staleThreshold = Timestamp.valueOf(LocalDateTime.now().minusMinutes(30));
        int updated = jdbcTemplate.update("""
                UPDATE m_mnzl_simulation SET status = ?, progress = 0, started_at = CURRENT_TIMESTAMP
                WHERE uuid = ? AND (status != ? OR started_at < ?)
                """, SimulationStatus.RUNNING.name(), uuid, SimulationStatus.RUNNING.name(), staleThreshold);
        if (updated == 0) {
            findByUuid(uuid); // throws SimulationNotFoundException if uuid is invalid
            throw new IllegalStateException("Simulation " + uuid + " is already running");
        }
        String scenarioJson = jdbcTemplate.queryForObject("SELECT scenario_json FROM m_mnzl_simulation WHERE uuid = ?", String.class, uuid);

        SimulationRequest request = parseRequest(scenarioJson);

        // Capture context for async execution
        FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>(ThreadLocalContextUtil.getBusinessDates());
        SecurityContext secCtx = SecurityContextHolder.getContext();

        try {
            simulationExecutor.execute(() -> executeSimulation(uuid, request, tenant, businessDates, secCtx));
        } catch (TaskRejectedException e) {
            log.error("Simulation {} rerun rejected — executor queue full", uuid, e);
            jdbcTemplate.update("UPDATE m_mnzl_simulation SET status = ?, error_message = ? WHERE uuid = ?", SimulationStatus.FAILED.name(),
                    "Executor queue full, try again later", uuid);
            return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage("Executor queue full, try again later").build();
        }

        return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.RUNNING).build();
    }

    @Override
    @Transactional
    public void deleteSimulation(String uuid) {
        SimulationResult existing = findByUuid(uuid);
        if (existing.getStatus() == SimulationStatus.RUNNING) {
            throw new IllegalStateException("Cannot delete a simulation that is currently RUNNING");
        }
        int rows = jdbcTemplate.update("DELETE FROM m_mnzl_simulation WHERE uuid = ? AND status != ?", uuid,
                SimulationStatus.RUNNING.name());
        if (rows == 0) {
            throw new SimulationNotFoundException(uuid);
        }
    }

    private void executeSimulation(String uuid, SimulationRequest request, FineractPlatformTenant tenant,
            HashMap<BusinessDateType, LocalDate> businessDates, SecurityContext secCtx) {
        ThreadLocalContextUtil.setTenant(tenant);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
        SecurityContextHolder.setContext(secCtx);
        MDC.put("simulationUuid", uuid);
        try {
            SimulationResult result = runner.run(request,
                    progress -> jdbcTemplate.update("UPDATE m_mnzl_simulation SET progress = ? WHERE uuid = ?", progress, uuid));
            jdbcTemplate.update("""
                    UPDATE m_mnzl_simulation
                    SET status = ?, result_json = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP
                    WHERE uuid = ?
                    """, result.getStatus().name(), GSON.toJson(result.getSnapshots()), result.getErrorMessage(), uuid);
        } catch (Exception e) {
            log.error("Simulation {} failed unexpectedly", uuid, e);
            jdbcTemplate.update("""
                    UPDATE m_mnzl_simulation
                    SET status = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP
                    WHERE uuid = ?
                    """, SimulationStatus.FAILED.name(), e.getMessage(), uuid);
        } finally {
            MDC.remove("simulationUuid");
            SecurityContextHolder.clearContext();
            ThreadLocalContextUtil.reset();
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
                if (actionObj.has("rate") && !actionObj.get("rate").isJsonNull()) {
                    builder.rate(actionObj.get("rate").getAsBigDecimal());
                }
                actions.add(builder.build());
            }
        }

        return SimulationRequest.builder().name(fromJsonHelper.extractStringNamed("name", root))
                .loanProductId(fromJsonHelper.extractLongNamed("loanProductId", root))
                .principal(fromJsonHelper.extractBigDecimalWithLocaleNamed("principal", root))
                .interestRatePerPeriod(fromJsonHelper.extractBigDecimalWithLocaleNamed("interestRatePerPeriod", root))
                .numberOfRepayments(fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", root))
                .repaymentEvery(fromJsonHelper.extractIntegerWithLocaleNamed("repaymentEvery", root))
                .repaymentFrequencyType(fromJsonHelper.extractIntegerWithLocaleNamed("repaymentFrequencyType", root))
                .disbursementDate(fromJsonHelper.extractStringNamed("disbursementDate", root))
                .submittedOnDate(fromJsonHelper.extractStringNamed("submittedOnDate", root))
                .approvedOnDate(fromJsonHelper.extractStringNamed("approvedOnDate", root))
                .interestChargedFromDate(fromJsonHelper.extractStringNamed("interestChargedFromDate", root)).actions(actions).build();
    }

    private static final Type SNAPSHOT_LIST_TYPE = new TypeToken<List<SimulationSnapshot>>() {}.getType();

    private static final class SimulationRowMapper implements RowMapper<SimulationResult> {

        @Override
        public SimulationResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            String resultJson = rs.getString("result_json");
            List<SimulationSnapshot> snapshots = resultJson != null ? GSON.fromJson(resultJson, SNAPSHOT_LIST_TYPE)
                    : Collections.emptyList();

            return SimulationResult.builder().uuid(rs.getString("uuid")).name(rs.getString("name"))
                    .status(SimulationStatus.fromString(rs.getString("status"))).errorMessage(rs.getString("error_message"))
                    .snapshots(snapshots).build();
        }
    }
}
