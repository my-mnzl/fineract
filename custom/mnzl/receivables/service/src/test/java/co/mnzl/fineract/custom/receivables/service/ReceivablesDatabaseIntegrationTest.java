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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.boot.FineractWebApplicationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Isolated real database and full native application. Evidence is written only after all accounting assertions pass.
 */
@EnabledIfEnvironmentVariable(named = "RECEIVABLES_TEST_DATABASE", matches = "mariadb|mysql|postgresql")
class ReceivablesDatabaseIntegrationTest {

    @Configuration
    @Import(FineractWebApplicationConfiguration.class)
    @ComponentScan({ "co.mnzl.fineract.custom.loan", "co.mnzl.fineract.custom.receivables" })
    static class Application {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ReceivablesJson json = new ReceivablesJson();
    private String base;
    private String tenantUrl;
    private String storeUrl;
    private String activeTenant = "default";
    private String databaseUser;
    private String databasePassword;
    private long ordinaryProductId;
    private long helPaymentTypeId;
    private final Map<String, ObjectNode> bookingCommands = new LinkedHashMap<>();
    private final Map<String, Long> accounts = new LinkedHashMap<>();
    private final LocalDate startDate = LocalDate.now(ZoneOffset.UTC);
    private LocalDate today = startDate;
    private final String prefix = "/mnzl/receivables";

    ReceivablesDatabaseIntegrationTest() throws Exception {}

