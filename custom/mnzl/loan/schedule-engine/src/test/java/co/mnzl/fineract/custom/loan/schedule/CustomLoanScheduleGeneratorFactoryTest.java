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
package co.mnzl.fineract.custom.loan.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyCodes;
import co.mnzl.fineract.custom.loan.instrument.MnzlLoanProductStrategyReadService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.CumulativeFlatInterestLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultLoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleSelectionContext;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ProgressiveLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.junit.jupiter.api.Test;

class CustomLoanScheduleGeneratorFactoryTest {

    @Test
    void createUsesCustomGeneratorForCumulativeDecliningBalance() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);

        CustomLoanScheduleGeneratorFactory factory = new CustomLoanScheduleGeneratorFactory(progressive, flat, custom, defaultFactory,
                strategyReadService);

        LoanScheduleGenerator result = factory.create(LoanScheduleType.CUMULATIVE, InterestMethod.DECLINING_BALANCE);

        assertThat(result).isSameAs(custom);
    }

    @Test
    void createUsesExistingProgressiveGeneratorForProgressiveLoans() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);

        CustomLoanScheduleGeneratorFactory factory = new CustomLoanScheduleGeneratorFactory(progressive, flat, custom, defaultFactory,
                strategyReadService);

        LoanScheduleGenerator result = factory.create(LoanScheduleType.PROGRESSIVE, InterestMethod.DECLINING_BALANCE);

        assertThat(result).isSameAs(progressive);
    }

    @Test
    void createDelegatesToDefaultFactoryWhenExplicitCoreStrategyConfigured() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleGenerator defaultGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(42L).build();
        when(strategyReadService.findScheduleStrategyCode(42L))
                .thenReturn(java.util.Optional.of(MnzlLoanProductStrategyCodes.SCHEDULE_CORE));
        when(defaultFactory.create(selectionContext)).thenReturn(defaultGenerator);

        CustomLoanScheduleGeneratorFactory factory = new CustomLoanScheduleGeneratorFactory(progressive, flat, custom, defaultFactory,
                strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isSameAs(defaultGenerator);
    }

    @Test
    void createUsesCustomGeneratorWhenDbLookupReturnsMnzlDecliningBalance() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(42L).build();
        when(strategyReadService.findScheduleStrategyCode(42L))
                .thenReturn(java.util.Optional.of(MnzlLoanProductStrategyCodes.SCHEDULE_MNZL_DECLINING_BALANCE));

        CustomLoanScheduleGeneratorFactory factory = new CustomLoanScheduleGeneratorFactory(progressive, flat, custom, defaultFactory,
                strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isSameAs(custom);
        verifyNoInteractions(defaultFactory);
    }

    @Test
    void createDelegatesToDefaultFactoryWhenNoStrategyConfigured() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleGenerator defaultGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(42L).build();
        when(strategyReadService.findScheduleStrategyCode(42L)).thenReturn(java.util.Optional.empty());
        when(defaultFactory.create(selectionContext)).thenReturn(defaultGenerator);

        CustomLoanScheduleGeneratorFactory factory = new CustomLoanScheduleGeneratorFactory(progressive, flat, custom, defaultFactory,
                strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isSameAs(defaultGenerator);
        verifyNoInteractions(custom);
    }

    // ---- Additional routing tests (Tasks C.2) ----

    private CustomLoanScheduleGeneratorFactory newFactory(ProgressiveLoanScheduleGenerator progressive,
            CumulativeFlatInterestLoanScheduleGenerator flat, CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom,
            DefaultLoanScheduleGeneratorFactory defaultFactory, MnzlLoanProductStrategyReadService strategyReadService) {
        return new CustomLoanScheduleGeneratorFactory(progressive, flat, custom, defaultFactory, strategyReadService);
    }

    @Test
    void create_progressiveDecliningBalance_returnsUpstream() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(LoanScheduleType.PROGRESSIVE, InterestMethod.DECLINING_BALANCE);

        assertThat(result).isNotInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(progressive);
    }

    @Test
    void create_cumulativeFlat_returnsUpstream() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(LoanScheduleType.CUMULATIVE, InterestMethod.FLAT);

        assertThat(result).isNotInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(flat);
    }

    @Test
    void create_cumulativeDecliningBalance_returnsCustom() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(LoanScheduleType.CUMULATIVE, InterestMethod.DECLINING_BALANCE);

        assertThat(result).isInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(custom);
    }

    @Test
    void create_withContext_strategyCoreDefault_returnsUpstream() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleGenerator defaultGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(101L).build();
        when(strategyReadService.findScheduleStrategyCode(101L))
                .thenReturn(java.util.Optional.of(MnzlLoanProductStrategyCodes.SCHEDULE_CORE));
        when(defaultFactory.create(selectionContext)).thenReturn(defaultGenerator);

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isNotInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(defaultGenerator);
        verifyNoInteractions(custom);
    }

    @Test
    void create_withContext_strategyMnzl_returnsCustom() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(202L).build();
        when(strategyReadService.findScheduleStrategyCode(202L))
                .thenReturn(java.util.Optional.of(MnzlLoanProductStrategyCodes.SCHEDULE_MNZL_DECLINING_BALANCE));

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(custom);
        verifyNoInteractions(defaultFactory);
    }

    @Test
    void create_withContext_strategyNull_returnsUpstream() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleGenerator defaultGenerator = mock(LoanScheduleGenerator.class);
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(303L).build();
        when(strategyReadService.findScheduleStrategyCode(303L)).thenReturn(java.util.Optional.empty());
        when(defaultFactory.create(selectionContext)).thenReturn(defaultGenerator);

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isNotInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(defaultGenerator);
        verifyNoInteractions(custom);
    }

    @Test
    void create_withContext_strategyUnknown_returnsUpstream() {
        ProgressiveLoanScheduleGenerator progressive = mock(ProgressiveLoanScheduleGenerator.class);
        CumulativeFlatInterestLoanScheduleGenerator flat = mock(CumulativeFlatInterestLoanScheduleGenerator.class);
        CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator custom = mock(
                CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        DefaultLoanScheduleGeneratorFactory defaultFactory = mock(DefaultLoanScheduleGeneratorFactory.class);
        MnzlLoanProductStrategyReadService strategyReadService = mock(MnzlLoanProductStrategyReadService.class);
        LoanScheduleGenerator defaultGenerator = mock(LoanScheduleGenerator.class);
        // Unknown strategy code: bypasses readService entirely (scheduleStrategyCode is non-null on the context)
        LoanScheduleSelectionContext selectionContext = LoanScheduleSelectionContext.builder().loanScheduleType(LoanScheduleType.CUMULATIVE)
                .interestMethod(InterestMethod.DECLINING_BALANCE).loanProductId(404L).scheduleStrategyCode("WHATEVER_NOT_REAL").build();
        when(defaultFactory.create(selectionContext)).thenReturn(defaultGenerator);

        CustomLoanScheduleGeneratorFactory factory = newFactory(progressive, flat, custom, defaultFactory, strategyReadService);

        LoanScheduleGenerator result = factory.create(selectionContext);

        assertThat(result).isNotInstanceOf(CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator.class);
        assertThat(result).isSameAs(defaultGenerator);
        verifyNoInteractions(custom);
        verifyNoInteractions(strategyReadService);
    }
}
