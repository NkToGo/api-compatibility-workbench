# Invoice campaign: paired quality and recorded resources

The [comparison result](evaluations/invoice-results-2026-10-29.json) combines all
twelve original invoice trials with the [completed semantic review](invoice-campaign-review-results.md).
**No trial satisfies the full frozen review gate, and useful coordination is not
established.** The comparison is `UNRESOLVED`: one independent-worker answer has
endorsed material uncertainty, and the final single-agent trial has uncertain
remote outcome and incomplete usage. Known failures and unmet criteria remain
visible alongside those uncertainties.

The [reviewed records](evaluations/invoice-reviewed-records-2026-10-29.json) and
[binding receipt](evaluations/invoice-results-binding-2026-10-29.json) link the
result to the original campaign, comparison plan, packet and exact review revision.
They are separate artifacts. Original operational records retain null reviews;
no participant, consumer, preferred retry or replacement trial ran for this report.
Routing remains `NOT_ESTABLISHED`, and [Milestone 4 acceptance](milestone-4-status.md)
stays open.

## Conditions, review and denominators

The [26 October measurement](invoice-campaign-measurement.md) freezes the same
known synthetic case, four compiled policies, three paired repetitions and
rotating D/S/I/T, S/I/T/D, I/T/D/S order. Three rotations do not fully
counterbalance four positions. The six-criterion rubric and authored business
contract are unchanged. The captured inventory contains 444 artifacts.

Each adaptive trial has the same ceiling: 60 model opportunities, 100 registered
evidence reads, a persistent twenty-minute deadline, 64,000 input bytes and
2,048 output tokens per call, with at most two concurrent calls. The model alias
is `gpt-5.6-terra`; it is not a fixed provider snapshot. Team investigation,
coordinator review, selected fresh follow-ups and reconciliation share that
ceiling and retain the frozen 24/6/24/6 allocation with forward carry.

The original twelve slots contain five completed trials, six failed trials and
one `UNKNOWN_REMOTE_OUTCOME`. Responses are five `ANSWERED`, three `ABSTAINED`
and four `NOT_PROVIDED`. Operational completion and response disposition remain
separate from reviewed answer quality.

The complete, unblinded model-assisted review covers 521 units, 72 criterion
entries and 3,826 judgment records. It has no independent human adjudication or
calibrated reviewer-accuracy claim. Repeated assertions are not independent
observations or a quality score. Its four historical unsupported and four
historical unresolved occurrences remain bound to the full review, including
rejected responses. Only endorsed material occurrences enter a provided answer's
comparison gate.

The version 2 export attaches review metadata to the eight provided answers or
abstentions. Four absent answers retain null output identities and null reviews;
the export does not invent an answer identity to hold grading metadata. The
binding receipt retains the complete twelve-trial inspection, including review
of rejected prose and accepted partial role reports. Their original review
judgments remain available even when there is no final answer to assess.

## Reviewed quality

| Configuration | Planned slots | Completed / failed / uncertain | Fully accepted bundles or cases | Quality-comparable slots | Unestablished slots |
| --- | --- | --- | --- | --- | --- |
| Deterministic (D) | 3 | 3 / 0 / 0 | 0 | 3 | 0 |
| Single agent (S) | 3 | 0 / 2 / 1 | 0 | 2 | 1 |
| Independent workers (I) | 3 | 2 / 1 / 0 | 0 | 2 | 1 |
| Coordinated team (T) | 3 | 0 / 3 / 0 | 0 | 3 | 0 |

There is one joint invoice case per bundle, so accepted case and bundle counts
coincide. Criteria with known omissions are negative judgments, not unknown
results. A known failed trial without an answer is also a known negative outcome.
An unresolved material assertion or uncertain trial/usage leaves its block
unestablished for paired quality comparison.

The shared evaluator's `failedBundles` counter includes failed, cancelled and
unknown-remote terminal statuses. The table above splits the actual status values
so an uncertain outcome is not presented as a known failed answer.

