# Durable invoice investigations and coordinator review

`InvoiceTeamRunner` executes the original webhook and reconciliation
investigations, seals both outcomes, then runs the
[invoice coordinator review](invoice-team-review.md). All stages use the same
captured [invoice bundle](invoice-comparison-inputs.md), shared runtime,
read/call limits and original deadline. It retains a recoverable workflow prefix;
it does not dispatch fresh follow-ups, reconcile answers, finalize the whole
trial or export a completed `CoordinationTrial`.

The separate [complete team adapter](invoice-team-comparison.md) registers all
stages up front and uses this runner for its original/review prefix. A historical
prefix-only trial cannot be upgraded with additional participants or a new deadline.

## Roles and shared stage limits

Registration binds the exact input identity, definition, coordinated-arm slot,
compiled policy and runtime specification before work. The fixed participants
are `invoice-reconciliation`, `invoice-webhook` and `invoice-coordinator`.
Each has one host-declared invoice protocol. A model cannot choose another role,
protocol, model, permission or deadline. The read-only invoice factory stays
closed to model access.

Original workers run concurrently in fair rounds with isolated contexts. Each
can read the same evidence inventory and sees only its own read history and the
existing role instructions. Neither receives its sibling's generated claims or
pages. Each active worker gets one opportunity per round. Round parity selects
a role when only one remains; a surviving worker can use all remaining original
opportunities. Every submitted task is joined before releasing ownership,
including after a local task error.

The unchanged cumulative allocation at a 60-call ceiling is:

| Stage | Initial opportunities | Cumulative ceiling |
| --- | ---: | ---: |
| Original investigations | 24 | 24 |
| Coordinator review | 6 | 30 |
| Future follow-ups | 24 | 54 |
| Future reconciliation | 6 | 60 |

Unused opportunities move forward. Two immediate original abstentions leave up
to 28 calls for review, while 24 original reservations leave six. Uncertain or
known-not-dispatched reservations consume an opportunity. A context rejected
before reservation does not. This prefix cannot spend the final 30 opportunities
as another review pool. Later stages belong to the separately registered complete
adapter, not this prefix-only entry point.

The two original metadata reads and every selected catalog/text read count toward
the shared 100-read ceiling. The coordinator uses its worker/claim inventory and
counted catalog/text reads; it receives no free copy of worker page contents.
The whole trial has one 20-minute deadline, at most two concurrent model calls,
64,000 UTF-8 input bytes and 2,048 output tokens per call. Review and restart
cannot renew those limits. Context retains every read's metadata and the latest
six page contents, trimmed without an extra summarization call.

## Original barrier and advisory review

Both role outcomes are retained before review begins. Completed dossiers must
pass the existing strict decision, citation and role-access guards. Completed
abstentions stay distinct from failed, cancelled and uncertain workers; missing
answers remain `NOT_PROVIDED`. Exhausted work retains its read history and stop
reason without inventing a finish decision.

The barrier binds both original histories, allocation, actual reservations and
registration identity. The coordinator receives every original summary and
finding under stable references. Statements remain advice, not execution facts
or accepted explanations. Selected questions are inert data at depth one;
possible conflicts remain `UNRESOLVED`. No follow-up dispatch is granted here.

The report distinguishes `REVIEW_RECORDED` from `REVIEW_STOPPED`, retaining
original histories, the coordinator's disposition, selected or missing plan,
stage reservation counts and frozen runtime trace. `wholeTrialStatus` remains
`PENDING`, with follow-up, reconciliation and comparison export explicitly
pending. A successful review may coexist with failed or unknown original work.
It does not establish a completed team investigation, semantic acceptance,
migration approval or coordination benefit.

## Run and inspect

After the README preparation/build commands, use an existing bundle and the
identity independently retained by its trusted capture caller:

```powershell
./scripts/Run-InvoiceTeamReview.ps1 -Action policy
./scripts/Run-InvoiceTeamReview.ps1 `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

The default `check` creates one prefix check using `gpt-5.6-terra` and the common
limits. Other arm/rubric identities are explicitly unrun/unreviewed placeholders.
This is not a frozen paired campaign. Another check creates a new trial; it must
not replace a less favorable outcome. The script uses the existing process
`OPENAI_API_KEY` or ignored `.env.local` without printing it. It neither recaptures
source evidence nor starts Docker.

