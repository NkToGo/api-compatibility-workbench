# API Compatibility Workbench

Assess API migrations through reproducible evidence tied to exact consumer code,
configuration and expected behavior. The workbench is being developed to help
maintainers investigate affected integrations, prepare repairs and review what
was actually verified before deploying a change.

## Current status

The first component is a Java domain library for comparing recorded behavioral
observations. It distinguishes a regression observed during a declared transition,
no regression observed within the declared scenarios, and an unresolved result.
Changed inputs make an earlier assessment stale.

A [curated Stripe migration case](cases/stripe-customer-sources-2020-08-27/README.md)
pins historical consumer code, SDK artifacts and synthetic fixtures with a
separate expected-behavior contract. A deterministic local runner now executes
the selected consumer methods with both SDKs and records bounded assessments.
The isolated case exposes regressions in the unrepaired upgrade and remaining
failures in the historical reference patch. Every run includes an offline HTML
report with scenario coverage, expandable observations and exact input identities.
See [running the case](docs/stripe-case-runner.md) and [reading the report](docs/case-report.md).

The case can also run inside a constrained Linux container, including consumer
compilation. The host controller enforces a fixed execution policy, captures bounded
output and removes the container. See [isolated execution](docs/isolated-execution.md).
Final execution records and exported evidence are automatically retained in a
local [evidence store](docs/evidence-storage.md), outside Maven build output.
The isolated-run command also keeps a [durable attempt journal](docs/execution-recovery.md)
with cancellation and explicit recovery after controller interruption.
Each new scripted attempt receives a narrowly scoped, expiring local execution
permission that can be [revoked while running](docs/execution-authorization.md).
A separate [host-side model broker](docs/model-credential-broker.md) supports scoped
OpenAI calls while keeping the credential outside workers. Its opt-in connectivity
check does not perform an investigation or alter compatibility results.
The host can expose [registered investigation tools](docs/investigation-tools.md)
for bounded input reads and fixed test requests, with retained evidence and retry
deduplication. A [bounded consumer investigator](docs/consumer-investigator.md)
now uses those tools to inspect the curated case and return cited advisory findings
or abstain. A separate [candidate generator](docs/repair-candidates.md) can propose
scoped source changes in an isolated source-only checkout. A separate
[protected verifier](docs/protected-repair-execution.md) now compiles and executes
registered candidates in isolated containers while the host owns fixture state
and assertions. Results remain limited to the declared synthetic scenarios.
The [repair evaluator](docs/repair-evaluation.md) now distinguishes fixed, remaining
and new failures in supplied evidence. The case report applies it to the historical
reference patch: two regressions fixed, two remaining and one passing control.
A separate opt-in [investigation evaluation](docs/investigation-evaluation.md)
records completion and abstention under three controlled evidence-availability
conditions, keeping operational failures separate from model decisions.
A second [subscription-period case](cases/stripe-subscription-periods-2025-07-30/README.md)
executes authored controls against eight synthetic scenarios. It distinguishes
item-aware repairs from first-item and minimum/maximum substitutions. A bounded
[subscription candidate generator](docs/subscription-candidates.md) now proposes
one repair or abstains from retained execution evidence. A [subscription investigator](docs/subscription-investigation.md)
now adaptively reads that evidence and can pass identity-bound advisory findings to generation.
A [repeated repair evaluation](docs/subscription-repair-evaluation.md)
now records per-trial repairs, abstentions and operational failures on this case;
broader repair-quality evaluation remains pending.
A separate [full subscription workflow suite](docs/subscription-investigated-repair-evaluation.md)
now repeats fresh investigations, identity-bound generation and protected verification,
retaining stage outcomes and model-record identities.
A [customer-sources repair suite](docs/customer-repair-evaluation.md) now repeats
fresh investigations, generation and protected verification with retained stage
outcomes. A [repeated missing-evidence suite](docs/missing-evidence-evaluation.md)
now withholds required source or execution access in fresh investigations on both
cases. Their abstention reasons now have a [recorded claim-level review](docs/missing-evidence-review.md);
sufficient-evidence findings now have a [bound review and acceptance report](docs/milestone-3-results.md).
The declared two-case exercise meets its criteria with one investigation excluded
from acceptance for an unsupported executor-scope explanation. This closes the
bounded single-investigator milestone without certifying a migration or release.
The subscription [host-side result protocol](docs/subscription-period-protocol.md) now validates
consumer responses against separately held expectations. A container adapter now
compiles subscription consumer source with host-owned assertions. Supplied source
pairs can now be registered for durable three-way verification, cancellation and
interruption recovery.

