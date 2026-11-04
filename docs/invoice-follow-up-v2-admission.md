# Invoice follow-up v2 runtime and broker admission

Step 4e5l adds a separate trusted-host admission path for original role workers,
coordinator review and the two [fresh follow-up contracts](invoice-follow-up-v2-contract.md).
It registers scoped model/read permissions and retains their accounting and
recovery. It does not execute a selected follow-up workflow or reconcile answers.

## Host-selected policy and isolation

`CoordinationRuntime.forInvoiceFollowUpV2` opens one immutable invoice bundle in
`invoice-team-follow-up-v2-trials`. `invoiceFollowUpV2Policy` binds:

| Reference | Binding |
| --- | --- |
| Adapter | Current runtime build fingerprint |
| Prompt | Canonical map of both original v2 role contracts, coordinator v2 contract and both fresh v2 role contracts |
| Tools | `invoice-describe-catalog-prefix-300-40-32768-text-4000-utf16-team-follow-up-v2` |
| Context | Canonical map of worker, coordinator and fresh contexts, fresh source-selection policy and shared team-budget policy |
| Selection label | `invoice-team-follow-up-v2-advisory-contract`; a policy identity, not a completed team result |
| Participant scope | Exactly the invoice case and one explicitly selected protocol per participant |

The five allowed protocols are `invoice-webhook-worker-v2`, `invoice-reconciliation-worker-v2`,
`invoice-team-review-v2`, `invoice-fresh-webhook-v2` and
`invoice-fresh-reconciliation-v2`. Joint-investigator, historical, final
reconciliation, empty and mixed protocol grants are rejected. Participant IDs and
membership are trusted host declarations; this generic boundary does not infer
roles from names, require a complete team or validate a selected question.

The earlier [review-only factory](invoice-team-v2-admission.md) retains its three
protocols. Investigation-only, navigation, read-only, v1 and legacy factories
reject fresh grants at registration and retained loading, including a grant
supplied with an older policy's labels. Copying files between namespaces cannot
upgrade either an old trial or a new one. Existing registrations, request bytes
and deadlines are not migrated or renewed.

## Strict broker routing

Each fresh protocol selects the corresponding role instructions and the exact
`invoice_follow_up_decision_v2` schema/function. The broker forces that function,
sets `parallel_tool_calls` to false, and retains the existing `store: false` and
foreground request settings. This uses the current
[OpenAI function-calling contract](https://developers.openai.com/api/docs/guides/function-calling).
The output extractor requires exactly one matching function call. Historical,
original-worker, coordinator, unknown, duplicate, absent and plain-text answers
cannot substitute for a fresh function response. Reasoning items are omitted.

Transport completion remains separate from decision acceptance. A matching
function with malformed arguments is retained as transport evidence and rejected
by the decision parser; it cannot become an accepted answer. A changed retained
request or role invalidates its broker grant before credential resolution.
Participant protocol, model, input-byte and output-token limits are also checked
by the trial before a broker reservation.

## Shared limits and retained work

The same declared trial pool covers original, review and fresh operations:
at most 60 model calls, 100 reads, two concurrent calls, 1,200,000 milliseconds,
64,000 UTF-8 input bytes and 2,048 output tokens per call. Limits can be lower.
Describe, catalogue, literal-prefix catalogue and text reads share the read pool;
unpaged input reads remain unavailable.

Exact completed calls and reads recover without dispatching or charging again.
Changing an operation's request or prefix is rejected. An interrupted registration
keeps its charged reservation without guessing the missing broker mapping.
Unknown remote outcomes remain unknown. Completed broker results survive a
controller interruption. Expiry, revocation and terminal trial state block cached
operations as well as new work.

A retained policy from another runtime build can be inspected and closed after
its identities are checked, but cannot start or resume operations under the
current build. Closure records operational facts and establishes no answer quality.

## Remaining execution boundary

The trusted caller must authenticate the actual trial, sealed original/review
artifacts and recomputed selection before supplying a fresh context. Canonical
question hashes alone do not prove runtime provenance or permission. The
[pure context/finish boundary](invoice-follow-up-v2-contract.md) checks owned
wrappers, selected anchors, structural readiness and advisory finishes; the
broker does not interpret its input string as an authenticated context.

A concrete runner must register its fixed membership, execute only selected
depth-one questions, enforce cumulative 24/6/24/6 stage allocation and the 54-call
follow-up ceiling within the shared 60, use actual reservation counts and the
unchanged deadline, and seal each fresh history without leaking sibling reads.
Generic admission enforces the whole-trial pool; it does not enforce stage order
or open the original/review barrier. The public
[prefix-only runner](invoice-team-runner-v2.md) remains limited to originals and review.
A separate [durable follow-up runner](invoice-follow-up-runner-v2.md) now integrates
the fixed original/review/fresh membership in a dedicated prefix mode and validates
actual sealed source histories before selected dispatch. Admission itself runs
no stage and does not grant access to a final reconciliation protocol.

Run the README preparation and Java verification commands. Offline controls cover
namespace isolation in both directions, exact policy/build/limit binding, scoped
requests, shared reservations, tampered records, uncertainty, restart and lifecycle
gates. A loopback HTTP test sends both typed fresh contexts through the actual
broker with a synthetic credential. Its original outcomes and selected questions
are authored fixtures, not observed original-stage execution or model quality.
Additional broker checks exercise strict wire routing and rejected responses.
No test calls the provider or dispatches a consumer.

Reconciliation, deterministic integration, common-condition
registration and measurement remain subsequent work. The original model,
ceilings, baselines, six review criteria and historical failures/unknowns remain
unchanged. [Milestone 4 acceptance](milestone-4-status.md) remains open.