The three deterministic outputs preserve useful recorded facts and uncertainty,
but do not supply the complete source/version/policy/joint-identity explanation.
The accepted independent-worker reports contain bounded useful observations,
but omit required parts of the frozen task. The first also retains three copies
of one unsupported adapter-scope assertion and three copies of a materially
unresolved source-flow hypothesis. These copies are not independent defects.

The two failed single-agent answers and the third independent-worker joint answer
remain `NOT_PROVIDED`; rejected finishes and an accepted partial webhook report
do not replace them. All three teams retain failed original workers and final
abstentions. Honest uncertainty can satisfy the evidence/uncertainty criterion
without satisfying the task's expected `ANSWERED` disposition or full gate.

| Left / right | Comparable blocks out of 3 | Unestablished blocks | Left-only / right-only / both / neither accepted | Equal accepted-case blocks |
| --- | --- | --- | --- | --- |
| D / S | 2 | 1 | 0 / 0 / 0 / 2 | 2 |
| D / I | 2 | 1 | 0 / 0 / 0 / 2 | 2 |
| D / T | 3 | 0 | 0 / 0 / 0 / 3 | 3 |
| S / I | 1 | 2 | 0 / 0 / 0 / 1 | 1 |
| S / T | 2 | 1 | 0 / 0 / 0 / 2 | 2 |
| I / T | 2 | 1 | 0 / 0 / 0 / 2 | 2 |

The I-first-repetition block is excluded from every pair involving it because of
endorsed material uncertainty. The S-third-repetition block is excluded because
its outcome/usage is uncertain. Neither is counted as a known loss or a tie.
Equal zero accepted cases does not imply equally useful prose or equal coverage.
The result selects no winning architecture and uses no assertion-count weighting.

## Inclusive resources

Every original trial remains in these totals, including the two quality-
unestablished slots. Coordinator and reconciliation work are included. The
[measurement record](evaluations/invoice-campaign-2026-10-26.json) retains participant
accounting; the reviewed export preserves every operational field, usage counter,
duration, input/output identity and original response disposition.

