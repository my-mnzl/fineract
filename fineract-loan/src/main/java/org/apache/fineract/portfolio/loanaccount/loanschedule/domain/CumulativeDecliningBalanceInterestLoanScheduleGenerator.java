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
package org.apache.fineract.portfolio.loanaccount.loanschedule.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.mapper.CurrencyMapper;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanproduct.domain.AmortizationMethod;
import org.springframework.stereotype.Component;

/**
 * <p>
 * Declining balance can be amortized (see {@link AmortizationMethod}) in two ways at present:
 * <ol>
 * <li>Equal principal payments</li>
 * <li>Equal installment payments</li>
 * </ol>
 * <p>
 * </p>
 *
 * <p>
 * When amortized using <i>equal principal payments</i>, the <b>principal component</b> of each installment is fixed and
 * <b>interest due</b> is calculated from the <b>outstanding principal balance</b> resulting in a different <b>total
 * payment due</b> for each installment.
 * </p>
 *
 * <p>
 * When amortized using <i>equal installments</i>, the <b>total payment due</b> for each installment is fixed and is
 * calculated using the excel like <code>pmt</code> function. The <b>interest due</b> is calculated from the
 * <b>outstanding principal balance</b> which results in a <b>principal component</b> that is <b>total payment due</b>
 * minus <b>interest due</b>.
 * </p>
 */
@Component
public class CumulativeDecliningBalanceInterestLoanScheduleGenerator extends AbstractCumulativeLoanScheduleGenerator {

    private final ScheduledDateGenerator scheduledDateGenerator;
    private final PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator;

    public CumulativeDecliningBalanceInterestLoanScheduleGenerator(final ScheduledDateGenerator scheduledDateGenerator,
            final PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator,
            final LoanTransactionRepository loanTransactionRepository, final CurrencyMapper currencyMapper) {
        super(loanTransactionRepository, currencyMapper);
        this.scheduledDateGenerator = scheduledDateGenerator;
        this.paymentPeriodsInOneYearCalculator = paymentPeriodsInOneYearCalculator;
    }

    @Override
    public ScheduledDateGenerator getScheduledDateGenerator() {
        return scheduledDateGenerator;
    }

    @Override
    public PaymentPeriodsInOneYearCalculator getPaymentPeriodsInOneYearCalculator() {
        return paymentPeriodsInOneYearCalculator;
    }

