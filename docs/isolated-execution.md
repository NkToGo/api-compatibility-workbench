# Isolated execution of the curated Stripe case

The Docker controller places compilation and all fifteen scenario executions
inside one disposable Linux container. The host builds only the workbench's own
reviewed code. The assessed method excerpts are compiled inside the container.
The existing allowlist still governs which consumer code and SDK bytes may run.

This is a container boundary around an entire case, not a separate security
boundary between each scenario, the consumer and the expected-behavior evaluator.
It does not support arbitrary repositories, build scripts, generated repairs or
public multi-tenant execution. Containers share their engine host's kernel;
container-escape resistance and independent verification require further work.

## Prepare and run

Requirements: JDK 21, PowerShell 7, a Linux/amd64 Docker engine with cgroup v2 and
seccomp enabled, and a trusted Docker context. Docker Desktop's Linux engine is
supported on Windows. Other architectures and cgroup v1 are not supported.

From the repository root:

```powershell
./scripts/Prepare-StripeCase.ps1
./mvnw.cmd -B -ntp -f backend/pom.xml clean verify
./scripts/Build-IsolatedWorker.ps1
./scripts/Run-IsolatedStripeCase.ps1
```

On Linux, invoke scripts using `pwsh -NoProfile -File` and Maven with `sh ./mvnw`.
Rebuild the backend and image after source changes. The selected image is a frozen
input, not a live view of the checkout. The ordinary local runner remains available
for fixture development; container failures never trigger a fallback to it.

Preparation downloads approved dependencies and may pull the official Temurin
base image. Its manifest digest is pinned in `containers/curated-worker/Dockerfile`.
The build context is a new directory containing only named library JARs, the six
case files, the three historical SDK dependencies and the reviewed Dockerfile.
Neither the repository root, Git metadata nor local credential files enter that
context. There are no external consumer Dockerfiles or networked build steps.
The resulting local image ID is stored in ignored `backend/target/worker-image.txt`.
The controller accepts an immutable image ID supplied by this trusted preparation
process; it does not authenticate images supplied by arbitrary external callers.

## Enforced policy

| Boundary | Policy |
| --- | --- |
| Input files | Immutable image; read-only container root; no host bind mounts, volumes or Docker socket |
| User | UID/GID 65532; all Linux capabilities dropped; no-new-privileges; default seccomp |
| Network | `none`; only loopback exists; no ports published |
| Environment | Cleared before Java starts; only PATH, HOME, TMPDIR and LANG supplied |
| Memory | 1 GiB for the whole container; equal memory/swap limit disables container swap |
| CPU | Two CPU equivalents through cgroup quota |
| Processes | 128 tasks, including JVM threads and subprocesses |
| Scratch | `/work` tmpfs 256 MiB, `/tmp` tmpfs 16 MiB; noexec, nosuid, nodev |
| Open files | 1,024 per process |
| Time | Host waits at most 180 seconds for attached execution; independent in-container 185-second kill backstop |
| Output | At most 2 MiB per stdout/stderr stream retained by the host; Docker logging disabled |

Standard container pseudo-filesystems, including `/dev/shm`, still exist and their
memory use counts toward the container limit. No executable is launched from
scratch; Java reads compiled class files as data. Docker daemon configuration,
host patching, disk space for image preparation and access to the Docker socket
remain operator responsibilities. The controller's Docker access is privileged;
it is never passed into the worker.

Before starting, the host inspects the resolved image, user, mounts, network and
resource settings. Before running a case, the worker checks kernel-visible UID,
capabilities, seccomp and no-new-privileges state, the environment, actual cgroup
limits, scratch capacity, denied filesystem writes and absence of external network
interfaces. It also attempts a connection to a documentation-only address; a
successful connection is rejected. Missing requirements fail closed.

The host uses a fixed command and fixed modes, not caller-supplied shell strings.
The internal timeout kills the process group; container exit tears down its
remaining processes. Host timeout, output overflow and ordinary exceptions enter
cleanup, which targets only the newly generated container name. Control commands
have their own deadlines; cleanup can take additional time after the execution
deadline. Failed cleanup is reported explicitly, never as a successful run.

## Results and failure handling

