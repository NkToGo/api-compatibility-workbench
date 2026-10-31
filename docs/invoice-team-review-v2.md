# Invoice coordinator review contract v2

Step 4e5h adds a pure coordinator boundary over the two original v2 invoice-worker
outcomes. It preserves their claims and incomplete work, builds a bounded review
context from owned captured reads and validates inert follow-up questions and
possible conflicts. It performs no filesystem operation, model call, permission
registration or follow-up dispatch. The [v1 review](invoice-team-review.md) and
all existing investigation factories retain their original behavior.

## Original histories and authority

`InvoiceTeamReviewV2.WorkerResult` declares the fixed worker, captured input,
role protocol, exact [worker contract](invoice-v2-contract.md), public-task identity,
operational status and stop reason. Its optional finish/bootstrap and ordered read
wrappers are defensively copied. Retained records require the complete shape,
including explicit nulls for absent finish/bootstrap data. Old, foreign-role,
changed-contract or missing-task declarations are rejected.

Construction requires both original roles over the opened immutable bundle.
The shared owned-read validator checks every supplied bootstrap and page against
that worker's participant, input, request and exact captured contents. Completed
workers need a parsed v2 finish admitted by the same structural gate as the
[independent v2 adapter](invoice-independent-workers-v2.md). Stopped workers retain
their failed/cancelled/uncertain disposition and available readiness, or explicit
unavailable readiness if no bootstrap exists; no dossier is invented for them.

The caller must authenticate histories against the actual trial, policy, runtime
records and sealed original-stage barrier. These in-memory declarations cannot
prove a model used a particular prompt, authenticate a grant, or establish that
a read was charged in that trial. Canonical value hashes bind supplied worker
histories and selected references, not the original files' serialization or
signed provenance. Future execution must retain those independent source bindings.

Every original summary and finding remains a separate `claim-N`, linked to its
`worker-N` outcome and report identity. Original worker page IDs are lineage only,
not coordinator reads. Raw worker pages are not injected into the coordinator's
context. Per-worker readiness appears once in its outcome instead of being repeated
inside every claim. Missing outputs, abstention and uncertain work remain visible;
none is promoted into semantic acceptance.

## Context and cumulative budget disclosure

The coordinator has a separate participant identity and owned describe/read wrappers.
It receives the unchanged revised public task, explicitly historical capture
metadata, its own structural readiness, every read's metadata, up to six recent
page contents, both original outcomes and all original claims.

The context derives its remaining review-stage allowance through the existing
`CoordinationTeamBudget` arithmetic. The ceiling remains at most sixty calls, with
the unchanged 24/6/24/6 allocation at that ceiling and unused calls carried forward.
Reservations in later stages, excessive cumulative counts and larger ceilings
are rejected. Scheduling describes this stage's remaining shared allowance and
last decision, not another trial pool or a promised future call. A finish consumes
an opportunity; this pure component does not reserve or dispatch it.

The complete canonical UTF-8 context must fit the supplied ceiling, at most 64,000
bytes. Oldest recent page contents are removed first; claims, outcomes, readiness
and read metadata are never silently dropped or replaced with model summaries.
If fixed content cannot fit, preparation stops explicitly. An exhausted review
stage stops before context preparation. Coordinator readiness discloses own
captured access; it is not a requirement to repeat every worker read before
proposing an anchored question.

## Decisions and inert selection

`InvoiceTeamDecisionV2` defines protocol `invoice-team-review-v2` and prospective
function `invoice_team_review_decision_v2`. Its strict object schema permits flat
catalogue, literal prefix catalogue, text-page and finish actions. The parser
retains existing bounded review/question/conflict shapes, duplicate rejection and
closed fields. The contract identity binds the schema, instructions, public task,
worker contracts, context, selection, navigation, readiness and budget policies.

A finished review must cite original worker/claim references or coordinator-owned
text pages. Flat/prefix catalogue IDs and worker-only page IDs are not direct
evidence references. Each of zero to two questions targets one fixed role at most
once and needs an original outcome/claim anchor from that role. Other-role or own
text references can supplement that anchor. Questions are sorted by worker and
retain depth one; they grant no new tools, permissions, calls or recursive work.

Each of zero to two possible conflicts links distinct original claims. Same-role
and cross-role scope remain unestablished; every conflict remains `UNRESOLVED`.
Selection neither chooses a winning claim nor resolves it by model agreement.
The result retains all original claims/outcomes, the authored decision, canonical
reference identities and `qualityReview: NOT_PERFORMED`.

## Validation and remaining integration

Run the README preparation and Java verification commands. Offline tests use one
captured synthetic fixture and exercise complete/missing/uncertain outcomes,
explicit role/task/contract identities, owned-wrapper integrity, strict parsing,
catalogue citation rejection, target anchors, unresolved conflicts, defensive
copies, full claim retention, byte trimming and cumulative stage limits.

There is no standalone command or new model grant. The broker and existing invoice
investigation policy factories still reject the coordinator v2 protocol. Dedicated runtime
admission, durable team stages, follow-up/reconciliation integration, common
condition registration and measurement remain separate work. This contract
establishes no live-model quality, coordination benefit or Milestone 4 acceptance.