A host can now register [isolated consumer tasks](docs/consumer-tasks.md), each
with separate captured inputs, investigator history, broker/execution records and
expiring task permission. A [bounded parallel scheduler](docs/parallel-investigations.md)
now runs fresh tasks with fixed membership, shared call reservations and retained
child outcomes. Tasks reuse the two existing case adapters. A bounded
[coordinator review](docs/coordinator-review.md) can now inspect captured completed
investigations, record unresolved possible conflicts and propose inert follow-up
questions. A separate host-authorized [follow-up dispatcher](docs/follow-up-dispatch.md)
can run selected questions in fresh bounded tasks, retaining response lineage and
usage. A separate read-only [reconciliation review](docs/follow-up-reconciliation.md)
can assess question coverage and link fresh claims to earlier claims while preserving
unresolved conflicts. These assessments remain advisory; the separate comparison
protocol measures recorded outcomes. An [offline comparison evaluator](docs/coordination-comparison.md)
now retains declared trial denominators, separate reviewed quality, resource use and
paired outcomes. A [shared trial runtime](docs/coordination-runtime.md) now captures
common evidence and provides policy-bound registration, whole-trial deadlines and
inclusive call/trace accounting. A [deterministic comparison adapter](docs/coordination-diagnostics.md)
now derives recorded scenario and synthetic projection diagnostics through that
runtime, with zero model calls and separately pending quality review. A
[single-agent comparison adapter](docs/coordination-single-agent.md) now investigates
both captured cases in one adaptive context under the same runtime budget, with
cited final dossiers and no automatic quality acceptance. An
[independent-worker adapter](docs/coordination-independent-workers.md) now runs
separate case contexts concurrently in fair rounds, sharing the same call pool
and preserving partial dossiers without model aggregation. The
[captured-team review boundary](docs/coordination-team-review.md) now validates
case-bound inert follow-up questions and unresolved conflicts while retaining
original claims. Its cumulative budget allocation keeps every team stage within
one declared ceiling. A [durable team runner](docs/coordination-team-runner.md)
now executes the original investigations and coordinator review under that same
pool and deadline, with sealed recovery and a retained partial-stage report.
The [complete captured-team adapter](docs/coordination-team-comparison.md) adds fresh
case-scoped follow-ups and reconciliation under the same pool and deadline, retaining
earlier claims and exporting unreviewed final dossiers with inclusive accounting.
Adapter completion establishes no semantic acceptance or routing decision; the
separate campaign review below judges the recorded answers.
A [frozen campaign registration](docs/coordination-campaign.md) now binds the four
compiled policies, captured inputs, rubric, shared limits and all twelve ordered
trial slots before measurement. It registers comparison metadata without dispatching
participants or establishing finding quality.
An [ordered campaign controller](docs/coordination-campaign-execution.md) now maps
each fixed slot to one recoverable trial, validates inclusive participant accounting
and seals all operational records without choosing retries or renewing deadlines.
Separate [sealed review-input inventories](docs/coordination-review-packets.md)
now bind every final dossier, original/fresh team claim and captured evidence,
keeping uncited claims and conflicts available. A separate
[claim-level semantic review](docs/coordination-semantic-review.md) now judges the
frozen campaign against its unchanged rubric, preserving incomplete criteria and
unsupported or unresolved assertions. The [paired quality/resource report](docs/coordination-results.md)
now retains all twelve trials, their costs in recorded units and unestablished
quality blocks; it selects no routing rule.
A separate [scoped routing command](docs/coordination-routing.md) now recommends
diagnostics first with mandatory maintainer review for matching recorded conditions.
Changed conditions have no automatic route. The command is read-only and grants
no execution or migration approval. [Milestone 4 acceptance](docs/milestone-4-status.md)
remains open because useful coordination has not been established by this comparison.

