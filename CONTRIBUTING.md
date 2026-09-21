# Development

Use JDK 21 and the repository's Maven Wrapper. The commands in `README.md` compile
the library with warnings treated as errors, run its tests and package the JAR.
GitHub Actions is configured to run the same checks on Windows and Linux.

## Review boundaries

Each numbered roadmap step is a coherent review boundary. A milestone contains
several steps; it is not one large change. Before moving to the next step:

1. Finish the step's behavior and relevant documentation.
2. Run the focused checks and report their actual results and limitations.
3. Perform the applicable hands-on verification scenarios and inspect the diff.
4. Commit and push the coherent step, then check remote CI and address failures.
5. Report the changes, verification evidence and limitations, suggested review
   order, commit identity and CI status before advancing.

The maintainer has delegated routine verification, staging, commits and pushes to
the coding agent. Preserve reviewable step boundaries and report actual results;
delegation does not remove review or allow skipped validation. Request maintainer
input when credentials, runtime approval or product decisions require it.
Running an automated test command manually is a build check; when the product
supports an interactive workflow, also exercise actual hands-on scenarios.

Keep commits meaningful. Do not split changes artificially or combine unrelated
steps merely because they belong to the same milestone.

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
