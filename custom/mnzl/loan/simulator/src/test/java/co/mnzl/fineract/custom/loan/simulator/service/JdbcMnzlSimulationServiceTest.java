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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.simulator.api.MnzlSimulationApiJsonValidator;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import co.mnzl.fineract.custom.loan.simulator.domain.SimulationStatus;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.IntConsumer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
class JdbcMnzlSimulationServiceTest {

    private static final String UUID = "16cbe6be-1b7a-4570-a105-e6d7b4b5f032";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformSecurityContext securityContext;
    @Mock
    private MnzlSimulationApiJsonValidator validator;
    @Mock
    private MnzlLoanSimulationRunner runner;
    @Mock
    private TaskScheduler simulationHeartbeatScheduler;
    @Mock
    private ScheduledFuture<?> heartbeatFuture;

    private JdbcMnzlSimulationService service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>());
        service = new JdbcMnzlSimulationService(jdbcTemplate, new FromJsonHelper(), securityContext, validator, runner, Runnable::run,
                simulationHeartbeatScheduler);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingSimulationsRecoversTimedOutExecutions() {
        SimulationResult failed = SimulationResult.builder().uuid(UUID).status(SimulationStatus.FAILED).build();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(failed));

        assertThat(service.findByUuid(UUID).getStatus()).isEqualTo(SimulationStatus.FAILED);

        assertThat(updateStatements()).anySatisfy(sql -> {
            assertThat(sql).contains("COALESCE(last_heartbeat_at, started_at)");
            assertThat(sql).contains("execution_token = NULL");
        });
    }

    @Test
    void rerunWritesProgressAndTerminalStateOnlyForItsExecutionToken() {
        String scenario = """
                {
                  "name": "token-test",
                  "loanProductId": 1,
                  "principal": "1000",
                  "numberOfRepayments": 1,
                  "disbursementDate": "2026-01-01",
                  "locale": "en",
                  "actions": [{"type": "DISBURSE", "date": "2026-01-01"}]
                }
                """;
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0, 1, 1, 1);
        doReturn(heartbeatFuture).when(simulationHeartbeatScheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), any(Object[].class)))
                .thenReturn(scenario);
        when(runner.run(any(), any(IntConsumer.class))).thenAnswer(invocation -> {
            IntConsumer progress = invocation.getArgument(1);
            progress.accept(1);
            return SimulationResult.builder().status(SimulationStatus.COMPLETED).snapshots(List.of()).build();
        });

        assertThat(service.rerunSimulation(UUID).getStatus()).isEqualTo(SimulationStatus.RUNNING);

        assertThat(updateStatements()).anySatisfy(sql -> {
            assertThat(sql).contains("started_at = CURRENT_TIMESTAMP");
            assertThat(sql).contains("last_heartbeat_at = CURRENT_TIMESTAMP");
        }).anySatisfy(sql -> {
            assertThat(sql).contains("SET progress = ?");
            assertThat(sql).contains("last_heartbeat_at = CURRENT_TIMESTAMP");
            assertThat(sql).contains("execution_token = ? AND status = ?");
        }).anySatisfy(sql -> {
            assertThat(sql).contains("result_json = ?");
            assertThat(sql).contains("execution_token = ? AND status = ?");
        });

        ArgumentCaptor<Runnable> heartbeatCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(simulationHeartbeatScheduler).scheduleAtFixedRate(heartbeatCaptor.capture(), eq(Duration.ofMinutes(1)));
        heartbeatCaptor.getValue().run();
        assertThat(updateStatements()).anySatisfy(sql -> {
            assertThat(sql).contains("SET last_heartbeat_at = CURRENT_TIMESTAMP");
            assertThat(sql).contains("execution_token = ? AND status = ?");
        });
        verify(heartbeatFuture).cancel(false);
    }

    private List<String> updateStatements() {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .map(invocation -> invocation.<String>getArgument(0)).toList();
    }
}
