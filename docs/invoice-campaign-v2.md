# Frozen invoice v2 comparison conditions

`InvoiceCampaignV2` registers one common condition for the four implemented v2
invoice adapters. It binds captured inputs, protected contract, unchanged rubric,
current public task, seven decision contracts, exact compiled policies, budgets
and every ordered trial slot before measurement. Registration creates comparison
metadata only: no participant dispatch, credential lookup or trial permission.

The [earlier campaign](invoice-campaign.md), its controller and historical results
are unchanged. V2 registrations have separate IDs and storage. Operational
registration checks are not measured participant trials or quality evidence.

## Common task and implementations

All four arms use the same captured inventory and the current
[v2 public requirements](invoice-v2-contract.md). `frozen.json` retains the public
task's exact UTF-8 text and SHA-256 separately from the original captured `task`
artifact. The latter remains untrusted evidence with its original identity.
The task contains no expected fixture values or grading decisions. Protected
contract, rubric and grades stay outside participant histories.

| Arm | Frozen implementation |
| --- | --- |
| Deterministic | [Unchanged fixed rules with v2 binding](invoice-diagnostics-v2.md) |
| Single agent | [One v2 joint context](invoice-single-agent-v2.md) |
| Independent workers | [Separate v2 role contexts and verbatim aggregation](invoice-independent-workers-v2.md) |
| Coordinated team | [Full v2 original/review/fresh/final workflow](invoice-team-comparison-v2.md) |

Snapshots include prompts/contracts, tools, context, selection, exact participants
and protocol scopes. All four must share one compiled adapter identity, which
binds application bytes, the JSON runtime library and Java runtime version.
Inspection checks the static v2 contracts and role/tool policies while allowing
historical build identities. The separate current-policy gate also requires the
currently running build and an independently retained campaign receipt identity.

The seven contract identities cover joint, webhook and reconciliation
investigation; coordinator review; both fresh roles; and final reconciliation.
They supplement the full policy snapshots rather than replacing build identity.
Changing task, contracts, policy, role membership or tools is not an accepted
mutation of a sealed condition.

## Unchanged denominator and limits

The model is `gpt-5.6-terra`. Each whole trial has at most 60 model opportunities,
100 counted evidence reads and a persistent 20-minute deadline, started by its
first participant operation. Requests are limited to 64,000 input bytes and
2,048 output tokens. Adaptive policies permit at most two concurrent calls;
the deterministic arm has no model protocol and consumes zero model calls.
The team retains nominal 24/6/24/6 stages, cumulative ceilings 24/30/54/60 and
forward carry of unused capacity. Registration starts no trial deadline.

One joint workload, three repetitions and four arms produce **12 planned trials
and 12 joint case outputs**. The existing rotations remain:

| Repetition | First | Second | Third | Fourth |
| --- | --- | --- | --- | --- |
| 1 | Deterministic | Single agent | Independent workers | Coordinated team |
| 2 | Single agent | Independent workers | Coordinated team | Deterministic |
| 3 | Independent workers | Coordinated team | Deterministic | Single agent |

The [six-criterion rubric](evaluations/invoice-review-rubric-v1.json) must retain
its exact original bytes, SHA-256
`9f89e515bcea47606e76573874304d2fda2fbcbd522e2283e7b520d4b4e960ab`.
A changed rubric is refused even if the caller supplies its new hash.
Failures, abstentions, missing outputs and uncertain usage remain in their
original slots. Later measurement and claim review are separate operations.

## Select, freeze and inspect

Build using the README commands. The selection has the same version 1 evidence
shape as [the original registration](invoice-campaign.md#select-and-freeze):
relative input directory and identity, protected contract directory and identity,
and rubric artifact and exact identity. These expectations must be retained
independently by the trusted capture/selection caller. V2 condition and receipt
records use format version 2; the generic comparison plan retains version 1.

```powershell
./scripts/Invoice-CampaignV2.ps1 -Action freeze `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -SelectionPath invoice-selection.json
./scripts/Invoice-CampaignV2.ps1 -Action read `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -CampaignId invoicecampaign-v2-EXISTING-UUID
./scripts/Invoice-CampaignV2.ps1 -Action verify-current `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 INDEPENDENTLY-RETAINED-RECEIPT-SHA256
```

Retain the returned ID and receipt identity independently. The default storage is
ignored `.workbench/invoice-campaigns-v2/invoicecampaign-v2-UUID/`; a custom state
root contains the same registration and `coordination-comparisons/` layout. Selection paths remain
relative. Preserve original evidence and the exact compiled runtime with state.
The existing v1 execution driver rejects v2 IDs; ordered v2 execution is separate
remaining work. A final measured condition must match the build used for its
execution. A later build change requires a new frozen ID before new work.

## Integrity, recovery and limitations

Create-only `frozen.json`, `plan.json`, exact `rubric.json`, comparison mapping and
receipt bind every layer. `resume` takes the same ID without a selection file and
can finish a complete frozen record's interrupted publication. It reuses the
unique exact orphan comparison; ambiguous or incomplete matches are refused.
An active owner lease, pending/unexpected artifact, linked/escaped/overlapping
path, altered source inventory, duplicate JSON field, invalid UTF-8 or oversized
artifact fails closed. Inspection does not rewrite source evidence. Metadata
output contains identities, counts and status rather than consumer payloads.

Hashes provide integrity checks on a trusted local store, not signatures against
an operator rewriting every file and expected identity. Known-case reuse, small
sample size, three rotations without full counterbalancing and varying provider
conditions remain limitations. V1 and v2 differ in task and protocol; comparison
between conditions cannot isolate one causal improvement. Neither registration
nor operational completion establishes finding quality, useful coordination,
migration approval or [Milestone 4 acceptance](milestone-4-status.md).

Offline tests cover sealed replay without dispatch, task/contract/policy/role
binding, exact rubric bytes, input and protected-source changes, all slots and
limits, current-build and independent-receipt gates, orphan/publication recovery,
fresh-process inspection, strict parsing and namespace separation. The full Java
verification remains the README command. Original records keep their original
meaning; revised measurement and semantic review remain pending.
