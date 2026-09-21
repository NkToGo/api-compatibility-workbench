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
failures in the historical reference patch. See [running the case](docs/stripe-case-runner.md).

There is no web application or general migration runner yet. Agent investigation,
provider adapters, isolated execution, persistence and repair publication are
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

Tests use local records and synthetic provider responses; they do not call OpenAI,
Stripe or GitHub. They need no Docker, database or credentials. Preparation downloads
checksum-pinned artifacts from Maven Central; execution then uses an offline fixture
transport. The build produces a library JAR and runtime dependencies, not a server.

After building, run `./scripts/Run-StripeCase.ps1` in PowerShell to produce a new
local report. The [runner documentation](docs/stripe-case-runner.md) explains the
results, artifacts and isolation limits.

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
- [Milestones and reviewable steps](docs/roadmap.md)
- [Development workflow](CONTRIBUTING.md)
- [First-step review guide](docs/reviews/m1-step-1.md)

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