For an explicitly supplied plan binding this prefix policy:

```powershell
$trial = ./scripts/Run-InvoiceTeamReview.ps1 -Action register `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -PlanPath plan.json -WorkloadId invoice-joint -Repetition 1
./scripts/Run-InvoiceTeamReview.ps1 -Action run -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceTeamReview.ps1 -Action read -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

Policy, registration and read-only inspection do not load credentials. The Java
entry point also needs no credential or new participant operation to inspect or
resume a completed prefix. Console output is status/usage/path metadata, not
captured contents or model claims. A zero exit alone is not semantic acceptance.

## Recovery, integrity and historical evidence

Ignored `.workbench/invoice-team-runs/trial-UUID/` holds registration, rounds,
metadata, requests, events/receipts, the original barrier, review, frozen trace
and result. The shared runtime stays under `invoice-comparison-trials/`. Keep
both stores and the input bundle on a trusted filesystem; records may contain
sensitive supplied source or claims.

One controller lease owns the prefix. Stable operations are sealed against their
registration, round/review turn, request, event and worker metadata. Restart
rebuilds the same contexts. Selected pages and metadata are checked against the
runtime's exact participant, operation, bundle and page request; that runtime
checks their source origin. Changed requests, foreign pages, modified barriers,
incomplete publication or unexpected files fail closed. Unmapped reservations
remain uncertain and are not redispatched. Completed responses can be reused
without another request. `ACTIVE` means resume the same trial.

The frozen trace must account for every prefix reservation, including uncertain
slots. Extra model reservations cannot be hidden in stage counts. The runner
does not finish the shared trial. Later append-only operations or revocation
leave the sealed prefix readable; existing trace-bound artifacts cannot change.
A missing result receipt can be restored only if the exact result still derives
from the original artifacts and trace. A missing stage-trace receipt on an
already published result remains an integrity failure.

These are trusted-host controls, not signed attestation, tenant isolation or
distributed persistence. Publication is create-only and bounded to 1 MiB per
record. The historical two-case runner, campaign and routing result stay separate.

## Validation and remaining work

Offline tests cover actual call overlap, context isolation, the original barrier,
shared/odd/carry budgets, exhausted reads, unreserved contexts, uncertain outcomes,
interruption recovery, sibling draining, exclusive ownership, occupied lanes,
expiry/revocation, unsupported actions/citations, changed state, retained history
and fresh-process credential-free inspection. They use synthetic local evidence
and fake brokers. Full Java verification remains the README command.

Fresh follow-up execution, reconciliation and complete comparison export follow
separately. Their final compiled policy must be fixed before a paired campaign.
Joint semantic review, measured benefit and Milestone 4 acceptance remain open.

A future complete adapter must register its full policy before any stage starts.
This historical prefix policy cannot be expanded into new participant permissions
or given a renewed deadline after registration.

## Recorded prefix check — 2026-10-22

One `gpt-5.6-terra` check reused the original invoice bundle with identity
`f33c31738c753c6af710f7b0797f4bef3a3e0911c74eb211f8068759adca7295`.
Trial `trial-d0760cba-e5d3-46d1-8b68-53058e42df19` recorded its review prefix in
46,896 ms. It reserved, registered and completed 30 model calls: 24 original
opportunities and six review opportunities, with 322,120 input tokens, 3,756
output tokens, 30 evidence reads, zero new consumer executions and no unresolved
usage slots. The full team trial remained `PENDING` and quality review remained
`NOT_PERFORMED`.

The reconciliation worker returned an abstention after 11 reads, retaining its
summary and four findings. The webhook worker exhausted its original stage after
12 reads and supplied no completed dossier. Review retained those distinct
outcomes and all five available original claims. It read five further pages and
selected zero questions and zero possible conflicts. A recorded review is not a
successful whole-team answer or an accepted explanation.

Fresh-JVM inspection and completed-prefix resume without a process API key left
all 778 non-lock files unchanged across the original input bundle and new stores,
including the 446 original input files. No second live check was substituted.
Result SHA-256:
`4b4ab4b19fb3d402472f75062a6254b0c7e6e6069915cca5ffecefdff37f94c9`.
