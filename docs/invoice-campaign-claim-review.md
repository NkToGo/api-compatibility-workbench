# Campaign-bound invoice claim-review records

`Review-InvoiceCampaignClaims.ps1` creates and verifies supplied claim-review
records over a [sealed twelve-trial invoice packet](invoice-campaign-review-inputs.md).
It revalidates the original campaign and complete packet before and after using
the shared Java claim checker. It runs no participant, calls no model, loads no
credential and supplies no semantic judgments.

The historical four-arm post-hoc format remains supported by the separate
[legacy command](invoice-semantic-review.md). Its timing and output fields are
unchanged; a campaign record cannot use that path as a substitute.

## Create explicit pending work

Build using the README commands. Keep original evidence and state beneath their
trusted common root. Select an existing `reviews/` directory outside both source
trees and the independently retained campaign receipt and packet digest:

```powershell
./scripts/Review-InvoiceCampaignClaims.ps1 -ReviewAction draft `
  -CampaignId invoicecampaign-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewPacketId reviewpacket-EXISTING-UUID `
  -ExpectedPacketSha256 EXPECTED-PACKET-SHA256 `
  -ReviewEvidenceDirectory /trusted/invoice-run `
  -EvidenceDirectory /trusted/invoice-run/evidence `
  -StateDirectory /trusted/invoice-run/state `
  -ReviewPath /trusted/invoice-run/reviews/review-1.json
```

Every original slot, output, unit identity and six-criterion set is included.
All units and all 72 criterion entries start `PENDING`; claim/evidence lists are
empty, coverage is not attested and reviewer provenance is `NOT_RECORDED`.
Failed trials, abstained/missing answers and uncertain broker outcomes stay
present. A valid draft is incomplete work, not an accepted review.

Creation is exclusive and never overwrites a file. It is not a transactional
review store: retain interrupted or earlier versions and use a new destination.
Keep review records outside original evidence/state trees and input packets so
grading material cannot become part of the agents' evidence.

## Verify an independently selected revision

After deliberate inspection, retain the exact review-file SHA-256 independently
and verify that revision with the same original campaign and packet identities:

```powershell
./scripts/Review-InvoiceCampaignClaims.ps1 -ReviewAction verify `
  -CampaignId invoicecampaign-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewPacketId reviewpacket-EXISTING-UUID `
  -ExpectedPacketSha256 EXPECTED-PACKET-SHA256 `
  -ReviewEvidenceDirectory /trusted/invoice-run `
  -EvidenceDirectory /trusted/invoice-run/evidence `
  -StateDirectory /trusted/invoice-run/state `
  -ReviewPath /trusted/invoice-run/reviews/review-2.json `
  -ReviewSha256 EXPECTED-REVIEW-SHA256
```

Verification writes nothing. A draft command also verifies its newly created
pending record before returning counts and identities. Input changes, review
changes during inspection, wrong independent digests and changed source values
fail validation. Original trials, input packets and earlier reviews remain
unchanged. There is no participant retry or replacement option.

## Claims, provenance and conservative gates

The record uses the existing [claim/anchor/evidence schema](invoice-semantic-review.md).
It preserves all twelve ordered slots and `FROZEN_BEFORE_TRIALS` timing from the
selected campaign packet. Material assertions require exact native or decoded
response anchors, explicit endorsement, verdict, evidence and rationale.
Containers delegate to their complete recorded sections; citations are not
semantic endorsement. Pending work cannot attest completeness, and historical
unsupported/unresolved assertions remain counted even when unendorsed.

`BROKER_OUTCOME` can be operational `CONTEXT` only when its entire value contains
the bounded typed broker metadata fields. Narrative, model text or unknown
fields cannot be hidden this way. A received HTTP 429 remains an operational
fact with unresolved usage; it does not establish its cause or imply zero usage.

Each trial reports `satisfiesDeclaredReview` only if supplied coverage is complete,
every frozen criterion is satisfied, no endorsed material unsupported/unresolved
claim remains, the response matches the task and the original trial completed.
Failed, cancelled, uncertain and missing/abstained work cannot pass through
satisfied criteria alone. These are descriptive gates over supplied judgments;
the checker does not decide whether propositions are true or citations support
them. `campaignAcceptance` always remains `NOT_ESTABLISHED`. Paired benefit,
routing promotion, release approval and Milestone 4 acceptance require separate
assessment.

Reviewer method, date and limitations remain explicit. Supported inspection is
offline and unblinded, with no new provider calls or claimed independent human
adjudication/calibrated reviewer accuracy. Mechanical completeness is not
exhaustive decomposition or reviewer accuracy.

## Boundaries and verification

The [27 October pending-review record](evaluations/invoice-campaign-pending-review-2026-10-27.json)
binds the original twelve trials, 521 pending units and 72 pending criteria. Its
independent structural audit found zero supplied judgments and accepted trials;
all 4,652 original evidence/packet files stayed unchanged. The historical
four-trial review retained its exact gates and counters. This records preparation
and binding only; semantic inspection is still pending.

Campaign packets and review records are bounded to 2 MiB. The shared legacy
parser keeps its 1 MiB default; the campaign path explicitly selects its larger
bound. Source reads are limited to 8 MiB with strict UTF-8/JSON, duplicate-key,
depth, collection and pointer checks. Existing per-trial unit/claim limits remain
unchanged. Source references stay inside the common root; linked paths and
source/output overlap are refused. Completed stable stores are required; this
is local byte consistency, not a transactional snapshot or signed protection
against replacing evidence and independently retained identities together.

The Java `InvoiceCampaignSemanticReview` CLI validates supplied packet/claim
bindings only. Use the PowerShell host command for full frozen-campaign and
packet rederivation. Java tests cover twelve-slot pending/complete records,
missing/uncertain gates, broker-context restrictions, legacy isolation, larger
bounded files, tampering and output protection.
`./scripts/Test-InvoiceCampaignClaims.ps1` tests host sequencing and independent
identities with explicitly synthetic packet-reader/engine fixtures. Both CI
platforms run those controls; no fixture is a semantic performance result.
