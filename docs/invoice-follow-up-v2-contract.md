# Scoped invoice follow-up contract v2

Step 4e5k adds a pure boundary for fresh webhook and reconciliation follow-ups.
It binds one selected question and its exact anchors to a fresh role context and
validates an advisory finish against that participant's own captured reads.
It does not register participants, grant model access, dispatch work or advance
the [durable v2 prefix](invoice-team-runner-v2.md).

## Source selection and fixed targets

`InvoiceFollowUpV2` recomputes the [v2 review selection](invoice-team-review-v2.md)
from supplied typed original histories, coordinator identity, review decision and
coordinator-owned bootstrap/reads over one opened immutable invoice bundle.
The existing review checks still require an original target-role anchor for each
question, at most one question per role and delegation depth one. Zero questions
produce no fresh work; unknown, duplicate or unselected targets are rejected.

The original role IDs map to fixed `fresh-invoice-webhook` and
`fresh-invoice-reconciliation` participant IDs. A coordinator cannot alias either
fresh participant. This prevents coordinator text from acquiring fresh read
ownership merely because the same participant name was reused.

The boundary retains the complete recomputed selection and its canonical value
identity. Each question binds the input identity, role, fresh participant,
fresh protocol, contract, shared public task, selection and exact selected
proposal. It carries only the cited anchors: original outcomes, original claims
or coordinator-owned text. Unselected claims, complete original read histories
and sibling follow-up messages are not injected into the fresh context.

Anchors have separate `anchor-N` labels, original reference IDs, source participant,
kind, canonical source hash and unchanged value. Explicit null readiness remains
null; absent or uncertain work does not become a supplied answer. Parent `read-N`
labels and worker page IDs are lineage, not the fresh participant's text evidence.

Canonical identities bind supplied JSON values, not their original file bytes,
signatures or actual runtime grants. The caller must authenticate the real trial,
sealed prefix, review selection and source artifacts before using these values
for execution. Recomputing a valid structural selection cannot establish that
the caller-supplied claims were actually authored by a model or are correct.

## Fresh context and strict decisions

`InvoiceFollowUpDecisionV2` declares `invoice-fresh-webhook-v2` and
`invoice-fresh-reconciliation-v2`, with function
`invoice_follow_up_decision_v2`. Role-specific instructions, schema, public task,
worker/review contracts, source/context, readiness, navigation and stage-budget
policies contribute to its identity. Existing v1 and v2 contracts are unchanged.

The strict decision shape retains the existing bounded catalogue, literal prefix,
text-read and finish actions. It permits no recursive delegation, role creation,
shell, network, edits, execution or permission changes. A selected question is
untrusted data and cannot change the protocol or tools.

Fresh context reuses the [v2 owned-read validator](invoice-v2-contract.md). Its
bootstrap and pages must belong to the fixed fresh participant, input and scope,
and match the opened captured contents. Original-worker, coordinator and sibling
wrappers cannot be imported as own reads. Catalogue metadata is non-citable.

The complete context must fit at most 64,000 UTF-8 bytes. Oldest recent own page
contents are removed first. All selected question/anchor values, readiness and
own read metadata remain intact; fixed content that cannot fit causes an explicit
`CONTEXT_BUDGET_EXHAUSTED` stop. There is no summarization call or silent anchor
truncation.

## Shared stage snapshot and advisory finish

The context requires the unchanged canonical team allocation: 24 original, six
review, 24 follow-up and six reconciliation opportunities at a sixty-call ceiling.
Its declared follow-up snapshot has a cumulative ceiling of 54, with unused
earlier opportunities carried forward. Two original and one review reservation
leave 51 follow-up opportunities, shared across the selected active workers.
Later-stage reservations, noncanonical allocations, excessive ceilings and an
impossible selected active-participant count are rejected. An exhausted stage
stops before context preparation.

Scheduling describes the current shared round and explicitly promises no future
turn or free finish. These are caller-supplied counts, not observed reservations.
The concrete runtime must enforce actual call/read accounting, fair dispatch and
the existing whole-trial deadline; this pure boundary consumes or renews none.

`accept` requires a strict finish and derives readiness from fresh owned reads.
An `ANSWERED` result requires structural readiness, cited summary and nonempty
cited findings. An abstention may retain cited partial findings. Parent anchors
cannot substitute for own text access, even when their local read labels match.

The result retains the question/selection binding, decision, readiness and own
text-reference identities, with `qualityReview: NOT_PERFORMED`. It declares no
operational completion, release approval, accepted explanation or resolved
original uncertainty. Later reconciliation must preserve and assess that lineage.

## Verification and remaining work

Run the README preparation and Java verification commands. Offline controls cover
target/anchor selection, foreign and altered wrappers, explicit missing values,
identity changes, defensive copies, original uncertainty, byte trimming, shared
stage snapshots, strict decisions and both positive role-specific finish gates.
The positive readiness fixture is authored structural data, not newly observed
consumer execution or a measured model result.

The broker now routes these fresh protocols through a separate
[host admission path](invoice-follow-up-v2-admission.md). Earlier runtime factories
still reject them. The pure boundary itself creates no grant or model call;
its typed values alone cannot authorize execution. A separate
[durable runner](invoice-follow-up-runner-v2.md) now authenticates actual sealed
stages and executes selected fresh questions through this contract. Reconciliation, deterministic integration,
common-condition registration and measurement remain subsequent work. The model,
ceilings, baselines, six criteria and historical failed/unknown results remain
unchanged. [Milestone 4 acceptance](milestone-4-status.md) remains open.
