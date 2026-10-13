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
rewrite a snapshot. The separately registered old-delivery controls below declare
legacy-adapter coverage; actual adapter verification and admission remain pending.
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

## Separate association corpus

The [association-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/association-v1/bundle.json)
adds four families without changing any `rollout-v1` or original contract bytes.
`InvoiceRenewalAssociationFixtures` pins its manifest SHA-256:
`991c268eb208ba25a1704ed7583e85d00cfa5165400f522eadd6a5f3ffff0940`.
It uses the same bounded byte/JSON boundary and original contract chain. This
manifest, its source files and its input/evidence identities remain distinct from
the rollout corpus. The [complete registry](invoice-renewal-registry.md) binds both
corpora explicitly, preserving their original identities.

| Family | Required variants now registered | Expected behavior |
| --- | --- | --- |
| `expanded-association` | Expansion on the webhook path or reconciliation path, each in Acacia and Basil | Same exact subscription as the other path's string reference |
| `two-subscriptions` | Acacia and Basil, two invoices for the same customer, reversed subscription listing order between paths | Two separate invoice/subscription bindings and two markers |
| `standalone-invoice` | Acacia explicit-null subscription; Basil explicit-null parent; manual billing reason | `NON_SUBSCRIPTION`, no marker |
| `quote-parent` | Acacia quote with explicit-null subscription; Basil guarded quote parent; quote billing reason | `NON_SUBSCRIPTION`, no marker |

The thirty source files are synthetic scoped invoice/event and subscription-state
representations. Quote origins use the pinned SDK's `quote_accept` billing reason,
legacy `quote` and Basil `parent.quote_details.quote` fields; these field facts are
covered by the existing provenance. An expanded association carries the identifier
and object type only; separate registered active-state evidence remains required.
Standalone and quote inputs still supply an unrelated active subscription: its
presence must not turn a non-subscription invoice into a renewal.

The two-subscription variants supply complete authored two-entry state lists with
opposite orders, not a live paginated query or customer-wide inference. Both
consumers must match the invoice association to the exact state and approved
binding. Each path processes both invoice deliveries independently. A path-alone
sequence appends two markers; either combined whole-path order appends two and
then retains two duplicates. These two block orders are not all possible
interleavings and do not establish concurrent database behavior.

[Scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/association-v1/scenarios.json)
name ten explicit variants and twenty-four deliveries, including configuration
and raw source references. [Separate expected answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/association-v1/expectations.json)
hold the semantic facts and forty ordered sequences. Expected classifications and
bindings are authored from the frozen application policy, not obtained from a
consumer or provider parser. Host registration attaches exact identities; worker
reads expose neither answers nor a permission grant. The subscription list is a
case fixture input, not a new general-purpose retrieval API.

## Missing and contradictory association corpus

The [refusal-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/refusal-v1/bundle.json)
registers two more frozen families. `InvoiceRenewalRefusalFixtures` pins its
manifest SHA-256:
`4a0eb74bce5f89b8ec85f200bda3dc5b2d7d16fa828bcac64fc7389521173f9f`.
Its twenty-four raw source files contain eleven invoice/event pairs and two
versioned active-state references. These are authored absence/rejection controls,
not captured responses or claims that malformed fields are valid provider shapes.

| Family | Registered variants | Independently expected fact |
| --- | --- | --- |
| `missing-association` | Acacia subscription field absent or null; Basil parent absent or null, subscription details absent or null, subscription ID absent or null | `UNRESOLVED` / `MISSING_ASSOCIATION` |
| `contradictory-parent` | Basil unknown parent discriminator; quote discriminator with subscription details; conflicting legacy and new association IDs | `UNRESOLVED` / `INVALID_ASSOCIATION` |

The missing variants still declare a subscription-cycle billing reason. An absent
or null association in that context cannot become a standalone invoice. Unknown
and contradictory parents cannot be resolved by choosing the first field or the
new field, or by ignoring contradictory details behind a quote discriminator.
Both paths preserve invoice/customer and known version, return no subscription
winner and retain the specific refusal reason. A supplied active `sub_primary`
state cannot repair an absent or contradictory association by itself.

[Scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/refusal-v1/scenarios.json)
register eleven explicit variants and twenty-two deliveries. [Separate expected answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/refusal-v1/expectations.json)
hold the facts and forty-four sequences: each path alone and both arrival orders.
Every registered sequence starts empty, records `NO_EFFECT` for every receipt and
ends empty. Correct refusal facts still require a completed, valid result exchange;
an operational failure or missing result remains `NOT_EVALUATED`.
These unit checks do not implement signature or adapter admission controls.

The association and refusal catalogs use `InvoiceRenewalVariantFixtures` for their
shared, package-private registration shape. Each catalog supplies compiled variant
identities, exact versions, its own inventory and its own manifest pin. This
sharing changes neither existing source bytes nor normalization. A retained
twenty-four-delivery identity reference was checked before and after extraction;
the original association input/evidence identities are unchanged. The rollout
loader and both earlier raw corpora remain unchanged. This boundary is specific
to these invoice inputs; it does not register arbitrary repositories or execute
consumers. All returned bundles remain immutable and reject full-case use.

## Repeated delivery and conflict corpus

The [delivery-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/delivery-v1/bundle.json)
registers two further frozen families. `InvoiceRenewalDeliveryFixtures` pins its
manifest SHA-256:
`9f8a794fd81f34ca322b9b3d867bc036080231255f716ce1bf7c0b7bc92af376`.
Eighteen synthetic source files hold fourteen distinct invoice/event inputs and
four versioned active-state references. Each variant uses one known API version;
Acacia and Basil each have all three variants below. Mixed rollout version vectors
remain in the original corpus, whose bytes and identities are unchanged.

| Family and variant | Initial ledger and delivery sequence | Separately expected behavior |
| --- | --- | --- |
| `duplicate-delivery` | Empty ledger; the same webhook input twice, another event ID for that invoice, and the same reconciliation input twice | Each fact resolves `sub_primary`; one append, remaining receipts duplicate; retain every observation |
| `conflicting-association`, seeded | Existing `in_primary` / `sub_primary` effect; both paths independently assert `sub_secondary` | Locally resolved facts, every receipt `CONFLICT`; no new effect and original value preserved |
| `conflicting-association`, fresh | Empty ledger; webhook asserts `sub_primary`, reconciliation asserts `sub_secondary` for the same invoice/customer | Each path alone appends its declared value; combined order appends the first and records the second as `CONFLICT` |

[Scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/delivery-v1/scenarios.json)
hold six variants, fourteen distinct deliveries and exact initial effects.
[Separate expected answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/delivery-v1/expectations.json)
hold twenty-four sequences and fifty-four receipts across each path alone and both
whole-path orders. Repeating a delivery ID means repeating the exact registered
input, including its event/retrieval bytes and input/evidence identities. It does
not collapse two observations into one receipt. The alternate event has distinct
input/evidence identity while retaining the same invoice/subscription binding.
These block orders are not every possible interleaving or a concurrent database test.

Both conflicting subscriptions are separately approved and have matching active
state. Their disagreement is a joint effect conflict, not an invalid local fact.
Fresh combined orders deliberately retain different first values; neither order
resolves the disagreement or permits approval under the frozen policy. Expected
conflicts passing their local assertions must remain visible to the future joint
readiness check. A matching final ledger cannot conceal a failed fact assertion.
The pure ledger already retains these outcomes; registration adds exact input and
answer bytes, not a new conflict-resolution algorithm.

The shared loader uses compiled layouts for repeated paths, state selection and
the exact seeded effect. Payloads cannot change these layouts or authorize new
files. Initial effects and answers stay host-side; worker reads expose only bound
inputs. Both earlier association and refusal catalogs retain their original
normalization: forty-six retained input/evidence identity pairs were checked before
and after the layout extension. All three earlier corpora and the original
contract remain byte-for-byte unchanged. Each corpus keeps its own pin and
partial-coverage gate.

