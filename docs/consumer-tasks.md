# Isolated consumer tasks

The host can register each curated consumer investigation as a separate task.
Every task has its own input snapshot, investigator history, model-call broker
records, execution journal and evidence store. Registration is deterministic and
does not call OpenAI or start a worker. Each task runs at most one bounded
investigator. A [bounded parallel scheduler](parallel-investigations.md) can claim
fresh tasks. A separate [follow-up dispatcher](follow-up-dispatch.md) can create
focused tasks for host-selected coordinator proposals with fresh permissions.

## Register, run and resume

After the README's backend build, register a customer-sources task using the
prepared immutable case image:

```powershell
./scripts/Build-IsolatedWorker.ps1
$task = ./scripts/Register-ConsumerTask.ps1 -Case customer
```

Alternatively, register subscription evidence from a completed verification:

```powershell
$task = ./scripts/Register-ConsumerTask.ps1 -Case subscription -VerificationId verification-EXISTING-UUID
```

`-StateDirectory` selects the task store's parent, default `.workbench/`.
`-EvidenceStateDirectory` selects the subscription source store, defaulting to
the same directory. `-Model` is fixed at registration and defaults to
`gpt-5.6-terra`; there is no fallback or run-time model override.

Running makes paid OpenAI calls with the existing host credential. Only the
registered curated inputs and observed tool results enter requests:

```powershell
./scripts/Invoke-ConsumerTask.ps1 -TaskId $task
```

Use the same command and task ID after interruption. The host reuses the stored
agent mapping, model-call mappings and tool events. Completed results return
without new requests. Ambiguous provider outcomes stop under the existing broker
policy; registration of a replacement task is new authorized work, not a retry.
No API call is required to revoke a task:

```powershell
./scripts/Invoke-ConsumerTask.ps1 -Action revoke -TaskId $task
```

## Context and environment boundary

Records live under `.workbench/tasks/task-UUID/`. `task.json` declares the exact
context, model, mode, case description and captured artifact hashes. Its `state/`
directory uses the existing adapters' layout with independent `agents/`,
`model-calls/`, `investigations/`, `executions/` and `evidence/` directories.
Adapters may create empty directories even when that capability is unused.
Keep the whole task directory together during backup or recovery. It survives
Maven `clean` and is excluded from Git.

Customer tasks capture the case inventory afresh and retain the existing fixed
container image, blocked egress, resource limits and cleanup rules. Each context
has its own two-test-request budget and deduplicated request mappings. Identical
request IDs in different tasks refer to separate attempts. Containers never
receive a sibling store or the host model credential.

Subscription tasks import a validated, bounded copy of completed evidence and
register a new local context. Subsequent edits or removal in the source store
cannot change the task snapshot. Import preserves original spec/result and input/
job hashes, including candidate artifacts needed to validate that original
record. These are host-only integrity records: tools still expose only approved
baseline inputs and baseline/target observations. Candidate source, candidate
outcomes, execution authorizations, started markers and model grants are not
available to the investigator. `run_case` and `read_run` remain retained-evidence
aliases with zero new worker executions.

## Filesystem validation

Artifact reads recheck their parent directories and reject missing directories,
symbolic links and redirected paths. On the default JDK Windows filesystem,
fresh non-following metadata is checked for every ancestor; ordinary directories
avoid repeated canonical-path resolution. Non-symbolic reparse points retain
the followed/non-followed canonical-path comparison, which rejects junctions.
The fast path is limited to the JDK's `WindowsFileSystemProvider`; other providers
retain the canonical-path check. The [OpenJDK Windows attribute implementation](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java)
marks non-symbolic reparse points with `BasicFileAttributes.isOther()`.
No directory or artifact contents are cached between reads, and the one-MiB
artifact bound is unchanged.

Keep the state on a trusted local filesystem. These checks do not make the
separate validation and open operations atomic against a hostile local process
replacing filesystem entries concurrently.

## Admission permission and failure handling

Each `grant.json` binds one task's spec hash, context indirectly through that
spec, exact model, four allowed tools and 20-minute admission deadline.
Model decisions cannot register another task, choose another context, change
the model, select files or inherit a sibling grant. The dispatcher owns these
bindings; arbitrary task selection is not a model tool. Source text and returned
tool data cannot enlarge the grant.

Before and after each model or tool operation, the host checks the grant,
revocation, deadline and captured artifact hashes. Registered model requests
must use the bound case description, investigation protocol and at most 2,048
output tokens. The existing investigator enforces 12 decisions and bounded input
and retry limits. Per-request model grants and per-attempt execution grants remain
separate inside the task store. Batch-assigned tasks additionally share the
scheduler's fixed call budget and admission checks.

`TASK_REVOKED`, `TASK_EXPIRED`, `TASK_AUTHORIZATION_DENIED` and
`TASK_INPUT_IDENTITY_MISMATCH` distinguish task-level stops from child model/tool
outcomes. A task lease prevents duplicate controllers creating multiple active
agents. A crash before publishing the agent mapping can leave an unstarted agent;
it has made no model call. Stored partial records remain available after a stop.

These are admission checks, not instantaneous cancellation of an in-flight
request or container. Such an operation may finish within its existing deadline;
its response is withheld from further task progress if task access ended. Revoking
one task does not revoke another. A completed result remains historical when its
task later expires or is revoked; reading it does not renew permission. Changed
completed inputs or result bytes are rejected rather than reinterpreted.

The boundary assumes a trusted host and store. It is not OS isolation between
host processes, multi-user authentication, hostile-host tamper protection or a
general repository sandbox. Linked artifacts/directories and escaping paths
are rejected, but hashes are not signatures. A host operator able to rewrite
both evidence and grants is outside this threat model. The worker's existing
container restrictions still govern execution of extracted consumer code.

## Verification and next capability

Default tests cover heterogeneous task histories, sibling grants/agent/call
mappings, immutable snapshots, budgets, expiry, revocation, interruption recovery
and completed-record changes. POSIX CI also tests symbolic-link redirection.
Real container tests execute independent task attempts, reject a sibling mapping,
verify separate retained evidence and demonstrate revocation without affecting
the other task. A Windows command-line check also exercised registration of both
case types, short-name path aliases, revoked-task admission and junction
redirection rejection without registering a model call. These checks use synthetic data; registration, revocation
and the automated tests need no OpenAI credential.

This supplies the task boundary used by bounded parallel scheduling.
It does not establish semantic finding quality, repair quality, coordination
benefit or migration compatibility. The earlier bounded
[Milestone 3 acceptance](milestone-3-results.md) remains a historical measurement;
adding a task boundary does not rerun or improve those trials.
