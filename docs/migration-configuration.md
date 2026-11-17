# Source-bound migration declarations

The host can now bind a maintainer's baseline/target version declarations to a
fresh [selected POM inventory](github-source-inventory.md). The initial profile
is the existing `stripe-invoice-subscription-2025-03-31` case with its webhook and
reconciliation roles. It records intended configuration and identifies gaps; it
does not discover or verify effective runtime settings.

An opt-in [input capture](migration-input-retention.md) can retain this binding
with the selected POM bytes and revalidate it offline. The inspection command
below remains a transient read without storage publication.

## Prepare and inspect

1. Configure the [read-only GitHub App](github-repository-connection.md) and obtain
   an inventory at the exact selected commit.
2. Copy [the configuration example](../examples/invoice-migration-configuration.json)
   to protected host configuration. Replace **all dummy repository IDs, names,
   zero commit/tree SHAs, paths and zero POM digests** with the inventory's values.
   Include exactly every selected POM, including files not mapped to a consumer.
3. Assign the two case roles to their selected POMs and explicitly declare baseline
   and intended target versions. Keep unavailable values as JSON `null`.
4. After [building and verifying](../README.md#build-and-test), run:

```powershell
./scripts/Inspect-MigrationConfiguration.ps1 -Selection ./local-repository-selection.json -Plan ./local-pom-plan.json -Configuration ./local-migration-configuration.json
```

The command loads bounded host configuration before making GitHub requests, then
repeats the App scope, exact-commit and Git-object checks used by inventory. It
does not read a configuration file from the assessed repository. Assessed source
cannot select another repository, expand the POM plan or authorize an operation.
No model, Stripe account, Maven execution, checkout or repository write is used.

The JSON result contains the declarations, fresh inventory, matched case profile,
binding identity, fixed issue codes and limitations. Exit `0` means
`DECLARATIONS_COMPLETE_NOT_VERIFIED`; exit `3` means `DECLARATIONS_HAVE_GAPS`.
Connection/interruption and startup/configuration failures use `1` and `2`.
The PowerShell launcher raises an error for any nonzero result. Preserve stdout
outside build output when retaining a report, including one with gaps. This
command has no durable registration, interruption journal or automatic retry.

## Declaration fields and profile scope

Each role names one selected POM and separate `baseline` and `target` objects.
Every version object must explicitly include all six fields:

| Field | Meaning and requirement |
| --- | --- |
| `sdkVersion` | Declared main Stripe Java SDK version; required for both roles |
| `requestApiVersion` | Declared request API version, independently supplied; required for both roles |
| `endpointApiVersion` | Declared webhook endpoint version; required for webhook, null for reconciliation |
| `eventApiVersion` | Declared version of the selected snapshot event; required for webhook, null for reconciliation |
| `accountApiVersion` | Separately declared account-default metadata; required for both roles in this case profile |
| `adapterApiVersion` | Declared event version handled by an adapter; optional unless a different main SDK event version requires it; null for reconciliation |

No field fills another. Account defaults do not establish explicit request
settings, and an SDK declaration does not establish a webhook endpoint or an
event's provenance. Unknown values remain null and produce scoped issues. A
target value is an intention, not evidence that the target build exists.

The profile pins the case's existing contract digest and the historical SDKs
`28.4.0` / `29.0.0`, paired with request API versions `2025-02-24.acacia` /
`2025-03-31.basil`. These are curated scope, not current-version recommendations
or a general Stripe support matrix. Other syntactically valid versions remain
outside this profile rather than being labeled incompatible. Both roles must be
mapped; one may retain its baseline configuration while the other upgrades, so
mixed consumer rollouts remain distinct.

Endpoint and event versions are preserved independently. The profile admits the
case's declared old-event path under the newer SDK only with a matching Acacia
adapter declaration. A Basil endpoint plus an Acacia event is not rewritten as a
Basil event. The adapter declaration supplies no proof that adapter code exists,
has been executed or passed verification. The reverse newer-event/older-SDK
adapter mapping is outside the registered case profile. No raw event payload or
customer/account identifier is accepted in this configuration.

POM declarations remain a separate source of facts. A differing unconditional
literal direct SDK declaration is flagged for review against the declared
baseline. Profiles, dependency management and property candidates are not
resolved or silently chosen. A versionless module dependency can therefore have
complete operator declarations while its **effective Maven version is still
unverified**. Source-to-role mapping itself is also a maintainer assertion.

## Exact inputs and gaps

The expected account/repository IDs, owner/name, commit and tree must match the
fresh snapshot. Every selected POM must be present, scanned and match its expected
SHA-256. Missing selections, changed files, unreadable POMs, incomplete inventory,
unmapped roles, unsupported settings and unchanged baseline/target declarations
remain issues. Earlier source facts remain in the report even when binding fails.

`bindingIdentity` is SHA-256 over versioned UTF-8 producer JSON containing the
binding policy, normalized declaration objects, matched profile, observed
repository/commit/tree, ordered inventory selection, inventory state and every
selected file's facts/declarations. Configuration POM/consumer lists are sorted
by path/role; observation times, token expiry and transport request counters are
excluded. Changed source, selection, roles or any declared version changes this
identity. The Java `hasSameInputs` helper recomputes it from supplied current
inputs rather than changing an earlier result.

This equality check is not report authentication, an immutable artifact store,
stale-approval enforcement or a future action grant. The CLI performs a fresh
read; the pure Java binder trusts its caller to supply genuine inventory objects
and must not be exposed as a report-deserialization trust boundary. A forged
report plus a recomputed hash establishes no authority.

Configuration JSON is limited to 32 KiB with strict fields, types, duplicate-key
rejection, one to eight unique POMs and one to eight unique role IDs. POM paths
inherit the inventory restrictions. Versions accept only bounded three-part SDK
numbers and calendar-date API identifiers; expressions, arbitrary notes and
credential fields are refused. Unsupported syntax fails startup with a fixed
message instead of echoing supplied values. Final linked/special configuration
files are refused; host configuration directories remain trusted.

## Remaining workflow boundaries

Complete declarations establish neither effective build settings nor compatible
behavior. The command does not query Stripe, authenticate event provenance,
verify the selected repository implements the case's business policy, capture
executable source bundles, produce trusted `InputSnapshot` execution observations,
or admit arbitrary customer source to the protected curated runners. Effective
configuration evidence, durable jobs, maintainer review, execution authorization
and PR publication remain separate workflow steps.

## References

- [Existing case scope and pinned source provenance](../cases/stripe-invoice-subscription-2025-03-31/README.md)
- [Stripe webhook versioning](https://docs.stripe.com/webhooks/versioning)
- [Stripe request, account and SDK versioning](https://docs.stripe.com/api/versioning)
