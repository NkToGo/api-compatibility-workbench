# Independent invoice workers

Two independent model contexts investigate the webhook and reconciliation paths
in the same captured [invoice bundle](invoice-comparison-inputs.md). This supplies
the `INDEPENDENT_WORKERS` comparison arm. A fixed host scheduler runs their calls
concurrently in rounds; ordinary code retains their dossiers verbatim. There is
no coordinator model, exchange of generated findings or model-written aggregation.
The coordinated arm, joint semantic review and paired campaign remain separate.

## Roles, common inputs and permissions

The two fixed participants are `invoice-reconciliation` and `invoice-webhook`.
Each has its own role-specific invoice protocol, prompt, read history, requests,
decisions, citations and final dossier. Each context receives only its own pages
and read metadata. The role is selected by the host and bound in the registered
policy; model arguments cannot choose another participant or protocol.

Both workers can read the same complete alias inventory as the
[single-context investigator](invoice-single-agent.md) and
[deterministic baseline](invoice-diagnostics.md). A role is an investigation focus,
not an exclusive partition of the provider evidence. Either worker may read the
other consumer's captured source or observations when useful. Neither receives
its sibling's messages, read history, generated claims, dossier or grading feedback.
These are isolated contexts on one trusted host, not a tenant/process sandbox.

Both role protocols reuse the existing strict invoice decision schema and forced
`invoice_investigation_decision` function. They permit only catalog, text-page and
finish actions. The [read-only runtime factory](invoice-comparison-runtime.md)
still rejects model-enabled policies. The explicit invoice investigation factory
accepts the joint protocol or one fixed role protocol per participant, subject to
the registered grant. Sibling or historical protocols cannot be selected by a call.
No shell, network tool, source edits, fresh consumer execution, delegation,
cross-worker messaging or permission changes are exposed.

## Inclusive budgets and scheduling

The check uses the same aggregate ceilings as the other invoice arms: 60 durable
model-call reservations, a 20-minute whole-trial deadline, 100 shared evidence
reads, 64,000 UTF-8 input bytes and 2,048 output tokens per call. Up to two calls
may overlap. These limits apply to both workers together, not separately to each.

Each worker's initial metadata read costs one shared read. Every selected catalog
or text page costs another, with the same 40-alias and 4,000-UTF-16-character page
limits. Exact retries reuse stable operations. Context includes all of that
worker's read metadata and its latest six page contents, trimmed oldest first
to the byte ceiling. There are no uncounted model summaries or aggregation calls.

Every active worker receives one call opportunity per round; both calls finish
before the next round starts. With a single opportunity left and both workers
active, round parity chooses from the fixed sorted worker IDs. After one worker
completes or fails, its sibling can use every remaining opportunity. There is no
private 30-call ceiling. A context rejected before reservation does not consume
an opportunity. Unknown and known-not-dispatched reservations still consume one.
Round membership and remaining allowance are retained before dispatch. Waiting
for the slower worker is an explicit scheduling tradeoff included in elapsed time.

## Advisory outputs and joint disposition

Every finding must cite text pages actually read by its own worker. A catalog
entry or a page read only by a sibling is not a valid citation. `ANSWERED` requires
a cited summary, at least one finding, complete task/configuration and own baseline
consumer source reads, a complete business artifact for that role, one complete
order artifact and complete source execution coverage. `ABSTAINED` may retain
partial cited findings. These are shape/access checks, not semantic support,
exhaustive case coverage or validation of the claimed explanation.

Each completed worker dossier is retained unchanged with its exact bundle,
role and selected-decision binding. The joint dossier embeds both verbatim
outputs, hashes, worker dispositions and stop reasons. It does not choose a
better worker, reconcile conflicting statements or replace earlier claims.

| Worker outcomes | Joint response |
| --- | --- |
| Both complete with `ANSWERED` | `ANSWERED` |
| Both complete; at least one declares `ABSTAINED` | `ABSTAINED`, retaining both dossiers |
| Any worker output missing after failure, exhaustion or interruption | `NOT_PROVIDED`, retaining available sibling output and missing-work state |

The exported comparison has one joint invoice `CaseOutput`. A `NOT_PROVIDED`
output has no accepted output identity; partial dossiers remain hash-bound in
the artifact inventory and joint record for inspection. Uncertain remote usage
remains `UNKNOWN_REMOTE_OUTCOME`. Cancellation and expiry do not become successful
abstention. Every semantic `review` is null. Operational completion establishes
no migration approval, semantic acceptance, routing rule or multi-agent benefit.

