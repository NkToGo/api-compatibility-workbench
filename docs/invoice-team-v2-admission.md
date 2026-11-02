# Invoice coordinator v2 runtime and broker admission

Step 4e5i adds explicit trusted-host admission for the
[v2 coordinator review contract](invoice-team-review-v2.md). It can register
scoped original workers and a coordinator under one trial. It does not run their
stages, open an original-outcome barrier, execute returned decisions, dispatch
follow-ups or establish finding quality.

## Separate host-selected policy

`CoordinationRuntime.forInvoiceTeamReviewV2` opens one immutable invoice input
bundle under `invoice-team-v2-trials`. `invoiceTeamReviewV2Policy` binds:

| Reference | Required binding |
| --- | --- |
| Adapter | Current opened runtime build fingerprint |
| Prompt | Canonical map of both scoped v2 worker contracts and the exact coordinator v2 contract |
| Tools | `invoice-describe-catalog-prefix-300-40-32768-text-4000-utf16-team-review-v2` |
| Context | SHA-256 of `InvoiceTeamReviewV2.CONTEXT` |
| Final selection label | `invoice-team-v2-advisory-contract`; a policy reference, not an implemented stage runner |
| Participant scope | Exactly the invoice case and one explicitly selected webhook v2, reconciliation v2 or coordinator-review v2 protocol |

Joint-investigator, historical, mixed and empty protocol grants are rejected.
Participant IDs are host declarations; this generic admission layer does not
infer team roles from their names or require a complete concrete team. The
concrete runner must register its fixed membership and authenticate the actual
original worker histories before preparing a review.

The existing investigation-only v2 factory keeps its three original scopes and
identities. It cannot admit this policy or coordinator protocol. Read-only,
navigation, v1 and legacy factories also reject team v2 grants during registration
and retained loading. Copying registrations between namespaces cannot promote
either an investigation-only registration or a team registration.

## Bounded operations and transport

All participants share at most sixty model reservations, one twenty-minute
deadline and one hundred captured reads, with at most two concurrent calls.
Definitions can lower these ceilings. Each call is limited to the declared model,
64,000 input bytes and 2,048 output tokens or lower declared limits. Describe,
flat/prefix catalogue and text reads share the same read pool. There are no shell,
edit, external retrieval, consumer-execution or delegation tools.

Coordinator requests use the exact v2 instructions, strict schema and forced
`invoice_team_review_decision_v2` function, with `parallel_tool_calls: false`,
`store: false` and `background: false`. Response extraction requires exactly one
call of that function. Historical review, worker, unknown, multiple or absent
functions are rejected. Existing worker and historical request bodies retain
their routing. Private reasoning items are not retained.

The runtime binds the complete approved request body to the participant's protocol,
model and durable reservation. A changed request under the same operation ID is
rejected. Changing an approved broker request invalidates its grant before
credential lookup or transport. Transport completion does not accept decision
arguments: malformed actions remain transport output and fail the separate strict
parser. Returned questions are still inert data, not dispatch instructions.

This admission boundary enforces whole-trial ceilings. Stage order, the sealed
original-outcome barrier, actual owned context construction, cumulative 24/6/24/6
allocation at sixty calls and decision execution belong to the separate concrete
team runner. The pure review component validates supplied cumulative snapshots;
the generic runtime does not reconstruct or authenticate their meaning.

## Retention and recovery

Exact completed calls and prefix reads recover without a new reservation or
dispatch. Unmapped registrations remain charged and uncertain. Unknown remote
outcomes stay unknown; they are not silently retried. A completed broker result
survives controller interruption. Read or request tampering fails retained
inspection rather than becoming original evidence.

Revocation, expiry and closure block new operations and cached call access.
Retained records from another build can be inspected and closed, but cannot
start or resume operations through the current build. Policy and record hashes
are trusted-host integrity checks, not signatures or protection against an
operator rewriting code and all expected identities.

## Verification and remaining work

Run the README preparation and Java verification commands.
`InvoiceTeamV2AdmissionTest` exercises scoped admission, namespace isolation in
both directions, exact identities, shared ceilings, altered requests/reads,
restart, interruption, uncertainty and lifecycle controls. Its real-broker test
uses a typed review context and localhost HTTP with a synthetic credential.
`ModelCallBrokerTest` checks strict coordinator routing, rejected function output,
malformed arguments, pre-credential grant rejection and reasoning omission.
These tests make no provider request.

There is no standalone team-v2 command yet. Durable original-worker/review stages,
follow-up and reconciliation stages, deterministic integration, common-condition
registration and measurement remain separate work. The model, ceilings, baselines,
six review criteria and historical negative/unknown results remain unchanged.
This admission step establishes no live-model success, answer-quality improvement,
routing promotion or [Milestone 4 acceptance](milestone-4-status.md).
