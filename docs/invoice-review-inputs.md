# Bound inputs for joint invoice review

`InvoiceReviewInputs` prepares a separate, ungraded inventory of four explicitly
selected retained invoice trials: diagnostics, single agent, independent workers
and coordinated team, in that order. It calls only their retained readers. It
loads no credential, runs no participant, dispatches no consumer and modifies no
original trial or input. Missing, failed, uncertain and abstained work remains
part of the four-arm denominator.

This is an operational inspection packet, **not a paired campaign**. The existing
trials were run before the [invoice review rubric](evaluations/invoice-review-rubric-v1.json)
was recorded. Any subsequent review of those trials is descriptive and post-hoc.
It cannot establish frozen campaign acceptance, a winner or a routing promotion.
A future paired campaign must independently freeze the rubric's exact bytes,
criterion IDs, inputs, policies, limits and ordered trial slots before execution.
The historical known-case campaign and its negative result remain unchanged.

For a completed frozen twelve-slot invoice campaign, use the separate
[campaign-bound command](invoice-campaign-review-inputs.md). Its transient
`InvoiceReviewInputs inspect` action reuses this inventory logic without
publishing a four-arm packet or inferring chronology. Historical packets retain
their existing format and post-hoc timing; the outer campaign binding establishes
the new campaign's criteria timing separately.

## Protected rubric and contract

The six criteria cover versioned association, independent version boundaries,
application eligibility rules, joint ledger behavior, execution/causal scope and
evidence/uncertainty. They derive from the authored case and business oracle;
they do not require agent communication or prescribe a winning architecture.
The expected task disposition is `ANSWERED`. An honest abstention may contain
supported limitations without satisfying that task; missing output remains
`NOT_PROVIDED`.

The rubric stays outside the agents' disclosure bundle. The packet binds it and
the separately selected protected case-contract directory alongside the unchanged
captured inputs. Their identities are separate: a selected contract snapshot is
not automatically authenticated provider truth or proof of its correspondence to
the historical capture. Use the preserved contract revision, retain the independently
selected digest, and inspect provenance when making judgments. No expectation or
grading result is copied into participant contexts.

## Selection and commands

Run the README preparation/build commands first. Choose an existing output state
directory and a common evidence root containing the selected stores, inputs,
protected contract and rubric. Artifact references below are relative to that
root; paths must use `/`, remain inside the root and contain no `.`/`..` segments,
drive names or links. Output packet storage must not overlap selected evidence.

The selection file has this shape; replace every placeholder with independently
retained identities and exact existing trial references:

```json
{
  "formatVersion": 1,
  "inputDirectory": "captured-invoice-inputs",
  "inputIdentity": { "sha256": "<original-manifest-sha256>" },
  "contractDirectory": "protected-invoice-case",
  "contractIdentity": { "sha256": "<selected-contract-inventory-sha256>" },
  "rubricArtifact": "invoice-review-rubric-v1.json",
  "rubricIdentity": { "sha256": "<selected-rubric-byte-sha256>" },
  "sources": [
    { "arm": "DETERMINISTIC", "stateDirectory": "diagnostics-state", "runId": "trial-UUID", "resultIdentity": { "sha256": "<retained-result-sha256>" } },
    { "arm": "SINGLE_AGENT", "stateDirectory": "single-state", "runId": "trial-UUID", "resultIdentity": { "sha256": "<retained-result-sha256>" } },
    { "arm": "INDEPENDENT_WORKERS", "stateDirectory": "workers-state", "runId": "trial-UUID", "resultIdentity": { "sha256": "<retained-result-sha256>" } },
    { "arm": "COORDINATED_TEAM", "stateDirectory": "team-state", "runId": "trial-UUID", "resultIdentity": { "sha256": "<retained-result-sha256>" } }
  ]
}
```

The rubric digest hashes its exact bytes. The contract digest hashes the sorted
relative-path/file-hash map using `CaseInputs.identity`; the metadata-only
`contract` command computes it for a deliberately selected trusted revision.
Computing a digest from an arbitrary supplied directory does not authenticate it.
The contract directory must include `README.md`, `business-oracle.md` and
`provenance.json`; all its non-lock files are bound, not only those three.

```powershell
./scripts/Prepare-InvoiceReview.ps1 -Action contract `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/review-state `
  -ContractDirectory protected-invoice-case
./scripts/Prepare-InvoiceReview.ps1 -Action prepare `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/review-state `
  -SelectionPath /trusted/invoice-review-selection.json
./scripts/Prepare-InvoiceReview.ps1 -Action read `
  -EvidenceDirectory /trusted/evidence -StateDirectory /trusted/review-state `
  -ReviewPacketId reviewpacket-UUID
```

The command prints identities and counts only. It rejects a stale JAR and never
loads an environment file. Retain the returned packet SHA-256 independently of
the packet store when referencing a later review.

## Review inventory

Every trial retains its exact result identity, full operational `CoordinationTrial`,
status, response, output identity, usage and timing. The source inventory covers
the original input bundle, protected contract/rubric, all selected runtime and
adapter records, and the team's original/review prefix. Lock files are excluded.
It includes neither unrelated trials nor later review/report artifacts.

Units bind an artifact, JSON Pointer and canonical pointer-value identity:

- Whole final dossiers, every top-level section, summaries and individual findings.
  Structured diagnostic records remain reviewable, without turning them into
  automatically accepted assertions.
- Both independent worker dispositions and every available role dossier. An
  incomplete joint aggregate remains a `PARTIAL_AGGREGATE`, with no fabricated
  final output identity; completed child answers remain visible.
- All original and fresh team claims, with direct final citation labels, plus
  worker outcomes, coordinator questions, possible conflicts and reconciliation.
  `CITED`/`NOT_CITED` describes direct references only, not semantic endorsement.
- Every retained model-response text field, including rejected or uncited finish
  decisions. Failed protocol output cannot disappear merely because no accepted
  dossier was produced. Raw responses are referenced, not printed or copied.

These units are containers or passages, not automatically extracted atomic claims.
Later semantic review must decompose material assertions, follow exact evidence,
distinguish endorsement from access, record reviewer provenance and preserve
unsupported/unresolved historical claims. The binding checker does not perform
that judgment or measure reviewer accuracy. Original `qualityReview` fields remain
`NOT_PERFORMED`, and case reviews remain null.

## Persistence and limits

Packets live under `invoice-review-packets/reviewpacket-UUID/` in the output state.
`packet.json` is create-only and its receipt binds exact bytes. Inspection rebuilds
the inventory using retained readers and checks every source identity and pointer.
Sources are inventoried twice to detect changes during inspection. Use stable,
completed stores without concurrent writers; this is not a transactional snapshot
or a signed defense against replacing an entire store and all bindings together.

Repeat `prepare` with the same selection and packet ID to recover an interrupted
publication. New writes use canonical JSON field order. Completed records must
describe the same derived inventory and retain their original bytes and matching
receipt; they are not re-encoded. Unpublished pending bytes must match exactly.
`read` refuses missing seals,
pending writes, extra packet artifacts, links and changed data. An exclusive owner
lease prevents competing publishers. A read of an absent packet creates no store.

Files and packets are limited to 1 MiB, the source inventory to 5,000 files and
5,000 visited directories, each inventoried source set to 64 MiB, and units to 2,000
per trial. JSON uses strict UTF-8, rejects duplicate keys and trailing data, and
bounds depth and collection sizes. Oversized evidence fails rather than being
silently truncated. Hashes establish local byte consistency, not semantic truth,
external approval or permission to execute.
