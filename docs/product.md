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
case, request its fixed test execution and return advisory findings. The team and
repair workflow are not implemented yet. Agent assertions cannot become test
results merely because they conform to a schema.

## Planned system boundaries

The product API will own registration, access and review records. Durable workflows
will own execution progress. Isolated workers will produce versioned artifacts;
protected verification will run separately from repair. A credential broker will
control authorized provider and GitHub actions. The first library does not yet
enforce these process, storage or permission boundaries.

The full release should include an installable application, documented operations,
cost and execution visibility, recoverable campaigns and a public gallery of
approved cases with recorded and live execution clearly distinguished.
