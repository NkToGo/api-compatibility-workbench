# Paired coordination results

The frozen campaign completed all 12 planned two-case trials. Under the recorded
model-assisted semantic review, no complete bundle earned acceptance. Deterministic
diagnostics supplied three accepted subscription reports; the adaptive configurations
supplied no fully accepted cases. All configurations correctly described the recorded
scenario outcomes, but required explanations and safeguards remained incomplete.
The coordinated team consumed more total recorded tokens and summed elapsed time
across the campaign than the other configurations. It was slower in every matched
repetition, without improving accepted-case counts. Its first repetition used fewer
input tokens than the single agent; aggregate spend does not erase that difference.

These observations describe two known synthetic cases under one frozen model and
budget. They do not establish general superiority, production readiness or a routing
policy. Step 4d will make a separate, scoped routing decision.

The [structured version 2 result](evaluations/coordination-results-2026-10-07.json)
retains all trial rows, case assessments, six paired outcomes and resource totals.
Its report ID is `report-7983cc37-e309-4ec9-80d8-ded8c7018c0b`, SHA-256
`d0fa2885b6756c48bbfe51010ad3ccb301e795c89e31e5cde0ea3c884ed4e258`.
The [binding manifest](evaluations/coordination-results-binding-2026-10-07.json)
links the result to the bound export and semantic review. Typed record serialization
can change JSON formatting and therefore its byte digest; their values were compared
before publication. The retained input export digest is
`8a6d50d3546f6e65894b35efbe650d439d1f4cfa660040a2ad21b63a73604aff`.

## Inputs, review and denominator

- Campaign: `campaign-8d49ba41-7cf4-4181-ad4b-fb20dd6c1915`.
- Comparison: `comparison-17e67ac0-5c1e-463b-a51e-9454200b6862`.
- Captured inputs: `inputs-6e4dfed9-be1a-479d-aa15-b40073041353`.
- Review packet: `reviewpacket-fe2793c0-8aad-4377-b5d9-f1f15e77e441`, SHA-256
  `df0dc3fa4860a4589c2ffee4d65937bc782e48d419c5e7c4dbc411bbab655c28`.
- Semantic review: `semanticreview-540d2802-3401-4b9d-80cf-0252b88a58fc`, SHA-256
  `2ba1dbb1f783982dcf5e2faaea0f14b0450f65c3bd54ea2277073ab3aa3db381`.
- Unchanged rubric: `coordination-known-cases-v1`, SHA-256
  `7f8f80022f30f5953616832d47ea845004196e5878313f22b57c4c2c58a4c9bb`.

Three repetitions pair four configurations on the same two-case bundle. Execution
order rotates D/S/I/T, S/I/T/D and I/T/D/S; three rotations do not fully counterbalance
four positions. D means deterministic diagnostics, S one multi-case agent, I separate
case workers and T the coordinated team. Each adaptive bundle has the same ceiling:
60 call reservations, 64,000 input bytes and 2,048 output tokens per call, and a
20-minute whole-trial deadline. The exact model is `gpt-5.6-terra`. Team original
workers, coordinator, fresh follow-ups and reconciliation share that ceiling.

All 12 trials completed operationally with 24 `ANSWERED` case outputs. All 160
reservations have registered completed responses; no not-dispatched or uncertain
usage slots remain. There are no missing, duplicate or unexpected trial records and
no recorded budget violation. This is operational coverage, not semantic acceptance.
The original unreviewed campaign records and all earlier evidence remain unchanged;
the reviewed version 2 export is a separate artifact. No trial was rerun for this report.

The [claim-level review](coordination-semantic-review.md) inspected 196 units and
1,068 assertions, including earlier claims retained by final answers. It was
model-assisted, unblinded and not independently human adjudicated or calibrated.
Those assertion counts are an inventory, not an accuracy score. The unchanged
criteria require complete answers and no endorsed material unsupported or unresolved
claim. Missing required reasoning is a known negative judgment; an unresolved
claim is kept distinct from that negative judgment.

## Reviewed quality

