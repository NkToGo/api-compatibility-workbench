# Bound invoice claim-review records

`InvoiceSemanticReview` records and checks supplied judgments over one existing
[invoice review-input packet](invoice-review-inputs.md). It does not dispatch an
agent, load a credential, rerun a consumer or decide whether a claim is true.
The original packet, trials and `qualityReview: NOT_PERFORMED` fields stay intact.

The selected operational trials predate the protected rubric. These reviews are
descriptive and post-hoc. Even a complete review passing every declared gate cannot
establish frozen-campaign acceptance, a coordination benefit, routing permission
or migration/release approval. The separate paired campaign remains future work.

## Start with explicit pending work

After the README preparation/build commands, retain the packet's exact byte digest
independently and create a new review file in an existing safe directory:

```powershell
./scripts/Review-InvoiceClaims.ps1 -Action draft `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/review-state `
  -ReviewPacketId reviewpacket-UUID -PacketSha256 <retained-packet-sha256> `
  -ReviewPath /trusted/reviews/review-1.json
```

The command creates the destination exclusively; it never overwrites an existing
review. Output cannot overlap the selected source stores, protected contract,
rubric, captured inputs or input-packet store. The draft includes all four ordered
arm/trial/output bindings, every unit and all six criterion IDs. Every unit and
criterion starts `PENDING`, coverage is not attested complete, and reviewer
provenance starts `NOT_RECORDED`. A valid draft is not a completed review.

Complete the review deliberately, keeping earlier revisions and their independently
retained digests when revising judgments. Draft publication is a create-only file
write, not a transactional or signed review store. An interrupted partial file is
invalid; preserve it and use a new destination. `verify` changes no files.

## Record judgments and their scope

The record has `reviewer`, `scope`, `evidence` and four ordered `trials` entries,
alongside the exact packet, rubric and protected-contract identities. Each trial
keeps its exact source and output binding, `coverageComplete`, `criteria` and
`units`. Replacing a failed trial with another run is a different packet, not a
review edit.

Each unit binds its original `unitId` and `valueIdentity`, plus `notes` and one
coverage disposition:

- `PENDING`: not inspected; no claims or delegation can hide inside it.
- `CLAIMS`: one or more supplied judgments, with no container delegation.
- `CONTAINER`: a whole final/worker/partial dossier delegates to exactly its
  recorded direct section/finding units in the same artifact. Cycles and omitted
  sections are refused. Pending children still leave the review incomplete.
- `CONTEXT`: limited operational/identity metadata, empty team arrays or strictly
  parsed catalog/text-read requests. Summaries, findings, historical claims,
  nonempty conflicts and rejected/accepted finish responses cannot be excluded
  this way. Arbitrary prose is not identity metadata.

A claim contains `claimId`, `anchor`, `proposition`, `verdict`, `material`,
`endorsed`, `evidenceIds` and `rationale`. `anchor` contains `representation`,
`pointer` and `valueIdentity`. `NATIVE` points within the original unit value;
`JSON_TEXT` can point inside the strictly decoded public response JSON of a
`MODEL_RESPONSE` unit. Neither representation exposes hidden model reasoning.
The canonical anchor-value digest must match. A passage can contain several
propositions; anchoring its text does not prove exhaustive decomposition.

Verdicts are `SUPPORTED_WITHIN_RECORDED_SCOPE`, `UNSUPPORTED` or `UNRESOLVED`.
Claim-bearing passages cannot be relabeled nonmaterial. Assertions in final or
independent-worker dossier sections remain endorsed for their own output review.
Historical original/fresh/model-response claims record endorsement separately;
`CITED`/`NOT_CITED` never supplies that semantic judgment automatically. Historical
material errors remain counted even when explicitly unendorsed. Counts include
repeated passages and are not independent error rates or quality scores.

Every claim cites a nonempty, duplicate-free list of evidence IDs. Each catalog
entry binds `evidenceId`, `artifact`, `sha256`, `pointer` and `valueIdentity`.
Sources must belong to the original packet. JSON objects support JSON Pointers;
other UTF-8 source files are bound as whole text at the empty pointer. A citation
can demonstrate an omission or uncertainty as well as support a positive claim.
The verifier checks bytes and references, not whether the cited material supports
the proposition. Unused catalog entries and changed source/anchor bytes fail.

Each criterion retains `criterionId`, `judgment`, `rationale`, `unitIds` and
`evidenceIds`. Judgment is `PENDING`, `SATISFIED`, `NOT_SATISFIED` or `UNRESOLVED`.
Reviewed criteria require reviewed unit references and evidence; pending criteria
carry neither. An omission is distinct from an unsupported assertion.

## Gates, provenance and verification

An incomplete review is valid, but cannot attest complete coverage or satisfy its
declared post-hoc gates. A trial satisfies those gates only with a completed
operational run, the rubric's expected answer disposition, complete coverage
attestation, no pending work, every criterion satisfied and no endorsed material
unsupported/unresolved claim. Honest abstention and failed/missing work remain
visible without becoming successful answers.

Judgments require an explicit offline inspection method, reviewer label and ISO
calendar date. The supported methods are `model-assisted-offline-inspection` and
`human-offline-inspection`. This record format is unblinded, has no independent
human adjudication or calibrated reviewer-accuracy claim and records zero new
provider calls. Those limitations are checked, not inferred from a reviewer name.
Reviewer identity, honesty, semantic truth and exhaustive decomposition are not
authenticated. `coverageComplete` remains an explicit reviewer attestation backed
by mechanical unit/criterion coverage checks, not a measured accuracy guarantee.

Retain the completed or partial review's exact digest independently, then run:

```powershell
./scripts/Review-InvoiceClaims.ps1 -Action verify `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/review-state `
  -ReviewPath /trusted/reviews/review-1.json -ReviewSha256 <retained-review-sha256>
```

The command prints identities, counts and derived gates only. It validates the
complete retained packet before and after inspection, all declared evidence and
claim anchors, four-arm/unit denominators, criterion membership and provenance.
It reports `INVOICE_REVIEW_BINDINGS_MATCHED` even for a valid incomplete review;
inspect `reviewedTrials`, `pendingUnits`, `pendingCriteria` and each trial's gate. `campaignAcceptance`
always remains `NOT_ESTABLISHED`.

Review files are limited to 1 MiB and use the packet's strict UTF-8/JSON parser:
duplicate/trailing fields, oversized collections and excessive nesting fail.
References, linked paths and changed evidence are refused by the retained-input
boundary. Use stable completed local stores on a trusted host. Hash consistency
does not protect against replacing the review and all independently retained
bindings together. Claim inspection of the four historical arms remains
incomplete; this step supplies the record/validation path, not a completed
semantic assessment or paired measurement.
