# Independent invoice workers v2

Step 4e5g connects two separate invoice-role contexts to the
[v2 runtime](invoice-v2-admission.md), [owned-read context](invoice-v2-contract.md),
prefix catalogue and structural finish gate. The webhook and reconciliation
workers inspect the same captured inventory under one shared budget. Their
outputs are retained verbatim, without a coordinator or model-written summary.
The [v1 adapter](invoice-independent-workers.md) and its original measurements
remain separate and unchanged.

## Registration and scope

`InvoiceIndependentWorkersV2` stores adapter records under
`invoice-independent-workers-v2/trial-UUID`, bound to the exact specification in
`invoice-v2-trials/trial-UUID`. Version 2 registration binds the captured input,
comparison definition/slot, policy, runtime build and revised public-task identity.
It accepts only the `INDEPENDENT_WORKERS` arm and the matching opened input.

Fixed participants `invoice-reconciliation` and `invoice-webhook` receive their
respective `invoice-reconciliation-worker-v2` and
`invoice-webhook-worker-v2` contracts. Both can inspect the full common
inventory. Role scope determines the public investigation focus and required
source/business reads; it does not hide the other consumer's captured evidence.
Each context contains only its own runtime-admitted describe/read wrappers,
readiness, citable text IDs, read metadata and up to six recent page contents.
Sibling histories, generated claims and protected grading material are absent.
No shell, edits, fresh consumer execution, delegation or messaging is granted.

## Shared scheduling and retained decisions

The check definition retains `gpt-5.6-terra` and the existing aggregate ceilings:
60 model opportunities, 100 reads, twenty minutes, 64,000 input bytes and 2,048
output tokens per request, with at most two concurrent calls. A supplied definition
may lower these ceilings. Each worker's describe costs a shared read; catalogue,
prefix and text reads use that same pool. A finish consumes a model opportunity.

One opportunity is scheduled for each active worker in each fair round. With two
active workers and one opportunity remaining, round parity selects one from the
fixed sorted membership. A completed or failed worker releases future opportunities
to its sibling; neither has a private thirty-call allocation. Pre-reservation
context failure consumes no model opportunity. Known-not-dispatched and uncertain
reservations still count. Both tasks are drained before ownership is released.

Every round records its membership, active-participant count and remaining shared
allowance before dispatch. Each model context receives that same snapshot, including
last-round or unassigned partial-round information. It never promises a future
turn or grants an uncounted finalization call. Recovery reconstructs the original
rounds and requests rather than extending the deadline or recalculating a fresh pool.

Only owned text pages support citations. Prefix/flat catalogue metadata cannot.
`ANSWERED` requires complete task/configuration and own baseline-source reads,
one complete role-appropriate business artifact, one complete order artifact,
captured source execution coverage and cited summary/findings. `ABSTAINED` may
preserve partial cited findings. These checks establish structural access only.
Rejected finishes retain the authored candidate and readiness; invalid decisions
and reads retain explicit rejection events. They trigger no hidden retry.

## Outputs, recovery and limits

Each accepted structural dossier records role, protocol, public task, input,
readiness and selected decision. The joint record preserves both dossiers and
hashes verbatim. Both `ANSWERED` yields `ANSWERED`; any completed abstention yields
`ABSTAINED`; a missing worker output yields `NOT_PROVIDED`, retaining the available
sibling output. Operational failure, cancellation and uncertain usage remain
distinct from the joint response. Semantic reviews remain null/`NOT_PERFORMED`.

The controller lease, event receipts, runtime reservations, create-only artifacts
and final inventory support exact restart and interrupted publication recovery.
Completed calls and sealed events are not redispatched. Changed wrappers,
requests, membership, source inputs or selected artifacts fail validation.
These are integrity controls on one trusted host, not tenant isolation or signed
attestations. Retained evidence and model prose belong outside Git.

After the README preparation/build commands:

```powershell
./scripts/Run-InvoiceIndependentWorkersV2.ps1 -Action policy
./scripts/Run-InvoiceIndependentWorkersV2.ps1 -Action check `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

`check` creates and runs a new live model trial; it is not an offline test or a
paired campaign. `register` accepts `-PlanPath`, `-WorkloadId` and `-Repetition`;
`run` and `read` accept the existing `-TrialId`. Every non-policy action also
requires the input directory and independently retained input identity. Policy,
registration and read-only inspection load no credential. The run wrapper uses
the existing process `OPENAI_API_KEY` or ignored `.env.local`, restoring the
previous process value afterward. Console output contains status, usage and paths.

Run the README verification commands. Offline controls exercise actual two-call
overlap, role/context isolation, prefix ownership, structural finish rejection,
odd/shared budgets, last-turn disclosure, partial and uncertain outcomes,
interrupted round recovery, mutation rejection and credential-free fresh-JVM
inspection. The real broker is also exercised against a localhost HTTP fixture
for both strict scoped v2 protocols, with no provider request.

This integration establishes no live-model finding quality, useful coordination,
campaign result or Milestone 4 acceptance. The remaining v2 adapters, common
condition registration, measurement and semantic review remain separate work.
