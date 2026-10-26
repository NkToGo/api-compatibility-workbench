# Invoice campaign: operational measurement

The 26 October 2026 campaign executes the four existing invoice policies in
three declared paired repetitions. It measures operational outcomes and recorded
resource use on one captured synthetic invoice case. **Answer quality remains
unreviewed.** Neither a completed process nor a protocol-valid answer establishes
semantic correctness, useful coordination or migration approval.

The [machine-readable record](evaluations/invoice-campaign-2026-10-26.json)
contains all twelve slots, participant accounting, execution timestamps, output
identities and retained receipt bindings. It includes the frozen definition,
policies, source-artifact inventory and dispatch-code identities. Raw local
requests, model responses and evidence histories are not included in that export.

## Frozen conditions

- Source revision: `0ff747e0e079ba82aac7a6ac9b2f49bdcec5549b`.
- Campaign: `invoicecampaign-c86bfdd4-8bc2-4fd3-bd44-ab80761d30d6`.
- Comparison: `comparison-784b2b42-fa95-4e13-a452-33617165c363`.
- Independently retained registration receipt SHA-256:
  `4481be329dffa6c3d8ee07ab0db642ee4bc5eae3f6f2aa008fb4f7e53724a0c1`.
- Captured input SHA-256:
  `f33c31738c753c6af710f7b0797f4bef3a3e0911c74eb211f8068759adca7295`.
- Protected contract SHA-256:
  `a4455ca1fad869e676d06b359cff6885ed6dcfd12d44219b85be9e9f7448abe8`.
- Rubric: unchanged [invoice review rubric v1](evaluations/invoice-review-rubric-v1.json),
  SHA-256 `9f89e515bcea47606e76573874304d2fda2fbcbd522e2283e7b520d4b4e960ab`.

The [registration](invoice-campaign.md) freezes 622 source artifacts before
dispatch. All arms receive the same bounded input inventory; protected grading
material stays outside participant histories. The six unchanged criteria cover
versioned association, independent version boundaries, application policy gates,
joint ledger behavior, execution/causal scope and evidence/uncertainty.

Each trial has at most 60 model opportunities, 100 retained evidence reads and
one persistent twenty-minute deadline. Each model request is limited to 64,000
input bytes and 2,048 output tokens, with at most two concurrent calls. Adaptive
arms use `gpt-5.6-terra`; diagnostics use no model. The team retains its cumulative
24/6/24/6 investigation/review/follow-up/reconciliation allocation with forward
carry. These are unchanged compiled policies, not allocations tuned after seeing
this campaign's answers.

Trials execute sequentially in the frozen rotations:

1. Deterministic, single agent, independent workers, coordinated team.
2. Single agent, independent workers, coordinated team, deterministic.
3. Independent workers, coordinated team, deterministic, single agent.

Every slot has one fresh trial; earlier pilots fill none of these slots. Failed
work is retained without replacement or selection of a preferred retry. The
[controller](invoice-campaign-execution.md) seals terminal outcomes, including
failure and abstention, separately from quality review. These trials inspect
retained consumer evidence and dispatch no new consumer execution.

## Recorded outcomes

All twelve slots are sealed: five `COMPLETED`, six `FAILED` and one
`UNKNOWN_REMOTE_OUTCOME`. Five joint responses are `ANSWERED`, three `ABSTAINED`
and four `NOT_PROVIDED`. Every output retains `qualityReview: NOT_PERFORMED`.

| Slot | Repetition | Arm | Status | Case response | Model opportunities | Reads | Trial seconds |
| --- | --- | --- | --- | --- | ---: | ---: | ---: |
| 1 | 1 | DETERMINISTIC | COMPLETED | ANSWERED | 0 | 99 | 1.85 |
| 2 | 1 | SINGLE_AGENT | FAILED | NOT_PROVIDED | 25 | 25 | 76.34 |
| 3 | 1 | INDEPENDENT_WORKERS | COMPLETED | ANSWERED | 41 | 41 | 73.32 |
| 4 | 1 | COORDINATED_TEAM | FAILED | ABSTAINED | 31 | 31 | 55.32 |
| 5 | 2 | SINGLE_AGENT | FAILED | NOT_PROVIDED | 38 | 38 | 91.97 |
| 6 | 2 | INDEPENDENT_WORKERS | COMPLETED | ANSWERED | 47 | 47 | 90.33 |
| 7 | 2 | COORDINATED_TEAM | FAILED | ABSTAINED | 31 | 31 | 58.49 |
| 8 | 2 | DETERMINISTIC | COMPLETED | ANSWERED | 0 | 99 | 1.83 |
| 9 | 3 | INDEPENDENT_WORKERS | FAILED | NOT_PROVIDED | 52 | 52 | 125.93 |
| 10 | 3 | COORDINATED_TEAM | FAILED | ABSTAINED | 30 | 30 | 44.93 |
| 11 | 3 | DETERMINISTIC | COMPLETED | ANSWERED | 0 | 99 | 1.82 |
| 12 | 3 | SINGLE_AGENT | UNKNOWN_REMOTE_OUTCOME | NOT_PROVIDED | 18 | 18 | 34.07 |

