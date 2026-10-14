# Versioned invoice consumer sources and builds

The [source registration](../cases/stripe-invoice-subscription-2025-03-31/consumer-sources-v1/build-registration.json)
pins authored webhook and reconciliation modules, their shared consumer policy,
Maven descriptors and SDK/runtime dependencies. `InvoiceConsumerBuilds` validates
this registration and compiles the six declared source/configuration combinations.
It never loads the resulting classes, dispatches consumers or executes a supplied
Maven descriptor.

This implements the source/build portion of invoice step 4e2b. Compiler success
and compiler failure are build facts, separate from behavioral assertions,
consumer execution observations, joint readiness and release approval.

## Source and dependency identities

The host-code registration pin is:
`1f74dcacd771d6a9202186fa501e29dc97426c60452887ec42835ccf49463610`.
The manifest binds the unchanged original business contract and
[complete fixture registry](invoice-renewal-registry.md). Its stage is
`SOURCE_AND_BUILD_ONLY`, and its origin is `authored-consumer-sources`.

Nine source/build files are registered: the reactor and three module POMs, one
shared consumer policy source and four versioned entry sources. Each consumer's
selected source identity includes its entry, shared policy and applicable Maven
descriptors. The webhook and reconciliation retain separate selected identities.
The shared implementation is consumer code; it is not the protected host oracle.

The SDK versions are the historical pinned `28.4.0` and `29.0.0`. Their JAR/POM
hashes remain those in the original provenance. Both SDK POMs declare Gson
`2.10.1` as their only runtime dependency; its JAR/POM hashes complete the six-file
runtime registration. JARs, dependency POMs and generated output stay in ignored
build directories. No SDK implementation or upstream consumer source is copied
into the registered source modules.

Changing a source, POM, dependency or registration byte invalidates capture.
Rewriting a local manifest cannot authorize different bytes without a host-pin
change. The loader bounds reads, rejects linked files/ancestors and unexpected
source files/directories, and captures immutable bytes. Generated root/module
`target` directories are excluded from source identity and never substituted for
the registered sources. Compiler inputs are materialized from captured copies.

## Declared build matrix

Each row applies independently to both consumers:

| Build suffix | Source | SDK | Observed compilation |
| --- | --- | --- | --- |
| `baseline` | Acacia accessors | `28.4.0` | Succeeds |
| `unrepaired-target` | Identical Acacia source | `29.0.0` | Fails: the old `Invoice.getSubscription()` accessor is absent |
| `basil-source` | Authored parent-aware source | `29.0.0` | Succeeds |

The baseline and unrepaired target share the exact selected source identity;
their configuration identities differ. The Basil source has its own identity.
It is an authored source control, not an agent-generated or behaviorally accepted
repair. Four successful builds and two rejected builds remain in the matrix.

The webhook entry uses the SDK's safe event-object deserializer and checks the
declared exact event version/type. Reconciliation uses the registered retrieval
representation and explicit request version. Neither entry calls Stripe. Both
return normalized invoice facts through authored policy code for association,
paid status, approved binding and supplied active-state evidence. These behaviors
are implementation intent at this stage; their correctness against all fixtures
is not established by compiling the source.

## Compiler boundary

The registered host compiler accepts only a known build ID and the byte-validated
source/dependency registration. It uses JDK 21, UTF-8, `--release 21`, disabled
annotation processing and strict warnings. It records success/failure, scoped
source/configuration/dependency identities, compiler/runtime identity and bounded
diagnostic codes, source basenames and positions. Diagnostics contain no source
snippets, payloads or personal output paths. Expected compile failures are
retained as failures rather than converted into successful builds.

This is a trusted-local compilation check of fixed reviewed sources. It is not
an arbitrary candidate compiler or execution sandbox. It grants no worker
permission, establishes no behavioral assertion and creates no invoice oracle,
execution observation or ledger receipt. Later protected execution must bind
the selected source/configuration, harness and runtime/image independently.

## Build commands and validation

From the repository root, `./scripts/Prepare-InvoiceConsumers.ps1` downloads or
checks the six pinned artifacts from Maven Central. It does not execute consumers
or call a provider API. The standard README verification invokes the backend
registration/compiler tests; CI also packages both Maven profiles.

To package the reviewed sources with Maven on Windows:

```powershell
.\mvnw.cmd -B -ntp -f cases/stripe-invoice-subscription-2025-03-31/consumer-sources-v1/pom.xml clean package
.\mvnw.cmd -B -ntp -f cases/stripe-invoice-subscription-2025-03-31/consumer-sources-v1/pom.xml -P basil clean package
```

On Linux/macOS, use `sh ./mvnw` in place of `.\mvnw.cmd`. These package commands
compile the sources; the consumer modules have no standalone behavioral tests.
The separate [protected host transport](invoice-consumer-execution.md) now exercises
registered builds against the four rollout vectors. The backend checks all six compiler combinations, identity
separation, byte tampering, local re-pinning, missing/unregistered files and
dependencies, immutable capture and unchanged prior fixture/evidence identities.

Constrained execution and host-owned protocol assertions now cover eight rollout
exchanges. [Host signature verification](invoice-webhook-signatures.md) now adds
actual HMAC checks without changing the pinned consumer sources or dependencies.
The next reviewable changes add actual version-specific/legacy adapter verification,
full business execution and actual ordered joint observations. Existing fixture identities, business expectations,
campaign history, review criteria and routing remain unchanged.
