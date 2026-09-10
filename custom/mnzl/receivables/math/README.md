# Egyptian receivables mathematics

This Java 21 module implements `EG_RECEIVABLES_ACT360_DAILY_V1` without Spring,
network, database or borrower dependencies. Native services call these functions;
other services consume native results rather than duplicating the calculations.

- `ReceivablesMath` prices individual accounts, solves gross/net yields, measures
  outstanding legs, derives posted income from target differences, and allocates
  rounded bases by largest remainder. `explain` returns conventions, input legs,
  day counts, solved rates/residuals and measured contributions.
- `ReceivableEvents` computes reset segments and immutable adjustment lots,
  full/partial settlement, cashless substitution, modification losses and the
  independent non-posting developer memo recurrence.
- `CreditAndFunding` measures forecast cash shortfalls, Stage 3 time passage,
  separate simple Actual/360 funding intervals and disclosed annualized margin.

All analytical amounts are **major EGP BigDecimal values** at 60 significant
digits. Monetary fields ending in `Minor` use `BigInteger` piastres. Convert wire
analytical minor-unit decimals by dividing/multiplying by 100; never use doubles.
Rates are annual nominal decimals and dates are `LocalDate`. Cashflow IDs are
stable measurement keys, not borrower identity.

A segment stores immutable contractual cashflows, rounded opening bases and full
precision solved yields. `position` accepts remaining face after committed
financial events; missing IDs retain full face. At the start-of-day boundary due
legs mature before events and stop acquisition accretion. A due payment reduces
remaining face; reversal restores it. Reducing future face requires authorized
settlement, not ordinary collection. `income` applies only across intervals with
no reset/modification/settlement; split at those events and supply exact receipts.
A month close measures the first day of the next month before that day's events.

`reset` returns the future segment separately from unchanged overdue face. The
caller keeps that overdue ledger and existing adjustment lots; it must not drop
those when installing the new segment. Lots stop unwinding at their due date.
Substitution carries existing adjustment allocations unchanged, releases the old
allowance, and requires a separately approved replacement forecast. Modification
retains the segment EIR and requires preserved legal schedule versions.

The caller owns currency/tenant/version validation, reconciled source cutoffs,
authorization, scenario/forecast evidence and freshness, event sequencing,
posting, netting approval and legal derecognition. No method performs cash or
journal side effects. `settlePortions` returns a `PartialSettlement` containing selected and retained
allocations. Persist its `remainingMeasurement(segment)` state. Use
`position(state, date)` and `reset(state, date, rate)` thereafter so another event
on that boundary consumes the exact retained targets. Later boundaries evaluate
unchanged analytical yields and recognize signed rounding in target differences;
final maturity clears discount/fee exactly. Separately release the selected allowance.
Forecast scenarios must be mutually exclusive with weights totaling exactly one;
recoveries cannot duplicate a payer/date/exposure or exceed the exposure. Stage 3
requires every positive-weight scenario to be conditioned on an observed default
(non-null default date no later than the measurement date); unconditional or
future-default forecasts are rejected without renormalizing Credit evidence.

Run focused checks with:

```sh
./gradlew :custom:mnzl:receivables:math:test :custom:mnzl:receivables:math:spotlessCheck
```

The test resource `reference-vectors.json` is byte-identical to MNZL's canonical
Flex fixture. The test loader expands fixture references before calling this
module. Independent daily recurrence checks use a different precision and no
production growth/PV functions, comparing every intermediate monetary position
and lifetime totals. Native journals and supported-database verification belong
to the native service integration suite.
