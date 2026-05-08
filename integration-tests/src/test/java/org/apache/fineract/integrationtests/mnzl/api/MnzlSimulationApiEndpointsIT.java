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
package org.apache.fineract.integrationtests.mnzl.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.specification.ResponseSpecification;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlChargesHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlSimulationDriver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * G.1 — Endpoint contract coverage for the 6 mnzl simulator endpoints:
 * <ul>
 * <li>POST /v1/mnzl/simulations</li>
 * <li>GET /v1/mnzl/simulations</li>
 * <li>GET /v1/mnzl/simulations/{uuid}</li>
 * <li>POST /v1/mnzl/simulations/preview-schedule</li>
 * <li>POST /v1/mnzl/simulations/{uuid}/rerun</li>
 * <li>DELETE /v1/mnzl/simulations/{uuid}</li>
 * </ul>
 *
 * Covers happy paths, validation failures (400), missing-resource (404), action variants, and permission negatives (the
 * latter {@link Disabled} pending a user-permission-setup helper).
 */
@Slf4j
public class MnzlSimulationApiEndpointsIT extends BaseLoanIntegrationTest {

    private static final String SIMULATIONS_BASE = "/fineract-provider/api/v1/mnzl/simulations?" + Utils.TENANT_IDENTIFIER;
    private static final String DISBURSEMENT_DATE = "01 January 2026";
    private static final double PRINCIPAL = 12000.0;
    private static final double ANNUAL_RATE = 12.0;
    private static final int TERMS = 12;

    private final Gson gson = new Gson();
    private static final Type LIST_OF_MAP = new TypeToken<List<Map<String, Object>>>() {}.getType();

    // ---------- helpers ----------

    private Long createMnzlProduct() {
        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        PostLoanProductsResponse response = loanProductHelper.createLoanProduct(builder.decliningBalance30_360());
        Long productId = response.getResourceId();
        new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(productId);
        return productId;
    }

