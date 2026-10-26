# Campaign-bound invoice review inputs

`Prepare-InvoiceCampaignReview.ps1` binds review inputs to all twelve original
slots of a completed [invoice campaign](invoice-campaign-execution.md). It reads
retained evidence only: no participant execution, model call, consumer dispatch,
credential loading or permission grant is involved. Failed trials, missing
answers, abstentions and uncertain usage remain in the denominator.

The command prepares an ungraded inventory. **`REVIEW_INPUTS_BOUND` establishes
neither answer quality nor complete material-claim inspection.** Claim judgments,
paired quality/resource reporting, routing and milestone acceptance remain
separate work.

## Prepare and inspect

Build the backend using the README commands. Select a trusted common root with
separate evidence and state directories beneath it:

```text
/trusted/invoice-run/
  evidence/    # Original selected inputs, protected contract and rubric
  state/       # Original campaign, execution, comparison and trial stores
```

Use the independently retained registration receipt SHA-256, not a digest newly
trusted from an arbitrary replacement store:

```powershell
./scripts/Prepare-InvoiceCampaignReview.ps1 `
  -CampaignId invoicecampaign-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewEvidenceDirectory /trusted/invoice-run `
  -EvidenceDirectory /trusted/invoice-run/evidence `
  -StateDirectory /trusted/invoice-run/state
```

The command returns a packet ID, packet SHA-256 and coverage counts only. Retain
the packet digest independently for inspection and later judgments:

```powershell
./scripts/Prepare-InvoiceCampaignReview.ps1 -Action read `
  -CampaignId invoicecampaign-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewPacketId reviewpacket-EXISTING-UUID `
  -ExpectedPacketSha256 EXPECTED-PACKET-SHA256 `
  -ReviewEvidenceDirectory /trusted/invoice-run `
  -EvidenceDirectory /trusted/invoice-run/evidence `
  -StateDirectory /trusted/invoice-run/state
```

Prepare also accepts an explicit packet ID for interrupted publication, and an
optional expected packet digest when one is already independently retained.
An absent read creates no packet store. Reads require both identities and never
repair incomplete publication.

## What is bound

The original controller reader validates the independently pinned campaign and
its twelve sealed outcomes before and after inspection. The packet binds frozen
registration, execution/slot receipts and comparison registration metadata, plus
the exact captured inputs, protected contract/rubric, adapter/runtime records and
team prefix. Later review/report files and unrelated trials are excluded.

The existing Java invoice inventory logic inspects four selected arms for each
repetition. A new transient `InvoiceReviewInputs inspect` action returns metadata
without publishing an arm packet or inferring criteria timing. The campaign
wrapper checks every selected result against its sealed slot, restores original
campaign order and establishes `FROZEN_BEFORE_TRIALS` from that campaign binding
and recorded chronology. Legacy four-arm packets retain their original post-hoc
format and interpretation.

Each global slot retains its full operational trial and units for final dossiers,
all sections/findings, independent worker outcomes and available dossiers,
original/fresh team claims, questions, conflicts and reconciliation. Partial
aggregates remain partial; no final dossier is invented for a missing answer.
Every available model-response text field remains referenced, including rejected
finish decisions. Non-completed broker results also receive a whole-record
`BROKER_OUTCOME` unit, including a received HTTP response without token usage.
The command never prints or copies raw model text into its output summary.

Unit identity consists of packet ID, global slot position and unit ID. Each unit
binds an artifact, JSON Pointer and canonical pointer-value digest. A unit can
contain several material claims. Direct citation labels describe references,
not endorsement or correctness. Source hashes and mechanically complete
inventories do not establish semantic support or reviewer accuracy. Original
trial case reviews stay null and `qualityReview` stays `NOT_PERFORMED`.

## Persistence and limits

Packets live under `state/invoice-campaign-review-packets/reviewpacket-UUID/`.
Three derived selection files bind the original four-arm input groups;
`packet.json` binds their exact bytes and the combined inventory. `receipt.json`
seals the packet. Completed publications preserve their bytes. Repeat prepare
with the same ID to recover matching pending writes or missing derived files and
seals; conflicting pending bytes stop recovery. No trial is replaced or rerun.

Inspection re-derives the inventory, verifies the external packet digest and
checks source hashes again after publication. Exclusive ownership prevents
concurrent publishers. Unknown packet artifacts, duplicate JSON keys, invalid
UTF-8, escaped references and linked paths are rejected, including beneath
hidden state directories. Case-distinct JSON keys remain distinct.

Packets are limited to 2 MiB; source files and inspector JSON to 8 MiB; source
inventories to 5,000 files and directories. Each four-arm Java inventory retains
its existing 64 MiB source limit and 2,000-unit limit per trial. Additional
campaign metadata is limited to 64 MiB and the combined source set to 256 MiB.
Oversized evidence is refused rather than silently truncated. These limits
govern inspection; the measured model/read/deadline budgets are unchanged.

Use completed stores without concurrent writers. This is a trusted local
filesystem boundary, not a transactional snapshot or signed protection against
an operator replacing both the evidence and independently retained identities.

## Verification

The [26 October measured-input record](evaluations/invoice-campaign-review-inputs-2026-10-26.json)
binds all twelve original trials, 521 review units and 4,647 source artifacts.
It retains 312 model-response units and the non-completed broker outcome.
An independent pointer/value-digest audit passed; pinned inspection and completed
prepare replay preserved all five non-lock packet files. Original measured
evidence and the earlier post-hoc packet remain unchanged. These are mechanical
binding results, with no semantic judgments or accepted cases.

`./scripts/Test-InvoiceCampaignReview.ps1` checks the wrapper's ordering, receipt
identities, missing/failed/uncertain denominator, exact recovery, tampering,
strict parsing, links and exclusive ownership. Its campaign reader and four-arm
inspector are explicitly synthetic fixtures; it calls no provider or model and
grades no advice. Java tests separately exercise the real invoice readers and
transient inventory, including an uncertain HTTP 429 outcome, no-write behavior
and legacy packet preservation. Both CI platforms run the wrapper controls.
