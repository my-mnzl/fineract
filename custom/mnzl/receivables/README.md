# Native receivables authorization

Historical commands require a grant from `POST /v1/mnzl/receivables/authorizations/v2`.
The strict `nativeHistoricalAuthorizationV2` request and response contain the existing
`scope`, `authorizationId`, `scopeHash`, `approvedBy`, `effectiveFrom`, `effectiveThrough`
and `mode` fields, plus required `commandPayloadHash`.

Finalize the entire command, including its original operation and idempotency IDs and
`executionAuthorization`, before calculating the digest. `commandPayloadHash` is the
lowercase SHA-256 of the complete command serialized with JSON Canonicalization Scheme
(JCS). No command fields are omitted. The digest lives in the separate grant, so there
is no self-reference. `executionScopeHash` retains its existing opaque basis meaning.

For a new historical effect, native execution computes the command digest and checks
it against the stored grant in addition to existing permissions, revocation, dates,
period state, mapping, ownership and expected-version checks. Reusing a grant with a
changed command is rejected. Reissuing the same grant ID requires equality of every
immutable grant field; it never changes or reactivates the stored grant.

The legacy `/authorizations` issuance endpoint returns `UNSUPPORTED_VERSION`. Existing
grants without a command digest cannot authorize new effects and cannot be upgraded
from a caller assertion. The forward migration leaves these grants unbound. Exact
already-persisted operations remain readable and replayable, including after their
grant expires or is revoked; changed payloads still conflict. Saved commands, receipts
and financial dates are not rewritten.

Issuance preserves the existing policy: the authenticated user needs
`CONFIGURE_MNZL_RECEIVABLES`, and `approvedBy` must identify that user. It does **not**
enforce separation from the configured integration user or establish independent
maker/checker approval. This change does not implement continuation of stale CURRENT
commands. CURRENT command and capability shapes remain unchanged.

The native database CI matrix verifies bound execution, payload substitution rejection,
immutable issuance, disabled legacy issuance, unbound legacy rejection, durable replay
and rollback of a failure after native journal posting on MariaDB, MySQL and PostgreSQL.

## Core office accounting closures

New native effects share a dedicated office accounting lock with core GL-closure
creation and deletion. Journal posting applies the core inclusive boundary: a posting
date on or before the latest office closure returns `PERIOD_CLOSED`. The actual
posting date is checked, including historical and adjusted closing dates. Existing
custom period guards remain in force; exact persisted replay precedes the new lock.

Closure creation and deletion increment `m_office.accounting_closure_version` while
holding the office lock. The physical epoch is intentionally unmapped in the office
entity so ordinary office edits cannot overwrite it. Under PostgreSQL repeatable
read, a command whose snapshot predates a committed closure cannot lock that changed
office row: it aborts with a serialization conflict and no financial effects. A fresh
retry observes the closure and rejects the closed posting date. MariaDB and MySQL
use a current locking closure read. A command that acquires the office lock first
finishes atomically before closure creation proceeds. Comment-only closure updates
and ordinary office lookups retain their existing behavior.

The real database matrix checks closed/equal/open date boundaries, exact replay after
closure, native-first serialization, and a snapshot established before a competing
closure commits. It checks PostgreSQL SQLSTATE `40001`, unchanged financial counts
after rejection, and fresh-retry `PERIOD_CLOSED` on all three databases.

## Observed posted-period proof

`GET /period-activity-proof?postingPeriod=YYYY-MM&eventWatermark=W` requires both
parameters and the existing scoped READ authority and mapping headers. `W` must not
exceed the current scoped event watermark. The version 1 response covers **all scoped
events** posted to that period through `W`, including HEL events without custom
journal lines. Its monetary proof covers only actual journals in deterministic
`R` + first 40 event-key character transactions. Ordinary HEL loan/disbursement/fee
journals are explicitly excluded; this is not a whole-native-book report.

