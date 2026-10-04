# Coordination comparison protocol

The offline evaluator compares supplied records for four declared configurations.
It retains the planned denominator, separately bound quality reviews, operational
failures and measured resource use. It does not run these configurations, grade
explanations, authenticate traces or select a routing policy.

## Implemented boundary

`CoordinationExperiment` defines immutable workloads, case input identities,
expected answer/abstention dispositions, criterion IDs, repetitions, one exact
model, arm implementation identities, a rubric identity and common resource limits.
Its definition digest covers every field using a versioned, length-prefixed encoding.
Changing any condition changes that digest and invalidates earlier trial bindings.

`CoordinationTrial` keeps terminal workflow disposition, per-case answer identities,
separate review identities, call inventory, known token totals, tool/execution counts
and elapsed time distinct. A review binds to the exact output being judged. Its
criterion judgments and complete claim-audit flag are supplied by a reviewer;
the evaluator cannot establish that those judgments are true.

`CoordinationComparison` derives all expected workload/repetition/arm slots. It
retains missing, duplicate and unexpected observations without selecting a best
retry or changing the denominator. Reused run IDs or trace identities cannot
count as separate repetitions. Exact definition, implementation, model and case
input bindings are required. Any surviving reviewed facts remain visible when
another case, resource measurement or trial is unresolved.

