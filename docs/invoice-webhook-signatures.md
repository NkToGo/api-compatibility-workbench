# Invoice webhook signature verification

`InvoiceWebhookSignatures` authenticates exact raw source bytes on the host using
Stripe's documented `Stripe-Signature` v1 scheme. It is an internal verification
boundary, not a deployed webhook receiver. No provider, model or credential service
is called. The registered historical fixtures and authored boundary conditions
remain unchanged.

## Authentication boundary

The host supplies the original bytes, signature header, active endpoint signing
keys, a clock and non-secret installation/endpoint/key-set version references.
Keys are not selected from event fields. The implementation computes HMAC-SHA256
over the canonical decimal delivery timestamp, a period and the **original bytes**;
it never authenticates parsed or reserialized JSON. Byte equality matters even
when two payloads parse to the same object.

Only `v1` signatures authenticate. Multiple v1 values and up to four configured
keys support rotation; every configured key/signature combination is compared
using `MessageDigest.isEqual` on 32-byte values without an early-match return.
Removing a key removes its ability to authenticate a fresh check. The host must
advance the key-set/version references when its signing configuration changes;
the verifier trusts those references rather than deriving a secret fingerprint.
This code does not schedule rotation or retrieve secrets.

The policy fixes a positive 300-second age window around the recorded check time.
It rejects both stale and excessively future-dated delivery timestamps, including
subsecond excursions outside the window. Event creation time is irrelevant: a
retry can legitimately carry an old event with a fresh delivery signature. The
future bound is intentionally stricter than the pinned Java SDKs' past-age check;
there is no zero-tolerance option that disables freshness.

The parser requires exactly one positive canonical timestamp and at least one
valid lowercase 64-hex v1 signature. Ambiguous timestamps, malformed v1 values,
control characters and trailing empty components reject the whole header. Unknown
well-formed schemes cannot authenticate. Bounds are 256 KiB of nonempty source,
4,096 header characters, 32 components, eight v1 signatures and four active keys.
Duplicate key references and invalid host configuration fail explicitly. Rejection
diagnostics contain fixed outcomes rather than request text.

## Evidence and admission

A receipt can only be created by the verifier in this process. It retains:

- Verification outcome, source SHA-256 and recorded check/delivery times.
- Fixed policy/window and an identity of the compiled verifier/helper classes.
- Host installation, endpoint and key-set version references; matching key references.
- A derived identity of this diagnostic record.

It retains no source body, signature/header, secret or secret-derived key
fingerprint. Diagnostic JSON is a copy and cannot be deserialized into trusted
receipt authority. The implementation identity covers the named compiled classes;
it is not a signature over an exported document or an identity of the entire JDK.

`InvoiceRenewalAdmission.inspectWebhook` ignores the authored signature flag and
requires an actual successful receipt for the same bytes and host scope. Wrong
installation, endpoint, key-set version or source binding blocks oracle creation
and worker startup. Failed and absent authentication remain separate from a
consumer timeout or failed behavioral assertion.

On this signature-only path, adapter state remains a supplied **authored condition**. A valid
signature cannot replace a missing, rejected or unchecked adapter. JSON validity,
event type, explicit API version, input/expectation identities, selected build and
business assertions remain independently checked. The old `inspect` path retains
its authored-control semantics and never acquires an authentic signature receipt.

For executions using the new path, `InvoiceSandbox` retains the signature record
only in the host result envelope. Worker startup remains limited to selected source,
SDK and dependency identities; no signing material or authentication receipt is
sent to the consumer. New harness identities include admission and signature
implementation classes. Earlier captured results retain their original identities.

## Validation and limits

Default Java verification includes a fixed UTF-8/CRLF vector independently
calculated with Node's crypto implementation and checked against both pinned
Stripe Java SDKs (28.4.0 and 29.0.0). Negative tests cover altered bytes, header
ambiguity/bounds, freshness, rotation/removal, copied diagnostics and cross-scope
reuse. Admission tests retain independent adapter, parsing, version and business
failures. The opt-in invoice container suite also performs one actual signed
synthetic webhook execution and retains `authenticated-webhook.json`; its adapter
condition remains authored. See [invoice execution](invoice-consumer-execution.md)
for image preparation and commands.

All signing keys in these tests are explicitly synthetic controls. Successful HMAC
verification shows that the bytes and timestamp match a configured signing key,
not that Stripe delivered a fixture or that an upgrade is correct. A receipt is historical
evidence, not a reusable dispatch lease or replay-prevention mechanism. A future
receiver must verify every new delivery, protect endpoint routing and secret
resolution, check clock health, and apply separate permissions/idempotency rules.
Actual version-specific/legacy adapter verification, full business/joint execution
and campaign acceptance are not established by signature checks. A separate
[observed adapter boundary](invoice-webhook-adapters.md) now verifies SDK parsing
and replaces the authored adapter flag through `inspectVerifiedWebhook`; full
business/joint execution and campaign acceptance remain pending.

The algorithm follows [Stripe's manual verification specification](https://docs.stripe.com/webhooks#verify-manually)
and [raw-body requirements](https://docs.stripe.com/events/manage-webhook-endpoints).
The small host implementation keeps historical SDK versions inside selected
consumer runtimes rather than making one SDK version the host's parser authority.
