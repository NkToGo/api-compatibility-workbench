# Deterministic invoice diagnostics

`InvoiceDiagnostics` supplies the fixed-rule invoice comparison arm. It reads the
same immutable [invoice bundle](invoice-comparison-inputs.md) as the
[single-context investigator](invoice-single-agent.md), through the shared
[read-only runtime](invoice-comparison-runtime.md). It makes no model, provider,
GitHub or Docker request, loads no credential and dispatches no new consumer.
Its dossier and comparison record remain unreviewed.

## Evidence selection and limits

The registered policy permits 100 shared reads and one participant with no model
protocol. The check definition uses the same joint invoice workload and ceilings
as the single-context check: 60 model calls, 20 minutes, 64,000 input bytes per
call and 2,048 output tokens per call. Unused model capacity is not exchanged for
additional reads. The deterministic record has a null model and zero model usage.

Metadata costs one read. Catalog pages cost one read each and disclose at most
40 aliases; the complete 444-artifact inventory takes 12 pages. Text pages each
cost one read and contain at most 4,000 UTF-16 characters. The remaining 87 reads
cannot inspect every artifact. Catalog membership and hashes do not mean the
contents were inspected.

Selection depends only on the catalog, alias names and declared page budget:

1. Start with the task, build configuration and both baseline consumer sources,
   in that order. These are the same minimum source/task accesses used by the
   single-context adapter; source reading alone establishes no code behavior.
2. Group remaining business aliases by corpus and consumer, ordered aliases by
   corpus and delivery order, and boundary aliases by placement. Other categories,
   including remaining sources, admission and execution controls, form their own
   strata. Sort strata lexically and their members by SHA-256 of alias text,
   using the alias as a tie-breaker.
3. Visit one member per stratum per round. Select the longest prefix whose files
   can all be read completely within the remaining text-page budget. Stop at the
   first file that cannot fit; do not skip it to choose more convenient files.

The retained selection lists every chosen alias/hash/length, available and selected
counts per stratum, unread count and first budget-excluded alias. This is a
reproducible bounded selection, not a random or representative population sample.
It cannot establish frequencies or correctness for the entire case. Rule changes
or selection changes alter the bound implementation; they require a new trial.
No protected answer, grading result, previous agent output or semantic review is
consulted to select evidence or derive facts.

## Fixed rules and their meaning

Only completely read artifacts can supply facts. Every diagnostic row cites its
captured alias and SHA-256; sequence position details also have JSON pointers.

- Single records preserve observed availability, admission, execution/transport
  status, protocol-invalid state and presence of returned facts. Supported actual
  status values remain distinct from execution completion. Where disclosed,
  compare actual input/evidence identities with their recorded identities and
  the actual API version with the declared input version. These comparisons are
  recomputed from disclosed values, not imported identity-match grades or protected
  expected business facts.
- Ordered and boundary records retain each position, recorded ledger receipt
  outcomes (including duplicate and conflict), unsupported or incomplete positions,
  initial/final effect counts and their difference. Receipt/position index gaps
  and observations contradicting recorded availability/admission are explicit
  issues. Matching effect counts do not certify an order or erase interruption.
- Duplicate JSON fields, unsupported types, excessive nesting/arrays and invalid
  record enums stay unsupported. A bad row does not erase supported rows elsewhere;
  an unsupported position stays visible within its sequence.

Authored input flags, assertions and permission claims never grant access or
become grades. Diagnostics expose categorical states, comparisons and counts,
not payloads or customer/invoice/subscription identifiers. The rules do not execute
or interpret source control flow, retrieve provider references, establish failure
causation, or reconstruct the protected correctness oracle. Earlier consumer
assertion grades remain outside the shared disclosure.

`ANSWERED` requires complete source execution coverage, the four minimum source/task
artifacts, and supported business and ordered records. Otherwise the response is
`ABSTAINED`, retaining any supported partial facts. Neither response is a semantic
acceptance result, a general compatibility conclusion or a routing recommendation.
Unread artifacts and unestablished analysis remain explicit limitations.

