# Running the historical Stripe case

The local runner executes the five curated scenarios against three variants:
the original consumer/SDK, the original consumer with the newer SDK, and the
upstream reference patch with that newer SDK. It derives assessments using the
same domain comparator as other recorded observations.

This page describes the local process runner. The [container controller](isolated-execution.md)
adds a Linux container boundary around the entire case, including compilation;
its report retention and operational requirements differ as documented there.

## Build and run

Use JDK 21, PowerShell 7 and the Maven Wrapper. From the repository root:

```powershell
./scripts/Prepare-StripeCase.ps1
./mvnw.cmd -B -ntp -f backend/pom.xml clean verify
./scripts/Run-StripeCase.ps1
```

On Linux/macOS, run the PowerShell scripts with `pwsh -NoProfile -File` and use
`sh ./mvnw -B -ntp -f backend/pom.xml clean verify` for Maven.

Preparation downloads the three pinned historical JARs from Maven Central and
checks their SHA-256 values. They are cached under the case's ignored
`target/dependencies` directory. Maven separately resolves the current runner's
dependencies. Once those dependencies are cached, execution itself is offline.
No OpenAI or Stripe credentials are read. No provider API request leaves the
fixture transport. `Run-StripeCase.ps1` checks the input cache and uses the last
built classes; rebuild after source changes.

## Results and their limits

| Variant | Passing scenarios | Failing scenarios | Assessment against baseline |
| --- | --- | --- | --- |
| Baseline | All five | None | Baseline evidence |
| Unrepaired target | Collection-present control | Four omitted-collection scenarios | `REGRESSION_OBSERVED` |
| Upstream reference | Modern method, empty account, collection-present control | Legacy method preservation and evidence before deletion | `REGRESSION_OBSERVED` |

The original target dereferences a missing source collection. The reference patch
skips that collection, then the unchanged cleanup loop deactivates a locally known
legacy card. In the confirmed-removal scenario, its final set happens to be right,
but it never established source absence before deleting. That still fails the
declared contract. The expectations are not changed to accept the patch.

These are observed results for preserved methods, controlled service doubles and
synthetic responses. They neither reproduce a full Kill Bill deployment nor
establish the behavior of a live Stripe account. The fixture harness has no
validated repair variant yet. An exit code of zero means a report was produced,
not that a migration passed. Read the assessment and per-scenario outcomes.

## What actually executes

The runner inserts the entire pinned method excerpts into a generated `Consumer`
class without altering the method bodies. The retained `Consumer.java` permits
inspection of that assembly. The surrounding imports and constructor come from
`Consumer.prefix`. `FixtureMain.java.txt` supplies explicit service doubles:

- In-memory method records, creation, update and deactivation replace Kill Bill's
  database and payment services. Events record the order of synchronization and
  deletion; deleted identifiers remain visible even when the final set is empty.
- Configuration supplies a fixed dummy string required by SDK request building.
  It is not a credential. The clock is fixed and logging is silent.
- Additional-data conversion retains identifiers only. Metadata serialization,
  SQL effects, default-card selection, transactions and real platform services are
  outside the coverage. Fixtures currently exercise existing records, not creation.
- Real Stripe SDK request construction, version headers, deserialization and
  collection iteration run against a custom `HttpClient`. It permits exactly the
  two supported GET endpoints and query shapes, verifies the effective API version,
  and has no network fallback. Unsupported endpoints or pagination are execution
  limitations, not observed consumer regressions. The explicit source-list fixture
  is reserved for a future repair; this runner does not silently fetch it.

Each scenario runs in a new JVM with its selected SDK and Gson 2.8.6 classpath.
The parent uses Gson 2.14.0; it never loads the historical SDKs into its classpath.
All workers use identical Java 21 options, including
`--add-opens=java.base/java.net=ALL-UNNAMED`. This narrowly scoped flag is necessary
because the historical Gson/SDK combination reflects into `RequestOptions.proxy`.
Without it, the newer historical SDK fails with `InaccessibleObjectException`
before reaching the consumer. That runtime failure must not be reported as an API
regression. The flag is recorded in the environment identity and applies to all
variants; it does not change the domain application's JVM settings.

The environment is cleared before launch except for Windows `SystemRoot` when
needed and temporary-directory paths. Each process has a 128 MiB Java heap limit
and a 30-second deadline. These are local process controls, **not an OS sandbox**
or a total memory limit. Compilation occurs in the parent. Only the explicitly
allowlisted excerpt and dependency hashes may execute. This runner must not be
used for arbitrary repositories, generated repairs or untrusted code; stronger
execution isolation belongs to the next milestone.

## Evidence files

Every invocation creates a fresh directory under `backend/target/case-runs/`.
Tests keep the normal-case evidence under `backend/target/test-case-runs/`.

- `inputs/` retains the case package, historical JARs, licenses, worker templates,
  and manifests describing the harness and environment.
- Each variant retains generated sources, compiled classes, compiler diagnostics,
  its configuration and upstream source identity.
- Each scenario retains the selected fixture, process stdout/stderr, request and
  state-change events, and the evaluated observation.
- `report.json` contains both derived assessments, the exact input snapshots,
  observations, details and a SHA-256 inventory of the other evidence files. It
  is written last with an atomic rename. A directory without it is incomplete.
- `report.html` is a self-contained [readable result view](case-report.md) of the
  same observations. It is generated before the completion marker and included
  in the JSON inventory. If `report.json` is absent, do not use the HTML as a
  completed run. The view has no scripts, remote assets or live input checks.

Source identity includes the repository, revision and exact excerpt digest.
Configuration identifies the SDK bytes, effective API version and fixture side.
The complete shared fixture/expectation bytes are hashed separately. Harness
identity includes the compiled runner, JSON library, templates, allowlist and
deadline; environment identity includes Java/OS metadata, Java executable and JDK
module-image digests, and worker options. Sorted JSON maps are used for generated
identity manifests. These hashes support provenance and stale-result detection,
not signatures, authorization or tamper-proof storage.

Environment identity also includes the execution boundary. Container runs add the
immutable image ID, verified isolation-check digest and internal deadline backstop.
Local and container runs therefore have distinct identities even when their
behavioral outcomes agree.

Process launch/compile failures, unsupported fixture behavior, missing or invalid
results, and deadlines cannot become passing assertions. A caught consumer null
dereference is instead a completed behavioral failure. Interrupting the parent
can leave incomplete evidence rather than a completed assessment. Resume and
durable recovery are not implemented. Reports and event ordering express repeatable
behavior; run identifiers, timestamps and serialized map-member order may differ.

Local evidence and SDK caches are Git-ignored. Maven `clean` removes backend
evidence, so copy any report bundle that needs to survive a clean build. CI keeps
the JSON and HTML reports as downloadable artifacts for 14 days; the full local bundles
are not uploaded. A CI report's hashes do not imply that its referenced files are
still stored by GitHub. Durable artifact storage is not implemented yet.