One read-only repeatable-read transaction validates stored event identifiers, period
and content hashes against the immutable JSON and recomputes each event hash. It
compares event lines, registry rows and independently enumerated actual GL rows in
both directions. Missing rows, unregistered extras, changed amounts/accounts/sides,
reversed custom rows and wrong posting dates fail with `JOURNAL_MISMATCH`. It examines
expected event transactions across all dates and actual-period rows from other
scoped event transactions, so a period filter cannot hide shifted journals. Normal
custom reversals append opposite unreversed lines and remain in gross counts and
debit/credit totals, even where their net is zero.

`observedGl.semantics = CURRENT_AT_REPEATABLE_READ`: the event watermark freezes
membership, **not historical GL row values**. `observedAt` describes when this read
observed the database, not a reusable database snapshot identifier. Store the returned
proof with the captured report. Re-reading can observe changed native rows and fail;
it must not be presented as historical as-of reconstruction.

All amounts, counts, watermarks and IDs are decimal strings where applicable. Account
`netMinor` is debit minus credit. Account controls are sorted lexically by original
mapping revision, semantic account, native GL ID and currency. Explicit zero rows
cover the complete current approved mapping; observed older-mapping groups retain
their original attribution without inventing a complete historical zero-account
population. Components remain verified event metadata, not a separate native GL
classification.

All hashes below use lowercase SHA-256 over JCS UTF-8:

- `selection` consists exactly of response `schemaVersion`, `tenantId`, `scope`,
  `postingPeriod`, `eventWatermark`, `readAccountMappingRevisionId`, `coverage`, and
  `excludedJournalPopulations`.
- `eventManifest.sha256` hashes `{selection, events}`, where `events` contains
  `{eventId, contentHash}` for every included event, sorted by event ID ascending.
  The count includes zero-line events. Existing `/events` pagination at the same `W`
  supplies immutable bodies; consumers filter `postingPeriod`, sort and verify this
  count and hash, detecting omitted zero-net pairs.
- `observedGl.sha256` hashes `{selection, rows}`, with actual rows sorted by numeric
  journal ID ascending. Each row contains exactly `journalId`, `eventId`,
  `transactionId`, `entryDate`, `nativeGlAccountId`, `currency`, `side`, `amountMinor`,
  boolean `reversed`, `originalMappingRevisionId`, `accountKey`, and `sourceLineId`.
  Raw observed rows are committed, not returned as a new mutable paging protocol.
- `proofHash` hashes the complete response before adding `proofHash`.

Each candidate event, registry and actual-journal SQL query fetches at most 100001
rows. More than 100000 candidates, or more than 100000 included event lines, fails
with `INVALID_DATA`; no partial/truncated proof is returned. This initial bound
applies to scoped candidates through the watermark, not just the requested month.

## Approved post-transfer receipt loss

`RECORD_POST_TRANSFER_RECEIPT_LOSS` records a new, independently verified outgoing
bank debit in the current open posting period after substitution or HEL conversion.
It requires a full-command CORRECTION grant, a signed Legal determination of no
surviving enforceable right, and separate Finance and Credit approvals. The actor
and all three approvers must be distinct. The integration is responsible for
verifying the referenced evidence before granting authority; native execution binds
its immutable identifiers and hashes and enforces the financial constraints.

This command supports only a full original receipt, entirely collected by one
unreversed COLLECT operation into the original account. It binds the original
command, bank source and event hashes and the downstream transfer hashes. The
original source can have only one loss disposition. The outgoing source must be
new and is owned atomically by this command. Its actual value date is preserved
separately from the original receipt date and current posting date.

`POST /receipt-loss-configuration` registers one immutable `receiptReturnLoss`
expense mapping extension per financier scope; GET reads it back. The extension has
its own ID and content hash and does not alter the original account mapping or its
configuration hash. Loss posting debits that expense and credits bank exactly once.
Period proofs retain the base mapping revision and expose the extension ID/hash on
loss controls. The result appends `receiptLossDisposition` lineage without changing
old receivable, successor, HEL, developer-lot or event state. This bounded command
does not recognize a recovery claim or support partial returns; cases with surviving
rights require their independently evidenced recovery path.
