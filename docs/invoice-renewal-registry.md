# Complete invoice fixture registry

`InvoiceRenewalRegistry.load(caseRoot)` registers the authored invoice workload
through one complete host-owned entry point. It loads each existing corpus with
its original compiled manifest pin, then compares the loaded variant and control
inventory against a separately pinned coverage catalog. A missing or changed
member prevents the registration from being returned.

This completes fixture registration. It does not execute the new consumers,
verify a webhook signature, establish an adapter implementation, authorize a
worker or satisfy migration/campaign acceptance.

## Coverage catalog

The [registry-v1 manifest](../cases/stripe-invoice-subscription-2025-03-31/fixtures/registry-v1/bundle.json)
pins [registry.json](../cases/stripe-invoice-subscription-2025-03-31/fixtures/registry-v1/registry.json)
and the unchanged original contract index. Its host-code pin is:
`ca7579e6856f50ce0264961f8511aed7c9f83af256bb934671217a99a867f9be`.
The stage is `COMPLETE_FIXTURE_REGISTRATION_ONLY`; the catalog authority is
`fixture-registration-only`.

| Corpus | Business variants | Distinct deliveries | Ordered sequences |
| --- | ---: | ---: | ---: |
| `rollout-v1` | 4 | 8 | 16 |
| `association-v1` | 10 | 24 | 40 |
| `refusal-v1` | 11 | 22 | 44 |
| `delivery-v1` | 6 | 14 | 24 |
| `state-v1` | 10 | 20 | 40 |
| `unpaid-v1` | 4 | 8 | 16 |
| `old-delivery-v1` | 2 | 4 | 8 |
| Total | 47 | 100 | 188 |

These variants cover all fifteen frozen business families. The catalog also
requires all 34 `boundary-v1` admission/result controls and their three declared
test placements: alone, healthy companion first and healthy companion last.
The existing boundary tests cover those 102 exchanges. Controls are a separate
catalog, not another business family or observed consumer execution.

Family names alone cannot satisfy the gate. The catalog binds each corpus's
manifest identity, role and installation, each variant's ID and family, its
delivery inventory, blocked delivery inventory, and every named sequence with
its exact ordered delivery IDs. Repeated deliveries remain repeated. Different
arrival orders and seeded/fresh variants remain distinct. The original corpus
pins also bind raw bytes, initial effects, expected facts, ordered outcomes and
final effects; the registry does not recalculate or replace those expectations.

## Identity and data boundaries

The complete registration's identity is the new registry manifest identity.
Each constituent corpus retains its own manifest identity. Existing normalized
inputs and input/evidence identities are unchanged: the aggregate identity is
not substituted into previously registered consumer facts.

All member manifests must reference the same original contract identity.
Registration uses the existing bounded byte/JSON checks, exact per-corpus file
inventories and linked-file/ancestor rejection. Rewriting a local manifest cannot
authorize different coverage without changing the compiled host pin. Files are
captured into immutable in-memory bundles; later reads return copies of those
captured bytes, rather than reopening changed source files.

The host receives six ordinary business corpora, the separately typed old-delivery
corpus and the separately typed boundary-control corpus. The missing-adapter
webhook still has no expected consumer fact, oracle, observation or ledger receipt.
Successful reconciliation does not remove its block. Authored host-verifier
states remain authored conditions and do not become authenticated receipts.

`inventory()` returns a copy of coverage metadata, without normalized fact
answers or payloads. It is not a worker tool, authorization record, result or
public wire schema. Metadata comparison cannot construct a trusted registration;
only the byte-validating loader can. Protected corpus bytes and expected facts
must remain host-only when the execution adapter is added.

## Coverage gate and validation

Only the complete `Registration.requireFullCaseCoverage()` passes. Every existing
partial bundle retains its rejecting gate. The rollout bundle's
`missingScenarioIds()` still describes that bundle alone.

Default Java verification checks the complete census and gate, missing/substituted
variants despite retained family names, missing controls/companion placements,
reordered/repeated deliveries, blocked positions, changed scope or authority,
unchanged constituent identities and facts, immutable retained data, and missing
or changed source bytes in every member corpus. It also rejects attempted local
re-pinning, changed contract bytes and unregistered registry files. Existing
oracle/ledger and boundary tests retain their independent golden expectations.

Pinned Java [consumer sources and compiler results](invoice-consumer-builds.md)
are now registered separately. A separate [business execution driver](invoice-business-coverage.md)
uses actual signature/parser verification and protected consumers across all
distinct business deliveries. A separate [ordered runner](invoice-ordered-coverage.md)
applies those typed observations through every registered business order without
dispatching consumers again. Boundary coverage, joint capture
and comparison, reviewed coordination benefit and release approval remain
separate work. No historical campaign, rubric, result or routing rule is
changed by complete fixture registration.