    @Override
    public PrincipalInterest calculatePrincipalInterestComponentsForPeriod(final PaymentPeriodsInOneYearCalculator calculator,
            final BigDecimal interestCalculationGraceOnRepaymentPeriodFraction, final Money totalCumulativePrincipal,
            @SuppressWarnings("unused") final Money totalCumulativeInterest,
            @SuppressWarnings("unused") final Money totalInterestDueForLoan, final Money cumulatingInterestPaymentDueToGrace,
            final Money outstandingBalance, final LoanApplicationTerms loanApplicationTerms, final int periodNumber, final MathContext mc,
            final TreeMap<LocalDate, Money> principalVariation, final Map<LocalDate, Money> compoundingMap, final LocalDate periodStartDate,
            final LocalDate periodEndDate, final Collection<LoanTermVariationsData> termVariations) {

        LocalDate interestStartDate = periodStartDate;
        Money interestForThisInstallment = totalCumulativePrincipal.zero();
        Money compoundedInterest = totalCumulativePrincipal.zero();
        Money balanceForInterestCalculation = outstandingBalance;
        Money cumulatingInterestDueToGrace = cumulatingInterestPaymentDueToGrace;
        Map<LocalDate, BigDecimal> interestRates = new HashMap<>(termVariations.size());

        for (LoanTermVariationsData loanTermVariation : termVariations) {
            if (loanTermVariation.getTermVariationType().isInterestRateVariation()
                    && loanTermVariation.isApplicable(periodStartDate, periodEndDate)) {
                LocalDate fromDate = loanTermVariation.getTermVariationApplicableFrom();
                if (fromDate == null) {
                    fromDate = periodStartDate;
                }
                interestRates.put(fromDate, loanTermVariation.getDecimalValue());
                if (!principalVariation.containsKey(fromDate)) {
                    principalVariation.put(fromDate, balanceForInterestCalculation.zero());
                }
            }
        }

        if (principalVariation != null) {

            for (Map.Entry<LocalDate, Money> principal : principalVariation.entrySet()) {

                if (!DateUtils.isAfter(principal.getKey(), periodEndDate)) {
                    int interestForDays = DateUtils.getExactDifferenceInDays(interestStartDate, principal.getKey());
                    if (interestForDays > 0) {
                        final PrincipalInterest result = loanApplicationTerms.calculateTotalInterestForPeriod(calculator,
                                interestCalculationGraceOnRepaymentPeriodFraction, periodNumber, mc, cumulatingInterestDueToGrace,
                                balanceForInterestCalculation, interestStartDate, principal.getKey());
                        interestForThisInstallment = interestForThisInstallment.plus(result.interest());
                        cumulatingInterestDueToGrace = result.interestPaymentDueToGrace();
                        interestStartDate = principal.getKey();

                    }
                    Money compoundFee = totalCumulativePrincipal.zero();
                    if (compoundingMap.containsKey(principal.getKey())) {
                        Money interestToBeCompounded = totalCumulativePrincipal.zero();
                        // for interest compounding
                        if (loanApplicationTerms.getInterestRecalculationCompoundingMethod().isInterestCompoundingEnabled()) {
                            interestToBeCompounded = interestForThisInstallment.minus(compoundedInterest);
                            balanceForInterestCalculation = balanceForInterestCalculation.plus(interestToBeCompounded);
                            compoundedInterest = interestForThisInstallment;
                        }
                        // fee compounding will be done after calculation
                        compoundFee = compoundingMap.get(principal.getKey());
                        compoundingMap.put(principal.getKey(), interestToBeCompounded.plus(compoundFee));
                    }
                    if (!loanApplicationTerms.isPrincipalCompoundingDisabledForOverdueLoans()) {
                        balanceForInterestCalculation = balanceForInterestCalculation.plus(principal.getValue());
                    }
                    balanceForInterestCalculation = balanceForInterestCalculation.plus(compoundFee);

                    if (interestRates.containsKey(principal.getKey())) {
                        loanApplicationTerms.updateAnnualNominalInterestRate(interestRates.get(principal.getKey()));
                    }
                }
            }
        }

        final PrincipalInterest result = loanApplicationTerms.calculateTotalInterestForPeriod(calculator,
                interestCalculationGraceOnRepaymentPeriodFraction, periodNumber, mc, cumulatingInterestDueToGrace,
                balanceForInterestCalculation, interestStartDate, periodEndDate);

        interestForThisInstallment = interestForThisInstallment.plus(result.interest());
        cumulatingInterestDueToGrace = result.interestPaymentDueToGrace();

        if (loanApplicationTerms.isInterestToBeRecoveredFirstWhenGreaterThanEMIEnabled()
                && loanApplicationTerms.isInterestTobeApproppriated()) {
            interestForThisInstallment = interestForThisInstallment.add(loanApplicationTerms.getInterestTobeApproppriated());
            loanApplicationTerms.setInterestTobeApproppriated(interestForThisInstallment.zero());
        }

        Money interestForPeriod = interestForThisInstallment;
        if (interestForPeriod.isGreaterThanZero()) {
            interestForPeriod = interestForPeriod.minus(cumulatingInterestPaymentDueToGrace);
        } else {
            interestForPeriod = cumulatingInterestDueToGrace.minus(cumulatingInterestPaymentDueToGrace);
        }

        Money principalForThisInstallment = loanApplicationTerms.calculateTotalPrincipalForPeriod(calculator, outstandingBalance,
                periodNumber, mc, interestForPeriod);
        if (loanApplicationTerms.isInterestToBeRecoveredFirstWhenGreaterThanEMIEnabled() && principalForThisInstallment.isLessThanZero()
                && !loanApplicationTerms.isLastRepaymentPeriod(periodNumber)) {
            loanApplicationTerms.setInterestTobeApproppriated(principalForThisInstallment.abs());
            interestForThisInstallment = interestForThisInstallment.minus(loanApplicationTerms.getInterestTobeApproppriated());
            principalForThisInstallment = principalForThisInstallment.zero();
        }

        // update cumulative fields for principal & interest
        final Money interestBroughtFowardDueToGrace = cumulatingInterestDueToGrace;
        final Money totalCumulativePrincipalToDate = totalCumulativePrincipal.plus(principalForThisInstallment);

        // adjust if needed
        principalForThisInstallment = loanApplicationTerms.adjustPrincipalIfLastRepaymentPeriod(principalForThisInstallment,
                totalCumulativePrincipalToDate, periodNumber);

        // Handle cases where interest for first payment exceeds fixed EMI and
        // - first period is extended and includes extra interest, and/or
        // - remaining principal causes final payment to exceed EMI as well.
        // Fix principal to the value it would be at if the first period were the normal length.
        // Then apply a flat interest rate for the extra days:
        // Adjusted first payment =
        //   normal first period principal +
        //   normal first period interest +
        //   ((normal first period interest / normal first period days) * extra days)
        BigDecimal fixedEmiAmount = loanApplicationTerms.getFixedEmiAmount();
        if (periodNumber == 1 && fixedEmiAmount != null) {
            PrincipalInterest idealPrincipalInterest = getIdealPrincipalInterest(loanApplicationTerms, calculator, mc, outstandingBalance);
            Money normalEmi = loanApplicationTerms.pmtForInstallment(calculator, outstandingBalance, 1, mc);
            Money idealInterest = idealPrincipalInterest.interest();
            Money idealPrincipal = normalEmi.minus(idealInterest);

            if (idealPrincipal.isGreaterThanZero()) {
                principalForThisInstallment = idealPrincipal;
            }

            LocalDate alignedFirstPeriodStart = getAlignedFirstPeriodStart(loanApplicationTerms);
            LocalDate interestChargedFromDate = loanApplicationTerms.getInterestChargedFromDate();
            if (interestChargedFromDate != null) {
                // Add 1 to include the interest charged from date in the extra days
                int extraDays = DateUtils.getExactDifferenceInDays(interestChargedFromDate, alignedFirstPeriodStart) + 1;
                if (extraDays > 0) {
                    PrincipalInterest alignedPrincipalInterest = getAlignedPrincipalInterest(loanApplicationTerms, calculator, mc, outstandingBalance);
                    Money alignedInterest = alignedPrincipalInterest.interest();
                    BigDecimal dailyInterestRatePercentage = loanApplicationTerms.getDailyNominalInterestRate(mc.getRoundingMode());
                    BigDecimal dailyInterestRate = dailyInterestRatePercentage.divide(BigDecimal.valueOf(100.0d));
                    Money interestPerDay = loanApplicationTerms.getPrincipal().multipliedBy(dailyInterestRate);
                    Money extraInterest = interestPerDay.multipliedBy(extraDays);
                    interestForThisInstallment = alignedInterest.plus(extraInterest);
                }
            }
        }

        PrincipalInterest principalInterest = new PrincipalInterest(principalForThisInstallment, interestForThisInstallment,
                interestBroughtFowardDueToGrace);
        principalInterest.setRescheduleInterestPortion(loanApplicationTerms.getInterestTobeApproppriated());
        return principalInterest;
    }

