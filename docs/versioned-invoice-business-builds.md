# Versioned webhook business consumer builds

The separate [versioned source registration](../cases/stripe-invoice-subscription-2025-03-31/versioned-webhook-v1/build-registration.json)
declares an authored business consumer for an Acacia event delivered after a Basil
main-SDK and endpoint upgrade. `InvoiceVersionedConsumerBuilds` captures its source
and compiles two components with separate SDK classpaths. It does not load either
component, authorize execution, create an observation or establish a passing
business assertion.

## Explicit route and component boundary

The registered build is `webhook-basil-with-acacia-companion`:

| Configuration | Exact value |
| --- | --- |
| Main SDK / main parser API | `29.0.0` / `2025-03-31.basil` |
| Original event / declared companion API | `2025-02-24.acacia` |
| Request / upgraded endpoint API | `2025-03-31.basil` |
| Companion SDK | `28.4.0` |

The `main` component compiles `VersionedInvoiceWebhookConsumer` and the original
`RenewalPolicy` against SDK 29 and Gson 2.10.1. The `acacia-adapter` component
compiles `AcaciaInvoiceAdapter` against SDK 28 and Gson 2.10.1. The companion is
not a dependency on the main application's classpath. Unknown components cannot
reach the compiler.

The main source requires the explicit route, retains the original event version
and uses the primary SDK's safe event parser. Its intended legacy path requires
that primary parser to refuse the old object, then loads the declared companion
in a separate class loader with a platform-only parent. Only a five-element string
array crosses that boundary; SDK objects and Gson types stay inside their own
runtime. Actual SDK/API fields are read reflectively so Java constant inlining
cannot disguise a different runtime. Neither component calls Stripe.

Three fixed system properties identify the companion classes, SDK and Gson:
`workbench.invoice.legacy.classes`, `workbench.invoice.legacy.sdk` and
`workbench.invoice.legacy.gson`. They are not read from an event or model output.
The separate [protected bootstrap](versioned-invoice-business-execution.md) sets
them from its verified runtime closure; their presence alone is not authentication
or permission to execute.

After extraction, the main source applies the existing consumer business policy
for invoice association, paid status, approved binding and supplied subscription
state. The source imports that policy's exact captured bytes; it does not copy or
change the host oracle. The separate executor now observes this policy on one
authenticated old-delivery exchange; full business coverage remains pending.

## Source and compiler identities

The new host pin is
`8965f53b87cadfc03a1111b7f58d16332b53d7c9905d718bb61dc827491c0eb4`.
The stage is `VERSIONED_SOURCE_AND_BUILD_ONLY`. The manifest binds six new
source/POM files and the original registration identity, which already binds the
shared policy, original business contract, complete fixture registry and six
historical dependency artifacts. No additional dependency download is required.

The [original six-build matrix](invoice-consumer-builds.md), its source files,
registration pin and expected upgrade failures remain unchanged. The new loader
validates that registration before accepting its own manifest. Changed bytes,
missing or unregistered files, linked ancestors and local manifest re-pinning
cannot authorize new code. Only named Maven `target` directories are excluded
from source inventory; generated files never replace captured inputs.

Each component has separate source, configuration and dependency identities.
Captured bytes are immutable. The compiler uses JDK 21, UTF-8, `--release 21`,
disabled annotation processing and strict warnings, with the same internal
captured-source compiler used by the original catalog. It executes no Maven
descriptor or consumer class. Diagnostic records contain codes, source basenames
and positions, not source snippets or local output paths.

## Build and validation

After the README preparation commands, the default backend tests compile both
components and retain compiler records under ignored
`backend/target/versioned-invoice-build-tests/`. They also compile the companion
against SDK 29 as a negative control: that build fails because the old invoice
accessor is absent. A failed compile remains a failed compile.

To package the three-module reactor on Windows:

```powershell
.\mvnw.cmd -B -ntp -f cases/stripe-invoice-subscription-2025-03-31/versioned-webhook-v1/pom.xml clean package
```

Use `sh ./mvnw` on Linux/macOS. The policy module reads the original pinned policy
source directory; the main and companion modules declare their SDKs separately.
CI packages this reactor in addition to both original profiles, then retains the
redacted compiler records with the case reports.

These are compiler facts only. The existing sandbox still accepts only its
original build catalog; this registration cannot silently dispatch through it.
The separate [isolated mixed-version executor](versioned-invoice-business-execution.md)
now requires actual verifier authority and observes the registered old-delivery
exchange. Full business and ordered joint coverage remain next. Signature/parser
receipts, business observations and ledger effects remain separate. The earlier campaign, review
criteria and routing are unchanged, and Milestone 4 acceptance remains open.