A separate [invoice subscription identity workload](cases/stripe-invoice-subscription-2025-03-31/README.md)
now defines source rights, primary-source provenance and an authored joint business
oracle for webhook and reconciliation consumers. Separate [host assertions and
ordered ledger checks](docs/invoice-renewal-host-checks.md) now preserve normalized
outputs, duplicate/conflicting effects and incomplete executions. A [pinned fixture
registration](docs/invoice-renewal-fixtures.md) now holds raw synthetic sources and
separate answers for the four rollout version vectors. Its partial-coverage gate
blocks full-case use until the remaining scenario families and controls are
registered. A separately pinned association corpus adds expansion on either path,
two subscriptions for one customer, standalone invoices and quote origins for both
versions. A separately pinned refusal corpus adds eight missing-association variants
and three authored contradictory-parent controls. A separately pinned delivery
corpus adds repeated events/retrievals and preexisting/fresh association conflicts
for both versions, preserving receipts and the first ledger value. A separately
pinned state corpus adds absent/failed lookup evidence, inactive state and wrong
customer/subscription controls for both versions, retaining the known invoice
association and specific refusal reasons. A separately pinned unpaid corpus adds
open invoices with fresh and preexisting ledgers in both versions. It retains
`NOT_PAID` facts and existing effects; its open invoice inside an `invoice.paid`
snapshot is an authored rejection control, not a claimed provider event. The six
corpora are preserved unchanged. A seventh, separately pinned old-delivery corpus
keeps an Acacia event after a declared Basil endpoint upgrade, with Basil requests
and state evidence. It distinguishes a declared legacy adapter from an absent
adapter: the blocked webhook has no consumer fact or execution observation,
while successful reconciliation remains visible. All fifteen scenario families
are represented. A separate [admission and incomplete-execution control suite](docs/invoice-renewal-boundary.md)
now checks source/identity/version binding and host-supplied verification states
before oracle construction, preserving blocked inputs separately from incomplete
consumer exchanges. Those verification states are authored test conditions;
they do not establish cryptographic signature or actual adapter verification.
A [complete fixture registry](docs/invoice-renewal-registry.md) now binds all
47 business variants, 188 ordered sequences and 34 boundary controls. It checks
exact variant/control coverage while preserving every constituent identity;
individual partial corpora still block full-case use. This completes authored
fixture registration. [Pinned invoice consumer sources and builds](docs/invoice-consumer-builds.md)
now register both roles with historical SDK/runtime dependencies and six compiler
combinations. Four builds compile; the two unchanged-source upgrades retain their
missing-accessor failures. A [protected invoice execution adapter](docs/invoice-consumer-execution.md)
now compiles and runs both roles in a separate constrained image, with host-owned
assertions for eight exchanges across the four rollout vectors. The two unrepaired
target builds retain compile failures without invented consumer observations.
The original exchanges use authored verification states. A separate
[host signature verifier](docs/invoice-webhook-signatures.md) now authenticates exact
bytes, bounded delivery age and active rotating keys, with scoped receipts that
cannot replace adapter or business checks. One additional signed synthetic
container exchange retains host-only signature evidence. An
[isolated adapter verifier](docs/invoice-webhook-adapters.md) now observes safe
parsing with both pinned SDKs and an explicitly selected Acacia companion under
a Basil main SDK. Actual receipts replace authored adapter flags at the new
admission boundary; absent adapters and incomplete checks remain blocked.
A separate [versioned business source registration](docs/versioned-invoice-business-builds.md)
now compiles a Basil main consumer and explicit Acacia companion on separate
SDK classpaths, importing the unchanged consumer policy. This grants no execution
authority. A separate [protected mixed-version executor](docs/versioned-invoice-business-execution.md)
now requires both observed verifier receipts, compiles both components in the
isolated image and checks one authenticated old delivery against the host oracle.
Missing companions stay blocked; compiler/runtime failures and interruptions
remain unevaluated. A [business coverage driver](docs/invoice-business-coverage.md)
now accounts for all 47 variants and 100 distinct deliveries, retaining actual
consumer observations and the missing-adapter block in separate records.
A separate [ordered ledger runner](docs/invoice-ordered-coverage.md) replays those
captured observations through all 188 registered orders, retaining 312 positions,
initial/final effects and distinct missing-adapter blocks. Replay creates ledger
receipts without dispatching consumers again. A [shared invoice-case capture](docs/invoice-case-coverage.md)
now binds these stages and all runtime boundary controls to one captured registry,
source/build closure and image. Complete synthetic execution coverage remains
separate from joint comparison, semantic acceptance and coordination benefit.
A [shared comparison-input bundle](docs/invoice-comparison-inputs.md) now discloses
captured sources, synthetic inputs and observed facts through bounded paged reads,
with protected expectations and grading results kept outside that view. A
[shared invoice read boundary](docs/invoice-comparison-runtime.md) now registers
counted metadata/catalog/text reads under fixed participant scopes and one durable
deadline, with model access blocked by the read-only entry point. A separate
[single-context invoice investigator](docs/invoice-single-agent.md) now selects
bounded evidence pages and returns cited advisory findings or abstention under
the shared call/read limits. A [deterministic invoice baseline](docs/invoice-diagnostics.md)
uses the same read boundary for reproducibly selected recorded-fact diagnostics,
with unread evidence and unreviewed quality explicit. Separate
[independent invoice workers](docs/invoice-independent-workers.md) now investigate
the two consumer roles concurrently under one budget, preserving both dossiers
without model aggregation. An [invoice coordinator review boundary](docs/invoice-team-review.md)
now preserves those original claims and validates inert role-bound questions and
unresolved possible conflicts. A [durable invoice review runner](docs/invoice-team-runner.md)
now executes original investigations and coordinator review with sealed recovery
under the common budget. A [complete invoice team adapter](docs/invoice-team-comparison.md)
now executes selected fresh follow-ups and final joint reconciliation, preserving
all prior outcomes and exporting one unreviewed trial. Separate
[invoice review-input packets](docs/invoice-review-inputs.md) now bind all four
selected operational arms, the protected rubric/contract and full claim lineage.
Separate [invoice claim-review records](docs/invoice-semantic-review.md) now bind
supplied judgments and explicitly pending work, with claim anchors, provenance and
conservative descriptive gates. A [complete descriptive invoice review](docs/invoice-review-results.md)
now records all 217 units and 24 criterion judgments, preserving an unsupported
scope assertion and all incomplete task outcomes. No arm meets every declared
gate. A separate [invoice campaign registration](docs/invoice-campaign.md) now
freezes the existing four policies, protected rubric/contract and all twelve
ordered slots before new measurement. Registration grants no trial capability.
An [ordered invoice controller](docs/invoice-campaign-execution.md) now binds each
slot to one recoverable trial, preserving failed/uncertain outcomes and inclusive
accounting. A [separately frozen measured campaign](docs/invoice-campaign-measurement.md)
now records all twelve fresh trials and their participant/resource accounting.
A [campaign-bound review-input command](docs/invoice-campaign-review-inputs.md)
now binds all twelve original slots, including rejected responses and uncertain
broker outcomes, without running participants or grading advice.
A [campaign-bound claim-review command](docs/invoice-campaign-claim-review.md)
now creates explicitly pending records and checks supplied anchored judgments
under the unchanged rubric, preserving failed/uncertain trials and historical
errors. A [complete campaign claim inspection](docs/invoice-campaign-review-results.md)
now reviews all 521 units and 72 criteria, preserving unsupported assertions,
interpretive uncertainty and every failed/missing answer. No trial satisfies
every declared review gate. The [paired invoice report](docs/invoice-campaign-results.md)
now preserves all twelve original trials, inclusive resources and distinct
quality/timing/usage denominators. Its bound, read-only comparison selects no
winning architecture or routing promotion; Milestone 4 acceptance remains open.
A [structural protocol diagnosis](docs/invoice-protocol-follow-up.md) now identifies
recorded read/citation and completion gaps and defines prospective changes.
The revised readiness/navigation protocol is not implemented or measured yet.

