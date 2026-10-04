# Deterministic comparison diagnostics

The deterministic arm reads the same [captured case bundle](coordination-runtime.md)
as the planned adaptive arms. Fixed rules produce a dossier for each case and an
unreviewed `CoordinationTrial` record. It makes no model, provider, GitHub or Docker
request, reads no credential, and performs no new consumer execution. The paired
campaign and semantic acceptance reviews remain separate work.

## What the rules establish

The adapter reads every available captured alias through `CoordinationRuntime.read`.
Its registered participant has both case scopes and no model protocol. It never
uses grading judgments, earlier investigator answers or prior repair outputs.
Scenario expectations are application requirements available equally to every arm;
they are not comparison acceptance judgments.

- **Recorded execution:** use scenario membership from expectations and fixtures,
  retain each supported observation, and derive baseline/target/reference comparisons.
  A completed baseline pass plus a completed target failure is an observed regression.
  Missing, duplicate, malformed, unexpected, unevaluated or invalid-baseline records
  leave the aggregate unresolved. Other unambiguous failures remain visible.
  An embedded upstream assessment is not used as the conclusion.
- **Customer projections:** distinguish omitted, null and present embedded `sources`.
  Count initially known methods present in the separate synthetic source/payment-method
  lists. Absence counts require both lists to be complete; otherwise unknown membership
  stays unresolved. Report declared deactivation counts and the application's
  `requireEvidenceBeforeDeactivation` flag. Omission alone establishes no absence.
  These are facts about supplied synthetic projections, not actual provider retrieval.
- **Subscription projections:** for declared `bound-item-renewal-v1`, report configured
  binding, matching membership, list completeness and selection eligibility. When
  eligible and a date is expected, compare the bound item, first item, minimum and
  maximum dates with that expectation. Counterexamples are computed from the current
  fixtures rather than returned from a table of known answers. These calculations
  neither run consumer code nor prove an implementation uses the correct selection.
  A declared `DEFERRED` expectation remains distinct from establishing a renewal date.

Facts cite captured artifact aliases, SHA-256 identities and JSON pointers.
Diagnostics expose counts and status comparisons rather than payloads, payment-method
identifiers or renewal timestamps. Source and provenance artifacts are available and
their hashes retained, but source control flow, failure causation and provider
documentation are explicitly unestablished by these rules. They do not interpret
source text as instructions or execute it. The historical null-guard's precise
control-flow limitation therefore still needs analysis outside this rule set.

Unsupported scenario/document shapes produce an abstention if no section can report
facts. An unsupported execution or projection section leaves that section unresolved
while preserving supported facts from another section. An answer can be partial and
does not imply that every question was answered. Strict JSON checks reject duplicate
fields, excessive nesting and oversized arrays before deriving facts.

## Freeze the actual implementation and run

After the normal README build:

```powershell
./scripts/Run-CoordinationDiagnostics.ps1 -Action policy
./scripts/Run-CoordinationDiagnostics.ps1 -InputBundleId inputs-EXISTING-UUID
```

The second command is an **offline adapter check**. It registers one two-case
deterministic trial with a 20-minute clock and the common comparison ceilings.
Other arm/rubric identities are explicitly unrun check placeholders. This is not
the fixed three-repetition campaign, a completed four-arm comparison, a quality
acceptance result or evidence for a routing decision. A repeated check creates a
new trial; it cannot replace an earlier outcome.

For an actual frozen comparison definition, use the policy identity returned above
as the deterministic entry in a `formatVersion: 1` comparison plan:

```powershell
$trial = ./scripts/Run-CoordinationDiagnostics.ps1 -Action register `
  -InputBundleId inputs-EXISTING-UUID -PlanPath plan.json `
  -WorkloadId sufficient-evidence-bundle -Repetition 1
./scripts/Run-CoordinationDiagnostics.ps1 -Action run -TrialId $trial
./scripts/Run-CoordinationDiagnostics.ps1 -Action read -TrialId $trial
```

The adapter policy binds the exact compiled application inventory, Gson library
and JDK runtime identity, plus fixed input/context/final-selection policies. It
declares no model prompt and no model access. A changed build/runtime cannot resume
unfinished participant work under an old policy. Completed historical reads do not
require the current build to have the same identity. These are content bindings,
not signed code attestations. The current policy permits at most 100 registered
reads; a full two-case run uses 14. It does not secretly acquire additional evidence
or reload fixtures from the repository.

## Retention, interruptions and review

State lives in ignored `.workbench/coordination-diagnostics/trial-UUID/` beside the
corresponding shared runtime trial. Registration binds the adapter specification
to the exact runtime specification and captured inputs. An exclusive local lease
prevents concurrent adapter writers. Completed case files are create-only; exact
resumes verify rather than overwrite them. Stable read IDs reuse the existing
runtime ledger and original whole-trial clock.

Each case has its own JSON dossier. A terminal marker binds the selected output
hashes and preserves completion or the explicit stop reason. Revocation is
`CANCELLED`; expiry and other runtime stops are
`FAILED`, with absent outputs represented as `NOT_PROVIDED`. They do not become
semantic abstentions. Available partial outputs remain. Operational completion
does not mean quality acceptance. Input-integrity errors refuse admission rather
than inventing an assessment from changed evidence.

`result.json` binds case hashes, registration, terminal marker and the runtime
trace; a final receipt binds its exact bytes. Reads verify the retained inventory,
runtime trace, captured identities and reconstructed comparison record. Interrupted
receipt publication can resume after checking the existing result; read-only
inspection rejects a missing receipt. A runtime registration interrupted before
adapter registration is complete is not automatically rerun or selected as a
replacement trial. File checks protect normal local integrity, not against a
trusted host operator rewriting the entire store. Deadline checks are cooperative;
they do not provide an OS process sandbox or instantaneous termination.

The result's `trial` field is an exporter-compatible record: model is null,
every semantic `review` is explicitly null, and usage comes from the completed
runtime trace. Its case output identities refer to the exact dossier bytes.
The exporter does not self-grade or assert a criterion is satisfied. The offline
comparison remains unresolved until separate complete reviews are supplied.

## Validation

Tests cover recomputed outcomes, baseline invalidity, missing/duplicate/malformed
observations, retained partial failures, omission versus complete/incomplete absence,
computed selection counterexamples, deferral scope, unsupported policy/input shapes,
duplicate JSON fields, nesting limits, untrusted instructions, zero model/execution
usage, exact retries, retained artifact changes, implementation binding, receipt
recovery, resumed partial output, revocation and whole-trial expiry.
They use synthetic local evidence and no API credential. The full repository
verification remains the command documented in the README.

An offline adapter check on 2026-10-04 used existing captured bundle
`inputs-6e4dfed9-be1a-479d-aa15-b40073041353` and retained trial
`trial-8710312b-aeae-422d-af73-9d429eac3455`. Its result SHA-256 is
`f0eb985cf455fd486766f6ab9ada53e397c4238f10ce339a0949a93929d42f4b`.
It completed 14 registered reads, with zero model calls, tokens or new case
executions. The customer dossier reported four target regressions and two
reference regressions; the subscription dossier reported four target regressions
and four passing checks. Repeated new-JVM inspection preserved all 42 non-lock
artifact hashes across the bundle, runtime and diagnostic stores. Both outputs
remain unreviewed. This check neither runs the paired campaign nor establishes
semantic acceptance or coordination benefit.
