# Development

Use JDK 21 and the repository's Maven Wrapper. The commands in `README.md` compile
the library with warnings treated as errors, run its tests and package the JAR.
GitHub Actions is configured to run the same checks on Windows and Linux.

## Review boundaries

Each numbered roadmap step is a coherent review boundary. A milestone contains
several steps; it is not one large change. Before moving to the next step:

1. Finish the step's behavior and relevant documentation.
2. Run the focused checks and report their actual results and limitations.
3. Summarize the change and give a short suggested reading order, exact manual
   verification instructions and expected results.
4. The maintainer performs manual verification and review; address any feedback.
5. Provide staging, commit and push commands. The maintainer executes them and
   confirms completion before the next implementation step begins.

Automation may run development checks. Manual verification, staging, commits,
pushes and remote repository creation remain maintainer actions unless explicitly
delegated. Running an automated test command manually is a build check; when the
product supports an interactive workflow, also provide actual hands-on scenarios.

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