There is no hosted web application or general migration runner yet. Broader
provider adapters, distributed workflows and repair publication are
planned in the [development roadmap](docs/roadmap.md). No provider transition is
currently certified as supported.

## Build and test

Requirements: JDK 21, PowerShell 7 and network access for the first dependency download.
The included Maven Wrapper selects Maven 3.9.16 and checks its distribution digest.
Run these commands from the repository root.

Windows PowerShell:

```powershell
./scripts/Prepare-StripeCase.ps1
./scripts/Prepare-InvoiceConsumers.ps1
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
```

Linux/macOS:

```sh
pwsh -NoProfile -File ./scripts/Prepare-StripeCase.ps1
pwsh -NoProfile -File ./scripts/Prepare-InvoiceConsumers.ps1
sh ./mvnw -B -ntp -f backend/pom.xml clean verify
```

The default tests use local records, loopback HTTP fixtures and synthetic provider responses; they do not
call OpenAI, Stripe or GitHub. They need no Docker, database or credentials. The
container integration tests are skipped unless their image IDs are supplied;
Linux CI builds both worker images and runs them explicitly. Preparation downloads
checksum-pinned artifacts from Maven Central; execution then uses an offline fixture
transport. The build produces a library JAR and runtime dependencies, not a server.

After building, run `./scripts/Run-StripeCase.ps1` in PowerShell to produce a new
local report. Open the printed `report.html` path in a browser; no server or account
is required. The [runner documentation](docs/stripe-case-runner.md) explains the
results, artifacts and isolation limits.

