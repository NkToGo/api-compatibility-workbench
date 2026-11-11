# Campaign-bound invoice v2 review inputs

`Prepare-InvoiceCampaignReviewV2.ps1` publishes a sealed inventory for all twelve
slots of a completed [v2 campaign](invoice-campaign-v2.md). It combines the
[transient v2 inspector](invoice-review-inputs-v2.md) with authenticated controller
reads, the independently expected campaign receipt and the original frozen order.
It creates no participant work or semantic judgments. Existing v1 packets and
commands remain unchanged.

## Prepare and inspect

Build using the README commands. Select a complete retained campaign, its original
execution runtime and a built inspection runtime containing `InvoiceReviewInputsV2`:

```powershell
./scripts/Prepare-InvoiceCampaignReviewV2.ps1 `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewEvidenceDirectory TRUSTED-COMMON-ROOT `
  -EvidenceDirectory TRUSTED-CAMPAIGN-EVIDENCE -StateDirectory TRUSTED-STATE `
  -RuntimeDirectory TRUSTED-CAMPAIGN-RUNTIME `
  -InspectionRuntimeDirectory TRUSTED-INSPECTOR-RUNTIME
```

The evidence and state directories must be separate descendants of the common
review root. All packet source references are relative to that root. The summary
returns a `reviewpacket-v2-UUID` and `packetSha256`; retain both independently.
It prints counts and identities, not model response text or customer payloads.

```powershell
./scripts/Prepare-InvoiceCampaignReviewV2.ps1 -Action read `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewPacketId reviewpacket-v2-EXISTING-UUID `
  -ExpectedPacketSha256 EXPECTED-PACKET-SHA256 `
  -ReviewEvidenceDirectory TRUSTED-COMMON-ROOT `
  -EvidenceDirectory TRUSTED-CAMPAIGN-EVIDENCE -StateDirectory TRUSTED-STATE `
  -RuntimeDirectory TRUSTED-CAMPAIGN-RUNTIME `
  -InspectionRuntimeDirectory TRUSTED-INSPECTOR-RUNTIME
```

Both runtime options default to `backend/target`. Each runtime has the backend JAR
and `dependency/` layout described by the controller. They serve different roles:

- `RuntimeDirectory` validates the original controller and retained participant
  histories, using the exact runtime already bound by execution registration.
  The publisher invokes only the controller's `read` action.
- `InspectionRuntimeDirectory` runs the transient inspector. Its relative JAR
  names and exact hashes are bound in the packet producer. A changed inspector
  or dependency is rejected before further inspection of an existing packet.
  An identical runtime copy at another path remains usable.

Retain both runtimes with the original stores. Rebuilding an inspector does not
replace or authorize work under the frozen campaign runtime. The command binds
binary identities but does not copy or download the binaries. Supplied runtime
paths are trusted host configuration, not assessed-repository or model input.

## Binding and interpretation

Only a sealed `FINISHED` controller result with twelve recorded slots, no missing
slots and `qualityReview: NOT_PERFORMED` is admitted. `FINISHED` includes failed,
cancelled and uncertain trials; it does not mean successful answers. Partial
campaigns are refused without creating a packet directory or starting another
slot. No credential is loaded and no model/provider call is made.

The publisher binds frozen registration, execution and slot receipts, comparison
metadata, original input/contract/rubric identities and the public-task identity.
Three derived selections inspect the four arms in each repetition. Every result
must match its sealed slot and adapter-result digest; rows are restored to the
original twelve-slot order, with no retry selection or denominator reduction.
Frozen timing is established from campaign membership and the frozen timestamp
preceding each retained trial start, rather than inferred from a selection file.

The combined inventory preserves all review units supplied by the v2 inspector,
including nested final decisions, original/fresh claims, conflicts, absent
answers, rejected finishes and uncertain broker outcomes. It references the
original files; it is not a self-contained copy of their contents. Sources and
the controller summary are checked again at completion.

`criteriaTiming: FROZEN_BEFORE_TRIALS` establishes the recorded chronology only.
`qualityReview` remains `NOT_PERFORMED` and `campaignAcceptance` remains
`NOT_ESTABLISHED`. Mechanical binding supplies no claim verdicts, accepted cases,
useful-coordination result, routing promotion or migration approval. V2 semantic
review integration and revised measurements remain separate work.

## Storage, recovery and limits

Packets use `state/invoice-campaign-v2-review-packets/reviewpacket-v2-UUID/` with
format version 2. `packet.json`, `receipt.json` and three selection files are
create-only, guarded by one owner lease. Repeat `prepare` with the same packet ID
to recover matching pending bytes or missing derived files/seals. Completed bytes
remain unchanged. When supplying `ExpectedPacketSha256` for recovery, the preserved
packet or its pending bytes must already match that independently retained digest.

Reads require both packet identities, do not repair publications and create no
absent packet store. Owner lock files support exclusive inspection/publication;
they are excluded from source inventories. Linked paths, duplicate JSON keys,
invalid UTF-8, unknown packet artifacts, changed binaries and conflicting pending
bytes fail closed. Use completed stores without concurrent writers. Hashes do not
provide signed protection against an operator replacing the store and all
independently expected identities.

Packet files are limited to 2 MiB; PowerShell metadata and individual runtime JARs
to 8 MiB, with one to 32 dependency JARs. The Java inspector retains its 1 MiB
source, 64 MiB four-arm inventory, 5,000 file/directory and 2,000-unit-per-trial
limits. Additional campaign metadata is bounded at 64 MiB; combined sources at
5,000 files and 256 MiB. Oversized evidence is refused, not truncated. Participant
budgets and the original six-criterion rubric are unchanged.

`./scripts/Test-InvoiceCampaignReviewV2.ps1` exercises ordering, scope/runtime
binding, recovery, tampering and rejection controls with explicitly synthetic
controller/inspector fixtures. Its runtime identity files are never executed.
The existing Java v2 inspector tests separately exercise real retained adapters
with scripted broker outcomes. Neither suite establishes measured model quality.
