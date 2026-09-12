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
