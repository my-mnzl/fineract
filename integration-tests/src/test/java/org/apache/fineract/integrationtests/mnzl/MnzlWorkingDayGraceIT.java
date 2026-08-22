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
package org.apache.fineract.integrationtests.mnzl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.LoanProductChargeData;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductBuilder;
import org.apache.fineract.integrationtests.mnzl.helpers.MnzlProductStrategyHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class MnzlWorkingDayGraceIT extends BaseLoanIntegrationTest {

    private static final String DEFAULT_WORKING_DAYS_RECURRENCE = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA,SU";
    private final List<Integer> createdHolidayIds = new ArrayList<>();

    @AfterEach
    void restoreGlobalConfigAndHolidays() {
        setWorkingDays(DEFAULT_WORKING_DAYS_RECURRENCE, 1);
        globalConfigurationHelper.resetAllDefaultGlobalConfigurations();
        for (Integer holidayId : createdHolidayIds) {
            Utils.performServerDelete(requestSpec, responseSpec,
                    "/fineract-provider/api/v1/holidays/" + holidayId + "?" + Utils.TENANT_IDENTIFIER, "{}", "resourceId");
        }
        createdHolidayIds.clear();
    }

    @Test
    public void cobGraceSkipsWeekendsAndHolidays() {
        runAt("21 May 2026", () -> {
            configureWorkingDayGrace();
            Long loanId = createLoanWithOverduePenalty();
            assertEquals(LocalDate.of(2026, 6, 21), firstInstallmentDueDate(loanTransactionHelper.getLoanDetails(loanId)));

            updateBusinessDate("29 June 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()), 0.0001);

            updateBusinessDate("02 July 2026");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertTrue(Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()) > 0);
        });
    }

    @Test
    public void scheduledOverduePenaltyJobUsesWorkingDayGrace() {
        runAt("21 May 2026", () -> {
            configureWorkingDayGrace();
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.BACKDATE_PENALTIES_ENABLED,
                    new PutGlobalConfigurationsRequest().enabled(true));
            Long loanId = createLoanWithOverduePenalty();

            updateBusinessDate("30 June 2026");
            schedulerJobHelper.executeAndAwaitJob("Apply penalty to overdue loans");
            GetLoansLoanIdResponse loan = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(0.0, Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()), 0.0001);

            updateBusinessDate("01 July 2026");
            schedulerJobHelper.executeAndAwaitJob("Apply penalty to overdue loans");
            loan = loanTransactionHelper.getLoanDetails(loanId);
            assertTrue(Utils.getDoubleValue(loan.getSummary().getPenaltyChargesOutstanding()) > 0);
        });
    }

    private void configureWorkingDayGrace() {
        setWorkingDays("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU,MO,TU,WE,TH", 2);
        globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.PENALTY_WAIT_PERIOD,
                new PutGlobalConfigurationsRequest().value(5L));
        createAndActivateHoliday("24 June 2026", "28 June 2026");
    }

    private Long createLoanWithOverduePenalty() {
        Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        Integer penaltyChargeId = ChargesHelper.createCharges(requestSpec, responseSpec,
                ChargesHelper.getLoanOverdueFeeJSONWithCalculationTypePercentage("1"));
        MnzlProductBuilder builder = new MnzlProductBuilder(fundSource, loansReceivableAccount, suspenseAccount, interestIncomeAccount,
                feeIncomeAccount, penaltyIncomeAccount, recoveriesAccount, writtenOffAccount, overpaymentAccount, interestReceivableAccount,
                feeReceivableAccount, penaltyReceivableAccount, goodwillExpenseAccount, interestIncomeChargeOffAccount, feeChargeOffAccount,
                penaltyChargeOffAccount, chargeOffExpenseAccount, chargeOffFraudExpenseAccount);
        PostLoanProductsResponse product = loanProductHelper.createLoanProduct(
                builder.decliningBalance30_360().charges(List.of(new LoanProductChargeData().id(penaltyChargeId.longValue()))));
        new MnzlProductStrategyHelper(requestSpec, responseSpec).setMnzl(product.getResourceId());
        return applyApproveDisburseLoan(clientId, product.getResourceId());
    }

    private Long applyApproveDisburseLoan(Long clientId, Long productId) {
        String date = "21 May 2026";
        BigDecimal principal = new BigDecimal("120000.00");
        PostLoansResponse loanResponse = loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(productId)
                .principal(principal).loanTermFrequency(12).loanTermFrequencyType(2).numberOfRepayments(12).repaymentEvery(1)
                .repaymentFrequencyType(2).interestRatePerPeriod(new BigDecimal("12.00")).amortizationType(1).interestType(0)
                .interestCalculationPeriodType(1).transactionProcessingStrategyCode("mifos-standard-strategy")
                .expectedDisbursementDate(date).submittedOnDate(date).dateFormat(DATETIME_PATTERN).locale("en").loanType("individual"));
        Long loanId = loanResponse.getLoanId();
        loanTransactionHelper.approveLoan(loanId,
                new PostLoansLoanIdRequest().approvedLoanAmount(principal).dateFormat(DATETIME_PATTERN).approvedOnDate(date).locale("en"));
        loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(date).dateFormat(DATETIME_PATTERN)
                .transactionAmount(principal).locale("en"));
        return loanId;
    }

    private LocalDate firstInstallmentDueDate(GetLoansLoanIdResponse loan) {
        return loan.getRepaymentSchedule().getPeriods().stream().filter(period -> Integer.valueOf(1).equals(period.getPeriod())).findFirst()
                .orElseThrow().getDueDate();
    }

    private void setWorkingDays(String recurrence, int repaymentRescheduleType) {
        Map<String, Object> body = new HashMap<>();
        body.put("recurrence", recurrence);
        body.put("locale", "en");
        body.put("repaymentRescheduleType", repaymentRescheduleType);
        body.put("extendTermForDailyRepayments", false);
        Utils.performServerPut(requestSpec, responseSpec, "/fineract-provider/api/v1/workingdays?" + Utils.TENANT_IDENTIFIER,
                new Gson().toJson(body), "");
    }

    private void createAndActivateHoliday(String fromDate, String toDate) {
        Map<String, Object> office = new HashMap<>();
        office.put("officeId", "1");
        Map<String, Object> body = new HashMap<>();
        body.put("offices", List.of(office));
        body.put("locale", "en");
        body.put("dateFormat", DATETIME_PATTERN);
        body.put("name", Utils.uniqueRandomStringGenerator("testing_", 5));
        body.put("fromDate", fromDate);
        body.put("toDate", toDate);
        body.put("reschedulingType", 1);
        Integer holidayId = Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/holidays?" + Utils.TENANT_IDENTIFIER, new Gson().toJson(body), "resourceId");
        Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/holidays/" + holidayId + "?command=activate&" + Utils.TENANT_IDENTIFIER, "{}", "resourceId");
        createdHolidayIds.add(holidayId);
    }
}
