# Bounded parallel investigations

The trusted host can schedule two to four fresh [consumer tasks](consumer-tasks.md)
with one or two concurrent investigators and a fixed shared model-call budget.
Each investigator adaptively selects its own reads and fixed test requests. The
scheduler is deterministic: it does not interpret findings, reconcile differences,
create agents dynamically or generate repairs. Coordinator follow-up remains planned.

## Run a batch

Build the backend as described in the README and prepare the case image. Register
fresh tasks shortly before the batch; existing tasks with an agent, result or tool
request are rejected. All tasks must belong to the selected state directory.

```powershell
./scripts/Build-IsolatedWorker.ps1
$customer = ./scripts/Register-ConsumerTask.ps1 -Case customer
$subscription = ./scripts/Register-ConsumerTask.ps1 -Case subscription -VerificationId verification-EXISTING-UUID
$batch = ./scripts/Invoke-ParallelInvestigation.ps1 -Action register -TaskIds $customer,$subscription -Parallelism 2 -MaxCalls 24
./scripts/Invoke-ParallelInvestigation.ps1 -BatchId $batch
```

Replace the example verification reference with completed subscription evidence.
Registration makes no model call or worker execution. Running uses the existing
host credential and makes paid requests. `-StateDirectory` defaults to `.workbench/`;
the task registration commands must use the same directory if it is overridden.
Models are fixed by each task's registration, with no batch override or fallback.

The batch seals the ordered task list, task/grant hashes, concurrency, call budget
and a 20-minute admission deadline. Each task retains its own earlier deadline,
12-decision ceiling, 64,000-byte model input ceiling, 2,048-token output limit,
four tool names and case-specific execution budget. At most two child operations
can run concurrently per batch, including model calls or container execution. Two to four
tasks imply at most 24 to 48 reserved calls; a smaller shared budget may stop tasks
before they finish. There is no fairness guarantee or transfer of a task's history
to another investigator. A slot is a call admission bound, not a currency quote.

## Ownership, recovery and stops

Records live in `.workbench/parallel-investigations/batch-UUID/`. Preserve this
directory and its assigned task directories together. Registration leases all
selected tasks, writes a sealed batch, permanently claims the tasks and finally
publishes a root grant. A crash in that sequence can leave claimed but unstarted
tasks without a usable batch grant. Claims are never automatically reassigned;
replacement registration is new work. The standalone task run/tool entry points
reject assigned tasks without publishing a terminal task result. The operator can
still revoke an individual task.

A root lease prevents overlapping controllers. Each child also retains its task,
agent and broker leases. Repeat the same run command after interruption or an
`ACTIVE` result. Completed child outcomes are reused; active children resume their
existing agent and call mappings. The controller holds its root lease while
interrupted workers drain. Existing transport/container deadlines still apply.
Abrupt process death is reconciled by the existing broker/execution journals;
an ambiguous paid request is not replayed.

Before broker registration, the scheduler durably reserves a slot under a shared
budget lease. It then records the exact request hash and broker call mapping.
Recovery reuses that mapping even if the child had not yet published its own call
mapping. If registration might have occurred but the root mapping is missing,
`BATCH_CALL_REGISTRATION_UNCERTAIN` stops that child and consumes the slot. It does
not guess that the request was never registered. A registered orphan may exist in
the child's broker store; no new request replaces it automatically.

```powershell
./scripts/Invoke-ParallelInvestigation.ps1 -Action revoke -BatchId $batch
```

Revocation is permanent and needs no API credential. Root checks occur before and
after model/tool operations. Queued tasks stop and in-flight operations can finish
within their existing deadlines; their results do not authorize subsequent work.
There is no instantaneous transport/container cancellation guarantee.

Child stops include `BATCH_REVOKED`, `BATCH_EXPIRED`,
`BATCH_AUTHORIZATION_DENIED`, `BATCH_TASK_IDENTITY_MISMATCH`,
`BATCH_CALL_BUDGET_EXHAUSTED`, `BATCH_CALL_REGISTRATION_UNCERTAIN` and
`TASK_OPERATIONAL_FAILURE`, alongside existing task/model outcomes. An individual
revoked or changed task does not stop another authorized task. Operational failures
are retained as terminal child outcomes; they are not silently retried. A controller
interruption leaves unfinished children resumable. Earlier evidence is preserved.

## Outcomes and usage

`COMPLETED` means the scheduler recorded every child outcome. A child may still
have abstained or stopped operationally. The command prints each child status;
inspect `children/task-UUID.json` and the underlying task/agent records for scope.
The aggregate has authority `orchestration-status-not-execution-evidence`.
`INVESTIGATED` findings remain advisory and require semantic review; batch completion
does not establish compatible behavior, a valid repair or a release approval.

The result binds child envelopes, task results and an accounting inventory by
SHA-256. Accounting distinguishes reserved slots, mapped broker requests, completed
responses and their input/output tokens. `unresolvedUsageSlots` conservatively
counts reservations without mappings, mapped calls without results and non-complete
responses whose dispatch was not ruled out. These may incur charges; completed
token totals are not a full invoice. The broker retains rate-limit and incomplete
outcomes without automatic retries. Reading a completed batch validates its bound
records without renewing expired or revoked permission or making new requests.

The existing trusted-host threat model applies. Hashes are integrity bindings,
not signatures against an operator who can rewrite the entire store. This is not
multi-user authorization, distributed scheduling or OS isolation between host
processes. Model-visible tools do not expose sibling contexts, grants, task
creation, batch selection, arbitrary commands or private reasoning.

## Validation

Default tests use synthetic model responses and no credentials. They verify actual
overlap with synchronization barriers, serial admission, racing shared-budget
reservations, separate histories, assigned-task rejection, copied grants, altered
claims/inputs, revocation, expiry, active-child recovery, interrupted worker drain,
uncertain registration, ambiguous provider outcomes and changed historical records.
These establish scheduler behavior rather than semantic quality or coordination
benefit. Milestone 4's later comparison step measures whether coordinated work
improves outcomes over single agents and independent investigators.

### Recorded two-case run, 2026-10-02

One bounded live batch used `gpt-5.6-terra` for both curated consumers, with two
concurrent investigators and 24 available call slots:

| Consumer | Task outcome | Model calls | Input / output tokens | New case executions |
| --- | --- | --- | --- | --- |
| Customer sources | `INVESTIGATED` | 9 | 44,194 / 1,106 | 1 |
| Subscription periods | `INVESTIGATED` | 6 | 18,154 / 865 | 0, retained evidence only |

All 15 reserved calls had completed responses: 62,348 input and 1,971 output
tokens, with zero unresolved usage slots. Thirteen of the 15 host-recorded broker
dispatch intervals overlapped an interval from the other task. The span from the
first dispatch marker to the last broker result was 41.216 seconds; this is not
the whole command duration or a measured speedup against a serial control.
Repeating the completed batch command preserved its result hash and registered
call counts, with no additional dispatch.

The local batch reference is `batch-a95d3763-aa04-4124-aab3-3ebf8b724c14`; its
result SHA-256 is `63f8d37ae79a19babdc46c93426b3f974c48aef6955a0e63ec7450ab32ef3e1d`.
Underlying records remain in the ignored local stores. This single run exercises
real scheduling, adapters and broker calls. It does not establish semantic finding
quality, model accuracy, migration compatibility or a coordination advantage.
