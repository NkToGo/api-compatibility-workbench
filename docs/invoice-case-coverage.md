# Shared invoice-case execution capture

`InvoiceCaseCoverage` runs the registered synthetic invoice workload under one
captured input plan and immutable Linux worker image. It combines existing
execution components; it adds no provider/model call, agent comparison, billing
write or release approval.

The input plan contains all 47 business variants and 100 distinct deliveries,
188 business arrival orders with 312 positions, and 34 boundary controls across
102 placements with 170 positions. The independently pinned fixtures, consumer
sources, host expectations, ledger policy and original build matrix are unchanged.

## Capture and authority

The byte-validating loaders capture the complete fixture registry and original
consumer source/build registration once. The versioned business registration
uses that same original build object for its shared policy and dependencies.
Both business and boundary plans use those captured objects; the ordered plan
uses the original business plan. Later execution reads their immutable captures.
Metadata copies cannot replace captured bytes or construct another trusted plan.

The command performs these stages in order:

1. [Business execution](invoice-business-coverage.md): 100 distinct deliveries,
   with actual signature/parser verification and protected consumer execution.
2. [Business-order replay](invoice-ordered-coverage.md): all 188 registered orders
   using the original typed business observations, without redispatch.
3. [Boundary admission](invoice-boundary-admission-coverage.md): 24 rejection
   controls and two healthy admissions with actual verifier receipts.
4. [Boundary execution](invoice-boundary-execution-coverage.md): eight admitted
   fault controls and two healthy consumers with protected transport.
5. [Boundary-order replay](invoice-boundary-coverage.md): all 102 placements from
   the original typed admission/execution runs, without redispatch.

The aggregate retains the original typed runs, not imported JSON assertions.
Assembly requires exact plan membership, each replay's original source run
objects and the same image across all five components. Identical-looking JSON
cannot substitute a different source run. Diagnostic evidence identities bind the
complete immutable component reports; they are not a public authentication or
authorization mechanism. No JSON-to-authority importer is provided.

## Coverage, failures and limits

`fullCaseExecutionCoverageSatisfied` is true only when all five component gates
pass and the capture has not stopped. It establishes complete execution coverage
for this synthetic workload. It is deliberately separate from agent-answer
quality, general API compatibility, migration readiness or authorization.

A complete capture retains 99 passing business observations and the one expected
missing-adapter block. Business-order replay retains 309 ledger receipts and
three blocked positions. Boundary replay retains 98 receipts and 72 blocked
positions. Known fault controls still have failed or unevaluated consumer
assertions even when their control verdict is verified. A passing control means
that the intended fault was observed; it does not make that consumer compatible.
No successful companion can erase blocked, conflicting or incomplete evidence.

An operator stop is latched across subsequent stages. A component's host-stage
failure or cancelled control verdict stops further dispatch across the capture.
Expected control cancellations remain successful fault checks and do not stop
unrelated work. Ordinary failed assertions remain available; they are not relabeled
as a host-stage failure. Each remaining component still writes its complete
unattempted inventory when storage and the host process remain available.
No missing observation, admission block or ledger receipt is invented.

The aggregate gate cannot pass with an interrupted replay, failed business
assertion, missing component or mismatched image/source reference. Each standalone
component retains its own limited gate. The aggregate report explicitly retains
`comparisonAdaptersAvailable`, `semanticAcceptanceEstablished` and
`migrationApproved` as false. Equal-disclosure comparison adapters, reviewed joint
answers and a separately frozen paired campaign remain later work. The historical
campaign, rubric, results and routing rule are unchanged; Milestone 4 remains open.

## Run and retain

Start a Linux Docker engine and complete the README preparation/build commands:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Run-InvoiceCaseCoverage.ps1 -ImageId $invoiceImage
```

The wrapper selects the fixed registered case and creates a new directory under
ignored `backend/target/invoice-case-runs/`. The original 30-second per-worker
deadline and container limits apply. This is not a 30-second whole-case deadline;
container startup, compilation, control and cleanup add time. The deliberate
timeout control still reaches its actual deadline. The replay stages perform no
additional consumer dispatch. No API credentials are required.

Each attempt has 438 create-only files when all journals are written:

| Location | Files | Contents |
| --- | ---: | --- |
| Root | 2 | Full input plan and final aggregate report |
| `business/` | 102 | Original delivery plan, 100 results and report |
| `ordered/` | 190 | Original order plan, 188 results and report |
| `admission/` | 28 | Original admission plan, 26 results and report |
| `execution/` | 12 | Original execution plan, 10 results and report |
| `boundary/` | 104 | Original placement plan, 102 results and report |

The aggregate report binds every component's plan identity, complete evidence
identity, outcome counts and relative journal directory. Evidence identities use
compact UTF-8 Gson JSON with explicit nulls and HTML escaping disabled; they are
not the pretty-printed file hashes. Keep the complete directory on trusted local
storage. Records exclude raw payloads, signing keys and private model reasoning.

An existing directory is never overwritten. Abrupt termination or storage failure
may leave a plan and partial records without a final aggregate report. Retain that
attempt and use a new directory; this command does not resume or redispatch an old
attempt automatically. Java exits 0 for complete synthetic execution coverage,
2 for retained incomplete coverage and 1 for entry-point/storage failure. The
wrapper throws for nonzero exit codes.

Default tests cover shared capture membership, create-only history, exact
inventory, interruption, missing stages, diagnostic-copy mutation, mixed images
and source-run substitution. Container tests execute the complete shared capture,
reuse its actual observations to test partial-replay rejection, and verify that
an infrastructure failure stops later dispatch while retaining its observation.
Linux CI retains `invoice-case-tests/` with the isolated execution receipts.

The separate [comparison-input exporter](invoice-comparison-inputs.md) can disclose
this original typed run and its exact plan without redispatch. Its input view
excludes protected expected facts and grading verdicts while retaining actual
observations, blocked admissions, effects and incomplete work. It adds no agent
comparison or semantic acceptance to this execution-coverage gate.
