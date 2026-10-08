# Invoice renewal fixture registration

The first registered corpus covers the four rollout version vectors in the frozen
[paid-renewal contract](../cases/stripe-invoice-subscription-2025-03-31/business-oracle.md):

| Scenario | Webhook snapshot | Retrieval response |
| --- | --- | --- |
| `legacy-agreement` | `2025-02-24.acacia` | `2025-02-24.acacia` |
| `target-agreement` | `2025-03-31.basil` | `2025-03-31.basil` |
| `request-first` | `2025-02-24.acacia` | `2025-03-31.basil` |
| `event-first` | `2025-03-31.basil` | `2025-02-24.acacia` |

Each scenario independently expects both consumers to resolve `in_primary` to
`cus_case` / `sub_primary`. Each path alone appends one renewal marker. In either
combined order the first fact appends that marker and the matching second fact is
a duplicate. Both facts and both receipts remain necessary; a shared final marker
does not establish correct behavior of both consumers.

## Authored sources and protected answers

The [rollout-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/rollout-v1/bundle.json)
registers six raw UTF-8 JSON files: event, invoice retrieval and subscription-state
responses for each version. They are authored synthetic scoped representations,
with invented identifiers, not captured Stripe responses or customer payloads.
Provider-version and association-field facts come from the separately retained
[primary-source provenance](../cases/stripe-invoice-subscription-2025-03-31/provenance.json).
The payloads do not assert validity of every omitted invoice or subscription field.
SDK parsing and actual consumer behavior remain untested for this new case.

[Scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/rollout-v1/scenarios.json)
declare the source files, initial ledger, approved bindings and each consumer's
SDK, request, endpoint, account-default and adapter versions. In these four vectors
each path uses the SDK matching its declared payload version. An account default
does not overwrite an explicit request version; an endpoint declaration does not
rewrite a snapshot. Later old-delivery controls need separate adapter admission.
These declarations are registered test configuration, not observed live settings.

[Expected answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/rollout-v1/expectations.json)
are authored separately: normalized facts, each path alone, both arrival orders,
ordered ledger outcomes and final effects. They are not extracted from candidate
code or calculated by a Stripe parser. The protected host adds input/evidence
identities to each expected fact. Worker reads expose the inputs and those
identities, not the expected facts, ordered outcomes or an execution permission.
This separation assumes the future worker receives only its controlled input
channel, not filesystem access to the protected corpus.

## Integrity and identity

`InvoiceRenewalFixtures` pins the manifest SHA-256 in host code:
`9696c4ef9321c0dc61f41e843f29bd93082121cdba876e6085f70b984629a0d1`.
The manifest hashes all eight input/expectation files and the original contract
index; that index hashes the original README, business oracle and provenance.
The original contract files and their `CONTRACT_ONLY` historical status are
unchanged. This separate corpus records later implementation progress.

Changing bytes, even harmless whitespace, invalidates registration. Rewriting
the manifest cannot authorize new bytes without an explicit change to the host
pin. Only the fixed flat inventory is admitted. Reads are bounded to 256 KiB per
file and reject symbolic/nonregular files and linked ancestor directories. JSON
validation rejects duplicate keys, trailing data, malformed UTF-8, excessive
nesting and oversized containers. Errors do not include payload content.
This is a trusted-local-host boundary, not protection against concurrent malicious
filesystem replacement or an authenticated provider receipt.

Raw source hashes protect exact bytes, including explicit nulls. Consumer input
identities use the separate null-preserving normalized envelope described in
[host checks](invoice-renewal-host-checks.md). Evidence identity binds that input
to the manifest, scenario and delivery. It does not prove execution or signature
verification. Raw bytes remain available to a future host verification boundary;
registration currently neither verifies webhook signatures nor dispatches work.

## Coverage and validation limits

Coverage is **`ROLLOUT_VECTORS_ONLY`**: four of fifteen required scenario families.
`missingScenarioIds()` lists the other eleven. `requireFullCaseCoverage()` rejects
this partial registration. Expansion, multiple subscriptions, explicit standalone
and quote origins, missing/contradictory association, repeated delivery, conflicts,
state failures, unpaid invoices and old deliveries remain to be registered with
their required variants. Admission and incomplete-execution controls also remain.
No full-case execution or acceptance may treat this partial corpus as sufficient.

Default Java verification checks byte integrity, the four declared version
vectors, input/answer separation, immutable data and authored sequence consistency
against the pure host ledger. Those sequence checks supply golden facts in unit
tests; they produce no recorded consumer execution evidence. Linux CI additionally
tests symbolic source and ancestor rejection. Pinned Java consumer execution,
signature admission, a joint capture envelope, campaign readiness, coordination
benefit and release readiness are not established by this registration.