    /**
     * Get the aligned first period start date.
     *
     * Aligned in this context means that the disbursement date is exactly one
     */
    private LocalDate getAlignedFirstPeriodStart(final LoanApplicationTerms loanApplicationTerms) {
        LocalDate alignedFirstPeriodEnd = loanApplicationTerms.getRepaymentsStartingFromLocalDate();
        LocalDate alignedFirstPeriodStart = switch (loanApplicationTerms.getRepaymentPeriodFrequencyType()) {
            case DAYS -> alignedFirstPeriodEnd.minusDays(loanApplicationTerms.getRepaymentEvery());
            case WEEKS -> alignedFirstPeriodEnd.minusWeeks(loanApplicationTerms.getRepaymentEvery());
            case MONTHS -> alignedFirstPeriodEnd.minusMonths(loanApplicationTerms.getRepaymentEvery());
            case YEARS -> alignedFirstPeriodEnd.minusYears(loanApplicationTerms.getRepaymentEvery());
            default -> alignedFirstPeriodEnd.minusMonths(loanApplicationTerms.getRepaymentEvery());
        };
        return alignedFirstPeriodStart;
    }

    /**
     * Calculate the aligned principal and interest for the first payment period.
     *
     * Aligned in this context means that the disbursement date is exactly one
     * period prior to the first payment date.
     */
    private PrincipalInterest getAlignedPrincipalInterest(final LoanApplicationTerms loanApplicationTerms,
            final PaymentPeriodsInOneYearCalculator calculator, final MathContext mc, final Money outstandingBalance) {
        LocalDate alignedFirstPeriodEnd = loanApplicationTerms.getRepaymentsStartingFromLocalDate();
        LocalDate alignedFirstPeriodStart = getAlignedFirstPeriodStart(loanApplicationTerms);

        PrincipalInterest alignedPrincipalInterest = loanApplicationTerms.calculateTotalInterestForPeriod(calculator, BigDecimal.ZERO, 1, mc,
                Money.zero(loanApplicationTerms.getCurrency()), outstandingBalance, alignedFirstPeriodStart, alignedFirstPeriodEnd);
        return alignedPrincipalInterest;
    }

    /**
     * Calculate the ideal principal and interest for the first payment period.
     *
     * Ideal in this context means that the first payment period is exactly one period away from the disbursement date
     * (no extra interest accrual).
     */
    private PrincipalInterest getIdealPrincipalInterest(final LoanApplicationTerms loanApplicationTerms,
            final PaymentPeriodsInOneYearCalculator calculator, final MathContext mc, final Money outstandingBalance) {
        LocalDate idealFirstPeriodStart = loanApplicationTerms.getExpectedDisbursementDate();
        LocalDate idealFirstPeriodEnd = switch (loanApplicationTerms.getRepaymentPeriodFrequencyType()) {
            case DAYS -> idealFirstPeriodStart.plusDays(loanApplicationTerms.getRepaymentEvery());
            case WEEKS -> idealFirstPeriodStart.plusWeeks(loanApplicationTerms.getRepaymentEvery());
            case MONTHS -> idealFirstPeriodStart.plusMonths(loanApplicationTerms.getRepaymentEvery());
            case YEARS -> idealFirstPeriodStart.plusYears(loanApplicationTerms.getRepaymentEvery());
            default -> idealFirstPeriodStart.plusMonths(loanApplicationTerms.getRepaymentEvery());
        };

        PrincipalInterest idealPrincipalInterest = loanApplicationTerms.calculateTotalInterestForPeriod(calculator, BigDecimal.ZERO, 1, mc,
                Money.zero(loanApplicationTerms.getCurrency()), outstandingBalance, idealFirstPeriodStart, idealFirstPeriodEnd);
        return idealPrincipalInterest;
    }
}
