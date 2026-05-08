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
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.organisation.holiday.domain.HolidayRepository;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstanceRepository;
import org.apache.fineract.portfolio.calendar.service.CalendarReadPlatformService;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateDTO;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateData;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.apache.fineract.portfolio.floatingrates.exception.FloatingRateNotFoundException;
import org.apache.fineract.portfolio.floatingrates.service.FloatingRatesReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * L1 unit tests for {@link MnzlLoanUtilService} (Task C.12).
 *
 * <p>
 * The Mnzl override exists for one reason: to construct a {@link FloatingRateDTO} that always treats the loan as
 * floating (vs. the upstream behaviour of honouring {@code loan.getIsFloatingInterestRate()}) and to silently swallow a
 * missing base lending rate. The protected {@code constructFloatingRateDTO(Loan)} hook is exercised here through a tiny
 * test-only subclass that re-exposes the method, keeping the test focused on the override semantics without reaching
 * into the much larger {@code buildScheduleGeneratorDTO} code path.
 * </p>
 *
 * <p>
 * The "passthrough" smoke is implicit: the test subclass does not override anything, so the parent-side fields
 * (configuration, holiday repo, etc.) feed straight through. We assert that for a non-floating loan the override
 * matches the upstream contract and returns {@code null}.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MnzlLoanUtilServiceTest {

    @Mock
    private ApplicationCurrencyRepositoryWrapper applicationCurrencyRepository;
    @Mock
    private CalendarInstanceRepository calendarInstanceRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private HolidayRepository holidayRepository;
    @Mock
    private WorkingDaysRepositoryWrapper workingDaysRepository;
    @Mock
    private LoanScheduleGeneratorFactory loanScheduleFactory;
    @Mock
    private FloatingRatesReadPlatformService floatingRatesReadPlatformService;
    @Mock
    private CalendarReadPlatformService calendarReadPlatformService;
    @Mock
    private NoteRepository noteRepository;

    private TestableMnzlLoanUtilService service;

    @BeforeEach
    void setUp() {
        service = new TestableMnzlLoanUtilService(applicationCurrencyRepository, calendarInstanceRepository, configurationDomainService,
                holidayRepository, workingDaysRepository, loanScheduleFactory, floatingRatesReadPlatformService,
                calendarReadPlatformService, noteRepository);
    }

    @Test
    void passthroughBehaviorPreserved_nonFloatingLoanReturnsNull() {
        // Smoke: when the product is not linked to a floating rate the override returns null, just like the upstream
        // implementation it replaces. This pins the early-return passthrough so we don't accidentally start
        // constructing a DTO for fixed-rate loans.
        Loan loan = mock(Loan.class);
        LoanProduct product = mock(LoanProduct.class);
        when(loan.loanProduct()).thenReturn(product);
        when(product.isLinkedToFloatingInterestRate()).thenReturn(false);

        FloatingRateDTO dto = service.exposedConstructFloatingRateDTO(loan);

        assertThat(dto).isNull();
    }

    @Test
    void floatingLoan_buildsDtoWithBaseLendingRatePeriods() {
        // Mnzl override hard-codes the floating flag to true and pulls base-lending-rate periods from the rate service.
        Loan loan = mock(Loan.class);
        LoanProduct product = mock(LoanProduct.class);
        when(loan.loanProduct()).thenReturn(product);
        when(product.isLinkedToFloatingInterestRate()).thenReturn(true);
        when(loan.getInterestRateDifferential()).thenReturn(new BigDecimal("1.50"));
        when(loan.getDisbursementDate()).thenReturn(LocalDate.of(2025, 1, 1));

        FloatingRatePeriodData period = new FloatingRatePeriodData(1L, LocalDate.of(2024, 12, 1), new BigDecimal("8.00"), Boolean.FALSE,
                Boolean.TRUE);
        FloatingRateData baseRate = mock(FloatingRateData.class);
        when(baseRate.getRatePeriods()).thenReturn(List.of(period));
        when(floatingRatesReadPlatformService.retrieveBaseLendingRate()).thenReturn(baseRate);

        FloatingRateDTO dto = service.exposedConstructFloatingRateDTO(loan);

        assertThat(dto).isNotNull();
        assertThat(dto.isFloatingInterestRate()).isTrue();
        assertThat(dto.getStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(dto.getInterestRateDiff()).isEqualByComparingTo("1.50");
        assertThat(dto.getBaseLendingRatePeriods()).containsExactly(period);
    }

    @Test
    void floatingLoan_swallowsMissingBaseLendingRate() {
        // Production-distinct behaviour: if no base lending rate exists, the upstream code propagates the
        // exception. The Mnzl override catches FloatingRateNotFoundException so MNZL floating-rate schedules can be
        // built without a configured base rate. This test pins that contract.
        Loan loan = mock(Loan.class);
        LoanProduct product = mock(LoanProduct.class);
        when(loan.loanProduct()).thenReturn(product);
        when(product.isLinkedToFloatingInterestRate()).thenReturn(true);
        when(loan.getInterestRateDifferential()).thenReturn(BigDecimal.ZERO);
        when(loan.getDisbursementDate()).thenReturn(LocalDate.of(2025, 3, 15));
        when(floatingRatesReadPlatformService.retrieveBaseLendingRate())
                .thenThrow(new FloatingRateNotFoundException("error.msg.floatingrate.not.found"));

        FloatingRateDTO dto = service.exposedConstructFloatingRateDTO(loan);

        assertThat(dto).isNotNull();
        assertThat(dto.isFloatingInterestRate()).isTrue();
        // Base lending rate periods stay null when missing — the DTO stores whatever the override passed in.
        assertThat(dto.getBaseLendingRatePeriods()).isNull();
    }

    /**
     * Tiny subclass that re-exposes the protected {@code constructFloatingRateDTO} hook. The override under test is the
     * only substantive behavioural change in {@link MnzlLoanUtilService}; everything else is straight passthrough to
     * the upstream {@code LoanUtilService}.
     */
    private static final class TestableMnzlLoanUtilService extends MnzlLoanUtilService {

        TestableMnzlLoanUtilService(ApplicationCurrencyRepositoryWrapper applicationCurrencyRepository,
                CalendarInstanceRepository calendarInstanceRepository, ConfigurationDomainService configurationDomainService,
                HolidayRepository holidayRepository, WorkingDaysRepositoryWrapper workingDaysRepository,
                LoanScheduleGeneratorFactory loanScheduleFactory, FloatingRatesReadPlatformService floatingRatesReadPlatformService,
                CalendarReadPlatformService calendarReadPlatformService, NoteRepository noteRepository) {
            super(applicationCurrencyRepository, calendarInstanceRepository, configurationDomainService, holidayRepository,
                    workingDaysRepository, loanScheduleFactory, floatingRatesReadPlatformService, calendarReadPlatformService,
                    noteRepository);
        }

        FloatingRateDTO exposedConstructFloatingRateDTO(Loan loan) {
            return constructFloatingRateDTO(loan);
        }
    }
}
