# Development

Use JDK 21 and the repository's Maven Wrapper. The commands in `README.md` compile
the library with warnings treated as errors, run its tests and package the JAR.
GitHub Actions is configured to run the same checks on Windows and Linux.
Prepare the pinned historical SDK cache as described there before running the
full test suite. The case runner's service doubles and generated source assembly
are documented in `docs/stripe-case-runner.md`.

## Changes and validation

Keep changes cohesive and independently reviewable. Include relevant tests and
documentation, and describe validation results and known limitations. For an
interactive feature, exercise the actual workflow as well as automated checks.
Investigate failing checks before submitting a change.

## Evidence-sensitive changes

Tests should challenge conclusions, especially false success, missing evidence,
stale inputs and disagreement. Do not replace a failing expected behavior simply
to make a proposed repair pass. A change in intended behavior must be explicit and
reviewed, and produces a new assessment identity.

The current unit tests use synthetic metadata. Real migration cases will need
source provenance, redistribution rights, pinned inputs and independently reviewed
expected behavior before they can support product claims.

## Secrets and build inputs

Keep local secrets in ignored files or an approved secret store. Do not pass them
to builds of assessed repositories. Avoid commands that print entire environments
or credential files. New dependencies and copied fixtures need provenance and
license checks. Do not execute arbitrary external build scripts on the host.
