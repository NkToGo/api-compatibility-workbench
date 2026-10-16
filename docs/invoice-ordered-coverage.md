# Ordered invoice ledger coverage

`InvoiceOrderedCoverage` replays the 188 registered business arrival orders against
the host's existing `InvoiceRenewalLedger`. The inputs are the original typed
observations from a [business delivery run](invoice-business-coverage.md), not
golden fact substitutions or observations reconstructed from JSON. The complete
plan has 312 delivery positions across 47 variants and all seven business corpora.

## Execution boundary

The fixed command first executes the 100 distinct business deliveries, retaining
their signature/parser evidence, protected consumer results and expected missing
adapter block. It then applies those captured results to a fresh host ledger for
each registered order, with that variant's registered initial state. The consumers
are stateless fact producers under fixed synthetic inputs; ledger effects belong
to the host. Reusing their observations isolates the declared order-sensitive
policy without repeating identical protected executions.

**Replay dispatches no consumers.** A repeated position reuses the same sealed
observation and creates a new ledger receipt; it is not a separately observed
provider delivery or consumer execution. This does not test concurrent handling,
mutable consumer storage, provider changes between deliveries, real webhook
arrival, live subscription lookup or live billing writes. Those behaviors cannot
be inferred from this synthetic host-policy replay.

The plan comes from the complete byte-validated registry and the same captured
business plan. Evaluation accepts only its original in-process business run.
Another plan with identical metadata cannot borrow that run. JSON reports have
no import route and grant no observation or execution authority. Before applying
an observation, the checker also binds its consumer role and independently held
expectation to the delivery. An observation from another scope produces an
explicit binding issue and cannot mutate that ledger.

## Outcomes and review

Each position retains its delivery key, source-run outcome, expected policy
outcome, actual policy outcome, original observation and receipt number when a
receipt exists. The order itself retains initial effects, actual and expected
final effects, every position and the final-state comparison.

- `VERIFIED` requires a passing consumer assertion and the expected ledger outcome.
  An expected missing-adapter position is instead verified as a retained admission
  block, with no observation or ledger receipt.
- `FAILED` retains a failed assertion or different policy outcome. A wrong but
  evaluable resolved fact supplies its actual simulated effect; the checker never
  replaces it with the expected value. A matching final effect cannot conceal a
  wrong version, wrong association or intermediate conflict.
- `NOT_EVALUATED` retains absent, incomplete or incorrectly scoped evidence.
  An incomplete typed observation can have a `NO_EFFECT` receipt and a returned
  actual fact; neither makes the assertion pass. No observation means no receipt.
- `CANCELLED` marks an interrupted order, retaining completed positions and effects.
  Its remaining positions are `NOT_ATTEMPTED`; later orders are also unattempted.
  Stopping before an order starts marks the whole order `NOT_ATTEMPTED`.

A sequence passes only when every position passes and its final effects match.
All 188 sequences must pass for `orderedCoverageSatisfied`. The complete synthetic
run produces 309 ledger receipts and three separately blocked positions. The
absent-adapter variant keeps `blockedPathReviewRequired` on all four of its orders,
including reconciliation alone. A healthy path cannot remove that review gap.

The report always retains `fullCaseCoverageSatisfied: false` and zero verified
boundary controls. A separate [runtime admission check](invoice-boundary-admission-coverage.md)
covers its rejection conditions; consumer execution and all 102 boundary placements
remain pending. The complete 34-control boundary workload, joint comparison,
semantic review and frozen campaign remain pending. A passing replay is not a
migration decision, release approval or evidence of useful coordination.

## Records and operation

After the README preparation and build commands:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Run-InvoiceOrderedCoverage.ps1 -ImageId $invoiceImage
```

The wrapper creates a new run under ignored `backend/target/invoice-ordered-runs/`.
Its `business/` directory holds the original 102-file delivery journal. The
`ordered/` directory contains `plan.json`, `001.json` through `188.json`, and
`report.json`. Each directory is new and every file is create-only. Existing
attempts cannot be overwritten. Abrupt termination can leave partial records
without a final report; no automatic resume or JSON-to-authority reconstruction
occurs. Keep the earlier directory and use a new attempt.

The plan and final report bind the registry, business plan, captured business
evidence, image and ordered harness. Per-order files retain their corpus/position
evidence and must be kept with that plan. Business records retain their own source, configuration,
dependency, verifier and execution identities. The business-evidence identity is
SHA-256 of its complete in-memory JSON encoded as compact UTF-8 Gson with explicit
nulls and HTML escaping disabled. It is not the pretty-printed file's byte hash.
All normalized nulls are preserved. Ordered records contain normalized facts and
policy effects, not source payloads, signing keys or private model reasoning.
Use trusted local storage; these files are not an authenticated public interchange.

The Java entry point exits 0 when both business execution and ordered replay satisfy their
coverage, 2 for a retained incomplete/failed result, and 1 for an entry-point or
storage failure. The PowerShell wrapper throws on a nonzero Java exit. The workflow
makes no provider/model calls and needs no API credential.
The business workers retain their existing 30-second execution deadlines and
separately bounded container control/cleanup. Replay is a bounded local traversal
with cooperative interruption between orders and positions.

Default tests check census/binding/immutability, wrong versions and associations,
conflicts, incomplete and unbound facts, missing execution, cancellation and
create-only history. The existing full business container test additionally
replays all orders from that actual run, verifies all persisted records and tests
interruption between and within orders. Linux CI retains `invoice-ordered-tests/`
with the other protected execution receipts. No extra consumer dispatch is added
by these replay assertions.