    @Test
    void nativeTransactionsJournalsAndOutboxAreAtomicOnSupportedDatabase() throws Exception {
        // Docker 29 no longer serves the obsolete API default used by the pinned docker-java version.
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
        Files.deleteIfExists(Path.of("build/receivables-database-evidence.json"));
        String database = System.getenv("RECEIVABLES_TEST_DATABASE");
        try (JdbcDatabaseContainer<?> container = container(database)) {
            container.start();
            storeUrl = container.getJdbcUrl();
            tenantUrl = container.getJdbcUrl().replace("/fineract_tenants", "/fineract_default");
            databaseUser = container.getUsername();
            databasePassword = container.getPassword();
            try (var connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE fineract_default");
            }
            List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.ssl.enabled=false", "--spring.main.banner-mode=off",
                    "--spring.datasource.hikari.jdbcUrl=" + container.getJdbcUrl(),
                    "--spring.datasource.hikari.driverClassName=" + container.getDriverClassName(),
                    "--spring.datasource.hikari.username=" + container.getUsername(),
                    "--spring.datasource.hikari.password=" + container.getPassword(), "--spring.datasource.hikari.minimumIdle=1",
                    "--spring.datasource.hikari.maximumPoolSize=3", "--fineract.tenant.host=" + container.getHost(),
                    "--fineract.tenant.port=" + container.getFirstMappedPort(), "--fineract.tenant.username=" + container.getUsername(),
                    "--fineract.tenant.password=" + container.getPassword(), "--fineract.tenant.name=fineract_default",
                    "--fineract.tenant.timezone=UTC", "--fineract.mode.batch-worker-enabled=false",
                    "--fineract.mode.batch-manager-enabled=false", "--fineract.job.loan-cob-enabled=false",
                    "--spring.quartz.auto-startup=false", "--fineract.events.external.enabled=false", "--logging.level.root=WARN"));
            try (var application = new SpringApplicationBuilder(Application.class).run(args.toArray(String[]::new))) {
                base = "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port") + "/fineract-provider/api/v1";
                JsonNode currencies = request("GET", "/currencies", null, 200);
                assertThat(currencies.path("selectedCurrencyOptions").isArray()).isTrue();
                setupProductAndControls();
                JsonNode purchase = purchase("account-1");
                assertThat(purchase.path("nativeTransactionIds").size()).isEqualTo(1);
                verifyPurchaseAndIdempotency(purchase);
                verifyRollback(database);
                boolean ordinaryPassed = ordinaryRegression();
                verifyServicingAndClose();
                verifyTenantIsolation(application);
                boolean journalsMatched = queryLong(
                        "select count(*) from m_mnzl_r_journal_line l join acc_gl_journal_entry j on j.id=l.native_journal_id where l.native_gl_id<>j.account_id or cast(l.amount_minor as decimal(19,0))<>j.amount*100") == 0;
                assertThat(journalsMatched).isTrue();
                assertThat(queryLong("select count(*) from m_mnzl_r_event where delivered_at is null")).isGreaterThanOrEqualTo(4);
                ObjectNode evidence = json.object();
                evidence.put("database", database);
                evidence.put("nativeJournalReadbackMatched", journalsMatched);
                evidence.put("ordinaryProductRegressionPassed", ordinaryPassed);
                evidence.put("nativeLoanTransactions", queryLong("select count(*) from m_loan_transaction"));
                evidence.put("nativeGlEntries", queryLong("select count(*) from acc_gl_journal_entry"));
                evidence.put("pendingOutboxEvents", queryLong("select count(*) from m_mnzl_r_event where delivered_at is null"));
                evidence.set("assertions", json.value(List.of("exact-face-and-small-cheque", "actual-native-journal-readback",
                        "idempotent-retry", "changed-payload-conflict", "stale-version", "scope-conflict",
                        "late-event-failure-rolls-back-native-and-subledger", "retry-after-rollback",
                        "ordinary-loan-disbursement-and-repayment", "hel-native-noncash-clearing", "separate-tenant-database-isolation",
                        "rate-reset", "impairment", "frozen-period-close", "closed-period-rejection", "due-collection-reversal")));
                Files.writeString(Path.of("build/receivables-database-evidence.json"), json.write(evidence));

            }
        }
    }

    private JdbcDatabaseContainer<?> container(String database) {
        return switch (database) {
            case "mariadb" -> new MariaDBContainer<>("mariadb:11.5.2").withDatabaseName("fineract_tenants").withUsername("root")
                    .withPassword("receivables-test");
            case "mysql" -> new MySQLContainer<>("mysql:9.1").withDatabaseName("fineract_tenants").withUsername("root")
                    .withPassword("receivables-test");
            case "postgresql" -> new PostgreSQLContainer<>("postgres:17.4").withDatabaseName("fineract_tenants").withUsername("root")
                    .withPassword("receivables-test");
            default -> throw new IllegalArgumentException(database);
        };
    }

    private JsonNode request(String method, String path, JsonNode payload, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90))
                .header("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString("mifos:password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Fineract-Platform-TenantId", activeTenant).header("Content-Type", "application/json")
                .header("X-MNZL-Platform", "mnzl").header("X-MNZL-Financier", "financier").header("X-MNZL-Environment", "test")
                .header("X-MNZL-Ledger-Epoch", "epoch").header("X-MNZL-Account-Mapping", "mapping-1");
        var response = http.send(builder
                .method(method,
                        payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(payload)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return json.read(response.body());
    }

    private ObjectNode scope(boolean developer) {
        ObjectNode scope = json.object();
        scope.put("platformId", "mnzl");
        scope.put("financierOrganizationId", "financier");
        scope.put("environment", "test");
        scope.put("ledgerEpoch", "epoch");
        if (developer) {
            scope.put("developerOrganizationId", "developer");
        }
        return scope;
    }

    private ObjectNode versions() {
        ObjectNode value = json.object();
        value.put("calculationVersion", "EG_RECEIVABLES_ACT360_DAILY_V1");
        value.put("productPolicyCode", "EG_RECEIVABLES_V1");
        value.put("schemaVersion", "1");
        value.put("policyRevisionId", "policy-1");
        value.put("calculatorBuild", "build-1");
        return value;
    }

    private ObjectNode command(String type, String operation, String subject, String kind) {
        ObjectNode command = json.object();
        command.put("commandType", type);
        command.put("executionMode", "CURRENT");
        command.putNull("executionAuthorization");
        command.put("accountMappingRevisionId", "mapping-1");
        command.put("operationId", operation);
        command.put("idempotencyKey", operation);
        command.put("expectedVersion", "0");
        command.put("businessDate", today.toString());
        command.put("basisHash", "0".repeat(64));
        command.set("scope", scope(true));
        command.put("subjectId", subject);
        command.put("subjectKind", kind);
        command.set("affectedAccountVersions", json.value(List.of()));
        command.put("executionScopeHash", "0".repeat(64));
        command.put("actorId", "operator");
        command.set("approverIds", json.value(List.of("approver")));
        command.set("approvalEvidenceIds", json.value(List.of("approved")));
        return command;
    }

    private void setupProductAndControls() throws Exception {
        request("PUT", "/currencies", json.read("{\"currencies\":[\"EGP\"]}"), 200);
        int code = 100;
        for (String semantic : ReceivablesConfiguration.ACCOUNTS.stream().sorted().toList()) {
            ObjectNode account = json.object();
            account.put("name", semantic);
            account.put("glCode", Integer.toString(code++));
            account.put("type",
                    semantic.endsWith("Income") ? 4
                            : semantic.endsWith("Expense") || semantic.equals("modificationGainLoss") ? 5
                                    : java.util.Set.of("developerPayable", "fundingPrincipal", "fundingInterestPayable", "cashUnapplied")
                                            .contains(semantic) ? 2 : 1);
            account.put("usage", 1);
            account.put("manualEntriesAllowed", true);
            account.put("description", "Isolated Flex integration");
            accounts.put(semantic, request("POST", "/glaccounts", account, 200).path("resourceId").asLong());
        }
        ObjectNode product = (ObjectNode) json.read(
                "{\"name\":\"Purchased receivable\",\"shortName\":\"FLEX\",\"currencyCode\":\"EGP\",\"locale\":\"en\",\"digitsAfterDecimal\":2,\"inMultiplesOf\":0,\"principal\":1000,\"numberOfRepayments\":3,\"repaymentEvery\":1,\"repaymentFrequencyType\":2,\"interestRatePerPeriod\":0,\"interestRateFrequencyType\":2,\"amortizationType\":1,\"interestType\":0,\"interestCalculationPeriodType\":1,\"inArrearsTolerance\":0,\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\",\"accountingRule\":1,\"isInterestRecalculationEnabled\":false,\"daysInMonthType\":1,\"daysInYearType\":1}");
        long productId = request("POST", "/loanproducts", product, 200).path("resourceId").asLong();
        request("PUT", "/mnzl/loan-products/" + productId + "/strategies", json.read(
                "{\"instrumentCode\":\"MNZL_PURCHASED_RECEIVABLE\",\"scheduleStrategyCode\":\"MNZL_FIXED_RECEIVABLE\",\"chargeStrategyCode\":\"MNZL_NO_BORROWER_CHARGES\",\"cobStrategyCode\":\"MNZL_DUE_INSTALLMENTS\"}"),
                200);
        ObjectNode configuration = versions();
        configuration.set("scope", scope(false));
        configuration.put("accountMappingRevisionId", "mapping-1");
        configuration.put("productId", Long.toString(productId));
        configuration.put("officeId", "1");
        configuration.put("integrationUserId", "1");
        setupOrdinaryProduct();
        configuration.put("helPaymentTypeId", Long.toString(helPaymentTypeId));
        configuration.put("helProductId", Long.toString(ordinaryProductId));
        configuration.put("bankAccountReference", "test-bank");
        List<JsonNode> mapping = new ArrayList<>();
        accounts.forEach((key, id) -> {
            ObjectNode row = json.object();
            row.put("accountKey", key);
            row.put("nativeGlAccountId", id.toString());
            mapping.add(row);
        });
        configuration.set("accountMap", json.value(mapping));
        assertThat(request("POST", prefix + "/configuration", configuration, 200).path("productConfigurationReady").asBoolean()).isTrue();
    }

    private JsonNode purchase(String id) throws Exception {
        ObjectNode basis = versions();
        basis.put("settlementDate", today.toString());
        basis.put("corridorObservationId", "rate");
        basis.put("corridorRate", "0.24");
        basis.put("spread", "0");
        basis.put("feeRate", "0.01");
        basis.put("sourceVersion", "1");
        basis.put("sourceHash", "0".repeat(64));
        basis.set("acceptedAccountPrices", json.value(List.of()));
        List<JsonNode> flows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ObjectNode flow = json.object();
            flow.put("cashflowId", id + "-" + i);
            flow.put("receivableId", id);
            flow.put("installmentId", id + "-" + i);
            flow.put("dueDate", today.plusDays(i == 0 ? 45 : 90).toString());
            flow.put("amountMinor", i == 0 ? "1" : "99999");
            flow.put("currency", "EGP");
            flow.put("scheduleVersionId", "schedule-1");
            flows.add(flow);
        }
        basis.set("cashflows", json.value(flows));
        ObjectNode calculate = versions();
        calculate.put("calculationType", "PRICE");
        calculate.set("basis", basis);
        calculate.put("basisHash", json.hash(basis));
        JsonNode pricing = request("POST", prefix + "/calculate", calculate, 200).path("pricing").path("accounts").get(0);
        ObjectNode accepted = json.object();
        for (String field : List.of("accountId", "grossPurchasePriceMinor", "integralFeeMinor", "netPurchaseCashMinor")) {
            accepted.set(field, pricing.get(field));
        }
        basis.set("acceptedAccountPrices", json.value(List.of(accepted)));
        ObjectNode source = json.object();
        source.put("bankSourceId", id + "-bank");
        source.put("bankAccountReference", "test-bank");
        source.put("verificationEvidenceId", "verified");
        source.put("valueDate", today.toString());
        source.put("currency", "EGP");
        source.set("amountMinor", accepted.get("netPurchaseCashMinor"));
        source.put("direction", "OUTGOING");
        ObjectNode allocation = json.object();
        allocation.put("allocationId", id + "-advance");
        allocation.put("kind", "ACQUISITION_ADVANCE");
        allocation.put("dealId", "deal");
        allocation.put("accountId", id);
        allocation.put("beneficiaryReferenceId", "developer");
        allocation.set("amountMinor", accepted.get("netPurchaseCashMinor"));
        source.set("allocations", json.value(List.of(allocation)));
        ObjectNode cash = command("RECORD_CASH_MOVEMENT", id + "-cash", "deal", "DEAL");
        cash.set("source", source);
        request("POST", prefix + "/commands", cash, 200);
        ObjectNode book = command("BOOK_PURCHASE", id + "-book", id, "RECEIVABLE");
        book.put("accountId", id);
        book.put("dealId", "deal");
        book.put("customerReferenceId", id + "-customer");
        book.set("acquisitionClearingAllocationIds", json.value(List.of(id + "-advance")));
        book.set("basis", basis);
        book.put("basisHash", json.hash(basis));
        ObjectNode forecast = json.object();
        forecast.put("forecastId", id + "-forecast");
        forecast.put("forecastVersion", "1");
        forecast.put("asOfDate", today.toString());
        forecast.put("validThroughDate", today.plusDays(365).toString());
        forecast.put("stage", "STAGE_1");
        forecast.put("contentHash", "0".repeat(64));
        List<JsonNode> recoveries = new ArrayList<>();
        for (JsonNode flow : flows) {
            ObjectNode recovery = json.object();
            recovery.set("sourceCashflowId", flow.get("cashflowId"));
            recovery.set("date", flow.get("dueDate"));
            recovery.set("amountMinor", flow.get("amountMinor"));
            recovery.put("payer", "BORROWER");
            recoveries.add(recovery);
        }
        ObjectNode scenario = json.object();
        scenario.put("scenarioId", "contractual");
        scenario.put("probability", "1");
        scenario.putNull("defaultDate");
        scenario.set("recoveries", json.value(recoveries));
        forecast.set("scenarios", json.value(List.of(scenario)));
        book.set("riskForecast", forecast);
        bookingCommands.put(id, book);
        return request("POST", prefix + "/commands", book, 200);
    }

    private long queryLong(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(tenantUrl, databaseUser, databasePassword);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void executeSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(tenantUrl, databaseUser, databasePassword);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Map<String, Long> counts() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("m_client", "m_loan", "m_loan_transaction", "acc_gl_journal_entry", "m_mnzl_r_account",
                "m_mnzl_r_command", "m_mnzl_r_event", "m_mnzl_r_segment", "m_mnzl_r_journal_line")) {
            counts.put(table, queryLong("select count(*) from " + table));
        }
        return counts;
    }

    private void verifyPurchaseAndIdempotency(JsonNode booked) throws Exception {
        JsonNode account = request("GET", prefix + "/accounts/account-1", null, 200);
        long loan = Long.parseLong(account.path("nativeLoanId").asText());
        assertThat(account.path("position").path("contractualOutstandingMinor").asText()).isEqualTo("100000");
        assertThat(queryLong("select count(*) from m_loan_repayment_schedule where loan_id=" + loan + " and principal_amount=0.01"))
                .isEqualTo(1);
        assertThat(queryLong("select sum(principal_amount*100) from m_loan_repayment_schedule where loan_id=" + loan)).isEqualTo(100000);
        assertThat(queryLong("select count(*) from m_loan_transaction where loan_id=" + loan + " and transaction_type_enum=1")).isZero();
        Map<String, Long> before = counts();
        ObjectNode command = bookingCommands.get("account-1");
        assertThat(request("POST", prefix + "/commands", command, 200)).isEqualTo(booked);
        assertThat(counts()).isEqualTo(before);
        ObjectNode conflict = command.deepCopy();
        conflict.put("customerReferenceId", "different-customer");
        assertThat(request("POST", prefix + "/commands", conflict, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        ObjectNode reset = command("RESET_RATE", "stale-reset", "account-1", "RECEIVABLE");
        reset.put("corridorObservationId", "new-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("developerAdjustmentDueDate", today.plusDays(45).toString());
        assertThat(request("POST", prefix + "/commands", reset, 409).path("code").asText()).isEqualTo("ACCOUNT_VERSION_CHANGED");
        reset.put("expectedVersion", "1");
        ((ObjectNode) reset.get("scope")).put("developerOrganizationId", "other-developer");
        assertThat(request("POST", prefix + "/commands", reset, 409).path("code").asText()).isEqualTo("OWNERSHIP_CONFLICT");
        assertThat(counts()).isEqualTo(before);
        JsonNode controls = request("GET", prefix + "/controls?businessDate=" + today + "&boundarySide=AFTER_EVENTS", null, 200);
        for (JsonNode balance : controls.path("balances")) {
            assertThat(balance.path("differenceMinor").asText()).isEqualTo("0");
        }
        assertThat(controls.path("nativeContractualOutstandingMinor").asText()).isEqualTo("100000");
    }

    private void verifyRollback(String database) throws Exception {
        // Reuse committed acquisition cash through a second allocation; failure is injected only at final outbox
        // insertion.
        ObjectNode original = bookingCommands.get("account-1");
        ObjectNode failed = original.deepCopy();
        failed.put("operationId", "failed-book");
        failed.put("idempotencyKey", "failed-book");
        failed.put("accountId", "account-rollback");
        failed.put("subjectId", "account-rollback");
        failed.put("customerReferenceId", "rollback-customer");
        ObjectNode basis = (ObjectNode) failed.get("basis");
        for (JsonNode flow : basis.get("cashflows")) {
            ObjectNode changed = (ObjectNode) flow;
            changed.put("receivableId", "account-rollback");
            changed.put("cashflowId", changed.path("cashflowId").asText().replace("account-1", "account-rollback"));
            changed.put("installmentId", changed.path("installmentId").asText().replace("account-1", "account-rollback"));
        }
        ObjectNode risk = (ObjectNode) failed.get("riskForecast");
        risk.put("forecastId", "rollback-forecast");
        for (JsonNode scenario : risk.get("scenarios")) {
            for (JsonNode recovery : scenario.get("recoveries")) {
                ((ObjectNode) recovery).put("sourceCashflowId",
                        recovery.path("sourceCashflowId").asText().replace("account-1", "account-rollback"));
            }
        }
        ((ObjectNode) basis.get("acceptedAccountPrices").get(0)).put("accountId", "account-rollback");
        failed.put("basisHash", json.hash(basis));
        // A new bank movement gives this command independent, unconsumed acquisition funding.
        JsonNode accepted = basis.get("acceptedAccountPrices").get(0);
        ObjectNode cash = command("RECORD_CASH_MOVEMENT", "rollback-cash", "deal", "DEAL");
        ObjectNode source = json.object();
        source.put("bankSourceId", "rollback-bank");
        source.put("bankAccountReference", "test-bank");
        source.put("verificationEvidenceId", "verified");
        source.put("valueDate", today.toString());
        source.put("currency", "EGP");
        source.set("amountMinor", accepted.get("netPurchaseCashMinor"));
        source.put("direction", "OUTGOING");
        ObjectNode allocation = json.object();
        allocation.put("allocationId", "rollback-advance");
        allocation.put("kind", "ACQUISITION_ADVANCE");
        allocation.put("dealId", "deal");
        allocation.put("accountId", "account-rollback");
        allocation.put("beneficiaryReferenceId", "developer");
        allocation.set("amountMinor", accepted.get("netPurchaseCashMinor"));
        source.set("allocations", json.value(List.of(allocation)));
        cash.set("source", source);
        request("POST", prefix + "/commands", cash, 200);
        failed.set("acquisitionClearingAllocationIds", json.value(List.of("rollback-advance")));
        Map<String, Long> before = counts();
        if (database.equals("postgresql")) {
            executeSql(
                    "CREATE FUNCTION flex_fail_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'flex-injected-event-failure'; END $$");
            executeSql("CREATE TRIGGER flex_fail_event BEFORE INSERT ON m_mnzl_r_event FOR EACH ROW EXECUTE FUNCTION flex_fail_event()");
        } else {
            executeSql(
                    "CREATE TRIGGER flex_fail_event BEFORE INSERT ON m_mnzl_r_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='flex-injected-event-failure'");
        }
        try {
            request("POST", prefix + "/commands", failed, 500);
            assertThat(counts()).isEqualTo(before);
            assertThat(queryLong("select count(*) from m_mnzl_r_cash_allocation where allocation_id='rollback-advance' and used_minor='0'"))
                    .isEqualTo(1);
        } finally {
            executeSql(database.equals("postgresql") ? "DROP TRIGGER flex_fail_event ON m_mnzl_r_event" : "DROP TRIGGER flex_fail_event");
            if (database.equals("postgresql")) {
                executeSql("DROP FUNCTION flex_fail_event()");
            }
        }
        JsonNode retried = request("POST", prefix + "/commands", failed, 200);
        assertThat(retried.path("nativeTransactionIds").size()).isEqualTo(1);
        assertThat(queryLong("select count(*) from m_mnzl_r_command where operation_id='failed-book'")).isEqualTo(1);
    }

    private void setupOrdinaryProduct() throws Exception {
        ObjectNode product = (ObjectNode) json.read(
                "{\"name\":\"Ordinary loan regression\",\"shortName\":\"ORD\",\"currencyCode\":\"EGP\",\"locale\":\"en\",\"digitsAfterDecimal\":2,\"inMultiplesOf\":0,\"principal\":1000,\"numberOfRepayments\":3,\"repaymentEvery\":1,\"repaymentFrequencyType\":2,\"interestRatePerPeriod\":2,\"interestRateFrequencyType\":2,\"amortizationType\":1,\"interestType\":0,\"interestCalculationPeriodType\":1,\"inArrearsTolerance\":0,\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\",\"accountingRule\":2,\"isInterestRecalculationEnabled\":false,\"daysInMonthType\":1,\"daysInYearType\":1}");
        for (String field : List.of("fundSourceAccountId", "loanPortfolioAccountId", "transfersInSuspenseAccountId")) {
            product.put(field, accounts.get("bank"));
        }
        product.put("loanPortfolioAccountId", accounts.get("contractualReceivable"));
        for (String field : List.of("interestOnLoanAccountId", "incomeFromFeeAccountId", "incomeFromPenaltyAccountId",
                "incomeFromRecoveryAccountId")) {
            product.put(field, accounts.get("portfolioInterestIncome"));
        }
        product.put("writeOffAccountId", accounts.get("impairmentExpense"));
        product.put("overpaymentLiabilityAccountId", accounts.get("developerPayable"));
        ObjectNode payment = json.object();
        payment.put("name", "HEL clearing");
        payment.put("description", "Noncash settlement clearing");
        payment.put("isCashPayment", false);
        payment.put("position", 0);
        helPaymentTypeId = request("POST", "/paymenttypes", payment, 200).path("resourceId").asLong();
        product.set("paymentChannelToFundSourceMappings", json
                .value(List.of(Map.of("paymentTypeId", helPaymentTypeId, "fundSourceAccountId", accounts.get("helSettlementClearing")))));
        ordinaryProductId = request("POST", "/loanproducts", product, 200).path("resourceId").asLong();
    }

    private boolean ordinaryRegression() throws Exception {
        ObjectNode client = json.object();
        client.put("officeId", 1);
        client.put("legalFormId", 1);
        client.put("firstname", "Ordinary");
        client.put("lastname", "Borrower");
        client.put("active", true);
        client.put("activationDate", today.toString());
        client.put("dateFormat", "yyyy-MM-dd");
        client.put("locale", "en");
        long clientId = request("POST", "/clients", client, 200).path("resourceId").asLong();
        ObjectNode loan = json.object();
        loan.put("clientId", clientId);
        loan.put("productId", ordinaryProductId);
        loan.put("principal", 1000);
        loan.put("numberOfRepayments", 3);
        loan.put("repaymentEvery", 1);
        loan.put("repaymentFrequencyType", 2);
        loan.put("interestRatePerPeriod", 2);
        loan.put("interestRateFrequencyType", 2);
        loan.put("amortizationType", 1);
        loan.put("interestType", 0);
        loan.put("interestCalculationPeriodType", 1);
        loan.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
        loan.put("expectedDisbursementDate", today.toString());
        loan.put("submittedOnDate", today.toString());
        loan.put("loanTermFrequency", 3);
        loan.put("loanTermFrequencyType", 2);
        loan.put("loanType", "individual");
        loan.put("dateFormat", "yyyy-MM-dd");
        loan.put("locale", "en");
        long id = request("POST", "/loans", loan, 200).path("loanId").asLong();
        ObjectNode approve = json.object();
        approve.put("approvedOnDate", today.toString());
        approve.put("dateFormat", "yyyy-MM-dd");
        approve.put("locale", "en");
        request("POST", "/loans/" + id + "?command=approve", approve, 200);
        ObjectNode disburse = json.object();
        disburse.put("actualDisbursementDate", today.toString());
        disburse.put("dateFormat", "yyyy-MM-dd");
        disburse.put("locale", "en");
        request("POST", "/loans/" + id + "?command=disburse", disburse, 200);
        ObjectNode repay = json.object();
        repay.put("transactionDate", today.toString());
        repay.put("transactionAmount", 100);
        repay.put("dateFormat", "yyyy-MM-dd");
        repay.put("locale", "en");
        request("POST", "/loans/" + id + "/transactions?command=repayment", repay, 200);
        boolean valid = queryLong(
                "select count(*) from m_loan_transaction where loan_id=" + id + " and transaction_type_enum in (1,2)") == 2
                && queryLong("select sum(interest_amount*100) from m_loan_repayment_schedule where loan_id=" + id) > 0;
        assertThat(valid).isTrue();
        assertThat(queryLong(
                "select count(*) from acc_gl_journal_entry j join m_loan_transaction t on t.id=j.loan_transaction_id where t.loan_id="
                        + id))
                .isGreaterThanOrEqualTo(4);
        assertThat(queryLong("select count(*) from m_loan_repayment_schedule where loan_id=" + id)).isEqualTo(3);
        assertThat(queryLong("select sum(principal_amount*100) from m_loan_repayment_schedule where loan_id=" + id)).isEqualTo(100000);
        assertThat(queryLong("select sum(case when j.type_enum=2 then j.amount*100 else -j.amount*100 end) "
                + "from acc_gl_journal_entry j join m_loan_transaction t on t.id=j.loan_transaction_id where t.loan_id=" + id)).isZero();
        assertThat(queryLong("select sum(case when j.type_enum=2 then j.amount*100 else -j.amount*100 end) "
                + "from acc_gl_journal_entry j join m_loan_transaction t on t.id=j.loan_transaction_id where t.loan_id=" + id
                + " and j.account_id=" + accounts.get("bank"))).isEqualTo(-90000);
        loan.put("externalId", "hel-test-loan");
        long helLoan = request("POST", "/loans", loan, 200).path("loanId").asLong();
        request("POST", "/loans/" + helLoan + "?command=approve", approve, 200);
        ObjectNode fund = command("FUND_HEL_TO_SETTLEMENT_CLEARING", "hel-fund", "hel-test-loan", "HEL_LOAN");
        fund.put("applicationId", "hel-application");
        fund.put("dealId", "deal");
        fund.put("expectedNativeClientId", Long.toString(clientId));
        fund.put("loanExternalId", "hel-test-loan");
        fund.put("expectedPrincipalMinor", "100000");
        fund.put("expectedFinancedFeesMinor", "0");
        fund.set("financedFeeIds", json.value(List.of()));
        JsonNode funded = request("POST", prefix + "/hel-funding/commands", fund, 200);
        assertThat(funded.path("clearingAmountMinor").asText()).isEqualTo("100000");
        assertThat(funded.path("nativeLoanStatus").asText()).isEqualTo("ACTIVE");
        assertThat(funded.path("nativeTransactionIds").size()).isEqualTo(1);
        assertThat(funded.path("journalIds").size()).isGreaterThanOrEqualTo(2);
        assertThat(request("POST", prefix + "/hel-funding/commands", fund, 200)).isEqualTo(funded);
        assertThat(queryLong("select sum(case when j.type_enum=2 then j.amount*100 else -j.amount*100 end) "
                + "from acc_gl_journal_entry j join m_loan_transaction t on t.id=j.loan_transaction_id where t.loan_id=" + helLoan
                + " and j.account_id=" + accounts.get("helSettlementClearing"))).isEqualTo(-100000);
        return valid;
    }

    private void verifyTenantIsolation(org.springframework.context.ConfigurableApplicationContext application) throws Exception {
        executeSql("CREATE DATABASE fineract_other");
        try (var connection = DriverManager.getConnection(storeUrl, databaseUser, databasePassword)) {
            cloneTenantRow(connection, "tenant_server_connections", Map.of("id", 2L, "schema_name", "fineract_other"));
            cloneTenantRow(connection, "tenants",
                    Map.of("id", 2L, "identifier", "other", "name", "Other isolated tenant", "oltp_id", 2L, "report_id", 2L));
        }
        application.getBean(org.apache.fineract.infrastructure.core.service.migration.TenantDatabaseUpgradeService.class)
                .afterPropertiesSet();
        long firstLoan = Long.parseLong(request("GET", prefix + "/accounts/account-1", null, 200).path("nativeLoanId").asText());
        activeTenant = "other";
        try {
            assertThat(request("GET", "/currencies", null, 200).path("selectedCurrencyOptions").isArray()).isTrue();
            request("GET", "/loans/" + firstLoan, null, 404);
            assertThat(request("GET", prefix + "/accounts/account-1", null, 409).path("code").asText()).isEqualTo("RECOVERY_REQUIRED");
        } finally {
            activeTenant = "default";
        }
    }

    private void cloneTenantRow(java.sql.Connection connection, String table, Map<String, Object> overrides) throws Exception {
        try (var select = connection.createStatement(); var row = select.executeQuery("select * from " + table + " where id=1")) {
            assertThat(row.next()).isTrue();
            var metadata = row.getMetaData();
            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                String name = metadata.getColumnName(column);
                columns.add(name);
                values.add(overrides.getOrDefault(name, row.getObject(column)));
            }
            try (var insert = connection.prepareStatement("insert into " + table + " (" + String.join(",", columns) + ") values ("
                    + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")")) {
                for (int index = 0; index < values.size(); index++) {
                    insert.setObject(index + 1, values.get(index));
                }
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private String version(String id) throws Exception {
        return request("GET", prefix + "/accounts/" + id, null, 200).path("position").path("accountVersion").asText();
    }

    private void moveDate(LocalDate date) throws Exception {
        JsonNode configurations = request("GET", "/configurations", null, 200);
        for (JsonNode configuration : configurations.path("globalConfiguration")) {
            if (configuration.path("name").asText().equals("enable-business-date")) {
                request("PUT", "/configurations/" + configuration.path("id").asLong(), json.read("{\"enabled\":true}"), 200);
            }
        }
        ObjectNode input = json.object();
        input.put("type", "BUSINESS_DATE");
        input.put("date", date.toString());
        input.put("dateFormat", "yyyy-MM-dd");
        input.put("locale", "en");
        request("POST", "/businessdate", input, 200);
        today = date;
    }

    private void verifyServicingAndClose() throws Exception {
        ObjectNode reset = command("RESET_RATE", "reset", "account-1", "RECEIVABLE");
        reset.put("expectedVersion", version("account-1"));
        reset.put("corridorObservationId", "reset-rate");
        reset.put("corridorRate", "0.30");
        reset.put("spread", "0");
        reset.put("developerAdjustmentDueDate", startDate.plusDays(45).toString());
        request("POST", prefix + "/commands", reset, 200);
        JsonNode lots = request("GET", prefix + "/developer-lots?businessDate=" + today + "&boundarySide=AFTER_EVENTS", null, 200);
        assertThat(lots.path("items").size()).isEqualTo(1);
        ObjectNode impairment = command("SET_IMPAIRMENT", "impair", "account-1", "RECEIVABLE");
        impairment.put("expectedVersion", version("account-1"));
        impairment.set("forecast", bookingCommands.get("account-1").get("riskForecast"));
        impairment.set("qualitativeFindingIds", json.value(List.of()));
        impairment.set("cureEvidenceIds", json.value(List.of()));
        request("POST", prefix + "/commands", impairment, 200);
        LocalDate boundary = startDate.withDayOfMonth(1).plusMonths(1);
        moveDate(boundary);
        String period = boundary.minusDays(1).toString().substring(0, 7);
        ObjectNode close = command("CLOSE_PERIOD", "close-prepare", period, "PERIOD");
        close.set("scope", scope(false));
        close.put("phase", "PREPARE");
        close.put("periodId", period);
        close.put("boundaryDate", boundary.toString());
        close.put("eventWatermark", request("GET", prefix + "/capabilities", null, 200).path("currentEventWatermark").asText());
        close.put("sourceCutoffHash", "0".repeat(64));
        close.set("forecastIds", json.value(List.of("account-1-forecast", "rollback-forecast")));
        JsonNode prepared = request("POST", prefix + "/commands", close, 200);
        String watermark = prepared.path("eventWatermark").asText();
        JsonNode frozen = request("GET",
                prefix + "/controls?businessDate=" + boundary + "&boundarySide=BEFORE_EVENTS&eventWatermark=" + watermark, null, 200);
        for (JsonNode balance : frozen.path("balances")) {
            assertThat(balance.path("differenceMinor").asText()).isEqualTo("0");
        }
        assertThat(frozen.path("nativeContractualOutstandingMinor").asText()).isEqualTo("200000");
        ObjectNode finalize = close.deepCopy();
        finalize.put("operationId", "close-finalize");
        finalize.put("idempotencyKey", "close-finalize");
        finalize.put("expectedVersion", "1");
        finalize.put("phase", "FINALIZE");
        finalize.put("eventWatermark", watermark);
        finalize.put("reconciliationId", "independent-test-reconciliation");
        request("POST", prefix + "/commands", finalize, 200);
        ObjectNode blocked = command("RESET_RATE", "closed-reset", "account-1", "RECEIVABLE");
        blocked.put("expectedVersion", version("account-1"));
        blocked.put("businessDate", boundary.minusDays(1).toString());
        blocked.put("corridorObservationId", "rate");
        blocked.put("corridorRate", "0.30");
        blocked.put("spread", "0");
        blocked.put("developerAdjustmentDueDate", startDate.plusDays(45).toString());
        assertThat(request("POST", prefix + "/commands", blocked, 409).path("code").asText()).isEqualTo("PERIOD_CLOSED");
        moveDate(startDate.plusDays(45));
        recordReceipt("due-receipt", "account-1", "1");
        ObjectNode collect = command("COLLECT", "collect-one", "account-1", "RECEIVABLE");
        collect.put("expectedVersion", version("account-1"));
        ObjectNode allocation = json.object();
        allocation.put("allocationId", "collected-one");
        allocation.put("cashMovementId", "due-receipt");
        allocation.put("cashflowId", "account-1-0");
        allocation.put("installmentId", "account-1-0");
        allocation.put("instrumentId", "cheque-1");
        allocation.put("amountMinor", "1");
        collect.set("allocations", json.value(List.of(allocation)));
        JsonNode collected = request("POST", prefix + "/commands", collect, 200);
        assertThat(request("GET", prefix + "/accounts/account-1", null, 200).path("position").path("contractualOutstandingMinor").asText())
                .isEqualTo("99999");
        ObjectNode reverse = command("REVERSE_COLLECTION", "reverse-one", "account-1", "RECEIVABLE");
        reverse.put("expectedVersion", version("account-1"));
        reverse.put("originalOperationId", "collect-one");
        reverse.set("nativeTransactionId", collected.path("nativeTransactionIds").get(0));
        reverse.set("allocationIds", json.value(List.of("collected-one")));
        reverse.put("reasonCode", "bank-return");
        request("POST", prefix + "/commands", reverse, 200);
        assertThat(request("GET", prefix + "/accounts/account-1", null, 200).path("position").path("contractualOutstandingMinor").asText())
                .isEqualTo("100000");
        assertThat(request("GET", prefix + "/controls?businessDate=" + boundary + "&boundarySide=BEFORE_EVENTS&eventWatermark=" + watermark,
                null, 200)).isEqualTo(frozen);
    }

    private void recordReceipt(String operation, String account, String amount) throws Exception {
        ObjectNode cash = command("RECORD_CASH_MOVEMENT", operation, "deal", "DEAL");
        ObjectNode source = json.object();
        source.put("bankSourceId", operation + "-bank");
        source.put("bankAccountReference", "test-bank");
        source.put("verificationEvidenceId", "verified");
        source.put("valueDate", today.toString());
        source.put("currency", "EGP");
        source.put("amountMinor", amount);
        source.put("direction", "INCOMING");
        ObjectNode allocation = json.object();
        allocation.put("allocationId", operation + "-allocation");
        allocation.put("kind", "RECEIPT_UNAPPLIED");
        allocation.put("dealId", "deal");
        allocation.put("accountId", account);
        allocation.put("beneficiaryReferenceId", "financier");
        allocation.put("amountMinor", amount);
        source.set("allocations", json.value(List.of(allocation)));
        cash.set("source", source);
        request("POST", prefix + "/commands", cash, 200);
    }
}
