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
package org.apache.fineract.integrationtests.mnzl.helpers;

import com.google.gson.Gson;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;

/** Creates mnzl-flavoured charges. The custom calculator (charge calculation type 6) is the differentiator. */
public final class MnzlChargesHelper {

    /** Charge calculation type 6 — % Amount + Interest + Penalties (registered by custom/mnzl/charge/starter). */
    public static final int MNZL_PERCENT_AMOUNT_INTEREST_PENALTIES = 6;

    private final RequestSpecification request;
    private final ResponseSpecification response;

    public MnzlChargesHelper(RequestSpecification request, ResponseSpecification response) {
        this.request = request;
        this.response = response;
    }

    /** Overdue penalty using MNZL custom calculator (% of principal+interest+penalties outstanding). */
    public Integer createMnzlOverduePenaltyPercent(String percentage) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", Utils.uniqueRandomStringGenerator("MNZL_OVERDUE_PCT_", 6));
        body.put("chargeAppliesTo", 1); // LOAN
        body.put("chargeTimeType", 9); // OVERDUE_INSTALLMENT
        body.put("chargeCalculationType", MNZL_PERCENT_AMOUNT_INTEREST_PENALTIES);
        body.put("chargePaymentMode", 0); // REGULAR — required for loan charges
        body.put("amount", percentage);
        body.put("currencyCode", "USD");
        body.put("active", true);
        body.put("penalty", true);
        body.put("locale", "en");
        return ChargesHelper.createCharges(request, response, new Gson().toJson(body));
    }

    /** Periodic monthly fee — mnzl assembler expands these on submission. */
    public Integer createMnzlPeriodicMonthlyFee(double amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", Utils.uniqueRandomStringGenerator("MNZL_PERIODIC_", 6));
        body.put("chargeAppliesTo", 1);
        body.put("chargeTimeType", 17); // LOAN_PERIODIC — periodic charge applied each repayment period
        body.put("chargeCalculationType", 1); // FLAT
        body.put("chargePaymentMode", 0); // REGULAR — required for loan charges
        body.put("amount", amount);
        body.put("currencyCode", "USD");
        body.put("active", true);
        body.put("locale", "en");
        body.put("feeFrequency", 2); // MONTHS
        body.put("feeInterval", 1);
        return ChargesHelper.createCharges(request, response, new Gson().toJson(body));
    }

    /**
     * Flat ad-hoc fee whose due date is supplied at attach-time (chargeTimeType = SPECIFIED_DUE_DATE). Suitable for the
     * simulator's ADD_CHARGE action: the action's date is forwarded as the charge's dueDate by the runner.
     */
    public Integer createSpecifiedDueDateFee(double amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", Utils.uniqueRandomStringGenerator("MNZL_SPECIFIC_", 6));
        body.put("chargeAppliesTo", 1);
        body.put("chargeTimeType", 2); // SPECIFIED_DUE_DATE
        body.put("chargeCalculationType", 1); // FLAT
        body.put("chargePaymentMode", 0); // REGULAR — required for loan charges
        body.put("amount", amount);
        body.put("currencyCode", "USD");
        body.put("active", true);
        body.put("locale", "en");
        return ChargesHelper.createCharges(request, response, new Gson().toJson(body));
    }

    /** Flat fee on disbursement. Useful as a control case (NOT periodic, NOT custom-calculator). */
    public Integer createFlatDisbursementFee(double amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", Utils.uniqueRandomStringGenerator("MNZL_FLAT_", 6));
        body.put("chargeAppliesTo", 1);
        body.put("chargeTimeType", 1); // DISBURSEMENT
        body.put("chargeCalculationType", 1); // FLAT
        body.put("chargePaymentMode", 0); // REGULAR — required for loan charges
        body.put("amount", amount);
        body.put("currencyCode", "USD");
        body.put("active", true);
        body.put("locale", "en");
        return ChargesHelper.createCharges(request, response, new Gson().toJson(body));
    }
}
