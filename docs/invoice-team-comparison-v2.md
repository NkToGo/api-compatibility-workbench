# Durable full invoice team v2

Step 4e5p adds `InvoiceTeamComparisonV2` and the opt-in
`Run-InvoiceTeamV2.ps1` command. It executes original investigations, coordinator
review, selected fresh questions and final joint reconciliation under one
pre-registered runtime. It closes operational accounting and exports one
unreviewed `CoordinationTrial`; completion does not establish answer quality,
useful coordination, migration safety or release approval.

## Fixed membership and isolated histories

Registration declares all six participants before any stage runs: the two
original invoice roles, `invoice-coordinator`, both fixed fresh roles and
`invoice-final-reviewer`. Each has exactly its admitted protocol and invoice case.
The [final-review policy](invoice-reconciliation-v2-admission.md) binds their
contracts, public task, current runtime build, tools, contexts, lineage policies
and unchanged limits. Existing three- and five-member trials are not upgraded.

The runtime lives in `invoice-team-reconciliation-v2-trials`. Separate internal
modes of the existing journal engines store originals/review under
`invoice-team-reconciliation-v2-prefix-runs` and selected fresh work under
`invoice-team-reconciliation-v2-fresh-runs`. The full controller stores final-stage
records under `invoice-team-comparisons-v2`. The public prefix and follow-up
constructors, commands, policies and older directories retain their earlier scope.

Before final dispatch, the controller inspects and replays the sealed source
stages, verifies their actual runtime registration and owned read histories, and
binds their exact result through `source.json`. It reconstructs the unique accepted
coordinator finish, selected questions and fresh finishes through the
[pure final-review contract](invoice-reconciliation-v2-contract.md).
`evidence.json` preserves all original/fresh outcomes and claims, selected questions,
question bindings, readiness, source identities and unresolved possible conflicts.
An unavailable selection remains distinct from a known empty selection.

An empty or unavailable selection dispatches no fresh worker. The final reviewer
can still inspect the available history and abstain. A later answer never replaces
an earlier failure or unknown outcome. Possible conflicts remain `UNRESOLVED`.

## Owned final review and inclusive limits

The final reviewer receives its own counted bootstrap and bounded captured reads.
Earlier participants' `read-N` labels are lineage, not its direct citations or
readiness. The exact final contract validates its context and advisory finish.
Unsupported answers retain rejected candidates and owned readiness without
exporting an accepted dossier. Malformed actions cannot expand the tool set.

Every stage shares the original ceiling of 60 model calls, 100 reads, two calls in
flight, a 20-minute deadline, 64,000 UTF-8 input bytes and 2,048 output tokens per
call. Lower declared limits still apply. The cumulative 24/6/24/6 allocation carries
unused earlier opportunities forward. The final stage uses the actual remaining
pool; it has no separate allowance, renewed deadline or free finish. Unexpected
model reservations cannot be omitted from accounting. No new consumer execution
is dispatched by this adapter.

## Seals, recovery and comparison output

Each final turn binds the source/evidence/spec identities and retains its exact
request, decision or stop, owned read, reservation flag and receipt. The shared
journal engine verifies these against actual runtime reads and call reservations.
Completed responses recover without another dispatch. Interrupted registrations
stay charged and uncertain; unknown remote outcomes are not silently retried.
An occupied controller or model lane reports `ACTIVE` for the existing trial.

`reconciliation.json` and its receipt seal the final outcome, owned pages, accepted
structural finish, event inventory and historical runtime inventory. Inspection
replays sealed events and reconstructs lineage and accounting. Missing source
markers or published stage seals are rejected before participant recovery;
completed inspection cannot repair them by issuing operations or recreating seals.
Incomplete publication can resume after its exact stored values are validated.

`terminal.json` freezes the aggregate operational status and adapter inventory
before runtime closure. Earlier unknowns take precedence over cancellation and
failure; all stages must complete for an operational `COMPLETED` status. Abstention
is a recorded model response, distinct from a failed or unknown worker. Runtime
closure retains inclusive usage and elapsed time under the original trial.

A validated final finish produces `dossier.json` with its full lineage, owned
readiness and reference identities. An absent or rejected finish exports
`NOT_PROVIDED`; there is no fallback to an original or fresh answer. A partial
dossier may coexist with a failed or unknown aggregate trial. The comparison
record binds one case output, the exact dossier and terminal runtime trace,
declared model/implementation/slot, inclusive resources and stage reservations.
Quality review remains null/`NOT_PERFORMED`. No automatic review or routing
promotion follows operational completion.

Final inspection and recovery of completed publication need no credential or new
participant operation, including after expiry. Hashes and receipts provide
trusted-host integrity checks, not signatures against a host rewriting all files.

## Run and validate

Build with the README preparation and Java verification commands. Policy is
credential-free and dispatches nothing:

```powershell
./scripts/Run-InvoiceTeamV2.ps1 -Action policy
```

`-Action register` takes `InputDirectory`, an independently retained `InputIdentity`,
`PlanPath`, `WorkloadId` and `Repetition`. `run` and `read` take the captured input
and `TrialId`; `-StateDirectory` selects retained state. `check` explicitly registers
a new operational check. Unfinished `check`/`run` can make provider calls using the
existing `OPENAI_API_KEY` configuration. Registration, policy and completed
inspection do not resolve a credential. A check is not a measured campaign.

Offline scripted controls cover fixed membership, owned final contexts, cumulative
budgets, unavailable/empty selection, uncertainty, rejected finishes, interruption,
lifecycle stops, source/event tampering and publication recovery. Authored model
outputs establish controller behavior, not live-model or semantic quality. A
positive answer control uses separately identified authored coverage metadata and
actual owned fresh reads while preserving original uncertainty; it is not newly
observed consumer execution.
The original model, ceilings, baselines, six criteria and historical records remain
unchanged. The [deterministic v2 adapter](invoice-diagnostics-v2.md) is separately
implemented. Common-condition registration and measurement remain separate work;
[Milestone 4 acceptance](milestone-4-status.md) remains open.