## Commands and retained records

After the normal README preparation/build commands, use an existing captured
bundle and its identity retained independently by the trusted capture caller:

```powershell
./scripts/Run-InvoiceDiagnostics.ps1 -Action policy
./scripts/Run-InvoiceDiagnostics.ps1 -InputDirectory CAPTURED-BUNDLE-DIRECTORY `
  -InputIdentity EXPECTED-MANIFEST-SHA256
```

The default `check` creates a new offline check trial. Other arms and the quality
rubric use explicitly unrun/unreviewed placeholders. It is not the separately
frozen paired campaign or a measured coordination benefit. Do not derive the
expected identity from an untrusted directory's own receipt.

For a separately frozen comparison plan with this exact deterministic policy:

```powershell
$trial = ./scripts/Run-InvoiceDiagnostics.ps1 -Action register `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -PlanPath plan.json -WorkloadId invoice-joint -Repetition 1
./scripts/Run-InvoiceDiagnostics.ps1 -Action run -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceDiagnostics.ps1 -Action read -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

State is create-only under ignored `.workbench/invoice-diagnostics/trial-UUID/`,
beside the shared invoice runtime trace. Registration binds the exact compiled
application, runtime/library identities, policy, definition and original bundle.
An exclusive adapter lease prevents concurrent writers. Stable metadata/catalog/
text operation IDs reuse the shared ledger after interruption; retained pages and
selection must match exactly. The whole-trial clock is never renewed on resume.

The dossier, pages, selection and terminal inventory are hash-bound to the final
record and receipt. Completed read/run commands do not admit participant work or
require credentials. A missing adapter receipt can be restored only after checking
the original result against retained artifacts and the completed runtime trace.
A missing runtime receipt or altered artifact remains an integrity failure.
Unexpected files and incomplete publication fail closed. Hashes are local trusted
host integrity bindings, not signatures or protection against an operator rewriting
the entire store. Deadline checks are cooperative, not an OS sandbox.

Cancellation, expiry and exhausted runtime reads remain operational stops with
absent outputs represented as `NOT_PROVIDED`; they do not become successful
semantic abstention. Already retained partial evidence remains. Console output
contains trial/status/usage/path metadata only. A zero command exit alone is not
semantic acceptance; inspect the retained status and stop reason.

## Validation

Offline tests cover stable bounded selection, whole-file exclusion, altered input
identity, record comparisons, forged input claims, duplicate/malformed/deep JSON,
partial facts, conflicting receipts, incomplete positions, shared read accounting,
zero model/consumer usage, restart without credentials, unchanged retained files,
receipt recovery, tampering, partial resume, revocation and the original deadline.
The full Java verification remains the README command. The original two-case
diagnostics and historical campaign remain unchanged.

An offline check on 2026-10-21 reused the existing full invoice disclosure with
identity `f33c31738c753c6af710f7b0797f4bef3a3e0911c74eb211f8068759adca7295`.
Trial `trial-d619cbc2-2adf-4125-8d18-0608c99ed0a6` completed 99 registered reads:
one metadata page, 12 catalog pages and 86 text pages covering 69 complete artifacts.
It left 375 artifacts unread. Its 63 supported diagnostic records included
26 business, 28 ordered, six boundary, two admission and one execution artifact.
Selected sequence records retained five conflict receipts, six duplicates and
two incomplete positions. Counts describe only this selected subset, including
separate arrival orders; they are not distinct billing events or full-case rates.
The response was `ANSWERED`, with semantic review explicitly absent, zero model
calls/tokens and zero new consumer dispatches.
Fresh-JVM read and completed-run inspection without a process API key preserved
all 656 non-lock files across the original bundle, runtime and adapter stores.
The result SHA-256 is
`a8fbc79c6036d25630ba064fbad53163cb808eeebdfbb3e6d50742285887c038`;
this operational check does not supply a semantic acceptance review.