| Configuration | Reserved / registered / completed calls | Known input tokens | Known output tokens | Registered reads | Incomplete-usage slots | Trial elapsed total (ms) | Trial min / median / max (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D | 0 / 0 / 0 | 0 | 0 | 297 | 0 | 5,498 | 1,816 / 1,831 / 1,851 |
| S | 81 / 81 / 80 | 1,035,463 | 8,031 | 81 | 1 | 202,378 | 34,067 / 76,337 / 91,974 |
| I | 140 / 140 / 140 | 1,728,538 | 18,993 | 140 | 0 | 289,577 | 73,319 / 90,330 / 125,928 |
| T | 92 / 92 / 92 | 941,338 | 9,523 | 92 | 0 | 158,741 | 44,933 / 55,320 / 58,488 |
| Total | 313 / 313 / 312 | 3,705,339 | 36,547 | 610 | 1 | 656,194 | Not a pooled latency estimate |

All twelve trials dispatch zero new consumer executions: they inspect the same
retained execution evidence. Reads count registered operations, not unique files
or fully understood sources. Complete usage inventory exists for eleven trials;
the reported token totals include all known records from the uncertain trial too.

The last single-agent slot retains a received HTTP 429 with no stored subtype or
usage for that response. Its unresolved usage slot is not assigned zero tokens
or silently removed. This report infers neither exhausted quota nor a transient
rate limit and performs no retry.

Tokens are recorded resource units, not a currency invoice. Cached-token billing,
effective prices and billing reconciliation were not captured. Cost per accepted
case cannot be estimated from this campaign's zero accepted cases. Offline review
and report processing are separate from participant runtime and are not measured
by these latency totals.

`elapsedMillis` is the runtime's retained started-to-trace interval for a whole
trial, not per-request model latency, CPU time or campaign wall time. The shorter
team runs ended in failure/abstention; they did not produce a fully accepted answer
faster. These observations cannot isolate parallelism from provider timing,
tool selection, context growth, filesystem work, stage limits or early stopping.

| Left / right | Known timing blocks | Complete-usage blocks | Right-minus-left ms, repetitions 1 / 2 / 3 | Median difference (ms) |
| --- | --- | --- | --- | --- |
| D / S | 3 | 2 | 74,486 / 90,143 / 32,251 | 74,486 |
| D / I | 3 | 3 | 71,468 / 88,499 / 124,112 | 88,499 |
| D / T | 3 | 3 | 53,469 / 56,657 / 43,117 | 53,469 |
| S / I | 3 | 2 | -3,018 / -1,644 / 91,861 | -1,644 |
| S / T | 3 | 2 | -21,017 / -33,486 / 10,866 | -21,017 |
| I / T | 3 | 3 | -17,999 / -31,842 / -80,995 | -31,842 |

Positive differences mean the right configuration took longer to reach its
recorded outcome. Timing and usage denominators differ from quality denominators.
These descriptive differences include failures and uncertainty; they are not
causal savings, latency-to-correct-answer results or population confidence estimates.

## Publish and inspect a bound report

After the README build, select the independently retained campaign/packet/review
identities and a report directory outside the original evidence/state trees and
selected review:

```powershell
./scripts/Compare-InvoiceCampaign.ps1 -ReportAction publish `
  -CampaignId invoicecampaign-c86bfdd4-8bc2-4fd3-bd44-ab80761d30d6 `
  -ExpectedReceiptSha256 4481be329dffa6c3d8ee07ab0db642ee4bc5eae3f6f2aa008fb4f7e53724a0c1 `
  -ReviewPacketId reviewpacket-8fb56f51-6a26-44ae-9a79-72ed85a115a7 `
  -ExpectedPacketSha256 666b667993833f0855b70ac01905885469d093101be9955ec69ddc85b975ca13 `
  -ReviewPath ./docs/evaluations/invoice-campaign-review-2026-10-27.json `
  -ReviewSha256 0981e5b5f5c9a1964c4f0337391a5311b8127824e24855ccb42c1561870875bf `
  -ReviewEvidenceDirectory /trusted/invoice-run `
  -EvidenceDirectory /trusted/invoice-run/evidence `
  -StateDirectory /trusted/invoice-run/state `
  -ReportDirectory /trusted/reports/invoice-comparison
```

Repeat with `-ReportAction read` and the same parameters to revalidate and inspect
the completed report without writing. Publication revalidates the full campaign,
packet and claim review, preserves the original record projection, and invokes
the existing comparison evaluator through its read-only file mode. It creates no
comparison registration, runtime state, participant grant or external operation.

The directory contains `records.json`, `assessment.json` and `receipt.json`.
Each is bounded to 1 MiB, UTF-8 with LF line endings and create-only publication.
Identical publication matches existing bytes. Matching pending bytes can recover
an interruption; changed pending/published files are refused and preserved. A
missing final receipt is incomplete. Use one publisher per destination and stable,
completed source stores: these checks are not a transactional snapshot or protection
against a compromised host replacing independently retained identities too.

The public files above are exact copies with dated names. The Java
`CoordinationComparisonReport evaluate PLAN RECORDS` mode checks supplied typed
records and computes the existing comparison rules, with no state directory or
semantic grading. The host command supplies the full campaign/review binding.
It rechecks selected source identities around publication. Changed implementation
semantics that produce a different result cannot silently overwrite an earlier
report.

A fresh checkout can inspect these artifacts and run controls. Full historical
revalidation requires the original retained source evidence; source fixtures alone
cannot recreate past API responses. The Java and PowerShell controls verify
binding, preservation, read-only behavior and failure handling, not semantic truth
or calibrated reviewer accuracy.

CI also replays the published supplied records against the captured public
definition with the existing evaluator, checking the stored assessment and
record/review identities. This metadata replay does not revalidate the unavailable
original claim sources or supply new semantic judgments.

## Interpretation

The campaign establishes no reviewed coordination benefit under these frozen
conditions. It also does not establish that agents can never help API migrations:
this known synthetic workload, model alias, policy allocation and three repetitions
do not support that generalization. Policy improvements require a separately
versioned and frozen exercise with the original negative results retained, not
replacement runs, retrofitted criteria or a workload selected to guarantee a team
winner. No routing promotion or Milestone 4 acceptance follows from this report.
