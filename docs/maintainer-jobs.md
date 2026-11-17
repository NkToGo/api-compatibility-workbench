# Durable planning refresh jobs

The `refresh-planning-inputs-v1` job takes a retained [planning input snapshot](migration-input-retention.md),
rechecks current read-only App access to its exact repository and commit, reads the
same selected POM paths, and retains a fresh snapshot with the same declarations.
It does not advance a branch, follow new modules, change declarations, run Maven
or consumer code, call a model, create a PR or approve a migration.

An input ID identifies historical planning material. It is never an execution
permission. A refreshed result means the bounded read and retention completed;
it does not establish compatibility or effective runtime configuration.

## Commands

After [building and verifying](../README.md#build-and-test), use PowerShell 7 and
the App setup in the [connection guide](github-repository-connection.md):

```powershell
./scripts/MaintainerJobs.ps1 -Action create -InputId INPUT_ID
./scripts/MaintainerJobs.ps1 -Action list
./scripts/MaintainerJobs.ps1 -Action status -JobId JOB_ID
./scripts/MaintainerJobs.ps1 -Action run -JobId JOB_ID -Selection ./local-repository-selection.json
./scripts/MaintainerJobs.ps1 -Action cancel -JobId JOB_ID
./scripts/MaintainerJobs.ps1 -Action recover -JobId JOB_ID
```

Replace `INPUT_ID` with the complete retained snapshot ID and `JOB_ID` with the
returned `planjob-...` identifier. The default evidence store is
`.workbench/evidence/`; the separate journal is `.workbench/maintainer-jobs/`.
Use `-EvidenceDirectory` and `-JournalDirectory` consistently for other trusted
local directories. Both defaults are ignored by Git and survive Maven `clean`.

Create, list, status, cancel and recover require no App credentials or network.
`run` loads the host's existing `WORKBENCH_GITHUB_APP_ISSUER` and
`WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE` only when starting a new read. Its
`-Selection` file must exactly match the retained installation, account,
repository and commit. A mismatch leaves the job queued; source content cannot
choose another scope. Terminal jobs can be reopened with `run` without selection
or credentials and never repeat the read. Historical input access is not evidence
of current App authorization; each newly started job checks access again.

A finite worker drains matching queued jobs in creation-time/ID order:

```powershell
./scripts/MaintainerJobs.ps1 -Action work -Selection ./local-repository-selection.json -MaximumJobs 2
```

The default limit is one job, with at most ten per invocation. One cooperating
worker runs per journal root. It skips other repository selections and jobs
requiring explicit recovery, and stops on a failure, interruption or recorded
provider failure, including a partial rate-limited capture. Retry hints remain
visible; the worker does not sleep and retry, mint replacement tokens for a failed
job or replay ambiguous requests. No daemon or recurring schedule is installed.

JSON output contains IDs, states, fixed failure codes/stages, HTTP status, retry
time where supplied, possible token activity and a bounded output summary. It
contains no source text, credentials or provider response bodies. Java exits `3`
for gaps, failed/interrupted/timed-out jobs, recovery-required or busy/in-progress
operations; a worker also exits `3` if any selected result needs attention.
Invalid scope, configuration or integrity exits `2`. The launcher raises an error
on nonzero status. Successful status/list operations do not imply compatibility.

## States, cancellation and limits

| State | Meaning |
| --- | --- |
| `QUEUED` | Input ID and binding are registered; no read started |
| `ACTIVE` / `WORKER_BUSY` | A cooperating owner/worker lease is held |
| `CANCELLATION_REQUESTED` | Cancellation is recorded; active work has not finalized |
| `RECOVERY_REQUIRED` | A started/checkpointed job has no final result and no owner |
| `REFRESHED` | Selected POM capture and complete declarations retained, still unverified |
| `REFRESHED_WITH_GAPS` | A new snapshot was retained with incomplete reads or declaration gaps |
| `FAILED` | A classified provider, host or local failure was recorded |
| `CANCELLED` / `TIMED_OUT` / `INTERRUPTED` | Work stopped; no completed output is claimed |

Cancellation is durable and idempotent. Before start it avoids all external calls.
During a read it is checked before and after each HTTP exchange, including App
authentication and token creation. An admitted request can finish before the
check observes cancellation. A completed observation can win a cancellation race;
its result and the cancellation marker both remain visible.

The job checks a 180-second admission deadline at the same boundaries. Each HTTP
exchange has the existing 30-second whole-response limit and byte ceiling. This
is not a preemptive 180-second bound on all filesystem and Java work. Existing
selected-file, total-byte and source-request limits remain unchanged.

Cancellation, timeout, process loss and many transport failures conservatively
retain `tokenMayRemainActive`. An already-created installation token is not
immediately revoked; it may remain valid until expiry. A token is scoped to the
selected repository, while reads are pinned to the selected commit. A completed
or recovered job renews no authority.

## Checkpoints and recovery

The journal publishes a specification before dispatch and a start record before
authentication. After capture, it records the exact expected output input ID and
binding before publishing any new input reference. The evidence store publishes
its verified input reference before the job publishes its terminal result.

`recover` takes the owner lease and uses only local records:

- A published output matching the capture checkpoint is strongly reopened and
  finalized. Its source bytes, hashes and derived configuration must still verify.
- A recorded failure is finalized with its original outcome and provider details.
- A lost read or a capture without its completed local reference becomes an
  interrupted result (or cancelled if requested), with no invented output.
- A damaged or inconsistent reference is an integrity error, not proof of absence.
  Recovery preserves it and does not finalize success or repeat the network work.

An active owner prevents recovery. After a process exits, OS cleanup may briefly
delay lease availability. Repeated recovery of a finalized job is read-only and
idempotent. To deliberately observe the source again after interruption or failure,
create a new job from the retained input ID; it is a new observation, not a resumed
unknown fetch. There is no automatic retry policy.

Metadata uses forced file writes and atomic rename through the existing local
journal publisher. Cooperating JVM/process leases protect one journal and one
evidence store. They do not supply distributed consensus, network-filesystem
safety, a power-loss guarantee, tenant isolation or protection against a hostile
local filesystem. A partial registration without its final specification is not
listed as a job. Orphan objects and pending files are not automatically deleted.

## Operation and remaining boundaries

Keep the journal, evidence store and backups under a trusted operator/service
account. The evidence store retains full selected POM bytes; the source privacy
limitations in the input-retention guide still apply. Journal records retain
metadata only. No App key is copied into either store. A local caller able to
alter host configuration and trusted stores is outside this authorization boundary.

This is a single-host planning queue, not a general execution scheduler. Consumer
source capture, execution admission, authenticated maintainer review, approval
invalidation, PR publication, a workflow UI, service supervision and organization
isolation remain separate work. Existing model/consumer journals and historical
comparison results are unchanged.

Tests cover scope mismatch, damaged inputs, concurrent owners, cancellation at
real loopback HTTP boundaries, rate-limit stop behavior, deadlines, controller
interruption, crash checkpoints and killed-process recovery without network replay.

- [JDK file-lock semantics](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)
