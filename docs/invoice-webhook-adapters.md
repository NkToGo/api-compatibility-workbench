# Observed invoice webhook adapters

The host can verify safe SDK parsing of an authenticated invoice snapshot in a
fixed isolated worker. `InvoiceWebhookAdapters` requires an actual
[signature receipt](invoice-webhook-signatures.md) for the same bytes and host
installation/endpoint/key-set scope before requesting any execution. It accepts
no consumer source, expected business answer, model verdict or ledger state.

## Explicit supported routes

| Main SDK | Original event / declared adapter API | Selected parser SDK |
| --- | --- | --- |
| 28.4.0 | 2025-02-24.acacia | 28.4.0 |
| 29.0.0 | 2025-03-31.basil | 29.0.0 |
| 29.0.0 | 2025-02-24.acacia | Explicit companion 28.4.0 |

These are exact registered versions. There is no `latest`, account-default or
endpoint-default inference, forward adapter under the older main SDK, or broader
same-release-train claim. An absent adapter declaration remains `ABSENT`, even
when its version could be guessed from the event. Unknown, contradictory and
unsupported inputs reject before dispatch. The old event retains Acacia while
the declared main SDK, request version and upgraded endpoint retain Basil.

The worker opens separate class loaders with platform-only parents for the
selected SDKs and pinned Gson 2.10.1. It checks the actual SDK/API constants and
uses the SDK's safe `EventDataObjectDeserializer.getObject()` path. It never calls
unsafe deserialization or rewrites event versions/association fields. SDK objects
stay inside their own loader; only primitive invoice values cross into the host
protocol. The primary parser's refusal of an old event is retained separately
from successful parsing by the declared companion.

This follows the version distinction in the
[pinned official SDK deserializer](https://github.com/stripe/stripe-java/blob/v29.0.0/src/main/java/com/stripe/model/EventDataObjectDeserializer.java)
and [Stripe's snapshot webhook versioning guidance](https://docs.stripe.com/webhooks/versioning).
This implementation deliberately supports only the three rows above; it is not a
general recommendation to load arbitrary SDK versions alongside an application.

## Isolation and protocol

The existing [invoice image](invoice-consumer-execution.md) already contains both
checksum-pinned SDKs. No host SDK dependency or new download is needed. A fixed
`InvoiceEventAdapterWorker` entry is added to the existing controller allowlist.
It has the same network, filesystem, user, capability, resource, deadline and
cleanup restrictions as other protected workers. Each library is checked against
the captured registration before loading; no key or credential is sent.

Startup holds only the main SDK, explicit adapter API, base64 of the exact signed
bytes and the three dependency hashes. This entry alone allows a 400,000-byte
startup frame to transport up to 256 KiB of source without reserialization. Other
workers retain their 150,000-byte limit. The response remains bounded to 16 KiB,
with strict duplicate-field/JSON parsing and one result followed by an acknowledgement.
There is no arbitrary class, path, source compilation or command argument supplied
by the event. SDK reflection uses fixed class/method names and allowlisted paths.

The host checks the report's source, main/adapter SDK and API identities, primary
acceptance state and normalized invoice values against a separate projection of
the original source. That projection verifies parsing fidelity, not paid-renewal
policy, approved associations, subscription state or ledger effects. A correct
parse can still produce a failed business assertion.

Missing, malformed, duplicate, trailing or contradictory reports cannot verify.
A valid-looking report followed by interruption, a failed process, timeout,
cancellation or failed cleanup remains `NOT_EVALUATED`. A completed SDK schema
refusal is `REJECTED`, separate from a process failure and from any consumer's
business refusal. Protocol controls retain reported values and invalid state;
sealed results cannot be rewritten by a later call.

## Receipts and admission

Only the actual execution path can construct a trusted adapter receipt. It binds
the source and signature receipt identities, host scope, declared main SDK and
adapter API, dependency/configuration/harness/environment identities and immutable
image. Its diagnostics retain execution facts and observed parser acceptance,
plus hashes of the report/projection. They omit the body, signing material and
invoice values. Diagnostic copies cannot restore receipt authority.

`InvoiceRenewalAdmission.inspectVerifiedWebhook` ignores both authored verification
flags and requires the actual signature and adapter receipts to match the input.
Wrong source, scope, signature receipt or SDK/version binding blocks oracle creation.
Successful consumer execution retains both verification records on the host;
neither record travels in consumer startup. The signature-only `inspectWebhook`
path and original `inspect` remain explicitly controlled paths; they do not gain
observed adapter authority. All fixture files, pins and historic records remain
unchanged.

## Validation and remaining execution work

Default tests cover strict route selection, pre-dispatch rejection, full-sized
startup, unchanged limits for older workers and adversarial protocol exchanges.
These authored protocol exchanges are not SDK execution evidence.

After the README build and `./scripts/Build-InvoiceWorker.ps1`, run on Windows:

```powershell
$invoiceImage = (Get-Content backend/target/invoice-worker-image.txt -Raw).Trim()
.\mvnw.cmd -B -ntp -f backend/pom.xml "-Dworkbench.invoice.image=$invoiceImage" '-Dtest=InvoiceSandboxContainerTest,InvoiceEventAdapterContainerTest' test
```

Use `sh ./mvnw` on Linux. CI executes both classes and retains their reports.
The new six-test container suite exercises all three parser routes, an actual
verified Basil business exchange, the separately pinned old-delivery cases,
real SDK schema refusal, dependency/startup rejection and interruption. Redacted
receipt JSON is kept under ignored `backend/target/invoice-adapter-tests/`.

This establishes observed parsing for synthetic controls only. The old-delivery
case can acquire actual parsing/admission evidence while preserving its main
SDK. The frozen consumer build matrix still rejects business dispatch for that
mixed version: it contains no registered dual-runtime business consumer. Such a
consumer must be registered explicitly. A separate [business source registration](versioned-invoice-business-builds.md)
now compiles its main/companion components; protected dispatch is still pending.
An Acacia baseline cannot silently replace
the declared Basil main SDK. Full business coverage, ordered joint execution,
comparison capture and coordination acceptance remain pending. No migration or
live provider delivery is certified by these receipts.
