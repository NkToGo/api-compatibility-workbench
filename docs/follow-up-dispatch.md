# Scoped follow-up investigations

The trusted host can select one or two recorded questions from a completed
[coordinator review](coordinator-review.md) and authorize fresh, bounded
[consumer tasks](consumer-tasks.md). The coordinator's proposal does not grant
permission or dispatch anything. Registration is a separate host action that
seals the selected questions, input identities, child membership and call budget.
Responses are retained independently. A separate [reconciliation review](follow-up-reconciliation.md)
can assess them without modifying this round's history.

## Register and run

Build the backend using the README commands. Inspect the completed review's
successful `propose_follow_up` events and select their host-assigned proposal IDs.
There may be at most one selected question per source task. Rejected, unknown or
duplicate selections are refused before registration creates runnable work.

```powershell
$round = ./scripts/Follow-Up.ps1 -Action register -ReviewId review-EXISTING-UUID -ProposalIds follow-up-8 -MaxCalls 12
./scripts/Follow-Up.ps1 -FollowUpId $round
```

Replace the review and proposal references with existing records. For two
different source tasks, pass two proposal IDs as a PowerShell array. `-MaxCalls`
defaults to 12 per selected task and may be reduced; the maximum is 24. Each
child retains its own 12-decision ceiling. Models and execution modes are copied
from the original task definitions, with no fallback or run-time override.
`-StateDirectory` defaults to `.workbench/`.

Registration makes no model call or worker execution and needs no credential.
Running makes paid requests through the host broker. The script uses the process
`OPENAI_API_KEY` or ignored `.env.local`, restores the previous environment on exit
and never prints the credential. Customer tasks retain their fixed container and
two-test-request budget; subscription tasks read retained evidence only and start
no new worker. No repair generation or publication is part of this command.

## Proposal authority and fresh permission

A successful proposal event can be selected even when the source review stopped
later, for example because its final summary had unsupported citations. Historical
validation must still accept that event under the original review protocol.
Selection does not promote the rejected summary, change the review status or
reinterpret earlier failures. It authorizes a new investigation of that question.

Records live in `.workbench/follow-ups/follow-up-UUID/`. Registration captures the
review definition/result and its inert input inventory, then copies each original
task's sealed case artifacts into a fresh task store. The source task definition
must still match the reviewed identity, and its captured case inputs must validate.
Original source stores are required during registration; their later changes or
removal cannot change the new snapshots. Original task permissions need not be
renewed. Previous agents, calls, execution grants and active ownership markers are
not copied. Keep original evidence separately for audit.

Each new case description contains `followUp` metadata: the question, source
task/review/proposal identities and source citation IDs. Its authority is
`advisory-question-not-evidence` and its content is untrusted data. Source citation
IDs record lineage; they are not eligible citations in the new investigator's
findings. That investigator must read its own inputs and cite its own tool events.
Question wording cannot add a tool, select another model, raise a budget or grant
access. Citation validity does not establish semantic answer quality.

A fresh task grant and owned child batch are nested under `child-state/`. A sealed
controller marker prevents standalone task or batch run/tool entry points from
executing this work outside the owning dispatcher. The normal batch command still
accepts two to four tasks; this controller owns a special one-to-two-task batch
with at most two concurrent investigators. Delegation depth is one: follow-up
tasks cannot be used as recursive source tasks. Registration is host-selected,
not unrestricted agent creation.

The outer `follow-up.json` binds selection, the copied review inventory, child
controller and batch/grant identities. Its separate grant permits only
`execute-selected-follow-ups`, expires after 20 minutes and is published last.
A crash during registration may leave inert owned children without a usable
outer grant. Registration is not automatically repaired or expanded.

## Recovery, stops and accounting

Repeat the same run command and round ID after interruption or `ACTIVE`. The
existing scheduler's durable call reservations, broker mappings and child
histories are reused. A reservation without a broker mapping consumes its slot
and stops as `BATCH_CALL_REGISTRATION_UNCERTAIN`. An ambiguous provider outcome
is retained without automatic replay. Unknown usage is not treated as zero cost.

