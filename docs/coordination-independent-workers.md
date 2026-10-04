# Independent-worker comparison adapter

Two adaptive investigators use separate case contexts through the
[shared trial runtime](coordination-runtime.md). This is the `INDEPENDENT_WORKERS`
arm of the [comparison protocol](coordination-comparison.md). The host schedules
and aggregates records deterministically; no manager model interprets, rewrites or
exchanges their findings. The coordinated-team adapter and paired quality
measurement remain separate work.

## Isolation and scheduling

Each participant is registered for exactly one case and its case-specific decision
protocol. Its request contains only that case's identity, available aliases, prior
read metadata and latest six page contents. Disclosure, UTF-8 trimming, pagination
and citation checks follow the [single-agent adapter](coordination-single-agent.md).
No worker receives another worker's source, pages, messages, output or grading
feedback. The strict forced Responses function `case_investigation_decision` has a
single-case schema; the host also rejects out-of-scope actions independently.

The fixed scheduler gives every active worker one decision per round, running the
two calls concurrently and waiting for both before the next round. With one call
opportunity left and both workers active, round parity selects one from the sorted
case IDs. Once a worker completes or fails, the remaining worker can use every
remaining shared opportunity. There is no private 12- or 30-call ceiling.
Round membership and the remaining allowance are retained before dispatch.
An operation rejected before runtime reservation does not consume the call pool.
Its worker still stops explicitly; a surviving worker keeps that opportunity.

Both workers together have the same common ceiling: 60 durable model-call
reservations, a 20-minute whole-trial deadline, 64,000 input bytes and 2,048 output
tokens per request. The shared runtime allows two simultaneous calls and at most
100 registered evidence reads. Unknown and known-not-dispatched reservations still
consume opportunities. Scheduling and aggregation make no additional model call.
Waiting for the slower worker at each round is an explicit scheduling tradeoff,
included in measured elapsed time; this is not a free-running work-stealing policy.

Only captured aliases are readable. There is no shell, network, edit, fresh
consumer execution, permission or delegation action. These are separate contexts
on one trusted host, not a hostile-process sandbox or tenant security boundary.

## Output and failure behavior

Each worker selects its complete final dossier, declaring `ANSWERED` or `ABSTAINED`
with a summary and cited observation/hypothesis/limitation findings. Answers require
complete baseline-source and execution page coverage, a cited summary and at least
one finding. Every citation must reference that worker's previously read pages.
These access and shape checks do not establish semantic support or completeness.

Aggregation preserves both dossiers verbatim with input bindings and selection
lineage. There is no best-attempt selection, generated summary or conflict
resolution. Missing answers are `NOT_PROVIDED`; exhaustion and operational failure
do not manufacture successful abstentions. A failed worker does not discard a
completed sibling dossier. Worker dispositions and stop reasons remain visible
beside the whole-trial disposition. Uncertain usage produces an unresolved remote
outcome. Final case records export `review: null` pending separately bound review.

## Register, execute and inspect

After the README build, configure `OPENAI_API_KEY` in the process environment or
ignored `.env.local`, and reuse an existing captured input bundle:

```powershell
./scripts/Run-CoordinationIndependentWorkers.ps1 -Action policy
./scripts/Run-CoordinationIndependentWorkers.ps1 -InputBundleId inputs-EXISTING-UUID
```

The second command creates a bounded live **adapter check** with `gpt-5.6-terra`.
Its other-arm and rubric identities are explicit unrun placeholders. It is not
the paired three-repetition campaign, semantic acceptance or a routing decision.
A new check creates a new retained trial and cannot replace earlier outcomes.
The script neither recaptures inputs nor starts Docker.

For a frozen comparison plan, bind the returned actual implementation identity
to `INDEPENDENT_WORKERS`, retaining the exact common model, inputs and limits:

```powershell
$trial = ./scripts/Run-CoordinationIndependentWorkers.ps1 -Action register `
  -InputBundleId inputs-EXISTING-UUID -PlanPath plan.json `
  -WorkloadId sufficient-evidence-bundle -Repetition 1
./scripts/Run-CoordinationIndependentWorkers.ps1 -Action run -TrialId $trial
./scripts/Run-CoordinationIndependentWorkers.ps1 -Action read -TrialId $trial
```

