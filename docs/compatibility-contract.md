# Compatibility and evidence contract

This contract describes the domain library currently implemented in `backend/`.
It compares supplied execution records; it does not fetch a repository, execute a
test, verify a provider, authenticate a receipt or authorize an operation.

## Supported input shape

A `ComparisonDefinition` contains exact baseline and target `InputSnapshot` values
and a nonempty list of unique required scenario IDs. It represents one case and
one declared transition. A scenario is a named behavioral assertion with reviewed
expected behavior, such as how a subscription entitlement date is selected.

Each snapshot declares:

| Field | Meaning |
| --- | --- |
| Case ID | Stable identity binding the consumer and behavior under investigation |
| Provider ID and version | Declared provider and effective API/behavior version |
| Source digest | SHA-256 identity of the exact consumer source bundle |
| Configuration digest | Identity of relevant resolved, non-secret configuration |
| Expectations digest | Identity of approved scenario expectations and business rules |
| Fixtures digest | Identity of the complete approved fixture bundle for the comparison |
| Harness digest | Identity of verification code and its dependencies |
| Environment digest | Identity of the declared runtime and execution environment |

`ContentDigest` validates the shape of a lowercase SHA-256 string. It does not
calculate the hash, retrieve content or establish that a producer is trustworthy.
Future ingestion must define canonical bundles, compute their digests and retain
their contents and provenance. Source metadata must include repository/module
identity and exact revision; branch names alone are insufficient.

SDK versions belong to the pinned source/build and configuration bundles. Request,
account-default and webhook versions must be resolved for the specific scenario;
unknown effective versions cannot be guessed into a verified observation. The
future provider adapter must reject unsupported or unresolved version mappings.
Secret references and version identifiers can affect configuration identity;
secret values must never appear in these records or ordinary logs.

Accepting these Java types does not establish support for any Stripe transition,
consumer JDK, webhook type or repository. That support matrix will come from real
curated and executed cases in subsequent steps.

The separate [local Stripe case runner](stripe-case-runner.md) now constructs these
records from one allowlisted, isolated-method experiment. Its synthetic responses
and service doubles establish only that case's coverage, not general Stripe support.

## Comparable transitions

Baseline and target must share case, provider, expectations, fixture bundle,
harness and environment. At least one of source, configuration or provider version
must change. This permits SDK-only migrations while rejecting a comparison of two
identical snapshots.

The shared fixture bundle can contain different baseline and target responses.
Changing the bundle itself changes the experiment. Expected behavior must remain
the same across this comparison; an intentional change to the business rule needs
a separately reviewed case. Runtime migrations and changes to the verification
harness are also outside this first comparator's scope.

Source and configuration can differ intentionally, so the result describes the
whole declared transition. It does not prove that the provider caused a failure.

## Execution facts

An `ExecutionObservation` links a required scenario to an exact snapshot and keeps
execution disposition separate from assertion outcome:

- Disposition: `COMPLETED`, `TIMED_OUT`, `CANCELLED`, `INFRASTRUCTURE_ERROR`.
- Assertion: `PASSED`, `FAILED`, `NOT_EVALUATED`.

Incomplete executions must use `NOT_EVALUATED`. A completed process may also have
no evaluated assertion, for example because the behavioral test was skipped.
That cannot satisfy a scenario. A successful compile is not a behavioral assertion.

There must be exactly one applicable observation per required scenario on each
side. Missing results, unknown scenarios, stale input identities, duplicates and
conflicting retries cannot be silently ignored or overwritten. A future runner
must select or reconcile attempts explicitly before supplying observations.

## Derived assessment

Use `CompatibilityComparison.compare(...)` to derive an assessment from a
definition and baseline/target observations. Do not accept a user- or agent-written
assessment as an authoritative result.

| Conclusion | Required evidence |
| --- | --- |
| `REGRESSION_OBSERVED` | All required assertions completed, all baseline assertions passed, and at least one target assertion failed |
| `NO_REGRESSION_OBSERVED` | All required assertions completed and passed on both sides |
| `UNRESOLVED` | Any blocking identity, coverage, execution or baseline-validity issue remains |

Every conclusion is limited to the declared scenarios and supplied records.
There is deliberately no unqualified `COMPATIBLE` result. An unresolved comparison
can still retain observed regressions in other scenarios; uncertainty must not
erase those findings. Its issue list explains what prevented a complete result.

For example, an entitlement-date scenario passing before an upgrade and failing
afterwards supports `REGRESSION_OBSERVED` for that transition. If a second required
webhook scenario times out, the aggregate is `UNRESOLVED` and retains the
entitlement regression. A maintainer sees both the failure and the incomplete work.

`assessment.appliesTo(currentDefinition)` checks exact definition equality.
Changing source, configuration, provider version, expectations, fixtures, harness,
environment or scenario selection makes the result stale. Reordering the declared
scenario list also conservatively invalidates reuse. The historical assessment is
immutable and remains a record about its original definition.

## Evidence authority and remaining work

Later evidence storage must distinguish versioned provider documentation,
historical captured payloads, actual provider test-environment observations and
model inference. They support different claims and cannot be flattened into a
single verified badge. Documentation and model inference alone cannot substitute
for behavioral execution.

The current Java records are in-process data contracts. Their constructors do not
protect fixtures from a repair worker, validate receipt signatures, retain logs,
enforce permissions or prove that a reported execution happened. Those guarantees
belong at the execution, artifact-storage and authorization boundaries in later
steps. Agent output must not directly populate trusted execution observations.

The separate [repair evaluator](repair-evaluation.md) adds a repaired target and
three-way scenario outcomes. It enforces equal declared test conditions but does
not physically protect tests or authenticate observations. Generated-code execution
still requires a protected adapter and review of intended behavior; there is no
release approval state. A later API will also need a versioned wire schema; these Java
records do not yet establish a public JSON contract.
