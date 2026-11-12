# Invoice v2 campaign claim-review records

`Review-InvoiceCampaignClaimsV2.ps1` creates pending review records or verifies an
independently selected revision against a [sealed v2 packet](invoice-campaign-review-inputs-v2.md).
It rederives the complete campaign/packet before and after processing. It calls
no model, starts no participant and supplies no semantic judgments.

The v1 campaign and historical post-hoc commands retain their formats and gates.
V2 uses `formatVersion: 2` and `semanticreview-v2-UUID`, binding the packet,
campaign receipt, public task, protected rubric/contract and all twelve slots.
V1 records and packets cannot substitute for these identities.

## Draft and verify

Build using the README commands. Keep an existing review directory outside the
original evidence and state trees. Select the independently retained campaign
receipt and packet digest:

```powershell
./scripts/Review-InvoiceCampaignClaimsV2.ps1 -ReviewAction draft `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewPacketId reviewpacket-v2-EXISTING-UUID `
  -ExpectedPacketSha256 EXPECTED-PACKET-SHA256 `
  -ReviewEvidenceDirectory TRUSTED-COMMON-ROOT `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -RuntimeDirectory TRUSTED-CAMPAIGN-RUNTIME `
  -InspectionRuntimeDirectory TRUSTED-PACKET-INSPECTOR `
  -ReviewRuntimeDirectory TRUSTED-REVIEW-ENGINE `
  -ReviewPath TRUSTED-REVIEWS/pending.json
```

Every unit and all 72 criterion entries start `PENDING`. Claim/evidence lists are
empty, coverage is not attested and reviewer provenance is `NOT_RECORDED`.
Failed, cancelled, uncertain, abstained and missing answers stay in their slots.
A valid draft is unfinished work, not an accepted review.

After deliberate inspection, retain the exact review-file SHA-256 independently.
Use the same command with `-ReviewAction verify`, `-ReviewSha256 EXPECTED-REVIEW-SHA256`
and the selected `ReviewPath`. Verification preserves its bytes. Drafts use
exclusive creation and never overwrite files. Retain interrupted or earlier
versions and use a new destination; this is not a transactional editing service.

The runtime options have distinct roles and each defaults to `backend/target`:

- `RuntimeDirectory` reads the original controller and participant histories.
- `InspectionRuntimeDirectory` must match the inspection binaries already bound
  in the packet. Preserve that runtime before rebuilding the application.
- `ReviewRuntimeDirectory` runs the new claim checker. Its relative binary names
  and hashes are checked during processing and returned with the verification
  result. An identical archive at another path remains usable.

Retain the selected runtimes and verification result with the original stores.
Rebuilding a checker does not replace the participant runtime or authorize a new
trial under a frozen condition. Paths are trusted host configuration. No credential
is read, and review evidence cannot become an input to participant investigation.

## Claims and conservative gates

The shared [claim/anchor/evidence schema](invoice-semantic-review.md) remains in
use. Every supplied proposition has an exact native or decoded-response anchor,
verdict, evidence references, rationale, materiality and explicit endorsement.
The unchanged six criteria must each be judged; pending work cannot attest
complete coverage. Source hashes, pointer values, original slot membership and
the separately frozen timing remain bound throughout verification.

V2 final dossiers contain a nested `decision`. Their `ADVISORY_DECISION` units can
delegate only to all recorded descendant units in that same artifact. Self,
sibling, missing or cyclic coverage is rejected. Nested summaries/findings and
material whole-final/worker dossier assertions cannot avoid review gates by
marking themselves unendorsed. Readiness and other narrative-bearing sections
cannot be dismissed as operational context.

Original/fresh intermediate assertions can be explicitly unendorsed; unsupported
or unresolved material assertions still remain in historical counters. Exact
reference labels do not imply endorsement. Strictly typed v2 prefix-navigation
requests and approved metadata can be context; added narrative and rejected
finishes cannot use that exception. A non-completed broker outcome can be context
only under the existing bounded metadata allowlist. Unknown usage is not zero
usage, and an HTTP status alone does not establish its cause.

A trial satisfies the declared descriptive review only when all coverage and
criteria are complete, the original trial completed, its response answers the
task, and no endorsed material unsupported/unresolved assertion remains. Satisfied
criteria alone cannot rescue failed, cancelled, uncertain or missing/abstained
work. `campaignAcceptance` always stays `NOT_ESTABLISHED`.

The checker validates supplied bindings and gates; it does not determine whether
propositions are true, citations support them, a reviewer is accurate, or
coordination is useful. Reviewer method/date/limitations stay explicit. Supported
inspection remains offline and unblinded, without claimed independent human
adjudication, calibrated accuracy or new provider calls. Measured outcomes,
semantic inspection and Milestone 4 acceptance remain separate. A
[bound v2 paired report](invoice-campaign-comparison-v2.md) can now export a
completed supplied review; it does not supply that inspection or a measurement.

## Boundaries and validation

Packets and reviews are limited to 2 MiB, source reads to 8 MiB. Strict UTF-8/JSON,
duplicate-key, pointer, collection and per-trial unit/claim limits remain in
effect. Source/output overlap, linked paths, independently mismatched digests and
changes during inspection fail validation. Stable completed stores are required;
hashes are not signed protection against replacing both evidence and all expected
identities.

The Java `InvoiceCampaignSemanticReviewV2` CLI checks supplied file bindings;
use the host command for full frozen-campaign and packet rederivation. Java tests
cover pending/completed supplied records, nested coverage, historical errors,
failure gates, strict parsing, larger bounded files, output protection and v1
isolation. Host controls use explicitly synthetic packet-reader/engine fixtures.
These tests establish mechanics, not measured model quality or completed review
of an actual v2 campaign. No original campaign evidence is regraded.
