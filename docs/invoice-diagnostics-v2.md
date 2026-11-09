# Deterministic invoice v2 diagnostics

`InvoiceDiagnosticsV2` binds the current [v2 public task](invoice-v2-contract.md)
and the same captured evidence used by the v2 investigators. It uses the existing
model-free [paged navigation boundary](invoice-prefix-catalogue.md), with at most
100 counted reads and the registered whole-trial deadline. Registration rejects
limits above the v2 ceilings: 60 calls, 20 minutes, 64,000 request bytes and
2,048 output tokens per call. The deterministic arm has no model protocol, loads
no credential and makes zero model calls, network requests or consumer executions.
Registration binds the exact compiled application and implementation policy.

This is a comparison adapter, not a new campaign registration or quality review.
The earlier diagnostic adapter, its rules and historical records are unchanged.

## Preserved strategy and task identity

The host supplies the same current public task as the model arms, retaining its
text and identity in `task.json`. This host task is neither evidence nor an
execution grant. The captured artifact named `task` remains original untrusted
evidence with its original bytes and identity. Host task disclosure does not
consume an additional evidence read, matching the model arms' task disclosure.

Selection and extraction reuse `InvoiceDiagnosticRules` unchanged:

- One metadata read and flat catalog pages of up to 40 entries.
- The four required task/build/baseline-source artifacts, followed by the fixed
  stratum round-robin and alias-hash ordering.
- A whole-file prefix fitting the remaining budget, using 4,000-character UTF-16
  text pages. An oversized next file is not skipped for convenient facts.
- The same categorical recorded facts, identity/version comparisons, sequence
  receipts, conflicts, incomplete positions and explicit limitations as v1.

Prefix navigation is available at the shared host boundary; this fixed strategy
continues to use the flat catalog. It is not retuned using historical model
outcomes. Binding the current task does not make these rules a source-code
interpreter or causal investigator. `ANSWERED` and `ABSTAINED` retain the
[existing diagnostic guards](invoice-diagnostics.md); neither establishes that
the full v2 task was answered well. Semantic review remains `NOT_PERFORMED` and
the trial carries no quality judgment.

## Ownership, reconstruction and recovery

State lives under ignored `.workbench/invoice-diagnostics-v2/trial-UUID/`, beside
its model-free runtime trace under `.workbench/invoice-navigation-trials/`.
Every retained metadata/catalog/text wrapper includes participant, operation,
case, bundle, exact page request and the untrusted-data marker. Inspection checks
the full wrapper against its actual runtime read record, reconstructs the fixed
selection, verifies text identities and recomputes the dossier. A changed local
page, selection or fact fails even if the adapter's local hashes were updated.
A completed dossier also requires an exact runtime read count.

The adapter has one writer lease and stable operation IDs. Charged but unpublished
reads are reused after interruption with the original start and deadline.
Before new participant work, retained pages must form the exact expected prefix.
Deadline/revocation checks also apply to cached tool operations. Once a
permission-checked dossier has been published, validated interrupted closure can
finish without new participant operations or renewed permission. Completion
describes that published work, not permission for future work.

Cancellation, expiry and read exhaustion remain operational stops. Without a
published dossier the output is `NOT_PROVIDED`, preserving partial evidence and
actual runtime accounting. `readAccounting` distinguishes retained owned pages,
all runtime reads and runtime reads not published into the adapter transcript;
the latter are not assumed to be semantically examined. A missing adapter receipt
can be restored only from the same reconstructed result and sealed runtime.
Missing runtime seals or selected evidence, changed task/policy binding, and
unexpected/incomplete publications fail closed. Inspection does not recreate
missing evidence.

Hashes are trusted-host integrity bindings, not signatures against an operator
rewriting the whole store. Permissions and deadlines are cooperative checks,
not a process sandbox. Outputs are diagnostics rather than compatibility
certification, migration approval or routing decisions.

## Commands

Run the README preparation and full Java verification first. Use an existing
captured bundle and its expected identity retained independently by its trusted
capture caller; do not derive that expectation from the directory's own receipt.

```powershell
./scripts/Run-InvoiceDiagnosticsV2.ps1 -Action policy
./scripts/Run-InvoiceDiagnosticsV2.ps1 -Action check `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

`check` creates an offline check trial with unrun placeholders for other arms
and an unreviewed rubric. It does not register the common-condition campaign.
For a separately frozen plan with this exact deterministic implementation:

```powershell
$trial = ./scripts/Run-InvoiceDiagnosticsV2.ps1 -Action register `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -PlanPath plan.json -WorkloadId invoice-joint -Repetition 1
./scripts/Run-InvoiceDiagnosticsV2.ps1 -Action run -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceDiagnosticsV2.ps1 -Action read -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

Console output contains status, usage and report-path metadata only. Inspect
terminal status and stop reason; a zero process exit is not semantic acceptance.
The unreviewed `CoordinationTrial` includes all reads/time, a null model, zero
model tokens/calls and zero new executions.

## Validation

Offline controls compare v1/v2 selection and facts, verify the current task,
shared accounting and zero model/consumer work, and replay completed runs in
another JVM without a process API key. Controls also cover unpublished-read
recovery, the original deadline, revocation, exhausted reads, interrupted dossier
closure, missing receipts/evidence, resealed page/selection/dossier tampering,
changed task, incomplete publication, wrong arm, legacy policy, wrong inputs and
oversized limits. The README verification remains required. Common-condition
registration, measurement and semantic acceptance remain separate work.
