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
import co.mnzl.fineract.custom.loan.simulator.data.SchedulePreviewPeriod;
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
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcMnzlSimulationService implements MnzlSimulationReadService, MnzlSimulationWriteService {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class,
                    (JsonSerializer<LocalDate>) (src, t, ctx) -> new JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE)))
            .registerTypeAdapter(LocalDate.class,
                    (JsonDeserializer<LocalDate>) (json, t, ctx) -> LocalDate.parse(json.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE))
            .create();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long EXECUTION_TIMEOUT_MINUTES = 30;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(1);
    private static final String EXECUTION_TIMEOUT_MESSAGE = "Simulation execution timed out before completion";

    private final JdbcTemplate jdbcTemplate;
    private final FromJsonHelper fromJsonHelper;
    private final PlatformSecurityContext securityContext;
    private final MnzlSimulationApiJsonValidator validator;
    private final MnzlLoanSimulationRunner runner;
    @Qualifier("simulationExecutor")
    private final TaskExecutor simulationExecutor;
    @Qualifier("simulationHeartbeatScheduler")
    private final TaskScheduler simulationHeartbeatScheduler;

    @Override
    public SimulationResult findByUuid(String uuid) {
        recoverTimedOutSimulations();
        List<SimulationResult> results = jdbcTemplate.query("SELECT * FROM m_mnzl_simulation WHERE uuid = ?", new SimulationRowMapper(),
                uuid);
        if (results.isEmpty()) {
            throw new SimulationNotFoundException(uuid);
        }
        return results.get(0);
    }

    @Override
    public List<SimulationResult> findAll(int offset, int limit) {
        recoverTimedOutSimulations();
        return jdbcTemplate.query("SELECT * FROM m_mnzl_simulation ORDER BY created_date DESC LIMIT ? OFFSET ?", new SimulationRowMapper(),
                limit, offset);
    }

    @Override
    public SimulationResult runSimulation(String json) {
        validator.validateForCreate(json);

        SimulationRequest request = parseRequest(json);
        String uuid = UUID.randomUUID().toString();
        String executionToken = UUID.randomUUID().toString();
        Long userId = securityContext.authenticatedUser().getId();

        // Insert initial record
        jdbcTemplate.update("""
                INSERT INTO m_mnzl_simulation (uuid, name, status, progress, total_actions,
                    loan_product_id, principal, interest_rate, number_of_repayments,
                    scenario_json, created_by, created_date, started_at, last_heartbeat_at, execution_token)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, uuid, request.getName(), SimulationStatus.RUNNING.name(), request.getActions().size(), request.getLoanProductId(),
                request.getPrincipal(),
                request.getInterestRateDifferential() != null ? request.getInterestRateDifferential()
                        : request.getInterestRatePerPeriod() != null ? request.getInterestRatePerPeriod() : BigDecimal.ZERO,
                request.getNumberOfRepayments(), json, userId, executionToken);

        // Capture context for async execution
        FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>(ThreadLocalContextUtil.getBusinessDates());
        SecurityContext secCtx = SecurityContextHolder.getContext();

        try {
            simulationExecutor.execute(() -> executeSimulation(uuid, executionToken, request, tenant, businessDates, secCtx));
        } catch (TaskRejectedException e) {
            log.error("Simulation {} rejected — executor queue full", uuid, e);
            failExecution(uuid, executionToken, "Executor queue full, try again later");
            return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage("Executor queue full, try again later").build();
        }

        return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.RUNNING).build();
    }

    @Override
    public SimulationResult rerunSimulation(String uuid) {
        recoverTimedOutSimulations();
        String executionToken = UUID.randomUUID().toString();
        int updated = jdbcTemplate.update("""
                UPDATE m_mnzl_simulation
                SET status = ?, progress = 0, error_message = NULL, completed_at = NULL,
                    started_at = CURRENT_TIMESTAMP, last_heartbeat_at = CURRENT_TIMESTAMP, execution_token = ?
                WHERE uuid = ? AND status != ?
                """, SimulationStatus.RUNNING.name(), executionToken, uuid, SimulationStatus.RUNNING.name());
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
            simulationExecutor.execute(() -> executeSimulation(uuid, executionToken, request, tenant, businessDates, secCtx));
        } catch (TaskRejectedException e) {
            log.error("Simulation {} rerun rejected — executor queue full", uuid, e);
            failExecution(uuid, executionToken, "Executor queue full, try again later");
            return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.FAILED)
                    .errorMessage("Executor queue full, try again later").build();
        }

        return SimulationResult.builder().uuid(uuid).name(request.getName()).status(SimulationStatus.RUNNING).build();
    }

    @Override
    public List<SchedulePreviewPeriod> previewSchedule(String json) {
        validator.validateForPreview(json);
        SimulationRequest request = parseRequest(json);
        return runner.previewSchedule(request);
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

    private void executeSimulation(String uuid, String executionToken, SimulationRequest request, FineractPlatformTenant tenant,
            HashMap<BusinessDateType, LocalDate> businessDates, SecurityContext secCtx) {
        ThreadLocalContextUtil.setTenant(tenant);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
        SecurityContextHolder.setContext(secCtx);
        MDC.put("simulationUuid", uuid);
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = simulationHeartbeatScheduler.scheduleAtFixedRate(() -> refreshHeartbeat(uuid, executionToken, tenant),
                    HEARTBEAT_INTERVAL);
            SimulationResult result = runner.run(request, progress -> jdbcTemplate.update("""
                    UPDATE m_mnzl_simulation SET progress = ?, last_heartbeat_at = CURRENT_TIMESTAMP
                    WHERE uuid = ? AND execution_token = ? AND status = ?
                    """, progress, uuid, executionToken, SimulationStatus.RUNNING.name()));
            jdbcTemplate.update("""
                    UPDATE m_mnzl_simulation
                    SET status = ?, result_json = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP, execution_token = NULL
                    WHERE uuid = ? AND execution_token = ? AND status = ?
                    """, result.getStatus().name(), GSON.toJson(result.getSnapshots()), result.getErrorMessage(), uuid, executionToken,
                    SimulationStatus.RUNNING.name());
        } catch (Exception e) {
            log.error("Simulation {} failed unexpectedly", uuid, e);
            failExecution(uuid, executionToken, e.getMessage());
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            MDC.remove("simulationUuid");
            SecurityContextHolder.clearContext();
            ThreadLocalContextUtil.reset();
        }
    }

    private void refreshHeartbeat(String uuid, String executionToken, FineractPlatformTenant tenant) {
        ThreadLocalContextUtil.setTenant(tenant);
        try {
            jdbcTemplate.update("""
                    UPDATE m_mnzl_simulation SET last_heartbeat_at = CURRENT_TIMESTAMP
                    WHERE uuid = ? AND execution_token = ? AND status = ?
                    """, uuid, executionToken, SimulationStatus.RUNNING.name());
        } catch (RuntimeException e) {
            log.warn("Failed to refresh heartbeat for simulation {}", uuid, e);
        } finally {
            ThreadLocalContextUtil.reset();
        }
    }

    private void failExecution(String uuid, String executionToken, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE m_mnzl_simulation
                SET status = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP, execution_token = NULL
                WHERE uuid = ? AND execution_token = ? AND status = ?
                """, SimulationStatus.FAILED.name(), errorMessage, uuid, executionToken, SimulationStatus.RUNNING.name());
    }

    private void recoverTimedOutSimulations() {
        Timestamp staleThreshold = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("UTC")).minusMinutes(EXECUTION_TIMEOUT_MINUTES));
        int recovered = jdbcTemplate.update("""
                UPDATE m_mnzl_simulation
                SET status = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP, execution_token = NULL
                WHERE status = ? AND (COALESCE(last_heartbeat_at, started_at) IS NULL
                    OR COALESCE(last_heartbeat_at, started_at) < ?)
                """, SimulationStatus.FAILED.name(), EXECUTION_TIMEOUT_MESSAGE, SimulationStatus.RUNNING.name(), staleThreshold);
        if (recovered > 0) {
            log.warn("Marked {} timed-out simulation execution(s) as failed", recovered);
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
                .interestRateDifferential(fromJsonHelper.extractBigDecimalWithLocaleNamed("interestRateDifferential", root))
                .numberOfRepayments(fromJsonHelper.extractIntegerWithLocaleNamed("numberOfRepayments", root))
                .repaymentEvery(fromJsonHelper.extractIntegerWithLocaleNamed("repaymentEvery", root))
                .repaymentFrequencyType(fromJsonHelper.extractIntegerWithLocaleNamed("repaymentFrequencyType", root))
                .disbursementDate(fromJsonHelper.extractStringNamed("disbursementDate", root))
                .submittedOnDate(fromJsonHelper.extractStringNamed("submittedOnDate", root))
                .approvedOnDate(fromJsonHelper.extractStringNamed("approvedOnDate", root))
                .interestChargedFromDate(fromJsonHelper.extractStringNamed("interestChargedFromDate", root))
                .firstRepaymentOnDate(fromJsonHelper.extractStringNamed("firstRepaymentOnDate", root)).actions(actions).build();
    }

    private static final Type SNAPSHOT_LIST_TYPE = new TypeToken<List<SimulationSnapshot>>() {}.getType();

    private final class SimulationRowMapper implements RowMapper<SimulationResult> {

        @Override
        public SimulationResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            String resultJson = rs.getString("result_json");
            List<SimulationSnapshot> snapshots = resultJson != null ? GSON.fromJson(resultJson, SNAPSHOT_LIST_TYPE)
                    : Collections.emptyList();

            String scenarioJson = rs.getString("scenario_json");
            SimulationRequest request = scenarioJson != null ? safeParseRequest(scenarioJson) : null;

            return SimulationResult.builder().uuid(rs.getString("uuid")).name(rs.getString("name"))
                    .status(SimulationStatus.fromString(rs.getString("status"))).errorMessage(rs.getString("error_message"))
                    .snapshots(snapshots).request(request).build();
        }
    }

    private SimulationRequest safeParseRequest(String json) {
        try {
            return parseRequest(json);
        } catch (RuntimeException e) {
            log.warn("Failed to parse scenario_json for saved simulation: {}", e.getMessage());
            return null;
        }
    }
}