## Unavailable subscription-state corpus

The [state-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/state-v1/bundle.json)
adds the frozen `unavailable-state` family. `InvoiceRenewalStateFixtures` pins its
manifest SHA-256:
`71a4413a2a85e9380aa7f4188f2de2dbf5941e4c143793c4b49f30aa52987a48`.
Thirty authored source files contain twenty invoice/event inputs and ten separate
state records. Acacia and Basil each register all five conditions below. Every
invoice is paid and explicitly associates `in_primary` / `cus_case` with
`sub_primary`; that known association must survive the refusal.

| State condition | Authored input | Separately expected fact |
| --- | --- | --- |
| Absent | Explicit lookup descriptor with `ABSENT` outcome and null response | `UNRESOLVED` / `STATE_UNAVAILABLE` |
| Failed | Explicit lookup descriptor with `FAILED` outcome, null response and bounded `LOOKUP_FAILED` code | `UNRESOLVED` / `STATE_FAILED` |
| Inactive | Matching subscription/customer with `canceled` status | `UNRESOLVED` / `STATE_NOT_ACTIVE` |
| Wrong customer | Active `sub_primary` state names `cus_other`, not the invoice's customer | `UNRESOLVED` / `STATE_BINDING_MISMATCH` |
| Wrong subscription | Active `sub_secondary` state names the same customer, but not the invoice's association | `UNRESOLVED` / `STATE_BINDING_MISMATCH` |

The absent and failed records use the source kind `authored-state-lookup`.
They are synthetic descriptions of lookup conditions, not Stripe Subscription
objects, captured provider errors or live lookup receipts. Their null responses
do not invent a successful provider response. The envelope's API version is the
registered lookup configuration, not evidence of an observed response version.
The other three records use the existing `subscription-retrieval` kind. These
scoped records do not claim every omitted provider field is valid. No lookup or
consumer is executed during registration.

[Scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/state-v1/scenarios.json)
hold ten variants and twenty deliveries, each starting with an empty ledger.
[Separate expected answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/state-v1/expectations.json)
hold forty sequences and sixty receipts across each path alone and both whole-path
orders. Every expected fact preserves the known invoice/customer/subscription and
known version with its specific reason. Every expected receipt has `NO_EFFECT`
and every final ledger remains empty. Neither a paid invoice nor another active,
approved subscription may substitute for matching active-state evidence.

A correct completed refusal passes its local assertion. A lookup-failure input
is distinct from a consumer infrastructure failure: a failed consumer run or
missing return remains `NOT_EVALUATED`, never a fabricated passing refusal.
Wrong refusal reasons, lost known associations and false standalone classifications
fail even when the final ledger is empty. An invented resolved fact fails its
assertion while its actual erroneous simulated effect remains visible. Supported
refusals never erase existing effects. These checks do not establish actual
consumer behavior or readiness for an unavailable-state condition.

State selection and source kinds come from the compiled catalog; payloads cannot
change file admission or permissions. The shared loader preserves every older
source kind and normalization. Sixty retained association/refusal/delivery
input/evidence identity pairs were checked before and after this extension.
The original contract and all four earlier corpora remain byte-for-byte unchanged.
The new corpus has its own pin, immutable inputs and blocking partial-coverage gate.

## Unpaid invoice corpus

The [unpaid-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/unpaid-v1/bundle.json)
adds the frozen `not-paid` family. `InvoiceRenewalUnpaidFixtures` pins its manifest
SHA-256: `a48cb128e73a59365e30938e66ba19e5d8050cbca6539ed92f024f8e5b074ec1`.
Ten authored source files hold eight invoice/event inputs and two scoped active
subscription records. All invoices have `status=open`, `paid=false`, a subscription
cycle origin and a known `in_primary` / `cus_case` / `sub_primary` association.
Both Acacia and Basil register each initial-ledger condition:

