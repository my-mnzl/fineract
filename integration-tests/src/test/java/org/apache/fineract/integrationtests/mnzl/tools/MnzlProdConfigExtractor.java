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
package org.apache.fineract.integrationtests.mnzl.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone JDBC extractor that connects to the local dev DB and emits anonymized JSON snapshots of mnzl product
 * configs, charges, holidays, working-day rules, and the rehab loan 13516 input shape.
 * <p>
 * Run via {@code ./gradlew :integration-tests:refreshMnzlProdConfigs}. Output is consumed by
 * {@code MnzlScenarioFixtures} as @MethodSource fixtures for parameterized tests.
 * <p>
 * Database connection is configured via environment variables, with local-dev defaults matching
 * {@code docker-compose-development.yml}. To target a different host or credential set, export before running:
 *
 * <pre>
 *   export MNZL_PROD_CONFIG_JDBC_URL=jdbc:mariadb://localhost:3307/fineract_default
 *   export MNZL_PROD_CONFIG_USER=root
 *   export MNZL_PROD_CONFIG_PASS=...
 * </pre>
 *
 * <p>
 * Anonymization: drops {@code name}, {@code short_name}, {@code description}, {@code external_id} and any
 * client-identifying free-text columns. Synthetic labels (configName, charge_&lt;id&gt;) are derived from numeric ids
 * and enum codes only.
 */
