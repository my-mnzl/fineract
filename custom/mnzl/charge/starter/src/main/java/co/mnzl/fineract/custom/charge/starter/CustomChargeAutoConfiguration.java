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
package co.mnzl.fineract.custom.charge.starter;

import org.apache.fineract.portfolio.charge.domain.BasicChargeCalculationDescriptor;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationDescriptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan("co.mnzl.fineract.custom.charge")
@ConditionalOnProperty(name = "mnzl.charge.enabled", havingValue = "true", matchIfMissing = true)
public class CustomChargeAutoConfiguration {

    @Bean
    public ChargeCalculationDescriptor mnzlAmountInterestAndPenaltiesChargeCalculationDescriptor() {
        return new BasicChargeCalculationDescriptor(6, "chargeCalculationType.percent.of.amount.interest.and.penalties",
                "% Amount + Interest + Penalties", true, false, false, false, false, false);
    }
}