```powershell
./scripts/Follow-Up.ps1 -Action revoke -FollowUpId $round
```

Revocation is permanent and needs no credential. The outer controller checks its
grant, deadline and captured identities before and after broker/tool operations;
the child batch and tasks retain their separate checks. Revoking the round also
revokes its owned batch. Queued work stops; an in-flight operation may finish
within its existing deadline, with paid usage retained and no further action
admitted. There is no instantaneous transport cancellation guarantee.

| Round status | Meaning |
| --- | --- |
| `ACTIVE` | Another controller or child remains active; resume the same round |
| `FOLLOW_UPS_RECORDED` | Every selected child has a retained terminal outcome |
| `FOLLOW_UP_REVOKED` / `FOLLOW_UP_EXPIRED` | Outer admission ended |
| `FOLLOW_UP_AUTHORIZATION_DENIED` | Missing or mismatched outer permission |
| `FOLLOW_UP_INPUT_IDENTITY_MISMATCH` | A sealed outer input changed |

A recorded child may have investigated, abstained or stopped operationally.
Inspect its status and underlying task/agent records; aggregate completion is
not success on the question. The aggregate has authority
`follow-up-workflow-not-execution-evidence`, preserves proposal-to-child lineage,
and always records `reconciliation: PENDING`. Earlier claims and possible conflicts
remain unchanged. No compatibility verdict or release approval is inferred.

Usage binds the owned batch's reservation/request/result inventory and reports
reserved slots, mapped calls, completed responses, input/output tokens and
`unresolvedUsageSlots`. It counts this round only; earlier batch and review usage
remain in their separate records. Token totals are not an invoice or currency
estimate. A completion record is sealed before aggregate publication. Recovery
can reconstruct a missing aggregate without a new call, including after revocation.
Historical reads validate captured inputs, child outcomes, broker history and
accounting, and reject altered conclusions or lineage.

Preserve the entire round directory together on a trusted local filesystem.
This is single-host workflow ownership, not multi-user authorization, OS process
isolation or a distributed scheduler. Hashes detect changes relative to retained
records; they are not signatures against an operator who rewrites the whole store.
No private model reasoning is stored or exposed.

## Validation

Synthetic tests exercise fresh heterogeneous task snapshots, malicious question
wording, changed source stores, rejected selections, controlled child entry points,
foreign grants, expiry/revocation, active-call recovery, publication recovery,
racing budgets, actual concurrent dispatch, ambiguous provider usage, interrupted
registration and altered aggregate/broker history. These use no credentials.

These checks establish bounded dispatch and retention. The separate reconciliation
component records question-coverage advice; semantic acceptance and controlled
measurement of coordination benefit remain distinct checks.

### Recorded follow-up round, 2026-10-03

One bounded live round selected the successful `follow-up-8` event from
`review-8c13f95e-c85b-4b81-8bdd-384a02c58171`. The question concerns the
customer-sources case: distinguishing an omitted collection from explicit
evidence of presence or absence when retaining/removing a known legacy method.
That original review remains `UNSUPPORTED_REVIEW`; selection did not approve its
rejected later citations or final summary.

The fresh customer task used its original fixed `gpt-5.6-terra` model, a
12-call shared budget and its own captured inputs/permissions:

| Round | Child | Calls | Input / output tokens | New case executions | Unresolved usage slots |
| --- | --- | --- | --- | --- | --- |
| `FOLLOW_UPS_RECORDED` | `INVESTIGATED` | 10 | 59,428 / 1,251 | 1 | 0 |

It retained nine tool events and four advisory findings. This is an operational
trial, not a semantic acceptance review or evidence that coordination improves
results. `reconciliation` remains `PENDING`; earlier findings and possible-conflict
records are not amended. The source batch result hash remained unchanged.

The round is `follow-up-31eb258e-f12d-4f73-82c0-b3518a4f7c17`; its aggregate
SHA-256 is `9d0cf9bf34ce8df6f81bd3d918f00003bddd9722ab76615195338e9bcb1b1c74`.
Repeating the completed command preserved that hash and the full inventory of all
ten broker directories. It registered/dispatched no replacement calls. The
underlying records remain in ignored local storage.