The two failed single-agent trials stopped at `UNSUPPORTED_DOSSIER`. The third
independent-worker trial retained a completed webhook worker and a reconciliation
worker stopped at that same dossier gate. In every team trial, both original
workers exhausted their stage call budgets; no fresh follow-up ran, and the
final joint response was an abstention. Coordinator review and final reconciliation
usage remain included rather than being treated as free work.

The last single-agent trial reserved eighteen model opportunities. Seventeen
completed; one received HTTP 429, stored as `RATE_LIMITED` with
`remoteOutcome: RESPONSE_RECEIVED`. The retained record has no error subtype or
token usage for that response. It does not distinguish exhausted quota from a
transient limit. The runtime conservatively records one unresolved usage slot and
an `UNKNOWN_REMOTE_OUTCOME` trial; this does not mean the HTTP response itself
was lost. There was no retry or replacement trial.

Campaign totals are **313 reserved and registered model opportunities, 312
completed responses, 610 evidence reads and zero new consumer executions**.
Known usage is **3,705,339 input tokens and 36,547 output tokens**; the unresolved
slot is not assigned zero usage. All deterministic trials make zero model calls.

`COMPLETED` describes the adapter's operational result. `ANSWERED`, `ABSTAINED`
and `NOT_PROVIDED` describe the joint case response. A dossier-gate rejection is
an application protocol/evidence-validation result, not an independent semantic
judgment that every assertion is false. Team worker stop reasons remain distinct
from the final response and are retained per participant in the JSON record.

## Integrity checks and inspection

The completed controller inspection validates all twelve original mappings,
unchanged policies, receipts, runtime traces and participant accounting. A
separate mechanical audit hashes the frozen source inventory, recomputes usage
from retained reservations, broker results and evidence-read records, and checks
each participant, trial and campaign total. Historical source evidence and the
earlier descriptive review remain unchanged.

A credential-free completed-campaign replay checks idempotence: it creates no
new trial, read or model reservation and leaves retained non-lock files unchanged.
Inspection uses the original evidence/state directories and the independently
retained campaign receipt:

```powershell
./scripts/Run-InvoiceCampaign.ps1 -Action read `
  -CampaignId invoicecampaign-c86bfdd4-8bc2-4fd3-bd44-ab80761d30d6 `
  -ExpectedReceiptSha256 4481be329dffa6c3d8ee07ab0db642ee4bc5eae3f6f2aa008fb4f7e53724a0c1 `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/state
```

The exported JSON reserializes selected metadata. Its hashes identify the
original retained bytes, rather than the formatting of the exported objects.
Revalidating those hashes requires the retained local evidence. A hash match does
not establish semantic truth, and reproducing new model calls need not reproduce
these responses.

## Interpretation and next work

This is a known synthetic case already examined in pilots. Three paired rotations
are not fully counterbalanced, and a model alias is not a fixed provider snapshot.
The record supports descriptive operational comparison under these conditions;
it does not measure held-out accuracy or general superiority. All twelve trials
remain in the denominator, including failed and missing answers. Known tokens
are recorded usage, not a provider invoice. Trial durations are separate from
campaign wall time and do not establish causal speedup.

Next, bind review packets to these exact twelve trial outputs and review their
complete available claim lineage against the frozen rubric and contract. Only
then can a paired quality/resource report assess benefit while preserving
unsupported, unresolved, historical and missing work. No routing change or
Milestone 4 acceptance follows from this operational measurement.
