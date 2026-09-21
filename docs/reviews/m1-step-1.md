# Milestone 1, Step 1 — Input identity and evidence conclusions

Status: implemented and locally validated; awaiting review before the first commit.

## What changed

The repository now contains a Java 21 domain library that compares reported
behavioral results across a declared transition. It binds observations to exact
inputs, checks that all required scenarios have usable evidence, and returns a
bounded conclusion with explicit issues when the comparison is unresolved.

For example, passing the original entitlement check and failing it after an
upgrade produces an observed regression. If another required check was skipped or
timed out, the overall result remains unresolved while retaining that regression.
Changing the reviewed expectations or other inputs prevents reuse of old evidence.

The repository also includes a pinned Maven Wrapper, a Windows/Linux CI definition,
secret exclusions, product scope, the evidence contract and the milestone roadmap.
The project uses Apache License 2.0 and includes the upstream Maven Wrapper notice.

## Suggested reading order

1. [README](../../README.md): current capability and how to run the checks.
2. [Compatibility contract](../compatibility-contract.md): inputs, result meanings
   and limits on what the records prove.
3. [Comparison logic](../../backend/src/main/java/io/github/nktogo/apicompatibility/compatibility/CompatibilityComparison.java):
   the core decision path and missing/duplicate/stale evidence handling.
4. [Behavior tests](../../backend/src/test/java/io/github/nktogo/apicompatibility/compatibility/CompatibilityComparisonTest.java):
   concrete cases challenging false success and invalid reuse.
5. [Roadmap](../roadmap.md): the next review boundaries.

The other Java files define the compact immutable records used by the comparator.
`mvnw` and `mvnw.cmd` are upstream Apache tooling, not application logic.

## Validation

Executed locally on Windows with Temurin JDK 21.0.12 and Maven 3.9.16:

```powershell
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
```

Result: **48 tests passed; zero failures, errors or skipped tests.** Compilation
used `-Xlint:all` and `-Werror`. The library JAR was packaged successfully.
An independent source review found no actionable issues within this step's scope.

Coverage includes missing and unevaluated assertions, failed baselines,
infrastructure failures, duplicate/conflicting observations, incorrect input
identity, changes to shared verification conditions, SDK/configuration-only
transitions, incomplete scenario coverage and stale historical assessments.

Documentation links resolve. `.env.local` and build output are excluded from Git.
The CI configuration has not run on GitHub; Linux execution has not been verified
locally. The tests use synthetic records and make no external API calls. They do
not validate the configured OpenAI key, execute a Stripe migration or prove that
the future runner can authenticate evidence.

## Review boundary

Follow the [manual verification instructions](../manual-verification/m1-step-1.md)
to build locally and exercise the comparator interactively. The maintainer performs
this verification, stages the reviewed files, commits and pushes them. These actions
have not been delegated to automation.

This completes the proposed scope of Step 1, not the whole milestone. No web
server, agent worker, database or cloud service has been added. No commit or remote
repository has been created. After review and the first commit, Step 2 will curate
a real provider/consumer transition with source rights, pinned inputs and reviewed
expected behavior.
