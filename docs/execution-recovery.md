# Execution status, cancellation and recovery

The isolated-run script registers an attempt before invoking Docker. A local
journal retains the immutable image, fixed mode, deadline, cancellation request
and final evidence reference. It survives controller termination and Maven `clean`.
This applies to the curated container runner, not the ordinary local fixture runner.

## Commands

After building the backend and preparing the worker image:

```powershell
./scripts/Run-IsolatedStripeCase.ps1
./scripts/Execution.ps1 -Action status
./scripts/Execution.ps1 -Action cancel -RunId acw-00000000-0000-0000-0000-000000000000
./scripts/Execution.ps1 -Action recover
```

Replace the example identifier with the printed `Registered run` identifier.
Cancellation can run in a second terminal while execution is active. `status` and
`recover` accept an optional `-RunId`; without it they visit all registered attempts.
Recovery closes queued attempts too, without starting them. It does not schedule work.
Use a specific ID if only one attempt needs reconciliation.

Both scripts accept `-ExecutionDirectory` and `-EvidenceDirectory`. Defaults are
`.workbench/executions/` and `.workbench/evidence/`, ignored by Git. Use the same pair
for subsequent commands. Do not delete a journal with active or unreconciled attempts.
After Maven `clean`, rebuild the backend to use the commands again; retained
metadata and evidence do not require the worker image to be rebuilt for recovery.

## States and guarantees

| State | Meaning |
| --- | --- |
| `QUEUED` | Specification published; execution has not been marked started |
| `ACTIVE` | Another cooperating controller holds the operating-system lease |
| `RECOVERY_REQUIRED` | No owner remains, but execution or cancellation was recorded without terminal publication |
| `CANCELLATION_REQUESTED` | Cancellation is recorded; the active owner still needs to stop and finalize |
| Terminal receipt status | Verified evidence has been published and the journal binds its exact receipt hash |

An `owner.lock` file persists, but its existence does not imply ownership. The
operating-system file lock does. Process death releases that lock; recovery never
guesses ownership from a PID or a timeout. Concurrent recovery skips a live owner.
Only one cooperating process may execute or recover an attempt at once.
An in-process guard prevents competing callers from opening a second channel for
the same held lock. This also protects against platforms where closing any channel
to a file releases the process's locks; see Java's [file-lock platform dependencies](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html).

The specification and start marker are forced to disk and atomically published
before external actions. The cancellation marker is durable and checked before
creation/start and during attached execution. Docker control calls have bounded
deadlines, so cancellation is not instantaneous. A completion/cancellation race
may finish as completed: cancellation does not rewrite a finalized receipt.

Recovery under the lease has two paths:

1. **Final receipt present:** validate and publish the original bytes to the evidence
   store, then publish the journal result. Retrying is idempotent, including a crash
   after evidence publication but before journal publication. No test is repeated.
2. **No final receipt:** verify the registered container's image, exact name and both
   ownership labels, remove it by immutable container ID, and record `INTERRUPTED`
   (or `CANCELLED` when requested). No exit code, scenario observations, compatibility
   conclusion or successful report is invented. Partial exports remain separately
   available in the journal; recovery does not treat them as finalized evidence.

If no start marker exists, recovery needs no Docker access because the execution
path could not have created a worker. Once started, daemon unavailability or an
ownership mismatch blocks reconciliation; absence is never inferred from a failed
Docker command. Resolve the daemon/ownership problem and retry the same run ID.

A receipt recording failed cleanup remains historically `CLEANUP_FAILED` even
after recovery removes the orphan. The journal's `cleanupReconciled` field records
that later reconciliation separately. A fresh execution always gets a new ID.
There is no transparent replay, automatic resume of lost stdout or exactly-once
claim for external operations.

## Layout and operational limits

Each registered ID contains `spec.json`, `owner.lock`, optional `started.json`,
`recovery-started.json` and `cancel.request`, original exports in `attempt/<run-id>/`, optional interrupted
receipt exports in `recovery/`, and finally `result.json`. The result binds the
evidence-store receipt hash and status; status reads verify the stored evidence.
Incomplete registration directories without `spec.json` are ignored by listing;
no worker can have started from them. Pending metadata files are not final records.
Once recovery is marked started, execution cannot start that ID even if recovery
itself is interrupted before publishing a terminal result.

Keep the journal and store on a trusted local filesystem with reliable locking
and atomic rename, restricted to the operator. They are not safe shared network
queues or multi-user authorization boundaries. Local write access can falsify
records; hashes detect inconsistencies, not authorship. Forced file writes do not
guarantee survival of every hardware failure or power loss; preserve tested backups.

Recovery is an explicit command, not a background service. Keep the same trusted
Docker context for execution and recovery. The worker's independent deadline
backstop still limits ordinary orphan execution. Credential brokering,
distributed scheduling and arbitrary repository execution are not implemented.
New scripted attempts have [expiring, revocable local permission](execution-authorization.md).
Recovery and cleanup remain available after permission is lost; they never renew it.

Tests use real container execution and a forcibly killed controller process to
verify lock release, orphan removal and honest interruption evidence. Additional
tests exercise cancellation, interrupted final publication, ownership mismatch,
invalid identities, missing retained evidence and repeated recovery.
