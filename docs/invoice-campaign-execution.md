# Ordered invoice campaign execution

`Run-InvoiceCampaign.ps1` executes a [frozen invoice campaign](invoice-campaign.md)
in its declared order. Each of the twelve slots receives one fresh registered
trial through its existing adapter. The controller retains operational outcomes,
inclusive resource accounting and unreviewed joint-case records. It neither grades
claims nor selects a preferred arm.

## Run, limit and inspect

Use the README build and an existing frozen registration. Supply the receipt
SHA-256 retained independently when that campaign was frozen:

```powershell
./scripts/Run-InvoiceCampaign.ps1 `
  -CampaignId invoicecampaign-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/state `
  -MaximumTrials 1
```

`MaximumTrials` limits newly completed or resumed slots in this invocation;
completed sealed slots do not consume it. The default is twelve. Repeating the
same campaign ID continues with the next unfinished slot; it does not repeat a
completed slot. An invocation without a bound may make paid model calls through
all remaining model arms. There is no retry-selection or replacement-trial option.

Inspection never dispatches participants:

```powershell
./scripts/Run-InvoiceCampaign.ps1 -Action read `
  -CampaignId invoicecampaign-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/state
```

Defaults are the repository as evidence root and ignored `.workbench/` as state.
The controller uses only allowlisted invoice adapters. Metadata operations and
diagnostics load no credential; model-arm execution uses the existing host-side
credential setup. The selected input directory and identity come from the frozen
registration, not from model output. Protected grading material remains outside
participant histories. All four policies, budgets and the original input bundle
remain unchanged.

## Registration and recovery

Execution metadata lives under `invoice-campaign-executions/invoicecampaign-UUID/`,
outside immutable registration. Preserve that directory, `invoice-campaigns/`,
`coordination-comparisons/`, selected source evidence, invoice runtime trials and
all arm stores together.

An exclusive controller lease prevents concurrent owners. Before new work the
controller verifies the independently expected campaign receipt and current
compiled policies. Its execution registration binds the driver, registration
wrapper and all four adapter wrappers. Changed dispatch code stops unfinished
work. Completed historical inspection uses retained bindings.

Each slot publishes an intent before trial registration and a mapping before
participant execution. A mapping binds the exact runtime specification/grant,
adapter specification/registration, input receipt, slot and policy. A newly
discovered registration must postdate its intent and must not have started before
mapping. A unique complete orphan can be reused after a lost registration
response; ambiguous, partial or unmapped-started registrations stop admission.

Mapped interrupted work resumes through the original adapter and deadline. A
completed adapter result is inspected rather than redispatched. No later slot
starts until the preceding outcome has a validated receipt. Missing derived seals
can be completed after validating the retained bytes; pending publications remain
explicit interruptions. Linked paths, unexpected/hidden files, duplicate JSON
fields, malformed UTF-8 and oversized records are rejected. These are trusted
local filesystem integrity controls, not signed protection against an operator
rewriting the whole store and its expected identities.

## Operational records and uncertainty

Each slot retains one joint invoice case output, operational status, original
start/deadline/finish times, resource issues, exact result/trace identities and
separate participant rows. Original workers, coordinator review, fresh follow-ups
and reconciliation are included in the same totals. Unknown reservations are
retained rather than counted as free calls. New consumer executions remain zero;
the deterministic arm has zero model use.

Partial inspection reports `PENDING`, sealed-trial totals, explicit unsealed
activity and the unchanged twelve-slot denominator. Unsealed activity may have
additional usage outside those totals. All twelve terminal outcomes produce
`records.json`, `result.json` and a final receipt. `FINISHED` means operational
records are sealed, including failed, cancelled, uncertain and abstained trials.
It does not mean twelve successful answers. Known tokens are not an invoice;
summed trial durations are not campaign wall time or a causal speedup estimate.

`qualityReview: NOT_PERFORMED` remains on every output. The measured campaign,
campaign-bound claim review and paired quality/resource report remain separate
work. Operational completion establishes no coordination benefit, routing
promotion, migration approval or Milestone 4 acceptance. Earlier pilots and
synthetic controller tests cannot fill measured slots.

## Verification

After building, run `./scripts/Test-InvoiceCampaignExecution.ps1`. It uses real
Java frozen registrations, policies and trial registration, with explicitly
simulated participant completion and retained reads. It exercises all twelve
slots, inclusive unknown usage, independently pinned receipts, strict parsing,
lost responses, immutable replay, original-clock recovery, publication gaps,
changed wrappers/driver, ambiguous registrations and concurrent ownership. Link
controls execute where the host permits link creation. These fixtures make no
API/provider or consumer-execution call and measure no model quality.

Both CI platforms run this suite and retain its synthetic journals with the case
reports. Real adapter execution still uses the unmodified adapter and its own
retained-evidence validators.
