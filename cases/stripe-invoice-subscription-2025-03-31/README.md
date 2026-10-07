# Invoice subscription identity across webhook and reconciliation consumers

Case ID: `stripe-invoice-subscription-2025-03-31`.
Status: **contract only; no executable consumer, fixture bundle or measured result**.

A subscription application records paid renewals from both invoice webhooks and a
scheduled reconciliation job. During a Stripe API upgrade, the two paths can read
different versions of the same invoice. Both must identify the same subscription
and produce one renewal record. Losing the association can miss a paid renewal;
guessing from the customer can attribute it to a different subscription.

This case selects that migration problem and freezes its authored business oracle
before consumer implementation or participant execution. It does not extend the
existing known-case campaign or establish useful-coordination acceptance.

## Provider facts and source provenance

Stripe's [2025-03-31.basil parent-field change](https://docs.stripe.com/changelog/basil/2025-03-31/adds-new-parent-field-to-invoicing-objects)
moves the invoice's subscription association into its typed parent. The target
path is `parent.subscription_details.subscription`, guarded by
`parent.type == subscription_details`; the previous path is `subscription`.
This is a provider schema fact, not a business rule for granting access.

The pinned Java SDKs are `28.4.0` (`2025-02-24.acacia`) and `29.0.0`
(`2025-03-31.basil`). Their generated invoice models and `ApiVersion.java` confirm
the fields and version mapping. These are a historical transition, not a claim
about the latest SDK or a recommendation to deploy either version today.

