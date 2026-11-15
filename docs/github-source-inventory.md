# Selected Maven source inventory

The host can read explicitly selected Maven POM files at one exact GitHub commit
using the [read-only App connection](github-repository-connection.md). It reports
Stripe Java dependency declarations and possible local property values. It does
not execute source, run Maven, resolve an effective dependency graph or assess
compatibility. There is no model call in this command.

## Run

Configure the App, host key and exact repository selection as described in the
connection guide, then complete [build verification](../README.md#build-and-test).
Copy [the POM plan example](../examples/github-pom-plan.json) and replace its paths
with the POM files to inspect. Paths are relative to the repository root; modules
and parents are never followed automatically.

```powershell
./scripts/Inventory-GitHubSources.ps1 -Selection ./local-repository-selection.json -Plan ./local-pom-plan.json
```

Only `formatVersion` (currently `1`) and `pomPaths` are accepted. Select one to
eight unique paths ending in `pom.xml`, at most 240 characters and eight segments
each. Segments allow ASCII letters, digits, dots, underscores and hyphens, but not
`.` or `..`. URLs, encoded paths, backslashes and arbitrary file kinds are refused.
The operator owns this selection; assessed source cannot expand access.

The JSON output contains repository/commit/tree identities, the selected paths,
per-file states, Git blob IDs, SHA-256 content digests, byte counts and declaration
metadata. Source bytes are held transiently and are not retained by the command.
Save stdout to a protected file outside build output when the metadata is needed
later. The CLI returns `0` for a complete selected-file census, `3` for an incomplete
census, `1` for connection failure/interruption and `2` for startup/configuration
failure. The PowerShell launcher raises an error on any nonzero result.

## What declarations establish

Only literal `com.stripe:stripe-java` coordinates in project/profile dependencies
and dependency management are inventoried. Each declaration has an indexed POM
locator and flags for profile and dependency-management context. Plugin
dependencies are outside this census. Missing/dynamic coordinates are counted as
unresolved rather than silently treated as unrelated dependencies.

Versions are classified as literal declarations, property references,
managed/unspecified, or unsupported/ambiguous. For a simple property reference,
literal values found in the same POM's project and profile properties are reported
as separate candidates. **No candidate is selected as the effective version.**
Profile activation, inheritance, imported management, environment/system values,
recursive interpolation and build arguments are not evaluated. A parent is
reported only as present, and is not fetched. A POM without a matching declaration
does not establish the absence of the SDK in the effective build.

For example, a root `${stripe.sdk.version}` with project value `28.4.0` and profile
value `29.0.0` produces two candidates. A module with a versionless dependency
remains managed/unspecified, even if both POMs were selected. Request API versions,
webhook endpoint/event versions and account defaults remain **not assessed**.

## Integrity, limits and failures

The command repeats the connection's five authorization/revision checks, then
reads nonrecursive Git trees rooted at the returned commit tree and selected
blobs by object ID. It reconstructs each fetched tree and blob to check its Git
SHA-1 identity, checks returned byte sizes, and calculates SHA-256 for selected
source bytes. These checks bind bytes to the selected Git objects; they are not
commit-signature verification or a guarantee against SHA-1 collisions. Returned
URLs are ignored. Symlinks, submodules and non-file selected entries are refused.

There are at most 24 additional source requests, 64 KiB per selected file and
256 KiB total verified source bytes. Trees are cached within one invocation;
truncated trees, more than 1,000 entries and malformed objects are refused. The
connection's 256 KiB response and strict JSON limits still apply. A 180-second
operation deadline is checked before source requests and after responses; an
in-flight request retains its separate 30-second response limit. Parsing and
local processing are separately bounded rather than preempted at an exact wall
clock instant. Installation expiry is checked around every source request.

POM parsing uses the JDK XML implementation, refuses DOCTYPEs and external
entities/resources, and limits element depth to 48. Only Maven `4.0.0` project
models, with the Maven namespace or no namespace, are accepted. Malformed,
oversized, deeply nested or ambiguous container structures remain rejected.
Maven settings, extensions, plugins, project instructions and build commands are
never loaded or executed.

Missing files, unsupported entries, size limits and rejected POMs remain per-file
states. Connection, integrity, request-budget and deadline failures stop subsequent
reads while preserving earlier successful entries; unread selections remain
visible. Rate-limit timing is retained without automatic retries. Interrupted
commands emit no completed inventory and have no recovery journal in this step.
`COMPLETE_WITHIN_SELECTED_FILES` means only that every selected POM was read and
scanned. It does not mean complete repository coverage, resolved SDK/API versions,
compatible behavior or permission to publish a repair.

## Data exposure and remaining boundaries

Reports omit raw POM text, arbitrary properties, profile IDs, provider error
bodies and credentials. Selected paths, version expressions and literal candidate
values are still source-derived metadata; treat reports as potentially private.
The accepted version syntax reduces unrelated output but is not a secret detector.
Protect the host and do not enable HTTP or parser payload diagnostics.

This is a bounded CLI read, not durable repository registration, a retained source
bundle, an asynchronous job service or the maintainer web workflow. Connecting
these declarations to effective configuration, retained evidence and authorized
repair review remains separate work. No repository writes or PRs are performed.

## Primary references

- [GitHub Git trees](https://docs.github.com/en/rest/git/trees#get-a-tree)
- [GitHub Git blobs](https://docs.github.com/en/rest/git/blobs#get-a-blob)
- [Git object identities](https://git-scm.com/book/en/v2/Git-Internals-Git-Objects)
- [Maven model, inheritance and properties](https://maven.apache.org/pom.html)
- [JDK XML security](https://docs.oracle.com/en/java/javase/21/security/java-api-xml-processing-jaxp-security-guide.html)
