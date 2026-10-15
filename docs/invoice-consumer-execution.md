# Protected invoice consumer execution

The invoice adapter compiles and executes only a selected build from the
[pinned source registration](invoice-consumer-builds.md). A separate, immutable
Linux image contains the backend bootstrap and checksum-pinned runtime JARs.
Source is sent through bounded stdin; no repository, fixture catalog, expected
facts, credential or ledger is mounted in the container. There is no host-process
fallback or arbitrary repository/candidate execution interface.

## Admission and version binding

Before any Docker dispatch, the adapter requires a successful host
[admission decision](invoice-renewal-boundary.md). The selected consumer role,
SDK and effective API version must match that decision. An unknown build or
blocked input cannot create a worker or an execution observation.

The original rollout exchanges exercise authored verification states, which must
not be used as production authentication receipts. A separate
[host signature verifier](invoice-webhook-signatures.md) now authenticates raw bytes
and binds its receipt to admission. A separate [observed SDK adapter](invoice-webhook-adapters.md)
now adds actual parser receipts without replacing the main SDK. Business dispatch
for Acacia events after a Basil main-SDK upgrade is not registered in this execution adapter yet.
A separate [source/build registration](versioned-invoice-business-builds.md) now
compiles the explicit main/companion components, without granting dispatch authority;
the separately registered old-delivery corpus and its blocked paths remain intact.

## Runtime boundary

The adapter reuses the established container controller: no external network,
read-only root, user 65532, no capabilities, no privilege escalation, default
seccomp, cgroup v2, fixed memory/CPU/process limits and bounded tmpfs. The image
must already exist under its immutable local ID. The bootstrap verifies the
isolation policy and checks the selected SDK/Gson bytes against the captured
dependency hashes before compilation. The consumer child uses its own classpath
with the selected SDK and Gson 2.10.1, separate from the bootstrap's Gson 2.14.0.

Compilation uses JDK 21, UTF-8, `--release 21`, disabled annotation processing
and strict warnings. Only the two registered Java source filenames and a fixed
transport adapter are compiled. Compiler output contains bounded diagnostic codes,
source basenames and positions, without source snippets or payloads.

The controller limits startup to 150,000 bytes, a protocol line and reply to
16,384 bytes, cumulative stdout to 262,144 bytes and stderr to 65,536 bytes.
The execution deadline is at most 180 seconds, with a separate 185-second
container backstop; Docker control calls have their own bounded deadlines.
Cancellation, output faults, nonzero exit and cleanup cannot silently become a pass.

## Separate compiler facts and assertions

The bootstrap sends one compiler receipt before the consumer starts. A failed
build retains `FAILED`, its diagnostic codes and worker exit code 4. It produces
no consumer fact and no evaluated assertion. Successful compilation alone also
produces no passing assertion.

After successful compilation, the transport permits one input read and one
normalized invoice fact. The host holds the expected fact separately and uses
the unchanged [invoice oracle](invoice-renewal-host-checks.md) to compare it.
Unknown operations, forged verdict fields, duplicate JSON keys and trailing
returns cannot pass. A missing result remains `NOT_EVALUATED`; a valid but wrong
fact is `FAILED`. A fact received before interruption or protocol failure remains
visible without acceptance. Terminal protocol records cannot be changed by late
messages or by mutating a returned JSON object.

The returned envelope retains compiler disposition/diagnostics, raw transport
status, worker exit code and removal status separately from the host observation.
The observation uses `COMPLETED`, `TIMED_OUT`, `CANCELLED` or
`INFRASTRUCTURE_ERROR`; other transport failures map to the latter. The envelope's
`executionStatus` is the raw controller status, not a replacement for the
observation's execution disposition. Source, configuration, dependency, harness
and environment identities remain separate, with the immutable image ID retained.

## Verification and scope

After the standard README verification, with a running Linux Docker engine:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
.\mvnw.cmd -B -ntp -f backend/pom.xml "-Dworkbench.invoice.image=$invoiceImage" '-Dtest=InvoiceSandboxContainerTest' test
```

Use `sh ./mvnw` on Linux/macOS. Linux CI builds this separate image and runs
the actual container suite; the default test run skips that suite without an image.
Receipts are retained under ignored `backend/target/invoice-container-tests/`
and uploaded with the isolated CI receipts.

The suite executes both consumers across the four rollout vectors: eight
independent input exchanges. It also retains both unchanged-source target compile
failures and exercises dependency/inventory rejection, protected-file/credential
absence, forged protocol output, timeout and cancellation. Altered sources in
negative tests are internal adversarial controls, not supported repair candidates.
An additional actual signed synthetic webhook exchange retains its signature
record only in the host result, in `authenticated-webhook.json`. Its adapter state
remains authored. The suite now contains six tests; the default run skips them
unless the immutable image property is supplied.
The separate six-test `InvoiceEventAdapterContainerTest` suite now checks actual
SDK parsing and one Basil business exchange gated by both real receipt types.
It shares the image property; CI runs both classes and retains redacted parser
receipts under `backend/target/invoice-adapter-tests/`.
The separate seven-test `InvoiceVersionedSandboxContainerTest` suite now uses the
same image property for [protected mixed-version business execution](versioned-invoice-business-execution.md).
CI runs all three classes and retains the new receipts separately.

This is observed synthetic execution for those exchanges only. A separate
[business coverage driver](invoice-business-coverage.md) now runs all 47 variants
and 100 distinct deliveries with actual verifier and consumer execution, retaining
the missing-adapter block. The versioned source catalog has its own protected
executor for the authenticated old-delivery route. The 188 ordered sequences,
34 boundary controls, joint evidence capture, comparison adapters and the separately
frozen coordination campaign remain planned. The earlier campaign and routing
decision are unchanged; Milestone 4 acceptance remains open.
