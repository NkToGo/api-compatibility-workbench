# Frozen invoice campaign registration

`InvoiceCampaign` binds the four existing invoice comparison adapters to one
captured input bundle before new measured trials. It freezes the protected
contract, exact rubric bytes, compiled policies, limits and every ordered slot.
Registration reads no credential, dispatches no participant or consumer, and
creates no trial grant. The earlier operational pilots and their descriptive
review remain separate historical records.

## Select and freeze

Build with the README commands. Prepare a local selection file with paths relative
to a deliberately selected evidence root and independently retained identities:

```json
{
  "formatVersion": 1,
  "inputDirectory": "captured-invoice/inputs",
  "inputIdentity": { "sha256": "<expected-input-manifest-sha256>" },
  "contractDirectory": "protected-invoice-contract",
  "contractIdentity": { "sha256": "<expected-contract-inventory-sha256>" },
  "rubricArtifact": "invoice-review-rubric-v1.json",
  "rubricIdentity": { "sha256": "<expected-exact-rubric-sha256>" }
}
```

Use the unchanged [invoice rubric](evaluations/invoice-review-rubric-v1.json).
The contract must include `README.md`, `business-oracle.md` and `provenance.json`;
its identity is `CaseInputs.identity` of the sorted relative-path/SHA-256 map
over all non-lock files. The [review-input contract command](invoice-review-inputs.md)
can compute that identity for a deliberately selected trusted revision. A hash
alone authenticates neither evidence nor its producer.

```powershell
./scripts/Invoice-Campaign.ps1 -Action freeze `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/state `
  -SelectionPath /trusted/invoice-selection.json
./scripts/Invoice-Campaign.ps1 -Action read `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/state `
  -CampaignId invoicecampaign-EXISTING-UUID
```

Defaults are the repository as evidence root and ignored `.workbench/` as state.
Retain the returned campaign ID and receipt SHA-256 independently. Preserve the
selected original evidence, `invoice-campaigns/` and `coordination-comparisons/`
together. Selection paths stay relative in stored records. Linked paths, escaped
references, overlapping evidence/storage, duplicate JSON fields, invalid UTF-8,
oversized artifacts and unexpected publication files are refused. Individual
records are bounded at 1 MiB; source inventories also have bounded counts/bytes.

## Fixed comparison conditions

There is one joint webhook/reconciliation workload, three paired repetitions
and four arms: **12 planned trials and 12 joint case outputs**. Each repetition
contains all four arms, including any later failure, abstention or missing output.
All arms receive the same captured inventory and the six unchanged rubric
criteria. Protected contract/grades stay outside participant histories.

| Condition | Frozen value |
| --- | --- |
| Model | `gpt-5.6-terra` |
| Model opportunities per whole trial | 60 |
| Counted evidence reads per whole trial | 100 |
| Input bytes per model request | 64,000 |
| Output tokens per model response | 2,048 |
| Whole-trial deadline | 20 minutes, started by the first participant operation |
| Team nominal stage allocations | 24 investigations / 6 review / 24 follow-ups / 6 reconciliation |
| Team cumulative ceilings | 24 / 30 / 54 / 60, with unused capacity carried forward |

Each arm uses its actual compiled policy snapshot, including prompt/schema,
context, selection, participant scopes and model protocols. Snapshots must share
one implementation identity. The deterministic arm has no model permission and
uses zero calls; the common ceiling is not a requirement to spend calls.
Registration starts no trial deadline and exposes no paid-call operation.
Freeze a measured campaign against the final compiled implementation that will
execute it. Registration checks created during development remain checks; later
code changes require a new frozen ID before measured execution.

| Repetition | First | Second | Third | Fourth |
| --- | --- | --- | --- | --- |
| 1 | Deterministic | Single agent | Independent workers | Coordinated team |
| 2 | Single agent | Independent workers | Coordinated team | Deterministic |
| 3 | Independent workers | Coordinated team | Deterministic | Single agent |

This preserves the existing comparison protocol's three rotations. It is not a
fully counterbalanced design. The workload and implementations were already
inspected in pilots: this freezes subsequent measurements prospectively, without
turning them into held-out accuracy data. Runtime/provider conditions may vary;
later reporting must retain actual order, times, inclusive usage and review gaps.
Neither this registration nor source execution-coverage metadata establishes
finding quality, a team advantage, migration approval or Milestone 4 acceptance.

## Sealing, recovery and dispatch boundary

`frozen.json` contains the selection, original source-file inventory, all policies,
comparison definition, order and team allocation. `plan.json` is the exact plan
accepted by the existing comparison evaluator; `rubric.json` retains exact source
bytes. `registration.json` binds the separately registered comparison's plan and
registration bytes. A final create-only `receipt.json` seals these layers.

Publication uses an exclusive lease, flushed pending files and atomic moves.
An unfinished `.pending` file remains a failure for explicit inspection. After a
complete `frozen.json`, an interrupted registration can resume its existing ID:

```powershell
./scripts/Invoice-Campaign.ps1 -Action resume `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/state `
  -CampaignId invoicecampaign-EXISTING-UUID
```

Recovery reuses the unique exact orphan comparison or creates the first one if
none exists. Multiple matches or an incomplete/conflicting comparison are refused;
recovery does not choose a better retry or overwrite historical records. An
incomplete directory without a frozen record cannot resume without the original
explicit selection. Read/repeat registration checks source inventory and receipt
bindings without changing original evidence.

Historical inspection does not require current code to equal frozen code. Before
any future slot admission, a dispatcher must separately call `verifyCurrentPolicies`
(CLI action `verify-current`) and compare the receipt SHA-256 with its independently
retained expected value.
Changed compiled code or policy requires a separately identified campaign. This
registration command supplies no dispatcher or participant capability. These are
trusted-local-filesystem integrity checks, not signed protection against an
operator rewriting the entire store and all expected identities.

## Verification and remaining work

The default Java suite includes `InvoiceCampaignTest`, exercising sealed no-dispatch
replay, input/contract/rubric changes, resealed order/budget/criterion attacks,
orphan recovery, ambiguous/incomplete registration, strict parsing, separate-process
receipt recovery and the separate live-policy gate. The linked-evidence control executes where link creation is
permitted. Run the README verification commands for the full suite.

Step 4e4a implements registration. The [ordered invoice controller](invoice-campaign-execution.md)
now provides durable slot execution. The measured campaign, campaign-bound semantic
review and paired results reporting remain subsequent work. Existing pilots cannot fill these newly declared trial slots. No routing
promotion is authorized by registration or operational completion.
