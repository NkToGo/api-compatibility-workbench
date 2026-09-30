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
A [customer-sources repair suite](docs/customer-repair-evaluation.md) now repeats
fresh investigations, generation and protected verification with retained stage
outcomes. A [repeated missing-evidence suite](docs/missing-evidence-evaluation.md)
now withholds required source or execution access in fresh investigations on both
cases. Their abstention reasons now have a [recorded claim-level review](docs/missing-evidence-review.md);
sufficient-evidence finding review remains pending.
Its [host-side result protocol](docs/subscription-period-protocol.md) now validates
consumer responses against separately held expectations. A container adapter now
compiles subscription consumer source with host-owned assertions. Supplied source
pairs can now be registered for durable three-way verification, cancellation and
interruption recovery.

There is no hosted web application or general migration runner yet. Multi-agent coordination,
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
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
```

Linux/macOS:

```sh
pwsh -NoProfile -File ./scripts/Prepare-StripeCase.ps1
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
- [Scoped repair candidates](docs/repair-candidates.md)
- [Repair evaluation rules and historical baseline](docs/repair-evaluation.md)
- [Protected candidate execution and recovery](docs/protected-repair-execution.md)
- [Investigation evidence-availability evaluation](docs/investigation-evaluation.md)
- [Repeated two-case missing-evidence evaluation](docs/missing-evidence-evaluation.md)
- [Claim-level review of missing-evidence explanations](docs/missing-evidence-review.md)
- [Milestones and reviewable steps](docs/roadmap.md)
- [Milestone 3 acceptance criteria and remaining work](docs/milestone-3-acceptance.md)
- [Development workflow](CONTRIBUTING.md)
- [First-step review guide](docs/reviews/m1-step-1.md)

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