| Configuration | Planned/completed bundles | Fully accepted bundles | Accepted cases out of 6 | Quality-comparable bundles | Unestablished bundles |
| --- | --- | --- | --- | --- | --- |
| Deterministic | 3 / 3 | 0 | 3 | 3 | 0 |
| Single agent | 3 / 3 | 0 | 0 | 3 | 0 |
| Independent workers | 3 / 3 | 0 | 0 | 2 | 1 |
| Coordinated team | 3 / 3 | 0 | 0 | 3 | 0 |

All customer reports lack the complete null-guard/deletion-loop explanation. The
deterministic reports meet the absence-precondition criterion; adaptive reports do
not explain its complete scope. Only the deterministic subscription matrices meet
the full configured-item/shortcut/membership/completeness criterion. Adaptive
subscription answers omit part of that requirement. Policy attribution is also
incomplete for the first single-agent and independent-worker subscription reports.

Two earlier customer claims still endorsed by the third coordinated-team answer
are unsupported: the original consumer does not establish an evidence-completeness
gate before deletion. They are known negative findings, so that trial can remain
quality-comparable while earning no acceptance. The third independent-worker
subscription answer has one unresolved definite claim about an actual target return.
Captured observations record assertion outcomes, not that returned value. That
claim leaves its bundle unestablished even though other criterion failures are
known. Its elapsed time, usage, satisfied criteria and negative criteria remain visible.

The structured result therefore has overall `UNRESOLVED` comparison coverage because
one material claim remains unestablished. This does not mean that an artifact or
trial is missing, that all results are unknown, or that the unresolved block is a
known loss. The audit itself is complete; uncertainty survives completed inspection.

## Paired quality outcomes

The primary result is a fully accepted completed bundle. Comparable pairs retain
both supported negative results and accepted partial cases. An unestablished block
does not enter the quality win/tie denominator.

| Left / right | Comparable blocks out of 3 | Unestablished blocks | Left-only / right-only / both / neither accepted bundle | Left-more / right-more / equal accepted cases |
| --- | --- | --- | --- | --- |
| D / S | 3 | 0 | 0 / 0 / 0 / 3 | 3 / 0 / 0 |
| D / I | 2 | 1 | 0 / 0 / 0 / 2 | 2 / 0 / 0 |
| D / T | 3 | 0 | 0 / 0 / 0 / 3 | 3 / 0 / 0 |
| S / I | 2 | 1 | 0 / 0 / 0 / 2 | 0 / 0 / 2 |
| S / T | 3 | 0 | 0 / 0 / 0 / 3 | 0 / 0 / 3 |
| I / T | 2 | 1 | 0 / 0 / 0 / 2 | 0 / 0 / 2 |

Equal accepted-case counts do not imply equally useful prose or equivalent coverage.
The evaluator uses the frozen criteria and whole-case acceptance; it does not weight
individual assertions or select whichever explanation appears most persuasive.

## Inclusive resources and timing

All three trials per configuration have unambiguous operational records and complete
usage. The table includes coordinator/review/follow-up/reconciliation calls and the
quality-unestablished independent-worker trial. No unsuccessful or uncertain-quality
trial is removed from costs. Reads mean registered tool reads, not the number of
unique documents. There were zero new consumer executions in every arm: the campaign
investigated the same retained synthetic execution evidence.

| Configuration | Reserved/registered/completed calls | Input tokens | Output tokens | Registered reads | Trial elapsed total (ms) | Trial elapsed min / median / max (ms) |
| --- | --- | --- | --- | --- | --- | --- |
| Deterministic | 0 / 0 / 0 | 0 | 0 | 42 | 12,453 | 4,117 / 4,168 / 4,168 |
| Single agent | 34 / 34 / 34 | 179,947 | 5,156 | 31 | 119,744 | 23,660 / 44,347 / 51,737 |
| Independent workers | 35 / 35 / 35 | 113,325 | 4,529 | 29 | 79,188 | 23,791 / 25,992 / 29,405 |
| Coordinated team | 91 / 91 / 91 | 533,872 | 14,161 | 77 | 333,973 | 60,263 / 62,262 / 211,448 |
| Total | 160 / 160 / 160 | 827,144 | 23,846 | 179 | 545,358 | Not a pooled latency estimate |