Stripe's own Java subscription sample uses the old association at
[revision eeb7737](https://github.com/stripe-samples/subscription-use-cases/blob/eeb773742e7bec3b8f6dd278abefb612e6e55a0a/fixed-price-subscriptions/server/java/src/main/java/com/stripe/sample/Server.java#L393)
and the parent association at
[revision fb6e9d4](https://github.com/stripe-samples/subscription-use-cases/blob/fb6e9d4a01bcbaea17eb3ad686e0e97e79f1def2/fixed-price-subscriptions/server/java/src/main/java/com/stripe/sample/Server.java#L406).
Those revisions pin SDKs `20.48.0` and `31.2.0`, respectively. They document a real
consumer use of the association; they are **not** the executable baseline/target
for this case, and their `invoice.paid` provisioning comments are not implemented
downstream services.

The [webhook upgrade guide](https://docs.stripe.com/webhooks/versioning) distinguishes
endpoint versions from request versions. An SDK upgrade does not rewrite an old
event. The selected SDK deserializer compares release trains; this case nevertheless
admits only the two exact declared API versions. A successful unsafe deserialization
is not evidence of semantic compatibility. Stripe also documents duplicate delivery
and unordered events in its [webhook guide](https://docs.stripe.com/webhooks).

[provenance.json](provenance.json) records exact source revisions, retrieved-byte
hashes, rights and Maven artifact identities. Provider documentation can change;
its hashes identify retrieved bytes, not immutable URLs or authenticated receipts.
No provider documentation, SDK source, upstream consumer code or binary is copied
into this case. Future excerpts require their license notices before redistribution.

## Authored workload and shared contract

Two separately built Java/Maven consumers are planned:

| Consumer | Input and boundary | Required output |
| --- | --- | --- |
| `invoice-webhook` | Host-verified `invoice.paid` snapshot; declared event API version; no arbitrary provider requests | Invoice/subscription fact, explicit non-subscription result, or unresolved reason |
| `invoice-reconciliation` | Registered invoice retrieval response; explicit request version; bounded registered subscription-state response | The same fact/result for the same invoice, without inferring its association from another subscription |

The host supplies the same approved subscription/customer binding and active-state
evidence to both paths when an effect is eligible. Separate tasks retain source,
SDK, version configuration and execution facts independently. Different module
roles and delivery contexts do not imply independent organizations or customers.

The shared `paid-renewal-v1` application policy in
[business-oracle.md](business-oracle.md) defines the canonical fact, idempotency,
ambiguity handling and scenario outcomes. The fact preserves invoice ID, customer
ID, subscription ID, effective API version and evidence identity. A host-owned
ledger applies a resolved, eligible fact once per `(installationId, invoiceId)`.
It detects conflicting associations instead of silently overwriting an earlier
record. The ledger is an authored application policy, not a Stripe API guarantee.

Consumer compilation, local unit answers and matching prose are insufficient.
The protected joint harness must feed both consumers' actual outputs to that
ledger and compare effects, refusal reasons and retained state with the oracle.
It must also test each path alone and both arrival orders so a broken consumer
cannot be hidden by the other one doing the work.

This topology is **authored**, grounded in the documented provider change and
upstream association use. It is not an extraction of two production services, a
reproduction of a reported customer incident or evidence of adoption. Fixtures
will be synthetic. No live Stripe account, production data or credential is needed
to implement the bounded workload. Live sandbox validation would be separate
evidence with separately authorized configuration.

## Selected scope and rollout conditions

The comparison keeps expected behavior unchanged while source/configuration and
effective provider versions change. The registered fixture bundle must include
both historical representations; fixtures, oracle, joint harness and environment
remain identical across compared candidates.

| Condition | Webhook event version | Reconciliation request version |
| --- | --- | --- |
| Baseline | `2025-02-24.acacia` | `2025-02-24.acacia` |
| Request path upgraded first | `2025-02-24.acacia` | `2025-03-31.basil` |
| Event path upgraded first | `2025-03-31.basil` | `2025-02-24.acacia` |
| Target | `2025-03-31.basil` | `2025-03-31.basil` |

Old deliveries in the target condition remain separate, explicitly versioned
inputs; processing them requires a verified legacy adapter or a blocked outcome,
not relabeling the event as Basil. A mixed rollout requires compatible versioned
adapters. This contract does not prescribe unsafe parsing, account-wide upgrades,
automatic deployment or one mandatory implementation strategy.

Per-consumer configuration must identify its SDK, request override, endpoint/event
version and account-default metadata independently. Unknown mappings block readiness.
Each consumer comparison retains its own effective version. A later joint result
must reference those comparisons and the version vector; it must not assign one
invented Stripe API version to a mixed pair. The current single-case comparator
does not yet provide this new joint envelope.
No secret values enter configuration digests. Expanded and unexpanded associations,
standalone invoices, ambiguous/missing fields, multiple subscriptions for one
customer, duplicate delivery, conflicting facts and missing evidence are required
controls. Deliberately malformed fixtures are rejection controls, not documented
provider responses.

Excluded: invoice line-item classification, amount/partial-payment accounting,
refunds, credit notes, taxes, Connect, subscription billing-period selection,
access-expiration calculation, cancellation/dunning, subscription-status history,
HTTP endpoint deployment and real queue/database concurrency. A renewal marker
does not grant access to a live service. These exclusions limit the conclusion,
not the requirement to preserve unresolved results.

## Selection and coordination hypothesis

The alternatives were subscription-period selection across multiple readers and
Checkout fulfillment across synchronous/asynchronous payment paths. Period selection
would largely repeat the existing authored case without a new demonstrated shared
consumer boundary. Checkout fulfillment is a useful product workflow, but adds
payment-state behavior beyond the selected API transition. Invoice identity has a
documented breaking change, explicit Java source evidence, independent version
surfaces and a small joint business effect that can be checked without live writes.

Multiple investigators may help reconcile independently discovered assumptions in
the two modules and request targeted missing evidence. A coordinator must preserve
case-specific citations, conflicts and grant boundaries. The migration may also be
handled adequately by deterministic rules or one capable investigator; selecting
this workload establishes **no team advantage**.

The new comparison must retain four arms: deterministic diagnostics, one agent
with both consumers, independent scoped workers with deterministic aggregation,
and a coordinated team with bounded follow-ups. Every arm receives access to the
same aggregate registered evidence and allowable tests under the same whole-bundle
ceiling; partitions must not create exclusive evidence for the team. An arm may
return joint conclusions without agent-to-agent messages. Message count, agent
count and orchestration completion are not quality criteria.

## Joint acceptance and freeze boundaries

Quality requires correct per-consumer findings **and** a supported joint account
of invoice identity, mixed-version operation, duplicate effects and blocked states.
Review every material claim, including earlier claims superseded by a follow-up.
Incorrect effects, false readiness, invented business rules, unsupported claims,
unknowns and operational failures remain explicit. Correct abstention under a
withheld-evidence condition is useful; blanket abstention on sufficient evidence
does not satisfy the positive scenarios.

Before any new participant run, freeze source/configuration/fixture/harness/runtime
identities, verified baseline evidence, disclosure policy, all arm implementations,
model/prompt identity, simpler diagnostics, joint rubric, review method, ordered
paired slots, whole-run call/token/time limits and final selection rules. Record
inclusive usage/time, review effort, follow-ups and all failures. An audit failure
or unresolved material claim blocks acceptance. Equivalent accepted quality with
extra resource/review cost does not establish useful coordination. No favorable
retry, criterion weakening or historical campaign reinterpretation is permitted.

The business oracle and provenance are frozen by [contract-integrity.json](contract-integrity.json).
That index is a content identity, not a signature or a finished execution snapshot.
Fixture/source/harness/environment identities and measured baselines do not yet
exist. Changes require a new recorded contract revision before a campaign; prior
contracts and outcomes remain available through versioned history. No campaign
can be called frozen or runnable from these documents alone.

## Reviewable implementation sequence

1. **4e1 defined:** workload selection, source rights/provenance and authored oracle.
2. **4e2a planned:** synthetic fixtures and host-owned pure oracle/ledger checks,
   including disagreement, duplicates, both arrival orders and rejection controls.
3. **4e2b planned:** pinned versioned Java consumers and constrained baseline/target
   execution; record actual local and joint observations without changing the oracle.
4. **4e3 planned:** captured joint evidence/adapters and separate joint quality
   review contract. Subdivide capture and arm changes before campaign registration.
5. **4e4 planned:** freeze and execute ordered fresh paired trials, then complete
   bound semantic review and inclusive reporting in separate reviewable steps.

Executable work must retain intermediate failures and the existing two-case
campaign. Milestone 4 acceptance and the current diagnostics-first routing remain
unchanged until new reviewed evidence supports a different conclusion.
