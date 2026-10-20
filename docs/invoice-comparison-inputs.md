# Shared invoice comparison inputs

`InvoiceComparisonInputs` creates one immutable, paged disclosure of captured
invoice consumer sources, synthetic inputs and observed execution facts. It is
the input boundary for later comparison adapters. It makes no model calls,
dispatches no consumer during disclosure and grants no execution permission.
The [invoice read boundary](invoice-comparison-runtime.md) now loads this bundle
through a separate runtime entry point. Default two-case entry points and the
historical campaign remain unchanged. A separate [single-context invoice adapter](invoice-single-agent.md)
now investigates through that boundary. A [deterministic invoice arm](invoice-diagnostics.md)
derives bounded recorded-fact diagnostics from the same disclosure; independent
workers, the coordinated arm and semantic review remain pending.

## Disclosed evidence

Capture requires the original typed [case run](invoice-case-coverage.md) and its
exact captured plan. Serialized execution JSON cannot create that run. Source
code and reference metadata come from the plan's immutable, validated captures;
the exporter does not reopen changed consumer files or import an arbitrary report.
Provider-reference metadata is captured against the original contract pin.

The bundle has 444 artifacts:

| Aliases | Count | Contents |
| --- | ---: | --- |
| `task`, `provider-references`, `build-configuration` | 3 | Scope, historical source metadata and declared build identities |
| `source/…` | 15 | Captured original and versioned consumer Java sources |
| `business/…` | 100 | Original synthetic inputs, observed admission/transport and actual consumer facts |
| `admission/…` | 26 | Runtime admission inputs and observed verifier/gate evidence |
| `execution/…` | 10 | Admitted fault/healthy inputs, transport status and actual returned facts |
| `order/…` | 188 | Initial/actual final ledger effects and every observed business position |
| `boundary/…` | 102 | Initial/actual final effects, admission blocks and observed boundary positions |

Input objects are explicitly labeled `untrusted-authored-synthetic`. Authored
verification flags and forged permission/assertion claims remain input data,
separate from the recorded host receipts and consumer observations. They cannot
grant access, change the disclosure policy or establish a passing assertion.
Source strings and tool output must also be treated as untrusted data by future
adapters.

The exporter omits protected expected facts, expected order outcomes/final
effects, business assertions, identity-match grades, control verdicts and fixture
answer files. It also excludes the host business oracle, grading rubric, prior
agent answers/reviews, credentials and private model reasoning. Application
consumer code still contains its declared business behavior; this is a known
synthetic workload, not a held-out accuracy measurement.

Actual wrong-version facts, protocol-invalid results, missing observations,
admission issues, conflicts and ledger receipts remain visible. A stopped position
is retained as `NOT_ATTEMPTED`, rather than invented as an admission block.
Reference URLs, hashes and recorded review dates are historical metadata, not
retrieved documentation or current provider verification. No third-party page or
dependency binary is copied into the disclosure.

## Identity, storage and reads

Each new directory contains 444 numbered UTF-8 text artifacts, `manifest.json`
and a final `receipt.json`: 446 create-only files. The manifest binds the exact
alias/file/hash inventory, disclosure policy and implementation identities,
original plan/capture identities and source execution-coverage state. Its SHA-256
is the bundle identity. The receipt marks completed publication; partial output
without it cannot open as a complete bundle.

`open(directory, expectedIdentity)` requires the identity retained by the trusted
capture caller or a later frozen registration. Never substitute the directory's
own claimed hash for that expected identity. Rewriting both manifest and receipt
cannot satisfy the original expected identity. These hashes are local integrity
bindings, not signatures, proof of a trustworthy producer or authorization.

Opening checks exact inventory, hashes, strict JSON, duplicate fields, file and
ancestor links, UTF-8 and size bounds. It accepts at most 768 artifacts, 1 MiB per
artifact and 8 MiB total. The manifest parser has its own bounded inventory policy;
the existing fixture parser's limits remain unchanged.

An opened bundle holds immutable copies. Later disk edits cannot change an active
view, while reopening against the original expected identity rejects those edits.
The catalog returns at most 40 aliases with hashes/character lengths per page.
Text reads return at most 4,000 UTF-16 characters with exact offsets and the full
artifact hash. Unknown aliases, traversal strings and invalid ranges are rejected.
Reads access the captured map, never caller-selected filesystem paths.

Every future arm must use the same bundle identity and available alias inventory.
This API supplies no arm-specific evidence or extra execution. The separate read
boundary now binds disclosure, read accounting, permissions and the whole-run
clock before participant work. The single-context adapter binds its invoice
protocol and model reservations; other arms must still bind every stage before
dispatch. The bundle itself does not register
a trial, implement adapters, accept semantic claims or promote a routing rule.

## Commands and incomplete evidence

After the README preparation/build commands and starting a Linux Docker engine:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Capture-InvoiceComparisonInputs.ps1 -ImageId $invoiceImage
```

The command creates a new directory under ignored
`backend/target/invoice-comparison-input-runs/`. It first executes a new registered
case into `coverage/` and then discloses the original typed result into `inputs/`.
This explicit capture command requires Docker and retains 884 files when both
journals complete; the original case command remains unchanged. Disclosure itself
does not dispatch additional consumers. Existing per-worker limits apply.

The Java entry point also supports `inspect BUNDLE_DIRECTORY EXPECTED_IDENTITY`.
Inspection opens the retained bundle without Docker, model calls or source-case
access and prints only identity/count/coverage metadata. Input contents are not
printed by either command. No API credential is required.

Incomplete case evidence can still be disclosed with
`sourceExecutionCoverageSatisfied: false`; missing work stays missing. The capture
and inspection entry points return 2 for that retained incomplete source, 1 for
an operation failure and 0 for a bundle from complete synthetic execution
coverage. None of these exits is migration or semantic acceptance. A later trial
registration must validate its declared evidence-availability condition explicitly.

Default tests check exact inventory, protected-answer exclusion, untrusted forged
claims, copy isolation, paging, bounds, source-plan membership, tampering, duplicate
manifest fields and incomplete/extra files. Linux also exercises a linked artifact.
The container check discloses a real complete case and a partial replay from its
same observations, preserves wrong facts/blocks and reopens the same bundle for
equivalent reads. CI retains these bundles inside `invoice-case-tests/`.
