# Frozen coordination campaign registration

The registration command binds the four implemented comparison arms to one
captured two-case input bundle before measurement. It freezes the exact rubric,
compiled policies, common limits and run order, then registers that definition
with the existing offline comparison evaluator. It reads no credential and makes
no model, provider or consumer-execution call. It creates no trial admission grant.

## Freeze and inspect

Use the normal README build and an existing bundle from
[captured input registration](coordination-runtime.md). Then run:

```powershell
./scripts/Coordination-Campaign.ps1 -InputBundleId inputs-EXISTING-UUID
```

The command prints a campaign ID, comparison ID, plan/order paths and receipt hash.
Inspect it with:

```powershell
./scripts/Coordination-Campaign.ps1 -Action read -CampaignId campaign-EXISTING-UUID
```

The returned `REGISTRATION_VALIDATED` status concerns metadata and input bindings.
It does not establish execution completion, semantic acceptance, compatibility or
coordination benefit. Repeating freeze with an existing campaign ID verifies and
reuses the registration; it does not create a replacement experiment.

State defaults to ignored `.workbench/`. Campaigns live under
`coordination-campaigns/campaign-UUID/`; comparison definitions live in the existing
`coordination-comparisons/comparison-UUID/` store. Preserve both stores and the
referenced `coordination-inputs/` bundle together.

## Fixed conditions

The definition declares `gpt-5.6-terra`, three repetitions and one two-case workload:
**twelve bundle trials and twenty-four case outputs**. All arms have the same
60-call ceiling, 64,000 input bytes per request, 2,048 output tokens per response
and 20-minute whole-trial deadline. Registration does not start that deadline.
The deterministic arm has no model permission; its actual model use is zero.

Each arm snapshot comes from its real compiled `policy` command. The snapshot
contains implementation, prompt/schema, tool scope, context policy, output-selection
and participant bindings. The shared compiled-code/library/JDK identity must match
across all four snapshots. Team allocation is recorded as nominal 24/6/24/6 calls
and cumulative ceilings 24/30/54/60, with unused capacity carried forward.

The [versioned rubric](evaluations/coordination-rubric-v1.json) contains the six
previously declared case criteria and complete material-claim review requirements.
Its exact bytes are bound to the definition and copied into the campaign. Grading
requirements and judgments remain outside participant histories; applications may
expose their own expectations as case evidence. A later review must cover final
dossiers and earlier claims they endorse, preserving unsupported and unresolved
claims rather than treating citations or agent agreement as acceptance.

The order is fixed before dispatch:

| Repetition | First | Second | Third | Fourth |
| --- | --- | --- | --- | --- |
| 1 | Deterministic | Single agent | Independent workers | Coordinated team |
| 2 | Single agent | Independent workers | Coordinated team | Deterministic |
| 3 | Independent workers | Coordinated team | Deterministic | Single agent |

Each repetition is one paired block. Three rotations of four arms are not a fully
counterbalanced design, and two known cases are not held-out accuracy data. Runtime
and provider conditions can vary; report the actual order, timings and known token
use. Tokens alone are not an invoice or a causal speedup estimate.

## Sealing and interruption handling

`frozen.json` binds nine snapshots: the plan, rubric, order, input manifest/receipt
and four policies. `registration.json` binds the comparison ID and its exact stored
plan/registration bytes. A final create-only `receipt.json` seals both layers.
Atomic publication, an exclusive owner lease, bounded reads and linked-path checks
prevent partial files from appearing as validated registrations. This is a trusted
local filesystem boundary, not a signed record against an operator rewriting a store.

If interruption occurs after freezing but before the terminal receipt, resume the
existing ID:

```powershell
./scripts/Coordination-Campaign.ps1 -CampaignId campaign-EXISTING-UUID
```

Recovery checks the frozen conditions, then searches for the comparison with that
unique experiment ID. One complete matching comparison is reused. Conflicting,
incomplete or multiple matches are refused rather than selecting a retry. A missing
mapping or terminal receipt can be completed without replacing the comparison.
An interruption before `frozen.json` exists leaves an incomplete directory that
cannot be resumed as a valid campaign; no participant has been dispatched.

Changed snapshot bytes, input content, scopes, model, limits, criterion/input
bindings, duplicate/missing ordered slots, escaped references and unexpected files
are rejected. Historical snapshots remain retained. Inspection does not require
the current compiled policies to equal the saved policies; before actual execution,
the dispatcher must compare them and refuse changed code or policy. Such a change
requires a new campaign rather than silently changing the registered experiment.

## Validation and next steps

Run `./scripts/Test-CoordinationCampaign.ps1` after building. Synthetic registration
fixtures exercise the real Java input/definition validation, sealed repeat reads,
negative integrity/denominator/budget controls, orphan comparison recovery,
ambiguous recovery and linked-state rejection when the host permits link creation.
The check creates no trial grant and makes no paid API call. Both CI platforms run it.

Step 4c1 implements registration. The [step 4c2 controller](coordination-campaign-execution.md)
executes fresh slots with durable mappings and inclusive accounting; step 4c3 will bind complete semantic
reviews and retain the paired report. Execution and review records belong outside
the immutable registration directory. Earlier operational checks cannot substitute
for these trials. Step 4d routing remains contingent on the measured evidence.