public final class MnzlProdConfigExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(MnzlProdConfigExtractor.class);

    private static final String JDBC_URL = envOrDefault("MNZL_PROD_CONFIG_JDBC_URL", "jdbc:mariadb://localhost:3307/fineract_default");
    private static final String USER = envOrDefault("MNZL_PROD_CONFIG_USER", "root");
    private static final String PASS = envOrDefault("MNZL_PROD_CONFIG_PASS", "password");
    private static final Path OUT_DIR = Paths.get("integration-tests/src/test/resources/mnzl/prod-configs");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MnzlProdConfigExtractor() {}

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty() ? v : fallback;
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT_DIR);
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASS)) {
            writeJson(extractProducts(c), "products.json");
            writeJson(extractCharges(c), "charges.json");
            writeJson(extractHolidays(c), "holidays.json");
            writeJson(extractWorkingDays(c), "working_days.json");
            writeJson(extractRehabLoan13516(c), "loan_13516.json");
        }
        LOG.info("Wrote prod configs to {}", OUT_DIR.toAbsolutePath());
    }

    private static List<Map<String, Object>> extractProducts(Connection c) throws Exception {
        // DISTINCT meaningful product configurations joined to mnzl strategy codes.
        // We deliberately drop name / short_name / description / external_id (PII / free text).
        // configName is derived from numeric productId + scheduleStrategyCode for parameterized labels.
        // The mnzl prod products carry a NULL nominal rate at the product level (rates are set per
        // disbursed loan in m_loan). To give parameterized scenario tests a representative rate,
        // we project the modal annual rate observed on m_loan rows for each product as
        // annualNominalInterestRate, falling back to the product column when set.
        String sql = "SELECT p.id AS productId," //
                + "       p.currency_code AS currencyCode," //
                + "       p.currency_digits AS currencyDigits," //
                + "       p.principal_amount AS principalAmount," //
                + "       p.nominal_interest_rate_per_period AS nominalInterestRatePerPeriod," //
                + "       p.interest_period_frequency_enum AS interestPeriodFrequencyEnum," //
                + "       COALESCE(p.annual_nominal_interest_rate, (" + "           SELECT l.annual_nominal_interest_rate FROM m_loan l"
                + "           WHERE l.product_id = p.id AND l.annual_nominal_interest_rate IS NOT NULL"
                + "           GROUP BY l.annual_nominal_interest_rate"
                + "           ORDER BY COUNT(*) DESC, l.annual_nominal_interest_rate DESC" + "           LIMIT 1"
                + "       )) AS annualNominalInterestRate," //
                + "       p.interest_method_enum AS interestMethodEnum," //
                + "       p.interest_calculated_in_period_enum AS interestCalculatedInPeriodEnum," //
                + "       p.allow_partial_period_interest_calcualtion AS allowPartialPeriodInterestCalculation," //
                + "       p.repay_every AS repayEvery," //
                + "       p.repayment_period_frequency_enum AS repaymentPeriodFrequencyEnum," //
                + "       p.number_of_repayments AS numberOfRepayments," //
                + "       p.grace_on_principal_periods AS graceOnPrincipalPeriods," //
                + "       p.grace_on_interest_periods AS graceOnInterestPeriods," //
                + "       p.grace_interest_free_periods AS graceInterestFreePeriods," //
                + "       p.amortization_method_enum AS amortizationMethodEnum," //
                + "       p.days_in_month_enum AS daysInMonthEnum," //
                + "       p.days_in_year_enum AS daysInYearEnum," //
                + "       p.days_in_year_custom_strategy AS daysInYearCustomStrategy," //
                + "       p.interest_recalculation_enabled AS interestRecalculationEnabled," //
                + "       p.interest_recognition_on_disbursement_date AS interestRecognitionOnDisbursementDate," //
                + "       p.repayment_start_date_type_enum AS repaymentStartDateTypeEnum," //
                + "       p.loan_schedule_type AS loanScheduleType," //
                + "       p.loan_schedule_processing_type AS loanScheduleProcessingType," //
                + "       p.loan_transaction_strategy_code AS loanTransactionStrategyCode," //
                + "       p.fixed_length AS fixedLength," //
                + "       p.is_equal_amortization AS isEqualAmortization," //
                + "       p.principal_threshold_for_last_installment AS principalThresholdForLastInstallment," //
                + "       p.grace_on_arrears_ageing AS graceOnArrearsAgeing," //
                + "       p.overdue_days_for_npa AS overdueDaysForNpa," //
                + "       p.min_days_between_disbursal_and_first_repayment AS minDaysBetweenDisbursalAndFirstRepayment," //
                + "       s.instrument_code AS instrumentCode," //
                + "       s.schedule_strategy_code AS scheduleStrategyCode," //
                + "       s.charge_strategy_code AS chargeStrategyCode," //
                + "       s.cob_strategy_code AS cobStrategyCode " //
                + "FROM m_product_loan p " //
                + "JOIN m_mnzl_loan_product_strategy s ON s.loan_product_id = p.id " //
                + "ORDER BY p.id";
        return queryRows(c, sql, row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            // Synthetic, PII-free configName for @MethodSource labels.
            Object productId = row.get("productId");
            Object scheduleStrategy = row.get("scheduleStrategyCode");
            out.put("configName", "product_" + productId + "_" + scheduleStrategy);
            out.putAll(row);
            return out;
        });
    }

    private static List<Map<String, Object>> extractCharges(Connection c) throws Exception {
        // Active loan charges only. We drop the human name and replace with synthetic charge_<id>.
        // chargeTimeType is emitted as the enum name string so MnzlScenarioFixtures can filter by "LOAN_PERIODIC".
        String sql = "SELECT id, currency_code AS currencyCode," //
                + "       charge_applies_to_enum AS chargeAppliesToEnum," //
                + "       charge_time_enum AS chargeTimeEnum," //
                + "       charge_calculation_enum AS chargeCalculationEnum," //
                + "       charge_payment_mode_enum AS chargePaymentModeEnum," //
                + "       amount, fee_on_day AS feeOnDay, fee_interval AS feeInterval," //
                + "       fee_on_month AS feeOnMonth, is_penalty AS isPenalty," //
                + "       is_active AS isActive, min_cap AS minCap, max_cap AS maxCap," //
                + "       fee_frequency AS feeFrequency " //
                + "FROM m_charge " //
                + "WHERE is_active = 1 AND is_deleted = 0 " //
                + "ORDER BY id";
        return queryRows(c, sql, row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Object id = row.get("id");
            // Anonymized synthetic label.
            out.put("name", "charge_" + id);
            out.putAll(row);
            // Map charge_time_enum -> human-readable enum string for fixture filters.
            out.put("chargeTimeType", chargeTimeName(row.get("chargeTimeEnum")));
            return out;
        });
    }

    private static List<Map<String, Object>> extractHolidays(Connection c) throws Exception {
        // Join m_holiday to m_holiday_office. Drop holiday name + description (free text).
        String sql = "SELECT h.id AS holidayId," //
                + "       ho.office_id AS officeId," //
                + "       h.from_date AS fromDate," //
                + "       h.to_date AS toDate," //
                + "       h.repayments_rescheduled_to AS repaymentsRescheduledTo," //
                + "       h.status_enum AS statusEnum," //
                + "       h.processed AS processed," //
                + "       h.rescheduling_type AS reschedulingType " //
                + "FROM m_holiday h " //
                + "JOIN m_holiday_office ho ON ho.holiday_id = h.id " //
                + "ORDER BY ho.office_id, h.from_date";
        return queryRows(c, sql, row -> row);
    }

    private static List<Map<String, Object>> extractWorkingDays(Connection c) throws Exception {
        String sql = "SELECT id, recurrence," //
                + "       repayment_rescheduling_enum AS repaymentReschedulingEnum," //
                + "       extend_term_daily_repayments AS extendTermDailyRepayments," //
                + "       extend_term_holiday_repayment AS extendTermHolidayRepayment " //
                + "FROM m_working_days " //
                + "ORDER BY id";
        return queryRows(c, sql, row -> row);
    }

    private static List<Map<String, Object>> extractRehabLoan13516(Connection c) throws Exception {
        // Only input fields needed to recreate the schedule. Drop client_id, group_id, account_no,
        // external_id, fund_id, loan_officer_id, loanpurpose_cv_id (PII / contextual) and all *_derived fields.
        // Loan id 13516 is contractually retained because it is pinned in production code as a legacy stub case.
        String sql = "SELECT id, product_id AS productId," //
                + "       loan_status_id AS loanStatusId," //
                + "       loan_type_enum AS loanTypeEnum," //
                + "       currency_code AS currencyCode," //
                + "       currency_digits AS currencyDigits," //
                + "       principal_amount AS principalAmount," //
                + "       approved_principal AS approvedPrincipal," //
                + "       net_disbursal_amount AS netDisbursalAmount," //
                + "       nominal_interest_rate_per_period AS nominalInterestRatePerPeriod," //
                + "       interest_period_frequency_enum AS interestPeriodFrequencyEnum," //
                + "       annual_nominal_interest_rate AS annualNominalInterestRate," //
                + "       interest_method_enum AS interestMethodEnum," //
                + "       interest_calculated_in_period_enum AS interestCalculatedInPeriodEnum," //
                + "       allow_partial_period_interest_calcualtion AS allowPartialPeriodInterestCalculation," //
                + "       term_frequency AS termFrequency," //
                + "       term_period_frequency_enum AS termPeriodFrequencyEnum," //
                + "       repay_every AS repayEvery," //
                + "       repayment_period_frequency_enum AS repaymentPeriodFrequencyEnum," //
                + "       number_of_repayments AS numberOfRepayments," //
                + "       grace_on_principal_periods AS graceOnPrincipalPeriods," //
                + "       grace_on_interest_periods AS graceOnInterestPeriods," //
                + "       grace_interest_free_periods AS graceInterestFreePeriods," //
                + "       amortization_method_enum AS amortizationMethodEnum," //
                + "       submittedon_date AS submittedOnDate," //
                + "       approvedon_date AS approvedOnDate," //
                + "       expected_disbursedon_date AS expectedDisbursedOnDate," //
                + "       expected_firstrepaymenton_date AS expectedFirstRepaymentOnDate," //
                + "       interest_calculated_from_date AS interestCalculatedFromDate," //
                + "       disbursedon_date AS disbursedOnDate," //
                + "       expected_maturedon_date AS expectedMaturedOnDate," //
                + "       days_in_month_enum AS daysInMonthEnum," //
                + "       days_in_year_enum AS daysInYearEnum," //
                + "       days_in_year_custom_strategy AS daysInYearCustomStrategy," //
                + "       interest_recalculation_enabled AS interestRecalculationEnabled," //
                + "       interest_recognition_on_disbursement_date AS interestRecognitionOnDisbursementDate," //
                + "       loan_schedule_type AS loanScheduleType," //
                + "       loan_schedule_processing_type AS loanScheduleProcessingType," //
                + "       loan_transaction_strategy_code AS loanTransactionStrategyCode," //
                + "       fixed_length AS fixedLength," //
                + "       fixed_emi_amount AS fixedEmiAmount," //
                + "       is_equal_amortization AS isEqualAmortization," //
                + "       grace_on_arrears_ageing AS graceOnArrearsAgeing " //
                + "FROM m_loan WHERE id = 13516";
        return queryRows(c, sql, row -> row);
    }

    private static String chargeTimeName(Object enumVal) {
        if (enumVal == null) {
            return null;
        }
        int v = ((Number) enumVal).intValue();
        switch (v) {
            case 0:
                return "INVALID";
            case 1:
                return "DISBURSEMENT";
            case 2:
                return "SPECIFIED_DUE_DATE";
            case 3:
                return "SAVINGS_ACTIVATION";
            case 4:
                return "SAVINGS_CLOSURE";
            case 5:
                return "WITHDRAWAL_FEE";
            case 6:
                return "ANNUAL_FEE";
            case 7:
                return "MONTHLY_FEE";
            case 8:
                return "INSTALMENT_FEE";
            case 9:
                return "OVERDUE_INSTALLMENT";
            case 10:
                return "OVERDRAFT_FEE";
            case 11:
                return "WEEKLY_FEE";
            case 12:
                return "TRANCHE_DISBURSEMENT";
            case 13:
                return "SHAREACCOUNT_ACTIVATION";
            case 14:
                return "SHARE_PURCHASE";
            case 15:
                return "SHARE_REDEEM";
            case 16:
                return "SAVINGS_NOACTIVITY_FEE";
            case 17:
                return "LOAN_PERIODIC";
            default:
                return "UNKNOWN_" + v;
        }
    }

    @FunctionalInterface
    private interface RowMapper {

        Map<String, Object> map(Map<String, Object> row);
    }

    private static List<Map<String, Object>> queryRows(Connection c, String sql, RowMapper mapper) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int colCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(mapper.map(row));
            }
        }
        return rows;
    }

    private static void writeJson(Object data, String filename) throws Exception {
        Path out = OUT_DIR.resolve(filename);
        try (Writer w = new FileWriter(out.toFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
            w.write('\n');
        }
        LOG.info("Wrote {}", out);
    }
}
