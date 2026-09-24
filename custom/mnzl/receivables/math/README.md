# Egyptian receivables mathematics

This Java 21 module implements two Actual/360 accretion rules without Spring,
network, database or borrower dependencies. Native services call these functions;
other services consume native results rather than duplicating the calculations.

- `EG_RECEIVABLES_ACT360_DAILY_V1` (`ReceivablesMath.CALCULATION_VERSION`, the
  default): daily compounding, `growth = (1 + r/360)^actualDays`.
- `EG_RECEIVABLES_ACT360_SIMPLE_V1` (`ReceivablesMath.SIMPLE_CALCULATION_VERSION`):
  simple interest between cheques, capitalised only on cheque due dates:
  `B(d_i) = B(d_{i-1}) × (1 + r × days(d_{i-1}, d_i)/360) − C_i`.

`ReceivablesMath.SUPPORTED_VERSIONS` lists both. A `Segment` pins the rule it was
booked under (`calculationVersion`); every position, reset, substitution,
modification, lot and forecast discounting reads it from the segment, so a book
can hold accounts under either rule. Overloads without a version parameter are
the daily rule, unchanged. Snapshots and lots written before versions existed carry
no field and deserialise as daily.

## The simple rule

Pricing is shared: the gross price is the **daily** present value at the quoted
rate under both versions, and fee/net follow the same rounding. The version only
decides how the paid amount accretes afterwards, so the same cash solves to
different gross (g) and net (e) yields. `pv(version, …)` for the simple rule is
`Σ C_i / Π_{k≤i}(1 + r × Δ_k/360)` over the distinct cheque dates after the
valuation date, which is decreasing in `r`, so `solve` uses the same bisection,
bracket, residual (1e-10), width (1e-18) and rejection rules as the daily rule.
The solved yields make the walked balance exactly zero after the last cheque.

A simple position on date `t` walks one pooled balance per basis (gross with g,
net with e) from the segment start through every cheque date on or before `t`,
then accrues simply from that last cheque date (the *anchor*) to `t`. Legs due on
or before `t` mature at face into the due ledger and stop accreting; partial
payments reduce their remaining face and reversals restore it. Each future leg
carries its share of the walked balance: its face discounted through the cheque
dates back to the anchor, grown by the anchor-to-`t` accrual. On a cheque date the
shares equal the closed-form discounting exactly; between cheques the total is the
balance-forward amount, which is larger than a fresh discounting from `t` because
simple interest does not split multiplicatively. `discount(version, rate, anchor,
from, to, boundaries)` is that anchored factor, and `anchor(start, date,
boundaries)` names the anchor; forecast recoveries in `CreditAndFunding.impairment
(segment, …)` use both so a no-default forecast reproduces contractual legs at any
date. The balance may exceed the opening basis when the first cheque is far away
(the Crown pool vector); that growth is ordinary accretion, not a loss.

`Position`, `Leg`, `Income`, `Explanation` and `MeasurementState` keep their
shapes; `explain` reports `SIMPLE_AT_CHEQUES` and the recurrence as the formula.
Income between two positions is unchanged: closing basis − opening basis + cash.
Piastre rounding of targets happens at boundaries exactly as for the daily rule.
A reset under the simple rule starts a new simple segment from the rounded bases,
and its adjustment lot unwinds simply to the next cheque date.

- `ReceivablesMath` prices individual accounts, solves gross/net yields, measures
  outstanding legs, derives posted income from target differences, and allocates
  rounded bases by largest remainder. `explain` returns conventions, input legs,
  day counts, solved rates/residuals and measured contributions.
- `ReceivableEvents` computes reset segments and immutable adjustment lots,
  full/partial settlement, contractual developer buyback, cashless substitution,
  modification losses and the
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
`settleBuyback` uses actual approved consideration with zero voluntary developer
share and reports the signed consideration-minus-N result. Below-gross buyback
requires the caller to verify workout authorization before asserting the helper
flag. Existing allowance release remains separate.

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
allocations. Persist its `remainingMeasurement(segment)` state.
The state constructor verifies the complete remaining-face map, original cashflow
content and analytical contributions against its segment yields; mismatched
segments fail before any later measurement. Use
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
and lifetime totals. Vectors for the simple rule carry
`"calculationVersion": "EG_RECEIVABLES_ACT360_SIMPLE_V1"` on the vector itself
(the fixture root stays daily) and use the operations `simpleAccrual`,
`priceSimple`, `positionSimple`, `segmentSimple` and `resetSimple`; their expected
values come from an independent balance-forward recurrence at 80 digits, and
`ReferenceVectorsTest` re-derives them with a 75-digit recurrence of its own.
Native journals and supported-database verification belong to the native service
integration suite.