`elapsedMillis` measures the retained runtime's started-to-recorded-trace interval
for each whole bundle, including its orchestration stages. It is not per-request
model latency, CPU time or campaign duration. Summing twelve bundle durations does
not measure speedup. Trials ran on one host in a fixed rotating order; the records
cannot isolate the effect of parallelism from provider latency, response content,
tool selection, filesystem overhead, context length or stage allocation.

The third coordinated trial used 55 calls, 392,232 input tokens, 8,489 output tokens
and 211,448 ms. It remains in all totals and ranges. Reporting only the shorter team
repetitions would hide the largest observed spend.

The following deltas pair the same repetition. Positive values mean the **right**
configuration took longer. Timing records are known for all three blocks, including
the block excluded from semantic comparison; this resource denominator differs
deliberately from the quality denominator above. These are descriptive differences,
not causal savings or population confidence estimates.

| Left / right | Timing blocks | Right-minus-left ms, repetitions 1 / 2 / 3 | Median paired difference (ms) |
| --- | --- | --- | --- |
| D / S | 3 | 47,569 / 19,492 / 40,230 | 40,230 |
| D / I | 3 | 19,623 / 21,824 / 25,288 | 21,824 |
| D / T | 3 | 56,095 / 58,094 / 207,331 | 58,094 |
| S / I | 3 | -27,946 / 2,332 / -14,942 | -14,942 |
| S / T | 3 | 8,526 / 38,602 / 167,101 | 38,602 |
| I / T | 3 | 36,472 / 36,270 / 182,043 | 36,472 |

Token totals are recorded units, not a monetary invoice. Cached-token breakdowns,
effective provider prices, billing reconciliation and review effort/time are not
measured here. The semantic review made zero additional provider API calls, but
that does not mean review effort or Codex usage was free. Neither review time nor
this offline report's processing time enters participant latency.

## Export and read

After the normal README build, with the original sealed local campaign available:

```powershell
./scripts/Export-CoordinationReviewedRecords.ps1 `
  -ReviewPath docs/evaluations/coordination-review-2026-10-07.json `
  -RecordsPath .workbench/coordination-reviewed-records/known-cases-v1.json
```

The command performs the full retained campaign/packet/claim-coverage verification,
then exports new comparison records with explicit unsupported **and unresolved**
material claim counts. It changes no operational field, original record, judgment,
permission or budget. Every review identity is the exact reviewed artifact digest.
It makes no model or consumer execution call and reads no credential.

A separate `.receipt.json` binds the exact export to the campaign, packet, original
records and semantic review, retaining reviewer provenance. Both files are create-only
and at most 1 MiB each, using UTF-8 with LF line endings on both platforms.
Linked paths and changed bytes are rejected. Repeating the
same export matches existing bytes; an interruption can resume the same pending
publication without rewriting a completed artifact. A missing final receipt remains
an incomplete export. Only one publisher should operate on an output path at a time.

```powershell
./scripts/Export-CoordinationReviewedRecords.ps1 -Action read `
  -ReviewPath docs/evaluations/coordination-review-2026-10-07.json `
  -RecordsPath .workbench/coordination-reviewed-records/known-cases-v1.json
./scripts/Compare-Coordination.ps1 -Action assess `
  -ComparisonId comparison-17e67ac0-5c1e-463b-a51e-9454200b6862 `
  -RecordsPath .workbench/coordination-reviewed-records/known-cases-v1.json
```

Read revalidates source bindings and makes no new report. Assessment creates a new
immutable version 2 report under the already registered comparison; repeated
assessment creates another report, so read the printed report ID for later inspection.
The generic evaluator does not independently load or grade the review. It compares
supplied metadata; the bound exporter establishes this record-to-review linkage.
These local hashes and receipts do not authenticate a producer, protect a compromised
host or replace independent semantic adjudication.

The synthetic controls cover missing/duplicate cases, changed identities, preserved
operational facts, unresolved counts, criterion judgments and publication recovery.
The Java tests cover versioned historical reads, explicit resolution counts and
unresolved pairing/acceptance gates. These tests validate the implementation, not
answer accuracy. The retained captured-input artifacts are local and ignored by Git;
the report does not claim that a fresh clone can reproduce the API responses.
