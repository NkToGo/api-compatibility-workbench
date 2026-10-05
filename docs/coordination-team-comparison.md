# Complete captured-team comparison adapter

`CoordinationTeamComparison` runs original case investigators, coordinator review,
zero to two fresh case investigators, and final reconciliation through the
[common captured-input runtime](coordination-runtime.md). All stages share one
registration, global call pool and whole-trial clock. The result is an unreviewed
`CoordinationTrial`; completion does not establish answer quality or coordination benefit.

## Commands

After normal preparation and verification, use an existing captured two-case bundle:

```powershell
./scripts/Run-CoordinationTeamComparison.ps1 -Action policy
./scripts/Run-CoordinationTeamComparison.ps1 -Action check -InputBundleId inputs-<uuid>
./scripts/Run-CoordinationTeamComparison.ps1 -Action read -TrialId trial-<uuid>
./scripts/Run-CoordinationTeamComparison.ps1 -Action run -TrialId trial-<uuid>
```

`check` creates one bounded operational check using `gpt-5.6-terra`, 60 model
reservations, a 20-minute whole-trial deadline, 64,000 UTF-8 input bytes per request
and 2,048 output tokens per response. It prints the ID before dispatch. Recover
with `run` on the same ID. A new check is a new trial, never recovery or a better-attempt selection.

`register` accepts `-InputBundleId`, `-PlanPath`, `-WorkloadId` and `-Repetition`
for a separately frozen comparison plan. Registration and read-only inspection
do not load a credential. Only `check`/`run` can reuse the existing ignored
`.env.local` when the process lacks `OPENAI_API_KEY`; the prior environment is restored.
Linked credential files are rejected. No key is printed or stored in trial artifacts.

Default state is ignored `.workbench`. Captured source, advice and question histories
can contain sensitive material; keep the full input/runtime/stage record together
on a trusted filesystem outside Git. There is no hosted multi-tenant service yet.

## Team membership and selection

Membership is fixed before work begins: two original case workers, one reviewer,
two available fresh case workers and one reconciler. The host dispatches a fresh
worker only for a valid question selected by the existing
[case-bound review rule](coordination-team-review.md). All valid questions are
selected once, sorted by case ID, at most one per case, at depth one. No recursive
delegation, dynamic permission change or execution capability is available.

Fresh workers receive their exact selected question, input identity and cited
same-case anchors as untrusted advice. Their read history starts empty; sibling
claims and page histories are absent. They use the existing single-case protocol
and must make their own captured reads before declaring a structurally supported
answer. A finish may instead explicitly abstain. A failed or uncertain worker is
not relabeled as an abstention.

Reconciliation sees all original/fresh summaries, findings and worker dispositions,
selected questions, review advice and possible conflicts. Stable references bind
each claim to its case, input, report and worker-local page citations. It can read
captured inputs and return one complete two-case dossier using
`captured-reconciliation-v1`. References to model advice remain advice references,
not execution observations or proof of correctness.

Final citations must be observed and case-local. `ANSWERED` requires summary
citations, findings, and either a cited claim from a structurally supported answered
worker or the reconciler's own complete baseline/execution reads. These are access
and lineage checks, not semantic entailment checks. Final semantic review must audit
the complete answer and earlier claims separately. Conflicts remain `UNRESOLVED`;
neither model agreement nor final wording closes them.

## Budgets and bounded context

For a 60-call trial the nominal allocation is 24 original, 6 review, 24 fresh and
6 reconciliation opportunities. Cumulative ceilings are 24, 30, 54 and 60.
Unused opportunities carry forward. For example, three calls in the first two
stages leave 51 fresh opportunities and then at least six reconciliation opportunities.
Unknown and unmapped registration slots are spent. Context rejected before a
reservation consumes no model opportunity. No stage renews the clock or grants.

Original and fresh workers use concurrent fair rounds, one decision per active
worker; a single remaining opportunity alternates by round. All calls/read actions
still pass through the common runtime's model, protocol, case, revocation, expiry,
concurrency and size guards. No new consumer execution occurs in this captured-input arm.

Every model request retains all relevant claim/outcome and own-read metadata.
The latest six own page contents are disclosed, with oldest content trimmed first
to fit the byte ceiling. Required metadata/claims are not silently summarized away;
if they cannot fit, the stage stops explicitly. Unicode paging preserves complete
characters. Runtime APIs enforce registered scopes on a trusted host; they are not
an authorization service for hostile independent callers.

## Recovery, final outputs and policy identity

The original/review prefix remains in `coordination-team-runs/<trial>` and is
sealed before new work. Fresh/reconciliation state is separately retained in
`coordination-team-comparisons/<trial>`. Requests, rounds, action events and receipts
bind exact operations to that prefix and registration. Completed responses and
reads are reused after a controller interruption; an uncertain registration is
never guessed into a retry. Incomplete publication fails closed.

Only the completed, supported reconciliation dossier supplies final case outputs.
There is no fallback to a preferred earlier worker or selection of the best attempt.
If reconciliation stops, final responses are `NOT_PROVIDED`; earlier advice remains
retained for audit. Operational failures, cancellation and unknown outcomes remain
in the trial even if reconciliation produces a dossier. Explicit abstention is a
valid response, independent of later quality assessment.

A terminal checkpoint precedes common-runtime finalization and export. The final
trace counts original, review, fresh and reconciliation reservations exactly once,
including unknown usage. Case outputs bind exact dossier bytes and have `review: null`.
The report retains all original/fresh claims, review advice, unresolved conflicts
and hashes of the prefix and stage artifacts. Completed inspection/resume requires
no credential or new admission, and preserves earlier partial-stage reports.

The full policy binds compiled implementation, prompts/schemas, tool/read scopes,
context rules, membership, allocation and final-selection policy. Older prefix
checks remain historical partial evidence; they are not full-arm campaign trials.
Freeze a new complete plan before paired measurement. No routing conclusion or
semantic acceptance follows from this adapter's operational check.

## Validation

Offline tests cover fresh concurrency and isolation, selection without unnecessary
delegation, original/fresh lineage, unresolved conflicts, cumulative budget carry,
context denial, interrupted/unknown/unmapped operations, forbidden reads/delegation,
unsupported reconciliation claims, own-page origin, expiry/revocation, tampered and
incomplete artifacts, terminal/receipt recovery and credential-free fresh-JVM replay.
Loopback transport tests bind the exact strict reconciliation function/schema and
reject the earlier review function. These tests make no external model calls.

One bounded live operational check on 2026-10-05 reused bundle
`inputs-6e4dfed9-be1a-479d-aa15-b40073041353`. Trial
`trial-c472fcdd-d2b8-42a2-bbe4-59ef85e94e1e` completed in 188,484 milliseconds,
using 55 reserved/registered/completed calls: 12 original, 2 review, 40 fresh and
1 reconciliation. Both selected questions produced fresh worker dossiers; both
final responses declared `ANSWERED`. There were 49 captured reads, zero new
consumer executions, zero unresolved usage slots, 370,978 input tokens and 8,160
output tokens. These declarations/access checks do not establish semantic quality,
cost effectiveness or superiority over another arm. No paired campaign ran.

Credential-free fresh-JVM inspection and completed resume left all 805 non-lock
artifacts across the inputs, full trial, its prefix and the prior historical prefix
unchanged. The old prefix remained partial. Full result SHA-256:
`beb8e6117dbf8cfb5c27aee7d910d802d95a9313627ab7dcea9a9fa020e1ef3c`.
