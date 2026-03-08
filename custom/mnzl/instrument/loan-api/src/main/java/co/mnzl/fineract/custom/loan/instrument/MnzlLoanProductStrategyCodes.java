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

public final class MnzlLoanProductStrategyCodes {

    public static final String INSTRUMENT_STANDARD_LOAN = "MNZL_STANDARD_LOAN";
    public static final String INSTRUMENT_BALLOON_LOAN = "MNZL_BALLOON_LOAN";

    public static final String SCHEDULE_CORE = "CORE_DEFAULT";
    public static final String SCHEDULE_MNZL_DECLINING_BALANCE = "MNZL_DECLINING_BALANCE";

    public static final String CHARGE_CORE = "CORE_DEFAULT";
    public static final String CHARGE_MNZL_INTEREST_AND_PENALTIES = "MNZL_INTEREST_AND_PENALTIES";

    public static final String COB_CORE = "CORE_DEFAULT";
    public static final String COB_MNZL_DUE_INSTALLMENTS = "MNZL_DUE_INSTALLMENTS";

    private MnzlLoanProductStrategyCodes() {}
}
