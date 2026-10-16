# Invoice business delivery coverage

`InvoiceBusinessCoverage` executes every distinct delivery in the complete
[invoice registry](invoice-renewal-registry.md): 47 business variants, 100
deliveries across seven corpora. It uses the registered consumer builds and the
existing protected execution adapters. It does not execute the 188 arrival-order
sequences, apply ledger effects or replace the separate 34-control boundary suite.
A separate [ordered host-ledger runner](invoice-ordered-coverage.md) now replays
these original typed observations with each registered initial state and order.

## Execution and scope

The plan is created only by loading the byte-validated registry and both pinned
source catalogs. Each delivery keeps its original corpus, normalized input and
evidence identities. Repeated source bytes are not deduplicated across deliveries.
The plan also retains the intentionally missing legacy-adapter branch, which has
no expected consumer fact. Neither JSON metadata nor caller-modified input copies
can replace the captured plan.

Build selection follows the declared consumer role, SDK and effective input
version, independently of the expected business answer. The four compiling
original builds handle their matching versions. The explicit mixed-version route
uses the [Basil main with Acacia companion](versioned-invoice-business-execution.md).
No unchanged-source failing target is silently selected as a repair, and no Acacia
baseline replaces a declared Basil main SDK.

Every webhook goes through real HMAC verification and isolated SDK-parser
verification before admission. This driver uses a newly generated, ephemeral
**synthetic fixture key**, not a Stripe credential. It signs the exact captured
bytes at the current clock time, with an explicitly synthetic endpoint/key-set
scope. These receipts establish execution of the verifier on synthetic data, not
provider-originated delivery. Keys and signature headers are not persisted or
passed to workers. Reconciliation needs its registered retrieval scope rather
than a webhook signature.

After admission, every executable delivery runs in its own protected consumer
container. Compilation, execution and host business assertions remain separate.
The worker receives its admitted exchange, not the fixture inventory, expected
answer, signing key or ledger. Supplied subscription-state evidence remains an
authored input; no provider lookup or live billing write occurs.

## Results and failure handling

The runner keeps the actual typed `InvoiceRenewalOracle.Observation` alongside
its diagnostic envelope through `InvoiceConsumerExecution`. Editing JSON cannot
construct or change that observation. The envelope and typed observation must
agree. This preserves typed facts for later ordered checks without treating a
serialized assertion string as authority.

Each registered delivery receives one result:

| Outcome | Meaning |
| --- | --- |
| `VERIFIED` | Actual protected execution completed and the host business assertion passed |
| `EXPECTED_BLOCK` | The registered absent companion was actually absent; admission blocked before consumer execution |
| `UNEXPECTED_BLOCK` | Admission failed for a delivery that should have executed, or for different issues |
| `FAILED` | An evaluated business fact disagreed with its separately held expectation |
| `NOT_EVALUATED` | Incomplete verification/consumer execution or a host-stage error; no invented business fact |
| `CANCELLED` | Cancellation was observed during the current delivery; retained stage evidence remains visible |
| `NOT_ATTEMPTED` | Work was not dispatched after cancellation or a host-stage error |

A host-stage exception or recorded worker infrastructure error stops further
dispatch with a fixed error code, retaining any typed failure observation without
logging exception messages or raw payloads. Ordinary failed assertions
and incomplete consumer results remain visible while other deliveries continue.
Stopping between deliveries preserves completed results and marks all remaining
entries unattempted. A blocked input has no consumer observation. An incomplete
consumer may retain a reported actual fact without an accepted assertion.

Business coverage succeeds only when all 100 deliveries are accounted for: 99
passing consumer observations and the one expected admission block. Summary
counts cannot conceal unexpected blocks, failed assertions or unattempted work.
`requireFullCaseCoverage()` still rejects: ordered sequences and boundary controls
remain separate work. Business coverage is not migration or release approval.

## Retained records and operation

Each run creates a new output directory and refuses to reuse an existing one.
`plan.json` records the complete inventory before dispatch. `001.json` through
`100.json` are written individually as results complete, then `report.json`
contains the final inventory, outcomes and limits. Abrupt process termination can
leave a plan and partial records without a final report; those files are not a
completed run and are not automatically resumed. Use a new directory for a new
attempt and retain the earlier one.

The new writer preserves explicit JSON nulls, including all eight normalized fact
fields and absent execution. Files use create-only writes; existing artifacts
cannot be overwritten. Root and delivery records bind corpus/input/source,
source catalogs, build/configuration/dependencies, image and execution harness
identities. The coverage harness and per-stage deadline are also recorded.
Records contain normalized facts and verification metadata, not source payloads,
signing keys or private model reasoning. Use trusted local storage; these are
local execution records, not a public authenticated interchange format.

After the README preparation/build commands, build the image and run:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Run-InvoiceBusinessCoverage.ps1 -ImageId $invoiceImage
```

The wrapper uses the fixed registered case and creates a new run under ignored
`backend/target/invoice-business-runs/`. The Java entry point exits 0 for complete
business coverage, 2 for a retained but incomplete run, or 1 for an entry-point
failure. Each verification/execution worker has a 30-second execution deadline;
separately bounded container control and cleanup can add time. Existing output
and isolation limits apply. No API credentials are required.

Default tests check planning, routing, immutable inputs, typed observation binding,
explicit-null persistence, pre-dispatch cancellation and output/configuration
guards. The three image-enabled `InvoiceBusinessCoverageContainerTest` tests run
the complete business matrix, interrupt after a completed delivery, and verify
fail-fast behavior for an unavailable image. Linux CI runs these with the other
invoice container suites and retains `backend/target/invoice-business-tests/`.

The separate ordered runner checks ledger behavior from captured observations;
this delivery report still records zero ordered sequences. All boundary placements,
joint comparison capture, semantic review and the separately frozen campaign
remain pending. Existing
campaign results and routing are unchanged; Milestone 4 acceptance remains open.