Each attempt gets a unique directory under `backend/target/isolated-case-runs/`:

- `request.json`: fixed container arguments and deadline, without credentials.
- `execution.json`: final host receipt, phase, execution status, image identity,
  inspected limits, worker guard observations, cleanup outcome and artifact hashes.
- `stderr.txt`: bounded worker diagnostics when attached execution was reached.
- `worker-report.json`: the original worker's comparison report for a completed
  case. Its inventory references files inside the ephemeral worker workspace.
- `worker-environment.json`: retained environment manifest, including image ID
  and isolation-check identity, checked against the assessment's environment digest.
- `worker-sources.json`, `worker-provenance.json`, `worker-harness.json` and
  `worker-expectations.json`: retained revisions, source and dependency provenance,
  harness identity and expected behavior, checked against the worker's identities
  or artifact inventory before export.
- `report.html`: freshly rendered on the host from validated typed observations;
  no worker-supplied HTML or destination paths are accepted.

The controller recomputes the comparisons, checks them against the worker's claimed
assessments, and verifies the supplied expectations and environment identities.
This detects contradictory records; it does not authenticate execution by an
untrusted image. The worker and its evaluator remain trusted components.

`COMPLETED` means execution produced structurally consistent evidence and cleanup
succeeded. It does not mean the migration passed: the normal case still records
four regressions in the upgrade and two in the historical patch. A running worker
has no recorded exit code yet. Timeouts, excessive output, nonzero exits, missing
guard evidence and invalid output cannot become completed case reports.

`execution.json` is written with an atomic final rename. If absent, the host attempt
is incomplete. Always read its status before using a report. A failed export or
cleanup may leave partial local files. CLI failure is nonzero and prints the
receipt location; diagnostics are kept in files rather than echoed automatically.

The full worker workspace is **not retained**: tmpfs contents disappear when its
container stops. The worker report's internal inventory is provenance, not a
promise that those files remain available. The host receipt hashes the actual
exported artifacts separately. Final host receipts and these exports are now
automatically copied to the [local evidence store](evidence-storage.md), including
failed runs. A storage failure causes a nonzero CLI exit; the temporary receipt
remains available for investigation or a later explicit import.
Temporary reports and image build contexts are ignored by Git and removed by Maven
`clean`; the default evidence store survives. Docker images use separate engine
storage and are not removed by Maven.

Hard termination of the host, daemon failure and machine shutdown can prevent
cleanup or receipt completion. The worker's deadline backstop limits normal orphan
execution, but abandoned containers are not yet reconciled automatically. Recovery,
durable cancellation and restart reconciliation remain later work. Inspect named
containers with `docker ps -a --filter label=io.github.nktogo.acw=isolated-case-v1`.

## Verification

After building the backend and image, run the actual container tests in PowerShell:

```powershell
$workerImage = (Get-Content -LiteralPath backend/target/worker-image.txt -Raw).Trim()
./mvnw.cmd -B -ntp -f backend/pom.xml "-Dworkbench.container.image=$workerImage" '-Dtest=DockerCaseExecutorTest' test
```

On Linux use `sh ./mvnw` with the same arguments. Tests execute the real case,
check kernel-enforced boundaries, deliberately exhaust scratch space, exceed the
output limit, exceed a deadline, exit unsuccessfully and request an unavailable
image without pulling or falling back to the host. They verify that each
container is absent afterward and that failed runs have no case report. Ordinary
tests separately reject mutable image references and arbitrary command modes,
and exercise bounded subprocess capture without Docker.

Linux CI runs both suites and retains the exported evidence for 14 days. Windows
CI runs the host tests; Docker Desktop execution is tested separately on Windows
against its Linux engine. Skipped container tests in an ordinary build do not
establish that isolation was verified.

## Platform references

- [Docker container run options](https://docs.docker.com/reference/cli/docker/container/run/)
- [No-network driver](https://docs.docker.com/engine/network/drivers/none/)
- [Resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)
- [Temporary filesystem mounts](https://docs.docker.com/engine/storage/tmpfs/)
- [Default seccomp policy](https://docs.docker.com/engine/security/seccomp/)
- [Docker security and daemon trust](https://docs.docker.com/engine/security/)