The [local report command](#local-command) registers a retained plan and writes
separate, immutable report directories. Historical reads recompute the assessment
and reject changed plans, records or derived verdicts. This component makes no
OpenAI, provider or Docker call, reads no credential and creates no execution grant.
The [shared input/trial runtime](coordination-runtime.md) now provides captured
input registration and whole-trial budget enforcement. Fresh measurement adapters
must use that boundary; they remain later work.

## First campaign design

The initial campaign will investigate the two known curated cases together, using
three paired repetitions. Each repetition has one two-case bundle for each arm:
**12 planned bundle trials, covering 24 case outputs**. These are known-case
measurements, not held-out accuracy estimates. The earlier milestone trials and
operational demonstrations cannot be retroactively presented as these fresh trials.

| Arm | Intended execution boundary |
| --- | --- |
| `DETERMINISTIC` | Fixed diagnostics over the same declared source/policy/execution inputs. Zero model calls. Structured facts can satisfy criteria; prose is not required. Rules must be frozen before measurement, and a diagnosis the rules do not establish remains absent. |
| `SINGLE_AGENT` | One adaptive context sees both cases and can use their permitted tools. Sequentially running two isolated investigators is not this arm. |
| `INDEPENDENT_WORKERS` | Separate adaptive case workers run concurrently, with deterministic aggregation and no exchange of findings or root-agent interpretation. |
| `COORDINATED_TEAM` | Separate case workers plus bounded coordinator review, host-selected fresh follow-ups and reconciliation when needed. Every stage consumes the same bundle budget; proposals cannot grant execution permissions. |

The proposed common AI ceiling is **60 model-call reservations per bundle**, at
most 64,000 input bytes and 2,048 output tokens per request, and a 20-minute whole
bundle wall-time limit. This is a complete comparison-run ceiling, separate from
existing per-session admission deadlines. Coordinator and follow-up calls are
included. Known-not-dispatched and uncertain reservations still occupy a slot.
The deterministic arm shares the wall-time limit and reports its actual tools
and executions, with zero model usage.

Equal ceilings do not require equal spending. All adaptive arms must have a real
opportunity to use the declared ceiling: comparing a 60-call team against a
single agent artificially restricted to one existing 12-decision case session
would not satisfy this protocol. Per-stage allocation, context management, prompt
versions, tool permissions and actual run order must be frozen in the campaign
registration before dispatch. Arm implementation identities cover their different
algorithms; the shared input identities cover the same available evidence.
Freeze the final-output selection rule as well: review the complete final dossier
and any earlier claims it still endorses, rather than choosing the best worker
attempt using grading answers. Operational stops retain available partial output
without retroactively promoting a failed coordinator's advice.

The current evaluator checks supplied bindings and aggregate limits. It does not
inspect requests to verify per-call byte/token limits, prove pre-registration,
validate run ordering or enforce runtime ceilings. The fresh adapters must supply
that trace-backed evidence and enforce the limits before actual measurement.
Only then can a report support a fair operational comparison.

## Quality criteria

The first rubric will assess answers to these concrete questions. Criteria are
judged against exact captured source, application policy and observed execution,
with brief evidence references and reasons in a separate review artifact. A task
may expose its application expectations; grading judgments and prior evaluated
answers must stay outside every participant's context.

| Case | Criterion ID | Required scope |
| --- | --- | --- |
| Customer sources | `observed-regressions` | Correctly report the declared scenario outcomes and passing control, without presenting them as general migration certification or confirmed failure causation. |
| Customer sources | `absence-preconditions` | Distinguish omission, observed presence and established absence. Omission alone cannot authorize deletion; absence can be established separately through complete source retrieval even when the embedded field is omitted. |
| Customer sources | `null-guard-limit` | Explain why the historical null guard alone does not protect a known legacy method from the later deletion loop; retain the observed reference failures. |
| Subscription periods | `observed-regressions` | Accurately describe baseline/target evidence and its eight declared scenarios, keeping a passing deferral check distinct from establishing a renewal date. |
| Subscription periods | `bound-item-selection` | Explain configured-item selection and why first-item/minimum/maximum substitutions are insufficient; preserve required membership/completeness checks. |
| Subscription periods | `policy-and-deferral-scope` | Attribute selection/deferral rules to this application policy, without inventing universal Stripe entitlement rules or interpreting a date as paid-through access. |

Every presented material claim needs review, not just the citations attached to a
final summary. A completed tool call, schema-valid answer, matching citation or
agreement between agents cannot substitute for semantic review. The
[recorded reconciliation spot review](follow-up-reconciliation.md#recorded-reconciliation-and-semantic-spot-review-2026-10-03)
illustrates why answer-completeness advice can require an explicit review block.

A case earns reviewed acceptance only when its exact output/input bindings match,
all declared criteria are `SATISFIED`, the claim audit is complete, no material
unsupported claim remains, and its answer/abstention disposition matches the task.
`NOT_SATISFIED` is a known negative judgment; `UNRESOLVED`, missing criteria or an
unfinished audit leave quality unestablished. An explicit `NOT_PROVIDED` entry
records a known absent answer; omitting a required case from an otherwise completed
report leaves coverage unresolved.

The primary result is a fully accepted, completed **bundle**. Reviewed accepted
cases are also reported, including useful partial work in a failed bundle. A
failure/cancellation with no answer remains a recorded negative trial. Unknown
remote outcomes and uncertain usage remain unresolved; they are not abstentions
or free successful runs.

## Resource and paired reporting

Call accounting distinguishes reserved, registered, completed, known-not-dispatched
and unresolved slots. Terminal accounting must cover every reservation, with no
unresolved usage, before a row is comparable. Known input/output tokens remain
visible while unknown usage blocks complete accounting. Output totals exceeding
the declared aggregate per-response bound also flag a resource violation; totals
under that bound do not prove each individual request obeyed it.

Reports distinguish reviewed accepted bundles from **comparable accepted bundles**.
A call/time limit violation preserves supported quality observations but excludes
the row from a fair paired comparison. Model usage in the deterministic arm is
also a boundary violation. These checks assess supplied measurements; they do
not prevent an executing producer from overspending.

Resource totals explicitly cover only unambiguous planned slots. Duplicate or
reused records are not added twice or assigned an invented zero cost; their
incomplete/ambiguous counts and full original records remain visible. Unexpected
records remain in a separate collection and make overall coverage unresolved.
Token totals are not an invoice: cache discounts, provider pricing and separate
review costs require their own recorded accounting. Summed bundle wall time is
not campaign duration, CPU consumption or speedup.

For each of the six arm pairs, report matched-block accepted-bundle counts and
accepted-case differences, retaining the number of unestablished blocks. There is
no weighted "agent score," automatic winner, confidence claim or routing action.
Three repetitions of two known cases cannot establish general reliability or a
statistically dependable superiority claim. `RECORDS_COMPLETE` means supplied
records and reviews cover the declared comparison; it is not proof of their
authenticity, review correctness, coordination benefit or production readiness.

## Local command

After the normal README build, create an empty **synthetic example**:

```powershell
./scripts/Compare-Coordination.ps1
```

This registers a `SYNTHETIC_EXAMPLE` plan with placeholder digests and writes an
`UNRESOLVED` report containing all 12 missing slots. It performs no live experiment
and provides no quality result. The printed report path is local JSON.

For supplied metadata, prepare a `plan.json` envelope with `formatVersion: 1` and
`definition`, and a `records.json` envelope with `formatVersion: 1` and `trials`:

```powershell
$comparison = ./scripts/Compare-Coordination.ps1 -Action register -PlanPath plan.json
./scripts/Compare-Coordination.ps1 -Action assess -ComparisonId $comparison -RecordsPath records.json
./scripts/Compare-Coordination.ps1 -Action read -ComparisonId $comparison -ReportId report-EXISTING-UUID
```

The example's retained `plan.json` and `records.json` show the envelope structure;
the Java records define trial fields. Required nullable fields must be explicit.
Unknown or missing fields, invalid enums/identifiers and negative/impossible call
inventories are rejected. This is a local record format, not a public product API.

State defaults to ignored `.workbench/coordination-comparisons/`. Registration
retains `plan.json` and its digest/registration metadata under `comparison-UUID`.
Each assessment writes new `report-UUID/records.json` and `result.json` files using
create-only writes. A final `receipt.json` binds the exact result bytes; missing
receipts and altered report metadata are rejected. Re-reading a completed report makes no new report or call.
Changing observations requires a separate assessment; old reports remain intact.
Each input or retained artifact is limited to 1 MiB; oversized reports are refused,
and a partially written directory is not a completed report.

Hash checks are not signatures against an operator rewriting the whole store.
The command does not follow trace/review digest references to retrieve or verify
their contents. `SUPPLIED_RECORDS` is a declared origin, not verified live execution.
Fresh trace exporters and claim-level review binding are prerequisites for the
actual campaign, which remains unrun.

## Validation scope and remaining steps

Synthetic tests cover complete paired records, full denominators, semantic blocks,
pending/missing judgments, changed bindings, missing/extra/duplicate cases and
trials, reused run/trace identities, partial results, known failures, unknown usage,
resource violations, numeric overflow, retained reports and altered derived verdicts.
They use no credential and do not measure a real configuration's performance.

An offline command check on 2026-10-03 retained example report
`report-2a41036d-ad59-4a4f-99fb-dc0751a5b15d`, with result SHA-256
`54627dcbb5c1a1b2982df5477cb05fddbd40fe31343755eee96d2b67b8823d96`.
It reported 12 missing slots, zero comparable trials and no routing decision.
A repeated read preserved the summary and all five retained file hashes.
This was a synthetic empty-record check, not a configuration measurement.

1. **4a implemented:** frozen comparison contract, offline evaluator and retained reports.
2. **4b1 implemented:** [common captured input registration and end-to-end budget/trace accounting](coordination-runtime.md).
3. **4b2 planned:** deterministic diagnostics with no model calls.
4. **4b3 planned:** one adaptive agent operating across the case bundle.
5. **4b4 planned:** independent-worker and coordinated-team adapters using the shared boundary.
6. **4c planned:** execute the fixed paired campaign, bind complete semantic reviews,
   report all planned trials and compare observed quality, latency and resource use.
7. **4d planned:** choose a scoped routing rule only if the evidence supports it;
   otherwise preserve the simpler configuration and the explicit uncertainty.

No measurement or routing conclusion is established by this implementation step.
