# Bound inputs for coordination quality review

`Prepare-CoordinationReview.ps1` prepares a separate, ungraded review inventory
from a sealed twelve-slot campaign. It invokes only the existing historical
campaign reader: no participant run, new investigation, model call, credential
loading or execution permission is involved. The original campaign records stay
unreviewed and unchanged.

Build the Java adapters as described in the [README](../README.md), then use the
existing campaign ID:

```powershell
./scripts/Prepare-CoordinationReview.ps1 -CampaignId campaign-EXISTING-UUID
```

The returned `packetId` identifies ignored local state under
`.workbench/coordination-review-packets/reviewpacket-UUID/`. Inspect it with:

```powershell
./scripts/Prepare-CoordinationReview.ps1 -Action read `
  -CampaignId campaign-EXISTING-UUID -ReviewPacketId reviewpacket-EXISTING-UUID
```

`-StateDirectory` selects another trusted state store. Keep the captured inputs,
campaign registration, execution records, comparison registration and all mapped
trial/adapter stores together. The packet contains relative references and hashes,
not a second copy of source files, payloads or model transcripts. The command
prints only identities, counts and its limited validation status.

## What is bound

The inventory covers the declared 24 case outcomes in their original order,
retaining failed trials, abstentions and missing outputs. Each case keeps its
exact input identity, response disposition and final dossier byte identity.
Missing output stays `NOT_PROVIDED` with no invented dossier or review units.
Final dossiers have a root review unit, so fields outside the summary or findings
remain in scope. Additional units identify summaries, findings and the structured
diagnostic sections, including unresolved results and scope limitations.

For coordinated teams, all original and fresh claims are included with their
recorded reference IDs and input bindings. `CITED` and `NOT_CITED` report only
whether a claim ID occurs in that case's final summary/finding citations. They
are not judgments about endorsement, rejection or correctness. Uncited earlier
claims remain available for review. Entire worker dispositions, coordinator
decisions, possible conflicts and reconciliation records are included, even when
the final dossier does not mention them. These shared team context units can
appear in both case entries; assess their case scope explicitly.

Each unit references an artifact and a JSON Pointer (the empty pointer denotes
the whole document). Its identity is the packet ID, slot position, case ID and
`unitId` together. Source hashes bind the frozen rubric, complete captured input
bundle, immutable campaign metadata, execution/slot receipts, comparison
registration, runtime read/call ledgers and all mapped adapter records, including
the original team prefix. Lock files are excluded. No later comparison reports
or unrelated historical trials are selected into this inventory.

These are **review units**, not automatically extracted atomic material claims.
A summary, finding or diagnostic object can contain several claims. Semantic
review must split and judge those claims, follow their evidence and lineage,
retain rejected/unsupported/unresolved material, assess every frozen criterion
and record reviewer provenance. A root unit or a citation alone is not evidence
of complete claim audit. The frozen [rubric](evaluations/coordination-rubric-v1.json)
and [comparison acceptance contract](coordination-comparison.md) remain unchanged.

## Persistence and validation

`packet.json` is create-only and `receipt.json` seals its exact bytes. A producer
script hash records provenance; it does not authenticate a reviewer. Reads
rebuild the ordered inventory and compare it to the retained packet. They also
validate the historical campaign and source bytes. Removing a unit, changing a
source, substituting a trial, shrinking the denominator or changing the sealed
packet is rejected. No judgments are added to the campaign's `records.json`.

For an interrupted publication, repeat `prepare` with the **same** campaign and
packet IDs. A complete packet is inspected without replacement; a missing seal
can be completed after validation. A pending write is published only when its
bytes match. `read` refuses incomplete publication, stale pending files and
unexpected packet artifacts. An exclusive local owner lock prevents competing
packet publishers. Symbolic links/reparse points and escaped references are
refused, including beneath hidden `.workbench` directories.

Packets/publications are limited to 1 MiB; individual source artifacts to 8 MiB;
source inventories to 5,000 files and 5,000 directories. Oversized inputs are
refused rather than truncated. Changes detected during inspection invalidate the
operation. This remains a trusted single-host boundary, not a transactional
snapshot across actively changing stores or protection against an operator
rewriting both source and packet. Inspect sealed stores without concurrent writers.

`REVIEW_INPUTS_BOUND` establishes exact review inputs and mechanically derived
coverage only. It does not establish semantic correctness, full material-claim
coverage, accepted cases, coordination benefit, a winner or a routing rule.
Completed claim judgments and the paired comparison report remain separate work.

## Verification

```powershell
./scripts/Test-CoordinationReviewPacket.ps1
```

The credential-free suite uses synthetic stores and stubs only historical campaign
validation. It checks complete denominators, diagnostic scope, original/fresh and
uncited lineage, conflicts, immutable replay, byte changes, missing seals and
pending-write recovery, linked/escaped paths and size limits. CI runs it on Windows
and Linux. These controls verify the review boundary; they do not grade answers.