| Condition | Declared initial effects | Separately expected outcome |
| --- | --- | --- |
| `open-fresh` | Empty | `UNRESOLVED`, reason `NOT_PAID`; no new effect; empty final ledger |
| `open-seeded` | One `in_primary` / `sub_primary` paid-renewal marker | Same refusal; no new effect; preserve the original marker |

The [scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/unpaid-v1/scenarios.json)
and [answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/unpaid-v1/expectations.json)
remain separate. Each variant declares each path alone and both whole-path arrival
orders: sixteen sequences and twenty-four `NO_EFFECT` receipts. Every refusal
preserves the known customer, subscription and version. Active subscription state
does not make an open invoice paid. Existing markers are initial host state, not
a claim that a real invoice transitioned from paid back to open. This policy does
not decide cancellation, dunning, entitlement revocation or payment amounts.

The frozen webhook boundary remains `invoice.paid`. Its open-invoice snapshot is
**deliberately inconsistent authored rejection data**, not a claimed Stripe event
shape or delivery. The reconciliation input is an authored scoped open-invoice
representation. Neither path supplies provider-observed evidence. A registered
event type, adapter version or seeded ledger cannot substitute for checking the
invoice's own fields. Source/event validity and dispatch remain subject to later
admission controls; these unit tests do not establish that such an event should be
dispatched in a deployed system.

The shared loader adds a compiled `SEEDED_REFUSAL` layout restricted to `not-paid`
with active-state evidence. It pins the initial marker without exposing it or
expected answers to workers. All earlier source kinds, input keys and serialization
remain unchanged. Eighty retained association/refusal/delivery/state input/evidence
identity pairs were checked before and after this extension. The original contract
and all five earlier corpora remain byte-for-byte unchanged. This corpus has its
own pin and blocking partial-coverage gate.

Incorrect refusal reasons, lost associations and false standalone answers fail
even when their effects match. A false resolved answer retains its failed fact
and actual erroneous simulated append; against the seeded ledger it can produce
a duplicate and the correct final effect while still failing. The expected fact
never replaces the returned one. Correct refusals cannot erase other preexisting
effects. Failed, missing or unbound output cannot become a passing refusal or alter
the declared initial effects.

## Old delivery after endpoint upgrade

The [old-delivery-v1 bundle](../cases/stripe-invoice-subscription-2025-03-31/fixtures/old-delivery-v1/bundle.json)
has a separate compiled pin in `InvoiceRenewalOldDeliveryFixtures`:
`566d590d793fcdb09e8809cd9306732b32fd677ad805803d58f022aa2179a2cb`.
Three authored sources describe an Acacia `invoice.paid` snapshot, a Basil invoice
retrieval and Basil active-state evidence for the same invoice/subscription. Both
paths declare SDK `29.0.0`, explicit Basil requests and a Basil endpoint. The event
retains its original Acacia version and legacy association field. The subscription
state record has its own Basil version; neither the SDK nor the endpoint rewrites
the event. No legacy SDK is silently substituted for the declared target SDK.

[Scenario inputs](../cases/stripe-invoice-subscription-2025-03-31/fixtures/old-delivery-v1/scenarios.json)
bind an authored delivery context: creation before the endpoint upgrade, delivery
after it. Those timestamps are registered conditions, not a runtime-clock inference
or an authenticated provider delivery. Two variants share the same raw sources:

| Variant | Webhook adapter declaration | Separate expected boundary |
| --- | --- | --- |
| `legacy-adapter-declared` | Acacia | Both paths have separately expected resolved facts; each path alone appends once and combined orders append once with a duplicate |
| `legacy-adapter-absent` | None | Webhook blocked for `UNSUPPORTED_ADAPTER`, with no expected consumer fact; reconciliation still resolves and appends once |

