# Invoice renewal host checks

The historical [invoice subscription contract](../cases/stripe-invoice-subscription-2025-03-31/README.md)
defines `paid-renewal-v1` before candidate implementation. The package-private
`InvoiceRenewalOracle` and `InvoiceRenewalLedger` implement its normalized-result
assertion and ordered in-memory ledger boundaries. They do not parse Stripe
payloads, compile consumers, verify webhook signatures or run a new migration case.

The original contract files and their content index remain unchanged. This document
records the implementation boundary separately. A [separate fixture registration](invoice-renewal-fixtures.md)
now covers the four rollout version vectors, four association families and two
missing/contradictory association families. Complete fixture coverage,
consumer/harness/runtime identities and observed baseline/target executions remain
pending. Normalized unit inputs are distinct from that raw fixture registry.

## Registered input and consumer output

A trusted host constructs an oracle for either `invoice-webhook` or
`invoice-reconciliation`, with a registered JSON input and an independently held
expected fact. The input identity is SHA-256 of the host's compact UTF-8 Gson
serialization through `InvoiceRenewalOracle.inputIdentity`, preserving explicit
nulls, object member order and Unicode string values. It identifies that normalized
envelope, not an original provider file. Absent and explicit-null fields remain
distinct. The separately supplied evidence identity must identify
the registered evidence bundle in the future harness. Digest syntax alone does not
authenticate evidence or establish that such a bundle exists.

The oracle requires the expected input identity to match the supplied input. It
copies the input and never discloses expectations. Its two-message protocol is:

1. `{"op":"read"}` returns format version 1, `input`, `inputIdentity` and
   `evidenceIdentity` once.
2. `{"op":"return","result":...}` returns one normalized fact. The worker
   cannot supply a verdict, permission or ledger action.

The fact has exactly eight fields: `status`, `invoiceId`, `customerId`,
`subscriptionId`, `effectiveApiVersion`, `inputIdentity`, `evidenceIdentity` and
`reason`. Nullable fields must be explicit JSON nulls; transport/persistence must
preserve them, as the existing worker `responseBytes` transport does. Identifiers have a bounded
ASCII format, digests are lowercase SHA-256, and the only declared versions are
`2025-02-24.acacia` and `2025-03-31.basil`.

- `RESOLVED` requires customer, subscription and version; reason is null.
- `NON_SUBSCRIPTION` requires customer and version; subscription and reason are null.
- `UNRESOLVED` requires a reason and preserves any known customer/subscription.
  An unknown version is null, never a guessed release or a later Basil version.

Reason codes distinguish missing/invalid association, unknown version, unapproved
binding, unavailable/failed/non-active/mismatched state, unpaid invoice and an
unsupported adapter. These structural constraints do not verify paid status,
active state or an approved binding. Those business assertions require the future
independently held scenario expectations and actual consumer execution.

## Execution and assertions

Only a completed, valid protocol exchange with a returned fact has an evaluated
assertion. Exact equality with the independently supplied expected fact yields
`PASSED`; a different structurally valid fact yields `FAILED`. Differences in
invoice, customer, subscription, version, reason or identities all matter.

Missing returns, malformed/extra messages, timeout, cancellation and infrastructure
failure yield `NOT_EVALUATED`. A returned fact from a failed execution is retained,
but cannot supply an effect. Absence of a return remains null; the host never
fabricates a consumer `UNRESOLVED` answer. Invalid messages poison the exchange.

The host seals one terminal observation. Later messages cannot change it, and a
different terminal execution status is rejected. Observations retain expected and
actual facts, consumer identity, controller-supplied execution status, protocol
validity, identity matching and assertion outcome separately. Serialized reports
are copies and are not accepted as typed observations by the ledger.

Invalid signatures and missing source/fixture admission must be rejected **before**
constructing or dispatching a consumer execution. These classes do not implement
admission or signature verification; constructing an oracle is not an execution
grant. Future integration must enforce this boundary, not invent a consumer result
for rejected input.

## Ordered ledger evidence

The trusted host supplies installation identity and initial effects. Each immutable
effect is keyed by `(installationId, invoiceId)` and holds customer, subscription
and the fixed action `RECORD_PAID_RENEWAL`.

A completed, structurally valid, identity-bound resolved fact appends one effect
for a fresh key. An identical effect is `DUPLICATE`. A different value is `CONFLICT`:
no new effect is appended and the first value remains. Non-subscription/refusal,
incomplete exchanges and unbound identities have `NO_EFFECT`; none removes an
existing effect. Every accepted observation receives an ordered receipt, including
repeated deliveries and failures. Initial state and immutable snapshots are retained.

This is a **simulation of the declared application policy** for checking outputs,
not a live billing write. A wrong but evaluable, identity-bound resolved fact can
produce a wrong simulated effect: its failed assertion and actual behavior must
remain visible. The ledger does not replace it with the golden answer. Conversely,
a correct final effect does not erase an incorrect version, a false standalone
classification or a conflict in intermediate receipts. Assertion outcomes and
ledger outcomes must both be reviewed in the future joint harness.

An empty ledger with disagreeing consumers retains whichever value arrived first
and records the second as a conflict. Tests exercise both orders; this order
dependence is preserved evidence, not a resolution. Two subscriptions for one
customer remain distinct invoice/subscription effects. Different event/input
identities for the same invoice do not create additional renewal effects.

## Validation and limits

The default Java verification exercises normalized facts, all four declared version
vectors, each path alone, both arrival orders, repeated deliveries, distinct
subscriptions, preexisting/fresh conflicts, safe refusals, failed executions,
malformed protocol, stale identities and immutable history. It also checks that
one correct path or a matching final ledger cannot hide another path's failure.

These tests establish pure host behavior. A separate [fixture registration](invoice-renewal-fixtures.md)
now pins authored sources and answers for the four rollout vectors, ten
association variants, eleven refusal variants, six duplicate/conflict variants
and ten unavailable-state variants. The delivery set holds preexisting ledger
state and repeated arrival sequences; state controls retain the actual
state/binding reason and known invoice association. An authored failed lookup
is an input condition; a failed consumer execution remains separately
`NOT_EVALUATED`. Registration blocks full case coverage until the remaining
families and controls are registered. Neither
test suite establishes the complete 15-scenario inventory, valid provider payload shapes, correct SDK parsing,
consumer compatibility, campaign readiness, coordination benefit or release
approval. The original business oracle remains authoritative. The next changes
complete fixture coverage, then execute pinned Java consumers.

This boundary assumes one trusted host. It is not a durable or concurrent database,
an authenticated receipt system, tenant isolation, a complete joint comparator or
a public wire API. Future capture must bind exact source, configuration, fixtures,
expectations, harness and environment, and preserve per-consumer versions rather
than inventing a single version for a mixed rollout.
