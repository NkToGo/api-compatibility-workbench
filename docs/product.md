# Product scope

## Maintainer workflow

A maintainer selects an API or SDK transition and the consumers to assess. The
workbench should identify affected assumptions, reproduce observable differences,
prepare bounded repairs and present evidence for review. A maintainer must be able
to distinguish what passed, what failed, what was not checked and what needs a
business decision.

The initial intended integration family is Java/Maven consumers of Stripe
Billing/Checkout APIs and webhooks. The exact SDK/API transitions, consumer JDKs,
event types and behaviors will be declared per curated case. Java 21 is the
workbench's own build requirement; it is not a consumer support matrix.

## Result boundaries

- Results apply to identified code, configuration, provider versions, expectations,
  fixtures, harness and execution environment.
- A passing compiler or build does not establish behavioral compatibility.
- A passing set of scenarios says only what those scenarios covered.
- A failure across a declared transition does not by itself identify its cause.
- Missing expected behavior requires a maintainer decision, not an invented rule.
- Source, configuration or evidence changes can invalidate reuse of a result.
- A merged repair, a deployment and successful post-deployment observation are
  separate events.

## Intended agent collaboration

Separate consumer investigators will adapt to different code and test results in
isolated environments. A bounded coordinator will reconcile shared assumptions and
request targeted follow-up when evidence conflicts. Provider research and a
separate verification investigation supply different evidence and permissions.

Scheduling, authorization, immutable identities, test execution, retries, budgets
and approval checks remain deterministic. Known transformations should run
directly. The team must be evaluated against deterministic tools, one capable agent
and independent parallel workers under comparable budgets.

A single [bounded investigator](consumer-investigator.md) can now read the curated
customer-sources case, request its fixed test execution and return advisory findings.
Scoped repair generation and separate protected verification are implemented for
that extracted consumer. Subscription-period repair generation uses retained
execution evidence directly. Adaptive subscription investigation now reads retained
evidence separately; connecting its findings to generation remains pending.
Repeated subscription repair trials retain behavioral and operational outcomes.
Coordinated agents and the repository-to-PR workflow are not implemented yet.
Agent assertions cannot become test results merely because they conform to a schema.

## Current and planned system boundaries

The current host controller owns local registration, scoped execution permissions,
durable attempt journals and retained evidence. Constrained containers execute the
curated consumers and candidate source; the host owns behavioral assertions.
A host-side model broker holds the API credential and enforces exact-request grants.
Recovery retains ambiguous outcomes without automatically replaying dispatched work.
These are single-host boundaries with trusted local storage, not multi-tenant controls.

The planned product API will own organization access and maintainer review records.
Selected repository access, provider actions, repair publication and distributed
execution remain future integrations. No current result approves a deployment.
The [Milestone 3 acceptance checklist](milestone-3-acceptance.md) separates implemented
evaluation machinery from the measurements still required before agent coordination.

The full release should include an installable application, documented operations,
cost and execution visibility, recoverable campaigns and a public gallery of
approved cases with recorded and live execution clearly distinguished.
