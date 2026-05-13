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
package org.apache.fineract.portfolio.loanaccount.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.configuration.domain.GlobalConfigurationProperty;
import org.apache.fineract.infrastructure.configuration.domain.GlobalConfigurationRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.entityaccess.domain.FineractEntityRelationRepository;
import org.apache.fineract.infrastructure.entityaccess.domain.FineractEntityToEntityMappingRepository;
import org.apache.fineract.organisation.holiday.domain.HolidayRepository;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstanceRepository;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.collateralmanagement.domain.ClientCollateralManagementRepositoryWrapper;
import org.apache.fineract.portfolio.collateralmanagement.service.LoanCollateralAssembler;
import org.apache.fineract.portfolio.group.domain.GroupRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleTransactionProcessorFactory;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleProcessingType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanMapper;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanproduct.domain.AdvancedPaymentAllocationsValidator;
import org.apache.fineract.portfolio.loanproduct.domain.InterestCalculationPeriodMethod;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductFloatingRates;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.loanproduct.serialization.LoanProductDataValidator;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanApplicationValidatorTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2025, 6, 1);
    private static final String TRANSACTION_PROCESSING_STRATEGY = "mifos-standard-strategy";

    @Spy
    private FromJsonHelper fromApiJsonHelper = new FromJsonHelper();
    @Mock
    private LoanScheduleValidator loanScheduleValidator;
    @Mock
    private ClientCollateralManagementRepositoryWrapper clientCollateralManagementRepositoryWrapper;
    @Mock
    private LoanChargeApiJsonValidator loanChargeApiJsonValidator;
    @Mock
    private LoanRepaymentScheduleTransactionProcessorFactory loanRepaymentScheduleTransactionProcessorFactory;
    @Mock
    private AdvancedPaymentAllocationsValidator advancedPaymentAllocationsValidator;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private LoanProductRepository loanProductRepository;
    @Mock
    private ClientRepositoryWrapper clientRepository;
    @Mock
    private GroupRepositoryWrapper groupRepository;
    @Mock
    private LoanReadPlatformService loanReadPlatformService;
    @Mock
    private LoanProductDataValidator loanProductDataValidator;
    @Mock
    private GlobalConfigurationRepositoryWrapper globalConfigurationRepository;
    @Mock
    private FineractEntityToEntityMappingRepository entityMappingRepository;
    @Mock
    private FineractEntityRelationRepository fineractEntityRelationRepository;
    @Mock
    private LoanRepositoryWrapper loanRepositoryWrapper;
    @Mock
    private LoanProductReadPlatformService loanProductReadPlatformService;
    @Mock
    private LoanCollateralAssembler collateralAssembler;
    @Mock
    private WorkingDaysRepositoryWrapper workingDaysRepository;
    @Mock
    private HolidayRepository holidayRepository;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private LoanLifecycleStateMachine loanLifecycleStateMachine;
    @Mock
    private CalendarInstanceRepository calendarInstanceRepository;
    @Mock
    private LoanUtilService loanUtilService;
    @Mock
    private EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService;
    @Mock
    private LoanMapper loanMapper;

    @InjectMocks
    private LoanApplicationValidator validator;

    private LoanProduct floatingLoanProduct;
    private LoanProduct fixedLoanProduct;
    private LoanProductRelatedDetail productRelatedDetail;
    private Client client;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
        HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE);
        dates.put(BusinessDateType.COB_DATE, BUSINESS_DATE.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(dates);

        productRelatedDetail = mock(LoanProductRelatedDetail.class);
        when(productRelatedDetail.getLoanScheduleProcessingType()).thenReturn(LoanScheduleProcessingType.HORIZONTAL);
        when(productRelatedDetail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        when(productRelatedDetail.getInterestMethod()).thenReturn(InterestMethod.DECLINING_BALANCE);
        when(productRelatedDetail.getInterestCalculationPeriodMethod())
                .thenReturn(InterestCalculationPeriodMethod.SAME_AS_REPAYMENT_PERIOD);
        when(productRelatedDetail.isAllowPartialPeriodInterestCalculation()).thenReturn(true);

        floatingLoanProduct = loanProduct(true, new BigDecimal("-1.00"), new BigDecimal("1.00"));
        fixedLoanProduct = loanProduct(false, BigDecimal.ZERO, BigDecimal.ZERO);
        when(loanProductRepository.findById(1L)).thenReturn(Optional.of(floatingLoanProduct));
        when(loanProductRepository.findById(2L)).thenReturn(Optional.of(fixedLoanProduct));

        Office office = mock(Office.class);
        when(office.getId()).thenReturn(1L);
        client = mock(Client.class);
        when(client.getId()).thenReturn(1L);
        when(client.getOffice()).thenReturn(office);
        when(clientRepository.findOneWithNotFoundDetection(1L)).thenReturn(client);

        GlobalConfigurationProperty officeSpecificProducts = new GlobalConfigurationProperty().setEnabled(false);
        when(globalConfigurationRepository
                .findOneByNameWithNotFoundDetection(GlobalConfigurationConstants.OFFICE_SPECIFIC_PRODUCTS_ENABLED))
                .thenReturn(officeSpecificProducts);
        when(configurationDomainService.allowTransactionsOnNonWorkingDayEnabled()).thenReturn(true);
        when(configurationDomainService.allowTransactionsOnHolidayEnabled()).thenReturn(true);
        when(holidayRepository.findByOfficeIdAndGreaterThanDate(anyLong(), any(LocalDate.class), anyInt())).thenReturn(List.of());
        when(loanRepositoryWrapper.findActiveLoansLoanProductIdsByClient(1L, LoanStatus.ACTIVE)).thenReturn(List.of());
    }

    @Test
    void validateForCreateAcceptsNegativeInterestRateDifferentialWithinProductRange() {
        validator.validateForCreate(command(createLoanJson(1L, "-0.25")));
    }

    @Test
    void validateForCreateRejectsInterestRateDifferentialBelowProductMinimum() {
        assertValidationError(() -> validator.validateForCreate(command(createLoanJson(1L, "-1.25"))), "interestRateDifferential");
    }

    @Test
    void validateForCreateRejectsInterestRateDifferentialAboveProductMaximum() {
        assertValidationError(() -> validator.validateForCreate(command(createLoanJson(1L, "1.25"))), "interestRateDifferential");
    }

    @Test
    void validateForCreateRejectsInterestRateDifferentialForFixedRateProduct() {
        assertValidationError(() -> validator.validateForCreate(command(createLoanJson(2L, "-0.25"))), "interestRateDifferential");
    }

    @Test
    void validateForModifyAcceptsNegativeInterestRateDifferentialWithinProductRange() {
        validator.validateForModify(command(updateLoanJson("-0.25")), loan("-0.25"));
    }

    @Test
    void validateForModifyRejectsInterestRateDifferentialBelowProductMinimum() {
        assertValidationError(() -> validator.validateForModify(command(updateLoanJson("-1.25")), loan("-1.25")),
                "interestRateDifferential");
    }

    @Test
    void validateForModifyRejectsInterestRateDifferentialAboveProductMaximum() {
        assertValidationError(() -> validator.validateForModify(command(updateLoanJson("1.25")), loan("1.25")), "interestRateDifferential");
    }

    private LoanProduct loanProduct(boolean linkedToFloatingRate, BigDecimal minDifferential, BigDecimal maxDifferential) {
        LoanProduct product = mock(LoanProduct.class);
        when(product.isLinkedToFloatingInterestRate()).thenReturn(linkedToFloatingRate);
        when(product.getLoanProductRelatedDetail()).thenReturn(productRelatedDetail);
        when(product.getTransactionProcessingStrategyCode()).thenReturn(TRANSACTION_PROCESSING_STRATEGY);
        if (linkedToFloatingRate) {
            LoanProductFloatingRates floatingRates = mock(LoanProductFloatingRates.class);
            when(floatingRates.getMinDifferentialLendingRate()).thenReturn(minDifferential);
            when(floatingRates.getMaxDifferentialLendingRate()).thenReturn(maxDifferential);
            when(floatingRates.isFloatingInterestRateCalculationAllowed()).thenReturn(true);
            when(product.getFloatingRates()).thenReturn(floatingRates);
        }
        return product;
    }

    private Loan loan(String interestRateDifferential) {
        Loan loan = mock(Loan.class);
        when(loan.isSubmittedAndPendingApproval()).thenReturn(true);
        when(loan.getLoanProduct()).thenReturn(floatingLoanProduct);
        when(loan.loanProduct()).thenReturn(floatingLoanProduct);
        when(loan.getClient()).thenReturn(client);
        when(loan.getGroup()).thenReturn(null);
        when(loan.getLoanProductRelatedDetail()).thenReturn(productRelatedDetail);
        when(loan.getLoanRepaymentScheduleDetail()).thenReturn(productRelatedDetail);
        when(loan.getInterestRateDifferential()).thenReturn(new BigDecimal(interestRateDifferential));
        when(loan.getExpectedDisbursementDate()).thenReturn(BUSINESS_DATE);
        when(loan.getSubmittedOnDate()).thenReturn(BUSINESS_DATE.minusMonths(1));
        when(loan.getLoanTermVariations()).thenReturn(List.of());
        return loan;
    }

    private static JsonCommand command(String json) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.json()).thenReturn(json);
        when(command.longValueOfParameterNamed("productId")).thenReturn(null);
        return command;
    }

    private static String createLoanJson(Long productId, String interestRateDifferential) {
        return """
                {
                  "locale": "en",
                  "dateFormat": "dd MMMM yyyy",
                  "productId": %s,
                  "clientId": 1,
                  "loanType": "individual",
                  "principal": 1000,
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "numberOfRepayments": 12,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "interestType": 0,
                  "interestCalculationPeriodType": 1,
                  "allowPartialPeriodInterestCalcualtion": true,
                  "isFloatingInterestRate": false,
                  "interestRateDifferential": %s,
                  "amortizationType": 1,
                  "expectedDisbursementDate": "01 June 2025",
                  "submittedOnDate": "01 May 2025",
                  "transactionProcessingStrategyCode": "mifos-standard-strategy"
                }
                """.formatted(productId, interestRateDifferential);
    }

    private static String updateLoanJson(String interestRateDifferential) {
        return """
                {
                  "locale": "en",
                  "dateFormat": "dd MMMM yyyy",
                  "loanType": "individual",
                  "interestRateDifferential": %s
                }
                """.formatted(interestRateDifferential);
    }

    private static void assertValidationError(Runnable action, String parameterName) {
        assertThatThrownBy(action::run).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(ex -> assertThat(((PlatformApiDataValidationException) ex).getErrors())
                        .anySatisfy(error -> assertThat(error.getParameterName()).isEqualTo(parameterName)));
    }
}