[Answers](../cases/stripe-invoice-subscription-2025-03-31/fixtures/old-delivery-v1/expectations.json)
remain separate from inputs. Eight authored sequences cover each path alone and
both arrival orders. Golden-fact tests produce nine ledger receipts and retain
three blocked positions as `BLOCKED_MISSING_ADAPTER`, separately from receipts.
The missing-adapter webhook has no oracle and no execution observation. It is not
a completed consumer returning a passing refusal or an infrastructure failure.
Reconciliation's successful fact/effect remains visible without satisfying the
blocked webhook path. A matching combined ledger cannot conceal that gap.

A separate loader records these different boundaries without changing the shared
variant loader, normalized oracle/ledger, original contract or six older corpora.
Worker inputs expose source/configuration/context identities, not the expected
facts, blocked-answer metadata, sequence outcomes or grants. Source inventory,
byte pins, linked-file restrictions and immutable copies reuse the existing host
boundary. Adapter declarations remain configuration evidence only: these tests
run neither SDK parsing nor a legacy adapter, perform no signature verification
and grant no runtime execution permission.

Incorrect effective versions remain failed facts even when they append the expected
marker. Incorrect associations retain conflicts and the first ledger value.
Incomplete or unbound returns cannot satisfy the old-event path or overwrite
successful evidence. Missing sources, changed contracts, extra files and rewritten
manifests cannot register alternative inputs under this pin.

## Coverage and validation limits

The eight coverage stages remain **`ROLLOUT_VECTORS_ONLY`**,
**`ASSOCIATION_VARIANTS_ONLY`**, **`ASSOCIATION_REFUSALS_ONLY`**,
**`DELIVERY_VARIANTS_ONLY`**, **`SUBSCRIPTION_STATE_VARIANTS_ONLY`**,
**`UNPAID_VARIANTS_ONLY`**, **`OLD_DELIVERY_VARIANTS_ONLY`** and
**`BOUNDARY_CONTROLS_ONLY`**.
Collectively they represent all fifteen families. The
[complete registry](invoice-renewal-registry.md) now binds all 47 business variants,
188 ordered sequences and 34 boundary controls with exact variant/order coverage.
The original rollout bundle's `missingScenarioIds()` continues to describe that
bundle alone. Every partial bundle's `requireFullCaseCoverage()` still rejects
full-case use; only the complete registration passes its fixture-coverage gate.
A separate
[boundary corpus](invoice-renewal-boundary.md) now registers admission and
incomplete-execution conditions with authored host verification states. No
full-case execution or acceptance may treat these partial corpora as sufficient.

Default Java verification checks byte integrity, the four declared version
vectors, input/answer separation, immutable data and authored sequence consistency
against the pure host ledger, plus the association shapes, reversed state lists,
explicit nulls, separate identities and forty new authored sequences. A deliberately
wrong first-entry/customer-only answer fails its fact assertion even when it
appends the expected number of markers. Refusal checks cover the eleven registered
input shapes and forty-four authored sequences. A wrong `NON_SUBSCRIPTION` answer
fails its fact assertion despite producing the same empty ledger; a failed run or
missing result does not become a passing refusal. Delivery checks retain repeated
receipt evidence, independently valid conflicting facts and order-dependent first
effects. Wrong facts that hide conflicts behind duplicates remain failed; missing,
failed or unbound output cannot change a seeded effect. State checks retain five
distinct conditions, known associations and specific reasons in both versions.
Unpaid checks retain four fresh/seeded variants, `NOT_PAID` facts, existing effects
and failed claims despite a matching final ledger. Old-delivery checks retain the
Acacia event version after the declared upgrade, separate Basil state evidence,
missing-adapter blocks and successful reconciliation without fabricated observations.
Those sequence checks supply golden facts in unit tests; they produce no recorded
consumer execution evidence. Linux CI additionally
tests symbolic source and ancestor rejection. Pinned Java consumer execution,
signature admission, a joint capture envelope, campaign readiness, coordination
benefit and release readiness are not established by this registration.
