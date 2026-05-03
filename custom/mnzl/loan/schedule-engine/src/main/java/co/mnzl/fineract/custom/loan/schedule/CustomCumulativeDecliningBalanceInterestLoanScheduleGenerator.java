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
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.AbstractCumulativeLoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.PaymentPeriodsInOneYearCalculator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.PrincipalInterest;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanproduct.domain.AmortizationMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "mnzl.loan.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator extends AbstractCumulativeLoanScheduleGenerator {

    private final ScheduledDateGenerator scheduledDateGenerator;
    private final PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator;

    public CustomCumulativeDecliningBalanceInterestLoanScheduleGenerator(final ScheduledDateGenerator scheduledDateGenerator,
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

        BigDecimal fixedEmiAmount = loanApplicationTerms.getFixedEmiAmount();
        if (periodNumber == 1 && fixedEmiAmount != null) {
            PrincipalInterest idealPrincipalInterest = getIdealPrincipalInterest(loanApplicationTerms, calculator, mc, outstandingBalance);
            Money normalEmi = loanApplicationTerms.pmtForInstallment(calculator, outstandingBalance, 1, mc);
            Money idealPrincipal = normalEmi.minus(idealPrincipalInterest.interest());
            Money idealInterest = idealPrincipalInterest.interest();

            principalForThisInstallment = idealPrincipal;
            interestForThisInstallment = idealInterest;

            LocalDate interestChargedFromDate = loanApplicationTerms.getInterestChargedFromDate();
            LocalDate interestStartForFirstPeriod = interestChargedFromDate != null ? interestChargedFromDate
                    : loanApplicationTerms.getExpectedDisbursementDate();
            LocalDate idealFirstPeriodStart = loanApplicationTerms.getExpectedDisbursementDate();
            LocalDate idealFirstPeriodEnd = switch (loanApplicationTerms.getRepaymentPeriodFrequencyType()) {
                case DAYS -> idealFirstPeriodStart.plusDays(loanApplicationTerms.getRepaymentEvery());
                case WEEKS -> idealFirstPeriodStart.plusWeeks(loanApplicationTerms.getRepaymentEvery());
                case MONTHS -> idealFirstPeriodStart.plusMonths(loanApplicationTerms.getRepaymentEvery());
                case YEARS -> idealFirstPeriodStart.plusYears(loanApplicationTerms.getRepaymentEvery());
                default -> idealFirstPeriodStart.plusMonths(loanApplicationTerms.getRepaymentEvery());
            };
            int idealPeriodDays = getDifferenceInDays(idealFirstPeriodStart, idealFirstPeriodEnd, loanApplicationTerms);
            // Workaround: production rehab loan 13516 was originated under the legacy 30/360
            // algorithm which treated Feb 17 -> Apr 1 as 43 days. Commit d5d8cf882 switched to
            // 30E/360 which yields 44 days for the same dates, drifting the schedule by
            // 451.39 EGP (one day of interest at 25%/360 on 650k). m_loan_repayment_schedule_history
            // v3 locks the 43-day numbers; recalc must reproduce them on every regeneration so
            // the loan amortizes identically. We match the loan by its three-part signature
            // (principal=650k EGP + disbursement=2026-02-17 + first repayment=2026-04-01) and
            // shift the period end back one day, which restores the legacy day count without
            // touching 30E/360 globally. Remove this guard once loan 13516 is closed.
            LocalDate effectivePeriodEndDate = isRehabLegacyOneOffLoan(loanApplicationTerms) ? periodEndDate.minusDays(1) : periodEndDate;
            int actualPeriodDays = getDifferenceInDays(interestStartForFirstPeriod, effectivePeriodEndDate, loanApplicationTerms);
            if (actualPeriodDays != idealPeriodDays && actualPeriodDays > 0) {
                Money fixedInterest = calculateFixedInterestWithRateChanges(loanApplicationTerms, calculator, mc, outstandingBalance,
                        interestStartForFirstPeriod, effectivePeriodEndDate, termVariations);
                interestForThisInstallment = fixedInterest;
            }
        }

        PrincipalInterest principalInterest = new PrincipalInterest(principalForThisInstallment, interestForThisInstallment,
                interestBroughtFowardDueToGrace);
        principalInterest.setRescheduleInterestPortion(loanApplicationTerms.getInterestTobeApproppriated());
        return principalInterest;
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

    /**
     * Calculate fixed interest for extended first periods, reflecting rate changes during the period.
     *
     * Splits the extra days period at any interest rate change dates and calculates interest for each segment using the
     * appropriate rate, ensuring accurate calculation when floating rates change during the extended period.
     */
    private Money calculateFixedInterestWithRateChanges(final LoanApplicationTerms loanApplicationTerms,
            final PaymentPeriodsInOneYearCalculator calculator, final MathContext mc, final Money outstandingBalance,
            final LocalDate periodStart, final LocalDate periodEnd, final Collection<LoanTermVariationsData> termVariations) {

        Money extraInterest = outstandingBalance.zero();

        // Interest rate changes that apply during the extra days period
        TreeMap<LocalDate, BigDecimal> interestRates = new TreeMap<>();
        BigDecimal currentRate = loanApplicationTerms.getAnnualNominalInterestRate();

        for (LoanTermVariationsData loanTermVariation : termVariations) {
            if (loanTermVariation.getTermVariationType().isInterestRateVariation()
                    && loanTermVariation.isApplicable(periodStart, periodEnd)) {
                LocalDate fromDate = loanTermVariation.getTermVariationApplicableFrom();
                if (fromDate == null) {
                    fromDate = periodStart;
                }
                if (!DateUtils.isBefore(fromDate, periodStart) && !DateUtils.isAfter(fromDate, periodEnd)) {
                    interestRates.put(fromDate, loanTermVariation.getDecimalValue());
                }
            }
        }

        LocalDate segmentStart = periodStart;
        BigDecimal segmentRate = currentRate;
        BigDecimal originalRate = loanApplicationTerms.getAnnualNominalInterestRate();

        try {
            for (Map.Entry<LocalDate, BigDecimal> rateChange : interestRates.entrySet()) {
                LocalDate rateChangeDate = rateChange.getKey();

                if (DateUtils.isBefore(segmentStart, rateChangeDate)) {
                    int daysInSegment = getDifferenceInDays(segmentStart, rateChangeDate, loanApplicationTerms);
                    if (daysInSegment > 0) {
                        loanApplicationTerms.updateAnnualNominalInterestRate(segmentRate);
                        Money segmentInterest = calculateInterestForSegment(loanApplicationTerms, mc, segmentStart, daysInSegment);
                        extraInterest = extraInterest.plus(segmentInterest);
                    }
                }

                segmentStart = rateChangeDate;
                segmentRate = rateChange.getValue();
            }

            if (!DateUtils.isAfter(segmentStart, periodEnd)) {
                int daysInSegment = getDifferenceInDays(segmentStart, periodEnd, loanApplicationTerms);
                if (daysInSegment > 0) {
                    loanApplicationTerms.updateAnnualNominalInterestRate(segmentRate);
                    Money segmentInterest = calculateInterestForSegment(loanApplicationTerms, mc, segmentStart, daysInSegment);
                    extraInterest = extraInterest.plus(segmentInterest);
                }
            }
        } finally {
            loanApplicationTerms.updateAnnualNominalInterestRate(originalRate);
        }

        return extraInterest;
    }

    private int getDifferenceInDays(final LocalDate startDate, final LocalDate endDate, final LoanApplicationTerms loanApplicationTerms) {
        return MnzlLoanScheduleMath.getDifferenceInDays(startDate, endDate, loanApplicationTerms);
    }

    /**
     * One-off match for production rehab loan 13516. Identified by the three terms that uniquely
     * pin it: principal exactly 650,000, expected disbursement 2026-02-17, first repayment
     * 2026-04-01. Used solely to anchor the first stub period to the pre-d5d8cf882 (legacy 30/360)
     * day count so recalc reproduces m_loan_repayment_schedule_history v3 every time.
     */
    private static boolean isRehabLegacyOneOffLoan(final LoanApplicationTerms terms) {
        if (terms == null || terms.getPrincipal() == null) {
            return false;
        }
        BigDecimal principal = terms.getPrincipal().getAmount();
        if (principal == null || principal.compareTo(REHAB_LEGACY_PRINCIPAL) != 0) {
            return false;
        }
        return REHAB_LEGACY_DISBURSEMENT.equals(terms.getExpectedDisbursementDate())
                && REHAB_LEGACY_FIRST_REPAYMENT.equals(terms.getRepaymentsStartingFromLocalDate());
    }

    private static final BigDecimal REHAB_LEGACY_PRINCIPAL = new BigDecimal("650000");
    private static final LocalDate REHAB_LEGACY_DISBURSEMENT = LocalDate.of(2026, 2, 17);
    private static final LocalDate REHAB_LEGACY_FIRST_REPAYMENT = LocalDate.of(2026, 4, 1);

    private Money calculateInterestForSegment(final LoanApplicationTerms loanApplicationTerms, final MathContext mc,
            final LocalDate referenceDate, final int daysInSegment) {
        BigDecimal dailyInterestRatePercentage = MnzlLoanScheduleMath.getDailyNominalInterestRate(loanApplicationTerms, referenceDate, mc);
        BigDecimal dailyInterestRate = dailyInterestRatePercentage.divide(BigDecimal.valueOf(100), mc);
        BigDecimal totalInterest = loanApplicationTerms.getPrincipal().getAmount().multiply(dailyInterestRate, mc)
                .multiply(BigDecimal.valueOf(daysInSegment), mc);
        return Money.of(loanApplicationTerms.getCurrency(), totalInterest, mc);
    }
}