## Run and inspect

After the README preparation/build commands, use an existing invoice bundle and
the identity retained independently by the trusted capture caller. Execution uses
the existing process `OPENAI_API_KEY` or ignored `.env.local` without printing it.

```powershell
./scripts/Run-InvoiceIndependentWorkers.ps1 -Action policy
./scripts/Run-InvoiceIndependentWorkers.ps1 `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

The default `check` creates a bounded live adapter check using `gpt-5.6-terra` and
the common limits. Other arms and the rubric have explicit unrun/unreviewed
placeholder identities. This is not the separately frozen paired campaign;
another check creates a new trial and cannot replace a less favorable outcome.
The script does not recapture source evidence or start Docker.

For a separately frozen plan binding the actual independent-worker policy:

```powershell
$trial = ./scripts/Run-InvoiceIndependentWorkers.ps1 -Action register `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -PlanPath plan.json -WorkloadId invoice-joint -Repetition 1
./scripts/Run-InvoiceIndependentWorkers.ps1 -Action run -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceIndependentWorkers.ps1 -Action read -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

Policy, registration and read-only inspection do not load credentials. Completed
run inspection performs no new participant work and needs no credential. Console
output contains status/usage/path metadata, not captured contents or model claims.
A zero command exit alone is not semantic acceptance; inspect retained status,
worker stop reasons and usage uncertainty.

## Recovery and local integrity

Ignored `.workbench/invoice-independent-workers/trial-UUID/` stores the bound
registration, per-worker metadata, rounds, requests, events and receipts, selected
dossiers, joint dossier and terminal result. The compiled application/library/JDK,
prompts, strict schema, common tools, context, scheduler and aggregation rules
are bound before work. Changed implementations cannot resume unfinished work.

One controller lease owns the trial; both tasks are drained before release,
including after a local task error. Resume rebuilds contexts through the original
rounds, checking retained requests and event seals. Sealed events need no new
participant operation. Interrupted unsealed actions reuse completed broker results
and stable read IDs, rather than redispatching. Unmapped call registrations remain
uncertain. The original whole-trial clock cannot be renewed by resume or a worker.

Final inventories bind both successful and incomplete evidence. A missing adapter
receipt can be restored only after the original result matches retained artifacts
and the completed runtime trace. Missing shared runtime receipts, changed/extra
files or inconsistent publication fail closed. `ACTIVE` means resume the existing
trial. These are local trusted-host integrity controls, not signed attestations
or distributed persistence. Retained pages and claims may be sensitive; keep the
state on a trusted filesystem outside Git. No private model reasoning is retained.

Offline tests cover actual call overlap, context isolation, verbatim aggregation,
shared and odd budgets, unreserved opportunities, role access floors, citation and
protocol restrictions, invalid actions, partial/unknown outcomes, expiry/revocation,
sealed restart recovery, integrity failures, exclusive ownership and credential-free
fresh-process inspection. Loopback broker tests cover both role protocols and
wrong-function rejection. Full Java verification remains the README command.

## Recorded adapter check — 2026-10-21

One `gpt-5.6-terra` check reused the original invoice bundle with identity
`f33c31738c753c6af710f7b0797f4bef3a3e0911c74eb211f8068759adca7295`.
Trial `trial-2ac92ace-a802-4195-84d2-3e9e63dde051` completed in 74,660 ms with
42 reserved/registered/completed calls, 474,898 input tokens, 6,171 output tokens,
42 evidence reads, zero new consumer executions and no unresolved usage slots.
Both workers declared `ANSWERED`; their first-round call intervals overlapped.
The joint record retains `review: null`.

The reconciliation context read nine distinct text aliases, including its baseline
consumer and renewal policy, two business observations and three arrival orders.
The webhook context read eight, including both baseline consumer sources, one
business observation and three arrival orders. Both read task/configuration.
These are selected subsets of the 444-artifact inventory, not exhaustive analysis.
Common captured evidence being read by both contexts is distinct from exchanging
their generated findings. Neither worker received the other's dossier.
Fresh-JVM inspection and completed-run resume without a process API key preserved
all 903 non-lock files across the original bundle, runtime and worker stores.

Result SHA-256:
`0d61a4e22dc9ed67e47292377fc38be02274e471d30468131354c846c74ddce1`.
Joint dossier SHA-256:
`9552aed35c602680df98cc3ef527694a6ac152487821c0bcf433989e7bf648f2`.
This operational check supplies no paired quality result or coordination benefit.