`policy`, `register` and `read` do not load credentials. Execution loads the existing
credential without printing it and restores the previous process environment.
The CLI prints only status, usage and report location. Evidence and model claims
remain in the local retained state for inspection.

## Durability and authority

Ignored `.workbench/coordination-independent-workers/trial-UUID/` retains the
registration, round schedule, exact per-worker requests, decisions/pages and event
receipts, selected dossiers, terminal inventory, result and final receipt. The
policy binds actual compiled code, Gson/JDK identities, prompt/schemas, tool and
context rules, scheduling and final selection. Unfinished trials refuse a changed
implementation; completed historical inspection remains available.

One controller lease owns the trial. Both tasks are drained before ownership is
released, including after a local task error. Resume rebuilds each case's context
in the original rounds, verifies requests and sealed events, and reuses completed
broker responses for an interrupted unsealed action. Sealed events are read without
new model calls, reads or admission renewal. `ACTIVE` retains the existing trial
for resume; unmapped registrations are never guessed or repeated.

If event publication stopped before its receipt, recovery revalidates the action
through the runtime. A changed event or an admission change that prevents exact
recovery fails closed. Expiry/revocation cannot be reset. A matching final adapter
result missing its own receipt can be finalized without model work. Incomplete
underlying runtime journals or receipts are not repaired or bypassed by this
adapter. Hash-bound final inventories reject changed or extra artifacts.

These checks detect local inconsistencies; they are not signed attestations or
distributed durable storage. State can contain sensitive captured content and
generated claims. Keep it on a trusted filesystem and outside Git. Operational
completion establishes no general provider certification, semantic acceptance or
benefit from coordination.

## Recorded adapter check — 2026-10-04

One `gpt-5.6-terra` check reused captured bundle
`inputs-6e4dfed9-be1a-479d-aa15-b40073041353` under the common ceilings.
Trial `trial-e414dbf5-9ce6-4b40-b850-8005a416272c` completed in 31,348 ms,
with 13 reserved/registered/completed calls, 45,550 recorded input tokens, 1,676
output tokens, 11 evidence reads, zero new case executions and no unresolved
usage slots. Both dossiers declared `ANSWERED` and retained `review: null`.
The first-round call intervals overlapped.

Both workers read their complete baseline-source and execution artifacts plus
expectations. The customer worker read only the first page of the historical
reference source; the subscription worker read its description and only the first
fixture page. Other available artifacts were not read. This does not establish
complete diagnosis, semantic acceptance or a benefit over another arm. No paired
campaign ran. Fresh-process inspection and resume without a process credential
left all 163 non-lock files across inputs, runtime and adapter unchanged.

Result SHA-256:
`f1a9598b4e43fcf1cffc8d13560ffa7c310030e6f1cf58860dd7ff5a65f39184`.
Customer dossier SHA-256:
`7ec8d39ae6cca021b5b0ca42f5b1cf2146f2ec0871a926933d8db4660183bed7`.
Subscription dossier SHA-256:
`be092f9222ecf974946cfaac8d2f81a3ffe8b38d51ea44b5d8920e9e4f08897d`.

A second check after unreserved-slot accounting was tightened retained trial
`trial-2825933b-0422-4c2c-a51e-44260d0b9060` against the same inputs and ceilings.
It completed in 28,642 ms with 11 reserved/registered/completed calls, 34,234 input
tokens, 1,544 output tokens, nine evidence reads, zero new case executions and no
unresolved usage. Its first-round calls overlapped, both dossiers were `ANSWERED`
with `review: null`, and credential-free fresh-process resume left all 143 non-lock
files unchanged. Both workers read complete baseline-source, execution and
expectations; the customer also read its case index. Other available inputs were
not read. Both operational checks remain retained; neither is a campaign trial
or a quality comparison.

Second result SHA-256:
`dc4f8408ae326ba8c55eb4dc6f34bd8da92b523b4038f5d58e13a35ed19bce6a`.
Customer dossier SHA-256:
`1fc47ed69586f5eda24f76be6d8508d44c0a350c36e1770e5d59a1b5d16e80d8`.
Subscription dossier SHA-256:
`b9283cdef6e5cb2eb50bd371965ea072866298a6d82ae268b25ad5140ae871c1`.
