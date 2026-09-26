# Subscription item renewal-date case

This authored synthetic consumer resolves the renewal date for one configured
subscription-item ID. It exercises a different migration failure from the older
customer-source case: a field relocation that requires preserving item identity.
It is not extracted from a customer repository or a historical repair.

## Provider facts and source limits

- Stripe removed subscription-level period fields in API 2025-03-31.basil and
  moved them to subscription items. [Versioned changelog](https://docs.stripe.com/changelog/basil/2025-03-31/deprecate-subscription-current-period-start-and-end)
- The [Acacia object reference](https://docs.stripe.com/api/subscriptions/object?api-version=2025-02-24.acacia)
  documents the old object shape; the [Basil item reference](https://docs.stripe.com/api/subscription_items/object?api-version=2025-07-30.basil)
  documents item-level periods.
- [Mixed-interval subscriptions](https://docs.stripe.com/billing/subscriptions/mixed-interval?dashboard-or-api=api)
  require flexible billing and permit different item periods. Its current guide
  says API 2025-06-30.basil or later. This case selects 2025-07-30.basil.

Reviewed 2026-09-26. The mixed-interval guide also contains examples/table text
with top-level period fields, in tension with the versioned removal changelog.
For field placement this case follows the versioned references and changelog,
not those examples. These are documentation-backed synthetic projections, not
captured Stripe responses or a verified live upgrade.

The transition includes adoption of flexible billing and changed item intervals;
it does not attribute every difference to an API-version header alone. Baseline
items share the subscription period. Target projections can have independent
periods. Unused provider fields and invoice calculations are omitted.

## Consumer policy

The configured item ID identifies the product whose next renewal the consumer
displays. Resolve only an active subscription with a complete item list and
exactly one matching ID. Without that binding, with missing/duplicate membership,
or with an incomplete list, return DEFERRED. Do not guess the first item, minimum
or maximum period, and do not interpret DEFERRED as a missing subscription.

This selection and deferral rule is authored application policy, not Stripe's
universal entitlement policy. A renewal date does not establish paid-through
access, payment success or authorization to revoke access. Cancellation, trials,
dunning, webhook verification and page retrieval are outside this case.

## Declared coverage

Eight scenarios cover a single item, a later addon first, an earlier addon first,
reordered items, absent configuration, missing bound item, an incomplete list and
duplicate identity. The duplicate case is intentionally malformed, not claimed
to be a valid Stripe response. Expectations are declared separately from fixtures.

The runner executes compiled authored controls: the original top-level reader,
first-item/minimum/maximum substitutions and a bound-item reader. All controls
share the membership/completeness guard to isolate date-selection errors.
The baseline and unrepaired target use the same original control.

After the normal backend build:

```powershell
./scripts/Run-SubscriptionPeriodCase.ps1
```

Reports and captured inputs are retained under the ignored
`.workbench/period-cases/<run-id>/`. Exact input and implementation identities
accompany observations and three-way repair assessments. A passed deferral check
means the consumer correctly declined to choose a date; it does not mean a date
was established.
CI retains this case's reports and captured inputs with the case-report artifacts.
Control implementation identities include the compiled build and selected entry
point; they do not represent separate customer repository commits. File hashes
detect changed inputs but do not authenticate a review or prevent a trusted host
operator from replacing both inputs and hashes.

Only reviewed compiled controls execute locally. This runner does not load or
compile candidate source, call Stripe or OpenAI, use a Stripe SDK, or certify a
full API transition. It is not yet connected to the investigator or protected
candidate executor. Agent-generated repairs for this case remain subsequent work.
All new fixtures, policy and controls are authored under the repository license;
provider documentation is linked rather than redistributed.
