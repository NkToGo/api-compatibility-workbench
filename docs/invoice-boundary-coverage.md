# Ordered runtime boundary coverage

`InvoiceBoundaryCoverage` combines the actual [admission controls](invoice-boundary-admission-coverage.md)
and [consumer execution controls](invoice-boundary-execution-coverage.md), then
replays their captured decisions and typed observations through all **102**
registered boundary placements. This is a host-ledger policy check over actual
synthetic execution evidence. It does not rerun consumers for each position,
exercise concurrent deliveries or establish live provider compatibility.

## One captured plan and both original runs

The plan captures the complete invoice registry and source/build registration
once, then constructs both stage plans from those immutable captures. Each stage
run retains its original plan reference. Replay requires those exact typed runs,
all 26 admission results and all ten execution results, their original entry
membership and one immutable image identity. A different plan or image is rejected
before any replay directory is created. JSON diagnostics cannot reconstruct these
objects or grant observation authority.

The original fixture corpus, its authored flags and historical golden protocol
records remain unchanged. Runtime admission still discards authored verifier
flags, and actual execution still keeps raw transport facts separate from host
dispositions. The derived SDK-refusal input retains its own runtime input/evidence
identity alongside the original control identity. Replay imports neither authored
verification states nor authored terminal flags as actual observations.

## Registered placements and independent expectations

Every one of the 34 controls runs through the three registered placements:

1. The control alone.
2. A healthy opposite consumer, then the control.
3. The control, then the healthy opposite consumer.

These make 102 sequences and 170 positions. Each sequence starts with an empty
in-memory ledger. The order-sensitive component is this host ledger; the consumer
fact producers have already executed their captured inputs. Replay dispatches no
additional worker and cannot convert a serialized verdict into an observation.

Ledger expectations come from the independently held fixture policy, not the
observed result: an admitted control's declared `APPENDED` outcome becomes
`DUPLICATE` after the healthy path; other declared outcomes remain unchanged. A
healthy path appends unless the control has already appended the same effect.
Expected final effects come from the protected healthy fact. Actual effects always
come from the retained actual observations, including incorrect evaluable facts.

For admitted positions, replay checks host-selected consumer scope and the exact
protected expected fact before accessing the ledger. It preserves actual
assertions, protocol validity and actual facts. A known wrong-version fact remains
`FAILED` even when its ledger effect matches the healthy path. Incomplete or
unbound observations produce their own `NO_EFFECT` receipts. A foreign scope
produces no receipt. Unexpected conflicts retain the first effect and the actual
conflicting fact; the checker never substitutes an expected answer.

For a rejected position, a verified runtime rejection and its bound admission
decision are required. It has no consumer observation or ledger receipt. Missing
or incomplete verifier work cannot be counted as an expected rejection. Healthy
positions require both the successful admission control and the captured actual
execution. Actual observations remain visible if an upstream coverage check is
incomplete, but that gap prevents a verified position.

The complete run retains **98 ledger receipts and 72 blocked positions**. Receipt
outcomes are 71 `APPENDED`, six `DUPLICATE` and 21 `NO_EFFECT`. The observations
retain 74 passing assertions, six failed assertions and 18 unevaluated assertions.
Those failure controls can be verified as expected behavior of the harness;
their consumer assertions are never changed into passes. A matching final ledger
cannot conceal a missing position, block, failed assertion or interruption.

## Verdicts, interruption and limits

`VERIFIED` means a position/sequence matched its control and ledger expectations,
including deliberately failed or incomplete consumer assertions. `FAILED` retains
an unexpected assertion, fact, policy outcome or upstream failed control.
`NOT_EVALUATED` retains missing, incomplete or incorrectly scoped evidence.
Cancellation before a sequence marks it `NOT_ATTEMPTED`; interruption within a
sequence preserves its completed positions and effects, marks the sequence
`CANCELLED`, and leaves its remaining positions and later sequences unattempted.
The execution suite's deliberate post-return cancellation is a captured test
condition, not a request to cancel replay.

`boundaryCoverageSatisfied` requires both runtime stages to satisfy their own
coverage and all 102 placements to match. `boundaryControlsVerified` counts
controls whose three placements verified; `boundaryPlacementsVerified` counts
verified placements. Partial progress remains visible without satisfying the
aggregate gate. No model calls, release approval or useful coordination benefit
is established by these counts.

`fullCaseCoverageSatisfied` remains false. Business deliveries and their 188
orders have their own captures; a shared full-case evidence capture, comparison
adapters, semantic review and the separately frozen campaign remain subsequent
work. Milestone 4 acceptance remains open.

## Running and retaining evidence

After the README preparation and Java verification:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Run-InvoiceBoundaryCoverage.ps1 -ImageId $invoiceImage
```

The fixed command creates a new directory under ignored
`backend/target/invoice-boundary-runs/`. It executes the 26 admission controls,
executes the ten consumer controls, then replays all placements. Stage deadlines
and container policy remain unchanged. The deliberate timeout control still
reaches its real 30-second consumer deadline. Replay is a bounded local traversal
with cooperative interruption between sequences and positions. No provider/model
calls or API credentials are required.

The `admission/` and `execution/` directories retain their original 28-file and
12-file journals. `placements/` contains `plan.json`, `001.json` through
`102.json`, and `report.json`: 104 create-only files. The replay plan/report bind
the original registry, both stage plans and complete evidence, image and replay
implementation. Evidence identities hash compact UTF-8 Gson JSON with explicit
nulls and HTML escaping disabled, not the pretty-printed files' bytes. Per-sequence
records retain every position, original/runtime identities, gate issues,
observations, receipts and initial/actual/expected final effects. Keep them with
their parent plan and both stage journals on trusted local storage.

An existing attempt cannot be overwritten. Abrupt termination may leave partial
records without a final report; retain them and use a new attempt. There is no
automatic resume or JSON-to-authority import. Raw payloads, signing keys and
private model reasoning are excluded from replay records.

Java exits 0 when boundary coverage is satisfied, 2 for retained failed/incomplete
coverage and 1 for entry-point/storage failure. The wrapper throws on a nonzero
Java exit. A zero exit is a synthetic control-coverage result, not migration or
release approval.

Default tests check exact census, role/scope binding, wrong facts, conflicts,
incomplete evidence, interruption and create-only history. The actual container
test captures both runtime stages once, replays all placements and tests replay
interruption and a missing upstream stage without extra consumer dispatch.
Linux CI retains `invoice-boundary-tests/` with its isolated execution receipts.

The separate [shared case capture](invoice-case-coverage.md) constructs this
boundary plan alongside the business/ordered plans from the same immutable
registry and build capture. Its aggregate execution gate additionally requires
the original typed source/replay runs and a matching image across all components.
This standalone boundary report still cannot satisfy that aggregate gate.
