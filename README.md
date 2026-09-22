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

There is no hosted web application or general migration runner yet. Agent investigation,
provider adapters, durable execution, persistence and repair publication are
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

The default tests use local records and synthetic provider responses; they do not
call OpenAI, Stripe or GitHub. They need no Docker, database or credentials. The
four container integration tests are skipped unless an image ID is supplied;
Linux CI builds that image and runs them explicitly. Preparation downloads
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
only; it does not authorize arbitrary repositories or generated repairs.

## Configuration

The current library has no runtime configuration. `.env.example` documents the
credential name reserved for future agent execution. If `.env.local` is present,
keep it local; Git ignores it. The Java tests neither read nor transmit its contents.
Never put credentials in case inputs, recorded observations or diagnostic output.

## Documentation

- [Product scope and boundaries](docs/product.md)
- [Compatibility and evidence contract](docs/compatibility-contract.md)
- [Stripe customer-sources case](cases/stripe-customer-sources-2020-08-27/README.md)
- [Local case execution and evidence](docs/stripe-case-runner.md)
- [Reading the offline result view](docs/case-report.md)
- [Container execution boundaries and verification](docs/isolated-execution.md)
- [Milestones and reviewable steps](docs/roadmap.md)
- [Development workflow](CONTRIBUTING.md)
- [First-step review guide](docs/reviews/m1-step-1.md)

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