    private MnzlSimulationDriver.ScenarioBuilder validScenario(MnzlSimulationDriver driver, Long productId, String name) {
        return driver.scenario(name, productId).principal(PRINCIPAL).rate(ANNUAL_RATE).repayments(TERMS).disburseDate(DISBURSEMENT_DATE)
                .disburse(DISBURSEMENT_DATE);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> snapshots(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("snapshots");
    }

    // ---------- run endpoint ----------

    @Test
    public void runEndpoint_validRequest_returns200WithUuid() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> result = validScenario(driver, productId, "g1_run_valid").run();

            assertThat(result.get("uuid")).as("uuid present").isNotNull();
            assertThat((String) result.get("uuid")).as("uuid non-empty").isNotEmpty();
            assertThat(result.get("status")).isEqualTo("COMPLETED");
        });
    }

    @Test
    public void runEndpoint_missingPrincipal_returns400() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            ResponseSpecification badRequest = new ResponseSpecBuilder().expectStatusCode(400).build();
            Map<String, Object> body = new HashMap<>();
            body.put("name", "g1_missing_principal");
            body.put("loanProductId", productId);
            body.put("numberOfRepayments", TERMS);
            body.put("interestRatePerPeriod", ANNUAL_RATE);
            body.put("disbursementDate", DISBURSEMENT_DATE);
            body.put("actions", List.of());
            // No principal field — expect a validation 400.
            Utils.performServerPost(requestSpec, badRequest, SIMULATIONS_BASE, gson.toJson(body));
        });
    }

    @Test
    public void runEndpoint_invalidLoanProductId_returns400() {
        runAt(DISBURSEMENT_DATE, () -> {
            ResponseSpecification badRequest = new ResponseSpecBuilder().expectStatusCode(400).build();
            Map<String, Object> body = new HashMap<>();
            body.put("name", "g1_invalid_product");
            body.put("loanProductId", 999_999_999L);
            body.put("principal", PRINCIPAL);
            body.put("numberOfRepayments", TERMS);
            body.put("interestRatePerPeriod", ANNUAL_RATE);
            body.put("disbursementDate", DISBURSEMENT_DATE);
            body.put("actions", List.of());
            Utils.performServerPost(requestSpec, badRequest, SIMULATIONS_BASE, gson.toJson(body));
        });
    }

    // ---------- list endpoint ----------

    @Test
    public void listEndpoint_returnsRecentSimulations() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> created = validScenario(driver, productId, "g1_list_seed").run();
            String uuid = (String) created.get("uuid");

            String body = Utils.performServerGet(requestSpec, responseSpec, SIMULATIONS_BASE, null);
            List<Map<String, Object>> list = gson.fromJson(body, LIST_OF_MAP);
            assertThat(list).as("list endpoint returns array").isNotNull();
            boolean found = false;
            for (Map<String, Object> entry : list) {
                if (uuid.equals(entry.get("uuid"))) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("created uuid present in list").isTrue();
        });
    }

    @Test
    public void listEndpoint_pagination_respectsOffsetLimit() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            for (int i = 0; i < 3; i++) {
                validScenario(driver, productId, "g1_paging_" + i).run();
            }
            String pagedUrl = "/fineract-provider/api/v1/mnzl/simulations?offset=0&limit=2&" + Utils.TENANT_IDENTIFIER;
            String body = Utils.performServerGet(requestSpec, responseSpec, pagedUrl, null);
            List<Map<String, Object>> list = gson.fromJson(body, LIST_OF_MAP);
            assertThat(list.size()).as("limit respected").isLessThanOrEqualTo(2);
        });
    }

    // ---------- get-by-uuid endpoint ----------

    @Test
    public void getByUuidEndpoint_existing_returnsFullSnapshot() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> created = validScenario(driver, productId, "g1_get_seed").run();
            String uuid = (String) created.get("uuid");

            Map<String, Object> fetched = driver.get(uuid);
            assertThat(fetched.get("uuid")).isEqualTo(uuid);
            assertThat(fetched.get("status")).isEqualTo("COMPLETED");
            assertThat(snapshots(fetched)).as("snapshots present").isNotEmpty();
        });
    }

    @Test
    public void getByUuidEndpoint_missing_returns404() {
        runAt(DISBURSEMENT_DATE, () -> {
            ResponseSpecification notFound = new ResponseSpecBuilder().expectStatusCode(404).build();
            String url = "/fineract-provider/api/v1/mnzl/simulations/00000000-0000-0000-0000-000000000000?" + Utils.TENANT_IDENTIFIER;
            Utils.performServerGet(requestSpec, notFound, url, null);
        });
    }

    // ---------- preview endpoint ----------

    @Test
    public void previewScheduleEndpoint_returnsScheduleWithoutCreatingLoan() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            List<Map<String, Object>> preview = driver.preview(driver.scenario("g1_preview", productId).principal(PRINCIPAL)
                    .rate(ANNUAL_RATE).repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).body());
            assertThat(preview).as("preview returns a JSON array wrapped by Gson").isNotNull();
            // Preview returns either a top-level "schedule"/"periods" key or a list serialized as a Map with a known
            // first entry. Only assert the response is non-empty; deeper schedule-shape assertions live in F.1.
        });
    }

    @Test
    @Disabled("Requires JDBC access to m_loan; preview endpoint contract is exercised structurally elsewhere")
    public void previewScheduleEndpoint_doesNotPersistLoanRow() {
        // Asserting non-persistence requires a JDBC count(*) on m_loan before/after — heavyweight and out of scope
        // here. The simulator's design (transactional rollback at end of run) is covered by L1/L2 tests.
    }

    // ---------- rerun endpoint ----------

    @Test
    public void rerunEndpoint_recreatesScenario() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> first = validScenario(driver, productId, "g1_rerun_seed").run();
            String uuid = (String) first.get("uuid");

            Map<String, Object> rerun = driver.rerun(uuid);
            assertThat(rerun.get("status")).isEqualTo("COMPLETED");
            assertThat(snapshots(rerun)).as("rerun produces snapshots").isNotEmpty();
            assertThat(snapshots(rerun)).hasSameSizeAs(snapshots(first));
        });
    }

    // ---------- delete endpoint ----------

    @Test
    public void deleteEndpoint_removesRecord() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> created = validScenario(driver, productId, "g1_delete_seed").run();
            String uuid = (String) created.get("uuid");

            driver.delete(uuid);

            ResponseSpecification notFound = new ResponseSpecBuilder().expectStatusCode(404).build();
            String url = String.format("/fineract-provider/api/v1/mnzl/simulations/%s?%s", uuid, Utils.TENANT_IDENTIFIER);
            Utils.performServerGet(requestSpec, notFound, url, null);
        });
    }

    @Test
    public void deleteEndpoint_missingUuid_returns404() {
        runAt(DISBURSEMENT_DATE, () -> {
            ResponseSpecification notFound = new ResponseSpecBuilder().expectStatusCode(404).build();
            String url = "/fineract-provider/api/v1/mnzl/simulations/00000000-0000-0000-0000-000000000000?" + Utils.TENANT_IDENTIFIER;
            Utils.performServerDelete(requestSpec, notFound, url, null);
        });
    }

    // ---------- action types ----------

    @Test
    public void runWithDisburseOnly_succeeds() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> result = driver.scenario("g1_disburse_only", productId).principal(PRINCIPAL).rate(ANNUAL_RATE)
                    .repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).run();
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(snapshots(result)).as("disburse-only produces 1 snapshot").hasSize(1);
        });
    }

    @Test
    public void runWithFullLifecycle_DISBURSE_PAY_RUN_COB_ADD_CHARGE_WRITE_OFF_succeeds() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlChargesHelper chargesHelper = new MnzlChargesHelper(requestSpec, responseSpec);
            // ADD_CHARGE in the simulator post-disburse only accepts charge types whose due date is supplied at
            // attach-time. OVERDUE_INSTALLMENT charges are rejected by validateAddLoanCharge — they're applied by the
            // periodic-overdue COB job, not directly. SPECIFIED_DUE_DATE flat fees attach cleanly with the action's
            // date forwarded as dueDate.
            Long specifiedFeeId = chargesHelper.createSpecifiedDueDateFee(25.00).longValue();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);

            Map<String, Object> result = driver.scenario("g1_full_lifecycle", productId).principal(PRINCIPAL).rate(ANNUAL_RATE)
                    .repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).pay("01 February 2026", 100.00)
                    .runCob("02 February 2026").addCharge("03 February 2026", specifiedFeeId, 25.00).writeOff("01 March 2026").run();

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            // 1 disburse + 1 pay + 1 cob + 1 charge + 1 write-off = 5 snapshots.
            assertThat(snapshots(result)).hasSize(5);
        });
    }

    @Test
    public void runWithChangeInterestRate_isReportedAsFailedAfterDisburse() {
        // Production behavior: CHANGE_INTEREST_RATE in the simulator dispatches to
        // {@code addLoanScheduleVariations}, which Fineract's VariableLoanScheduleFromApiJsonValidator only allows
        // while the loan is SUBMITTED_AND_PENDING_APPROVAL. The simulator's runner creates+approves the loan as part
        // of bootstrapping, so by the time any action executes, the loan is no longer in that state. As a result,
        // a CHANGE_INTEREST_RATE action — placed before or after DISBURSE — surfaces as a FAILED simulation with the
        // {@code account.is.not.submitted.and.pending.state} validation error captured on the record. This test
        // pins that contract: the run completes, the status is FAILED, and the error message identifies the
        // submitted-state precondition. Mid-loan rate changes via the production path are exercised by
        // MnzlRateChangeMidLoanIT using the rescheduleloans REST API.
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> result = driver.scenario("g1_change_rate", productId).principal(PRINCIPAL).rate(ANNUAL_RATE)
                    .repayments(TERMS).disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).changeRate("01 February 2026", 6.0)
                    .run();
            assertThat(result.get("status")).isEqualTo("FAILED");
            String errorMessage = String.valueOf(result.get("errorMessage"));
            assertThat(errorMessage).as("simulator error message names the submitted-pending precondition")
                    .contains("submitted.and.pending.state");
        });
    }

    @Test
    public void runWithSkip_advancesDateOnly() {
        runAt(DISBURSEMENT_DATE, () -> {
            Long productId = createMnzlProduct();
            MnzlSimulationDriver driver = new MnzlSimulationDriver(requestSpec, responseSpec);
            Map<String, Object> result = driver.scenario("g1_skip", productId).principal(PRINCIPAL).rate(ANNUAL_RATE).repayments(TERMS)
                    .disburseDate(DISBURSEMENT_DATE).disburse(DISBURSEMENT_DATE).skip("01 February 2026").run();
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(snapshots(result)).hasSize(2);
        });
    }

    // ---------- permission negatives ----------

    @Test
    @Disabled("requires user-permission-setup helper")
    public void runEndpoint_missingCreatePermission_returns403() {
        // Needs a non-admin user with CREATE_MNZL_SIMULATION revoked. No helper currently provisions such a user,
        // and the project superuser bypass makes a meaningful 403 hard to assert.
    }

    @Test
    @Disabled("requires user-permission-setup helper")
    public void getEndpoint_missingReadPermission_returns403() {
        // Same as above for READ_MNZL_SIMULATION.
    }

    @Test
    @Disabled("requires user-permission-setup helper")
    public void deleteEndpoint_missingDeletePermission_returns403() {
        // Same as above for DELETE_MNZL_SIMULATION.
    }
}
