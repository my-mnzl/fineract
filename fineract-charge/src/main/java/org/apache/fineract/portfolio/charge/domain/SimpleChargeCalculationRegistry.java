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
package org.apache.fineract.portfolio.charge.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SimpleChargeCalculationRegistry implements ChargeCalculationRegistry {

    private final Map<Integer, ChargeCalculationDescriptor> descriptorsById;

    public SimpleChargeCalculationRegistry(List<ChargeCalculationDescriptor> descriptors) {
        this.descriptorsById = descriptors.stream()
                .collect(Collectors.toUnmodifiableMap(ChargeCalculationDescriptor::id, descriptor -> descriptor));
    }

    @Override
    public ChargeCalculationDescriptor find(int id) {
        ChargeCalculationDescriptor descriptor = descriptorsById.get(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown charge calculation type: " + id);
        }
        return descriptor;
    }

    @Override
    public List<ChargeCalculationDescriptor> loanDescriptors() {
        return descriptors(ChargeCalculationDescriptor::supportsLoan);
    }

    @Override
    public List<ChargeCalculationDescriptor> savingsDescriptors() {
        return descriptors(ChargeCalculationDescriptor::supportsSavings);
    }

    @Override
    public List<ChargeCalculationDescriptor> sharesDescriptors() {
        return descriptors(ChargeCalculationDescriptor::supportsShares);
    }

    @Override
    public List<ChargeCalculationDescriptor> clientDescriptors() {
        return descriptors(ChargeCalculationDescriptor::supportsClients);
    }

    @Override
    public List<ChargeCalculationDescriptor> shareAccountActivationDescriptors() {
        return descriptors(ChargeCalculationDescriptor::supportsShareAccountActivation);
    }

    @Override
    public List<ChargeCalculationDescriptor> trancheDisbursementDescriptors() {
        return descriptors(ChargeCalculationDescriptor::supportsTrancheDisbursement);
    }

    private List<ChargeCalculationDescriptor> descriptors(Predicate<ChargeCalculationDescriptor> predicate) {
        return descriptorsById.values().stream().filter(predicate).sorted(Comparator.comparing(ChargeCalculationDescriptor::id)).toList();
    }
}