For container isolation, start a Linux Docker engine with cgroup v2 and default
seccomp support, then run these commands after building the backend:

```powershell
./scripts/Build-IsolatedWorker.ps1
./scripts/Run-IsolatedStripeCase.ps1
```

Preparation may download the pinned base image. Execution has no external network
and never falls back to the local process runner. This supports the curated case
only. Generated repairs use the separate verifier described above; arbitrary
repositories remain unsupported.

## Configuration

The domain library and offline runner need no credentials. The opt-in model broker
uses `OPENAI_API_KEY`, documented in `.env.example`. If `.env.local` is present,
keep it local; Git ignores it. The Java tests neither read nor transmit its contents.
Never put credentials in case inputs, recorded observations or diagnostic output.

The isolated-run script accepts `-EvidenceDirectory` to select a local evidence
store. Its default is the Git-ignored `.workbench/evidence/` directory. Maven
`clean` does not remove that directory. It needs filesystem access, not credentials.
`-ExecutionDirectory` selects the attempt journal, default `.workbench/executions/`.
Keep both directories on a trusted local filesystem and preserve them together.

## Documentation

- [Product scope and boundaries](docs/product.md)
- [Compatibility and evidence contract](docs/compatibility-contract.md)
- [Stripe customer-sources case](cases/stripe-customer-sources-2020-08-27/README.md)
- [Local case execution and evidence](docs/stripe-case-runner.md)
- [Reading the offline result view](docs/case-report.md)
- [Container execution boundaries and verification](docs/isolated-execution.md)
- [Evidence retention, integrity and export](docs/evidence-storage.md)
- [Execution status, cancellation and recovery](docs/execution-recovery.md)
- [Expiring execution permission and revocation](docs/execution-authorization.md)
- [Host-side model credential broker](docs/model-credential-broker.md)
- [Registered investigation tools](docs/investigation-tools.md)
- [Bounded consumer investigator](docs/consumer-investigator.md)
- [Isolated consumer tasks and admission permissions](docs/consumer-tasks.md)
- [Bounded parallel investigations and shared call accounting](docs/parallel-investigations.md)
- [Coordinator evidence review and inert follow-up proposals](docs/coordinator-review.md)
- [Host-authorized follow-up dispatch and retained responses](docs/follow-up-dispatch.md)
- [Read-only follow-up reconciliation and preserved claim/conflict history](docs/follow-up-reconciliation.md)
- [Coordination comparison contract and offline reports](docs/coordination-comparison.md)
- [Shared captured inputs and comparison trial runtime](docs/coordination-runtime.md)
- [Durable captured-team investigations and review](docs/coordination-team-runner.md)
- [Scoped repair candidates](docs/repair-candidates.md)
- [Repair evaluation rules and historical baseline](docs/repair-evaluation.md)
- [Protected candidate execution and recovery](docs/protected-repair-execution.md)
- [Investigation evidence-availability evaluation](docs/investigation-evaluation.md)
- [Repeated two-case missing-evidence evaluation](docs/missing-evidence-evaluation.md)
- [Claim-level review of missing-evidence explanations](docs/missing-evidence-review.md)
- [Runtime invoice admission controls](docs/invoice-boundary-admission-coverage.md)
- [Runtime invoice execution controls](docs/invoice-boundary-execution-coverage.md)
- [Ordered runtime boundary coverage](docs/invoice-boundary-coverage.md)
- [Milestones and reviewable steps](docs/roadmap.md)
- [Milestone 3 acceptance criteria and remaining work](docs/milestone-3-acceptance.md)
- [Development workflow](CONTRIBUTING.md)
- [First-step review guide](docs/reviews/m1-step-1.md)

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
