# Paid renewal business oracle v1

Case: `stripe-invoice-subscription-2025-03-31`.
Policy: `paid-renewal-v1`. Authority: **authored application policy**.
This contract precedes consumer/repair implementation. Provider facts are referenced
in [README.md](README.md); they do not dictate these business decisions.

## Values and effects

All IDs below are invented. The installation is `install_case`; the approved binding
maps `(cus_case, sub_primary)` and `(cus_case, sub_secondary)` to distinct subscription
records. Neither customer equality nor collection position selects a subscription.
Registered active-state evidence must identify the same customer and subscription.
No runtime clock, network or other customer's data determines expected outcomes.

Each consumer emits one of:

- `RESOLVED`: exact invoice/customer/subscription IDs, effective API version and
  input/evidence identities; the invoice is paid and registered subscription state
  is active with an approved binding.
- `NON_SUBSCRIPTION`: a complete supported invoice representation explicitly has
  no subscription-generating parent/association. This is not a missing-evidence result.
- `UNRESOLVED`: the association, effective version, status, binding or evidence
  cannot be established. Record a reason; preserve the invoice and input identity.

The joint ledger consumes these facts as an ordered sequence. Its key is
`(installationId, invoiceId)`; the immutable effect value includes customer ID,
subscription ID and action `RECORD_PAID_RENEWAL`. Different source/event IDs may
identify evidence for the same key. Raw provider payloads and secret values are
not ledger values.

For a fresh resolved key, add one effect. An identical later fact adds no effect
and retains both evidence references. A contradictory fact for an existing key
adds no effect, preserves the original effect and records `CONFLICT`; the aggregate
cannot approve migration. A refusal never deletes or rewrites prior effects. Each
receipt records the consumer output and any evaluated host assertion separately.

`NON_SUBSCRIPTION` adds no effect. A supported quote parent or explicit null parent
is non-subscription only when the complete scoped representation and billing reason
do not contradict that classification. A claimed subscription origin with absent
association, unknown discriminator or contradictory fields is `UNRESOLVED`.
An unpaid invoice has no effect and is `UNRESOLVED` for this paid-renewal policy;
this case does not decide a dunning or entitlement revocation rule.

For every sufficient-evidence scenario, execute each consumer alone, webhook then
reconciliation, and reconciliation then webhook from the declared initial state.
Both paths must independently yield the expected classification and IDs. A correct
combined ledger produced only by one working path is insufficient. Matching final
effects also do not validate wrong intermediate facts or unresolved conflicts.

## Required scenarios

The fixture implementation must map these stable IDs to exact bytes and initial
state before execution. Descriptions are inputs to fixture authoring, not alleged
Stripe observations. In ordinary scenarios both paths see the same paid invoice,
customer and active subscription; differences are explicitly stated. Fresh ledgers
start empty unless specified. A count denotes newly appended effects.

| ID | Declared input/control | Independently expected behavior |
| --- | --- | --- |
| `legacy-agreement` | Acacia event and request; `in_primary`, `cus_case`, `sub_primary` | Both `RESOLVED` with those IDs; 1 effect combined, 1 for each path alone |
| `target-agreement` | Basil event and request; same business identities | Same facts/effects as legacy; provider-version evidence remains Basil |
| `request-first` | Acacia event, Basil request; same identities | Both `RESOLVED`; 1 combined effect; evidence preserves the two distinct versions |
| `event-first` | Basil event, Acacia request; same identities | Both `RESOLVED`; 1 combined effect; versions preserved |
| `expanded-association` | String association on one path, expanded Subscription on the other, for both declared versions | Resolve `sub_primary` by identity in either representation; same effect; expansion does not change the business binding |
| `two-subscriptions` | `in_primary` maps to `sub_primary`; `in_secondary` maps to `sub_secondary`; both share `cus_case`; listing order reversed between paths | 2 effects, each with its own invoice/subscription pair; no first-subscription/customer-only inference |
| `standalone-invoice` | Paid `in_standalone`, manual billing reason; explicit null legacy association/Basil parent; complete representation | Both `NON_SUBSCRIPTION`; 0 effects; no invented subscription |
| `quote-parent` | Complete paid invoice with quote origin: Acacia quote field/no subscription, Basil `parent.type=quote_details` with quote details, quote billing reason | Both `NON_SUBSCRIPTION`; 0 effects; do not read subscription details without the discriminator |
| `missing-association` | Subscription-cycle origin; association missing in Acacia, or subscription parent with absent ID/details in Basil | Both `UNRESOLVED` with missing-association reason; 0 effects; not standalone |
| `contradictory-parent` | Deliberately malformed Basil controls: unknown parent type, quote type plus subscription details, or conflicting legacy/new associations | Both `UNRESOLVED` for each control; 0 effects; these are authored rejection fixtures, not claimed provider shapes |
| `duplicate-delivery` | Repeated same event; different event IDs for the same invoice; repeated reconciliation; all facts agree | Each path resolves; exactly 1 effect across all deliveries and both arrival orders; retain evidence references |
| `conflicting-association` | Existing `in_primary` effect for `sub_primary`; incoming paid invoice fact asserts `sub_secondary`, valid individually under the binding | Incoming fact is `RESOLVED` locally; host records `CONFLICT`, 0 new effects, preserves original `sub_primary` effect; joint readiness blocked in either consumer order |
| `unavailable-state` | Invoice association known; registered active-state lookup is absent, fails, is non-active or names the wrong customer/subscription | Both `UNRESOLVED` with the actual state/binding reason; 0 effects; do not infer active status from a paid invoice alone |
| `not-paid` | Supported associated invoice whose status is open rather than paid | Both `UNRESOLVED` for this policy; 0 effects; preserve any preexisting state |
| `old-delivery-after-upgrade` | Basil request plus an Acacia snapshot delivered after the declared endpoint upgrade | A registered, verified Acacia adapter yields the same resolved fact and 1 combined effect; absent adapter must block that path and joint readiness, preserving successful facts from the other path |

For `conflicting-association`, additionally test an empty ledger receiving one
fact from each path: the first fact creates one effect, the second creates no new
effect and records a conflict; the retained first value differs with arrival order.
That order dependence is explicit preserved evidence, not an approved resolution.
No repair may choose an arbitrary winner and report the pair as fully verified.

Host admission controls additionally require missing/unknown event or effective
request version, missing required source/result, invalid signature, rejected adapter
or incomplete fixture identity to remain blocked/unresolved. Consumers never verify
themselves into a grant. Invalid webhook signatures are rejected before consumer
dispatch and produce no consumer execution observation. A failed/unavailable run
has `NOT_EVALUATED`, not a fabricated `UNRESOLVED` consumer response or a passing
assertion. Missing evidence must identify which claim could not be established.

## Verification and review limits

The protected host holds this oracle, assertions, initial state and fixture registry.
Repair work can alter only registered consumer source/configuration; it cannot
rewrite expected values, delete scenarios or turn missing evidence into a pass.
Baseline, unrepaired target and repaired target use the same oracle/fixture/harness
bundle. Baseline errors block regression conclusions while retaining actual failures.

Passing an expected refusal control establishes safe behavior in that control; it
does not make an intentionally unsupported version combination deployable. The
later review must separate scenario correctness, evidence completeness and actual
scope readiness. It must check identity handling, version mapping, both working
paths, joint duplicate/conflict behavior and cited limitations without requiring
agent communication or a particular repair algorithm.

This is independently held **from candidate implementations and agent outputs**,
not independently adjudicated by an external business owner. No production customer
has approved it. A future customer's policy must receive its own contract identity;
these expectations cannot silently become universal access rules.
