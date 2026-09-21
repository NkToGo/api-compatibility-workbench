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
separate expected-behavior contract. Its inputs are checked in CI; the migration
has not been executed yet.

There is no running web application or migration runner yet. Agent investigation,
provider adapters, isolated execution, persistence and repair publication are
planned in the [development roadmap](docs/roadmap.md). No provider transition is
currently certified as supported.

## Build and test

Requirements: JDK 21 and network access for the first dependency download.
The included Maven Wrapper selects Maven 3.9.16 and checks its distribution digest.
Run these commands from the repository root.

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
```

Linux/macOS:

```sh
sh ./mvnw -B -ntp -f backend/pom.xml clean verify
```

Tests use local records and do not call OpenAI, Stripe or GitHub. They do not need
Docker, a database or credentials. The build produces a library JAR in
`backend/target/`, not an executable server.

## Configuration

The current library has no runtime configuration. `.env.example` documents the
credential name reserved for future agent execution. If `.env.local` is present,
keep it local; Git ignores it. The Java tests neither read nor transmit its contents.
Never put credentials in case inputs, recorded observations or diagnostic output.

## Documentation

- [Product scope and boundaries](docs/product.md)
- [Compatibility and evidence contract](docs/compatibility-contract.md)
- [Stripe customer-sources case](cases/stripe-customer-sources-2020-08-27/README.md)
- [Milestones and reviewable steps](docs/roadmap.md)
- [Development workflow](CONTRIBUTING.md)
- [First-step review guide](docs/reviews/m1-step-1.md)

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
