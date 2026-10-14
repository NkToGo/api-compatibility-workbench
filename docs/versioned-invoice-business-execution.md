# Protected mixed-version invoice execution

`InvoiceVersionedSandbox` executes the separate [registered business source](versioned-invoice-business-builds.md)
for an Acacia webhook snapshot delivered after a Basil main SDK and endpoint
upgrade. It runs one fixed consumer route in the constrained invoice image and
uses the existing host oracle to check the returned business fact.

## Admission and authority

Dispatch requires an admitted `inspectVerifiedWebhook` decision created from
both actual [signature](invoice-webhook-signatures.md) and [SDK parser](invoice-webhook-adapters.md)
receipts. Admission binds the parser receipt to the authenticated source,
installation/endpoint/key-set scope and exact signature receipt. A private typed
authority marker records this completed verification path. Authored verifier
flags, signature verification alone and edited diagnostic JSON cannot set it.

The registered route additionally requires webhook role, SDK 29.0.0, original
event API `2025-02-24.acacia`, explicit Acacia companion, and Basil request and
endpoint versions. Other routes are rejected before container creation. Missing
companions remain admission blocks with no consumer oracle or observation; a
passing result from another delivery cannot replace the missing execution.

The original source catalog and execution adapter remain separate. They do not
accept the new build ID, and an Acacia baseline cannot replace a declared Basil
main runtime. The source registration's `VERSIONED_SOURCE_AND_BUILD_ONLY` stage
still describes compiler registration, not an execution receipt or permission.

## Runtime boundary

The host startup contains only the fixed build ID, two captured source components,
their entry classes and pinned JAR digests. It contains no fixture catalog,
expected answers, ledger, signature keys or verification receipts. The host sends
the admitted exchange only after both compiler receipts succeed.

`InvoiceVersionedWorkerMain` validates the complete component inventory and both
image dependency closures before compilation. It uses independent output
directories and SDK classpaths:

| Component | Classes | Compiler SDK | Runtime |
| --- | --- | --- | --- |
| `main` | Main consumer, unchanged policy and fixed transport | 29.0.0 | Business child process with SDK 29.0.0 and Gson 2.10.1 |
| `acacia-adapter` | Acacia companion | 28.4.0 | Separate platform-parent class loader with SDK 28.4.0 and Gson 2.10.1 |

The main runtime's classpath contains neither companion classes nor SDK 28. Its
three companion path properties are fixed by the bootstrap under `/work` and
`/opt/workbench/dependencies`; event fields cannot choose paths or class names.
The main source verifies its actual SDK/API fields reflectively, retains the
Acacia event version and requires the SDK 29 safe parser to refuse that object.
The companion independently verifies its actual SDK 28 runtime and safely parses
the same snapshot. Only five strings cross the loader boundary. The unchanged
business policy then evaluates invoice association, paid status, approved binding
and supplied subscription state. No provider call is made.

The child process has no backend JAR on its classpath. Expected facts and ledger
state remain in the host process, not the image or startup. Classpath separation
prevents accidental SDK substitution; the external container and host protocol
provide the execution boundary, not a Java class loader security sandbox.

The existing image policy applies: immutable image ID, Linux cgroup v2/seccomp,
no network or repository mounts, read-only root, non-root user, dropped
capabilities, bounded writable work area, memory/process limits and deadline.
Startup remains bounded to 150,000 bytes; protocol messages, output and diagnostic
counts retain their existing limits. Cleanup removes the owned container.

## Compiler facts and business observations

The bootstrap reports `main` and `acacia-adapter` compilation in that order.
Each receipt contains component, disposition and bounded diagnostic codes,
basenames and positions. Both receipts are retained even when one compile fails;
that failure prevents launch. Missing, reordered, duplicate, conflicting or
forged receipts cannot release input or produce a passing business observation.

The consumer may return a normalized fact, never an assertion verdict. The host
oracle alone compares it with the separately held expectation. A completed
correct or incorrect fact yields `PASSED` or `FAILED`; malformed protocol,
missing facts, process failure, timeout and cancellation remain `NOT_EVALUATED`.
Already reported facts survive incomplete transport without becoming accepted.
Terminal results are immutable. The absence path has no fabricated observation.

Host result envelopes bind the original and versioned registrations, each
component's SDK/source/configuration/dependency identities, aggregate source and
configuration identities, dependency closure, harness, image and environment.
The environment identity includes the fixed Linux child arguments independently
of the host operating system. Host-only signature/parser evidence remains
separate from compiler facts, business assertions and eventual ledger effects.

## Validation and limits

After the README build and preparation commands:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
.\mvnw.cmd -B -ntp -f backend/pom.xml "-Dworkbench.invoice.image=$invoiceImage" '-Dtest=InvoiceSandboxContainerTest,InvoiceEventAdapterContainerTest,InvoiceVersionedSandboxContainerTest' test
```

Use `sh ./mvnw` on Linux. Linux CI runs all three invoice container classes and
retains receipts under ignored `backend/target/versioned-invoice-container-tests/`
with the isolated execution artifacts. The seven new container tests cover the
registered authenticated old delivery, missing-adapter and rebound-authentication
blocks, actual loader separation, either compiler failure, image/startup mismatch,
wrong loaded companion SDK, forged verdict, timeout and cancellation. Mutated
startups are explicitly labeled adversarial controls, not registered product
executions or accepted candidate repairs. Default tests cover protocol and
admission rules; container tests skip without the immutable image property.

These observations concern synthetic, pinned exchanges. They do not certify live
Stripe delivery or a migration. Full 47-variant business coverage, 188 ordered
joint sequences and 34 boundary controls through actual consumers remain the
next execution step. Joint evidence capture, comparison adapters, quality review
and the separately frozen coordination campaign follow. Existing campaign
results and routing are unchanged; Milestone 4 acceptance remains open.
