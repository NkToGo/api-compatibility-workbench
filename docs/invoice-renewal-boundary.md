# Invoice admission and incomplete-execution controls

The [frozen business oracle](../cases/stripe-invoice-subscription-2025-03-31/business-oracle.md)
requires rejected inputs to remain separate from consumer execution failures.
`InvoiceRenewalAdmission` implements a package-private host consistency gate;
`InvoiceRenewalBoundaryFixtures` registers a separate controlled corpus. Neither
dispatches a process, calls a provider or authorizes a migration.

## Before constructing an oracle

The host supplies its registered bundle/input/evidence identities, exact source
bytes, signature-check state bound to those bytes and adapter-check state/version.
These are host-owned inputs, not fields accepted from a consumer's answer. The gate
checks complete identities, normalized input/evidence binding, consumer scope,
source digest/payload/kind, explicit effective versions and required webhook type.
It never selects a version from the SDK, endpoint or account default when the
event or effective request version is missing or unsupported.

A webhook additionally needs a host-supplied verified signature state for the
same source bytes and a verified adapter for its exact event version. A known
Acacia event can therefore remain Acacia alongside Basil request/state evidence.
Reconciliation uses its explicit request version; payload claims cannot switch
the host-selected consumer role to bypass webhook checks. Protected expected facts
must match the admitted input, evidence and effective version.

The result retains all applicable issue codes. It copies its input and emits only
`admitted` and fixed `issues` in its diagnostic JSON. A blocked result rejects
oracle construction, so it has no consumer output, execution observation or ledger
receipt. It is not a consumer returning `UNRESOLVED` or a timed-out process.
An admitted result only allows a normalized control exchange: actual process
dispatch still needs the controller's separate task permission, execution policy
and complete supported case registration. The gate creates no such grant.

## Separately pinned authored conditions

The [boundary-v1 manifest](../cases/stripe-invoice-subscription-2025-03-31/fixtures/boundary-v1/bundle.json)
has compiled SHA-256
`6b125b99a1fbccd13364022bd87d4471947b94d96265f7941e03489596db85eb`.
Seven raw synthetic sources hold Basil events, a retrieval and subscription state,
plus absent/null/unknown event versions and a rejected event type. Original
contract files, shared oracle/ledger/loaders and all seven earlier corpora remain
unchanged. The flat inventory and bounded byte/JSON validation reuse the existing
host file boundary.

[Control inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/boundary-v1/controls.json)
declare **`hostReceiptAuthority=authored-control-condition`**. Their `VERIFIED`,
`REJECTED` and unchecked signature/adapter states are authored preconditions,
not cryptographically authenticated receipts or observed adapter test results.
No signing secret, real customer payload or provider-observed delivery is included.
The future runtime must replace these controlled states with trustworthy verifier
records tied to the actual source and implementation; payload or model claims
cannot establish that authority. This gate does not implement HMAC verification,
signature-age checking, secret rotation or adapter verification. A separate
[runtime signature verifier](invoice-webhook-signatures.md) now supplies actual
scope-bound HMAC receipts through `inspectWebhook`; that path ignores the authored
signature flag. It does not upgrade these stored authored conditions or establish
actual adapter verification. The separate [SDK parser worker](invoice-webhook-adapters.md)
now supplies actual adapter receipts through `inspectVerifiedWebhook`, which
ignores both authored flags and binds source, signature, scope and SDK/version.

[Independent answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/boundary-v1/expectations.json)
hold admission issues, expected normalized facts, execution assertions, actual-fact
presence, protocol validity and ledger outcomes. Worker reads disclose only their
input and identities, not these answers or authored host receipt states.

| Control group | Declared conditions | Expected boundary |
| --- | --- | --- |
| Versions and event type | Absent/null/unknown event version, missing/unknown request version, different event type | Block before oracle construction; no guessed version |
| Signature states | Rejected, unchecked, verified for different bytes | Block; no consumer observation |
| Adapter states | Absent, rejected, unchecked, wrong event version | Block; no consumer observation |
| Source and scope | Missing bytes, wrong digest/kind, spoofed consumer, forged permission claims | Retain specific issues; payload claims create no grant |
| Fixture/expectation identity | Missing tuple members, stale input/evidence, missing protected answer | Block independently from consumer behavior |
| Admitted protocol controls | Correct, missing, timed-out, cancelled, infrastructure failure, malformed/trailing returns | Preserve execution/protocol facts; incomplete exchanges are `NOT_EVALUATED` |
| Wrong or unbound facts | Incorrect known version, incorrect input identity | `FAILED` with retained actual fact; identity mismatch cannot supply effects |

The registry contains 34 controls: 24 blocked and 10 admitted under the authored
host conditions. Tests exercise each control alone and with a healthy opposite
consumer in both orders: 102 sequences, 98 ledger receipts and 72 separately
blocked positions. Correct existing markers are never erased. A successful other
path or matching final ledger cannot hide blocked input, an incomplete exchange,
an unbound fact or an incorrect version. Incorrect but evaluable facts preserve
their actual simulated effects and failed assertions.

## Remaining boundaries

These are golden protocol tests of host consistency, not executions of the new
invoice consumers or evidence of provider compatibility. `BOUNDARY_CONTROLS_ONLY`
and all previous partial corpora still reject `requireFullCaseCoverage()`. The
combined registry now [checks every required variant and control](invoice-renewal-registry.md)
and closes authored fixture registration. [Registered consumer execution](invoice-consumer-execution.md)
and separate runtime signature/adapter paths now exist. The [business coverage driver](invoice-business-coverage.md)
now runs all distinct deliveries, including registered mixed-version execution.
A separate [ordered ledger replay](invoice-ordered-coverage.md) now checks all
business arrival orders using captured actual observations. Boundary execution
now has a separate [runtime admission driver](invoice-boundary-admission-coverage.md)
for all 24 rejection controls and two healthy admissions, preserving the authored
corpus and separately identifying actual verifier conditions. A separate
[consumer execution driver](invoice-boundary-execution-coverage.md) now runs
the eight admitted fault controls and two healthy consumers with actual transport
facts. A separate [runtime boundary replay](invoice-boundary-coverage.md) now
checks all 102 placements from the captured typed decisions and observations,
preserving blocks, failed assertions, incomplete results and healthy other paths.
Protected joint full-case capture, campaign readiness,
coordination benefit and release approval remain
pending. The historical campaign and rubric are unchanged.
