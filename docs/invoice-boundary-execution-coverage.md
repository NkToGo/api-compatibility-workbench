# Runtime invoice execution controls

`InvoiceBoundaryExecutionCoverage` executes the eight registered consumer/protocol
controls and two healthy controls from `boundary-v1`. Each uses the actual pinned
consumer algorithm, protected container transport and host oracle. A verified
fault control means the expected failure was observed; it does not turn a failed
or incomplete consumer assertion into a compatibility pass.

The separate [admission driver](invoice-boundary-admission-coverage.md) covers the
24 rejection conditions. Neither driver satisfies combined boundary coverage:
their standalone reports leave placement coverage pending. A separate
[boundary replay](invoice-boundary-coverage.md) now captures both original typed
runs under one plan and checks all 102 placements and their ledger effects.

## Actual admission and fixed controlled sources

The plan captures the complete registry, original source/build registration,
dependencies and the ten admitted inputs with their protected expected facts.
Authored signature/adapter flags and declared execution statuses grant no runtime
authority. Every webhook receives actual ephemeral synthetic HMAC and isolated
SDK parser verification before admission. Reconciliation retains its explicit
request version and host-selected role. The fixed startup builder requires the
captured input and actual typed webhook authority; diagnostic JSON cannot restore
that permission.

Healthy controls execute the original registered source unchanged. Each fault
uses a fixed wrapper around the original webhook consumer's `consume` method.
The original algorithm computes the fact first using the pinned SDK and policy.
The wrapper then changes only the declared output or termination behavior. It
never receives an expected fact, a desired assertion or a fixture verdict. Source
files, original pins, build catalog and earlier evidence remain unchanged.
This is an internal synthetic fault harness, not a candidate repair interface or
support for arbitrary source execution.

The plan records the original registered source identity separately from the
controlled worker-source identity. The latter hashes a sorted map of Java
basenames to UTF-8 source digests. The original identity also covers the registered
build files, so the two hash schemes differ even for unchanged healthy sources.
Timeout and cancellation share the same source; their host execution controls
are different and remain bound through mode/environment identity.

| Control | Actual controlled behavior | Retained host result |
| --- | --- | --- |
| Healthy webhook/reconciliation | Return the computed fact normally | `COMPLETED`, `PASSED` |
| Missing result | Compute, then exit 0 without returning it | `COMPLETED`, no actual fact, `NOT_EVALUATED` |
| Returned then timed out | Send the computed fact, then wait past the real controller deadline | `TIMED_OUT`, retained actual fact, `NOT_EVALUATED` |
| Returned then cancelled | Send the fact; host cancellation is triggered only after the protocol accepts it | `CANCELLED`, retained actual fact, `NOT_EVALUATED` |
| Returned then worker failure | Send the fact, then exit 23 | Raw `WORKER_FAILED`; observation `INFRASTRUCTURE_ERROR`, retained fact, `NOT_EVALUATED` |
| Malformed return | Add a forbidden verdict field to the computed fact | Raw `PROTOCOL_ERROR`, no accepted actual fact, poisoned protocol, `NOT_EVALUATED` |
| Trailing return | Send one fact, then send another | Raw `PROTOCOL_ERROR`, first fact retained, poisoned protocol, `NOT_EVALUATED` |
| Unbound return | Replace the computed input identity | `COMPLETED`, retained incorrect fact, `FAILED`, identity mismatch |
| Wrong version | Replace the computed version with the other known version | `COMPLETED`, retained incorrect fact, `FAILED` |

The worker-failure control exercises an actual nonzero exit and its existing
infrastructure-disposition mapping. It is not an injected Docker, network or
storage outage. The protocol controls preserve the actual controller error status;
they do not reuse the historical golden test's authored `COMPLETED` status.

## Verdicts, interruption and coverage

Independent runtime expectations require the declared transport status, host
execution disposition, assertion, protocol validity, identity match and exact
retained actual fact. They also require successful actual compilation and container
cleanup. Exit 23 is checked separately for its control. `VERIFIED` is a control
verdict only: a complete run retains two passing assertions, two failed assertions
and six unevaluated assertions.

An unexpected observation is `FAILED`. Parser or worker infrastructure failure
stays `NOT_EVALUATED` and stops further work with the inventory preserved. External
interruption keeps completed records and any current actual observation, marking
the interrupted control `CANCELLED` and later ones `NOT_ATTEMPTED`. The deliberate
post-return cancellation is confined to its own control; it cannot cancel the
remainder of a healthy coverage run.

`executionCoverageSatisfied` requires all ten controls to match. Even then,
`boundaryControlsVerified` and `boundaryPlacementsVerified` remain zero and
`fullCaseCoverageSatisfied` stays false. No ledger is mutated by this driver.
The stored typed observations support subsequent host-policy checks; serialized
diagnostics cannot be imported as observation authority. The separate
[boundary replay](invoice-boundary-coverage.md) now establishes combined boundary
placement coverage while keeping failed and incomplete consumer assertions
visible and the full-case gate unresolved. Shared full-case evidence capture,
campaign quality review and Milestone 4 acceptance remain open.

## Running and retaining evidence

After the README preparation and Java verification:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Run-InvoiceBoundaryExecutionCoverage.ps1 -ImageId $invoiceImage
```

The fixed command uses 30-second adapter and consumer stage deadlines, with the
existing separate bounds on container control/cleanup. The timeout control
intentionally reaches that deadline after returning a fact. A timeout before a
required fact is observed cannot verify that control. There are no provider/model
calls, credentials or live customer payloads.

Each new directory under ignored `backend/target/invoice-boundary-execution-runs/`
holds 12 create-only files: `plan.json`, `001.json` through `010.json`, and
`report.json`. They retain input/evidence/source/build/dependency/image/harness
identities, verifier receipts, actual compiler/transport facts, normalized
observations and separate control verdicts. Raw payloads, signing keys and private
model reasoning are excluded. Keep each journal together on trusted local storage;
these files are not an authenticated interchange or resumable permission store.
Abrupt termination can leave partial records; keep them and start a new attempt.

Java exits 0 for complete control coverage, 2 for a retained failed/incomplete run
and 1 for entry-point/storage failure. The PowerShell wrapper throws on a nonzero
Java exit. A zero exit means coverage of these synthetic controls, not a safe
migration or release decision.

Default tests check source binding, scope, immutable snapshots, runtime
expectations, post-return tracking, cancellation and create-only history. Actual
container tests execute all ten controls and check infrastructure failure and
external interruption. Linux CI runs that suite and retains its
`invoice-boundary-execution-tests/` journals with the protected execution receipts.
