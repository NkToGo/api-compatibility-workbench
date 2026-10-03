# Coordinator evidence review

A bounded coordinator can review a completed [parallel investigation](parallel-investigations.md),
read captured evidence, record possible conflicts and propose follow-up questions.
The host executes these local review actions. Proposals are inert: this component
cannot register an investigator, run a case, generate a repair or grant permission.
A separate host-authorized [follow-up dispatcher](follow-up-dispatch.md) can execute
selected questions. A separate [reconciliation protocol](follow-up-reconciliation.md)
can assess the fresh responses and preserve the original review history.

## Register and run

Build the backend using the README commands. Use a completed batch in the same
trusted local state directory; unfinished batches are rejected without starting
their tasks. Registration validates retained batch/task identities and captures
the evidence needed for review. It needs no API credential and makes no paid call.

```powershell
$review = ./scripts/Coordination-Review.ps1 -Action register -BatchId batch-EXISTING-UUID
./scripts/Coordination-Review.ps1 -ReviewId $review
```

Replace the batch reference with an existing completed batch. Registration accepts
`-Model`, defaulting to `gpt-5.6-terra`. The model is then fixed by the review grant;
run and revoke do not accept a model override. Running makes paid OpenAI requests
through the existing [host broker](model-credential-broker.md). The script uses the
process `OPENAI_API_KEY` or ignored `.env.local`, restores the previous environment
on exit and never prints the credential. `-StateDirectory` defaults to `.workbench/`.

Records live in `.workbench/coordination-reviews/review-UUID/`. The review spec seals
its model, protocol, deadline and captured input inventory. The capture includes
the batch definition/result, task definitions/results, investigator results and
host-assigned references for task outcomes, summaries, individual claims and tool
events. Captured references retain their task identity and original authority.
Original task/broker/execution grants and credentials are not copied. Completed
source permissions need not be renewed. Changes or deletion in the original store
after registration do not change the captured review; changing its captured bytes
is detected by identity checks. Keep the original evidence for separate audit and
any future execution; the capture does not contain complete runnable case inputs.

## Decisions and evidence authority

The `coordination-v2` broker protocol forces one `coordination_decision` function
response with a strict schema and rejects other function names. The host also
checks the parsed action and every referenced identity. This follows the current
[OpenAI function-calling contract](https://developers.openai.com/api/docs/guides/function-calling);
valid schema output does not establish a supported claim or authorized operation.

| Action | Host behavior |
| --- | --- |
| `read_reference` | Read only a captured host-listed reference, in pages of at most 4,000 UTF-16 characters |
| `propose_follow_up` | Record a question for one listed task, with observed citations and at least one citation belonging to that task |
| `record_conflict` | Record a possible disagreement between two distinct observed claim references, preserving both and their task identities |
| `finish` | Record a cited advisory summary; derive the review outcome from retained proposals/conflicts |

A reference can support a citation only after successful reads cover its complete
contents without gaps. References are identifiers, not caller-controlled paths.
Each v2 request includes the host-derived `fullyReadReferences` list so eligibility
is explicit. Earlier v1 request instructions, grants and retained reviews remain
unchanged and readable; they are not reinterpreted as successful v2 reviews.
There are at most two proposals, one per task, and four possible conflicts. Rejected
actions remain in the history and consume decisions. Summary/tool-event references
cannot substitute for the two claim references required to record a conflict.

Investigator summaries and claims remain model advice. A tool event records what
the existing adapter returned; it does not turn a failed read or test into verified
behavior. Captured source text, claims and tool output are untrusted data and cannot
add tools, change budgets, select a new model or expand permissions.

Conflict entries have authority `possible-conflict-model-inference` and state
`UNRESOLVED`. Their scope distinguishes different case IDs from the same case ID;
matching case IDs still do not establish comparable transitions. Different inputs,
roles or assumptions can explain a difference. This component neither proves a
logical contradiction nor decides which claim is correct. The coordinator cannot
resolve an entry, majority-vote a verdict or approve a release in its final text.
Citation validity establishes observed support availability, not semantic accuracy.

| Review status | Meaning |
| --- | --- |
| `REVIEW_RECORDED` | Cited review completed without recorded proposals or possible conflicts |
| `FOLLOW_UP_PROPOSED` | At least one inert proposal was recorded, without a recorded possible conflict |
| `UNRESOLVED_CONFLICTS` | At least one possible conflict remains open, regardless of final wording or proposals |

All three are advisory workflow outcomes with authority
`coordinator-advice-not-execution-evidence`. They do not amend earlier execution
records, compatibility assessments, semantic reviews or release decisions.

## Limits, interruption and revocation

Each review permits at most 12 decisions, 64,000 UTF-8 bytes per model input and
2,048 output tokens per call, with a 20-minute admission deadline. Each captured
artifact is limited to 1 MiB, the inventory to 120 files and references to 100.
Large references may require more pages than the decision/context budgets allow;
the coordinator must stop rather than claim it read unavailable evidence.

A root lease prevents overlapping controllers. Before broker registration the
host durably reserves a decision slot and request hash, then records the broker
mapping before dispatch. Repeat the same run command after interruption or an
`ACTIVE` response. Existing mappings and broker journals are reused. A reservation
without a mapping stops as `CALL_REGISTRATION_UNCERTAIN`, consumes its slot and
never creates an automatic replacement request. An ambiguous provider outcome is
retained and not replayed. Provider rejection/rate limits also remain terminal.

```powershell
./scripts/Coordination-Review.ps1 -Action revoke -ReviewId $review
```

Revocation is permanent and needs no API credential. Checks before and after model
calls/actions enforce review identity, grant, expiry and revocation. An in-flight
call can finish within the broker deadline; its usage is retained, and a revoked
review admits no subsequent action. There is no instantaneous transport cancellation
guarantee. Other stops include authorization/input mismatch, invalid model output,
unsupported final citations and exhausted decision/context budgets. The command
reports a failure for these stops; inspect the retained records before replacement.

Finalization seals a completion record before publishing the aggregate result.
Recovery reconstructs a missing aggregate from that record without dispatch.
Historical reads verify the captured inventory, call requests, model decisions,
replayed local action results, aggregate outcome and usage. They reject changed
summaries, statuses, events, inventory or accounting. Reading an intact completed
review needs no fresh permission, even after expiry or revocation.

Usage distinguishes reserved slots, mapped calls, completed responses, input/output
tokens and `unresolvedUsageSlots`. Unmapped reservations, missing broker results
and non-complete responses whose dispatch was not ruled out conservatively consume
an unresolved slot. These may incur charges. Completed token totals are not a full
invoice or a currency estimate. The coordinator budget is additional to the source
batch's recorded usage; it does not overwrite that accounting.

This remains a trusted-host local runtime. Hash bindings detect changes relative
to retained records; they are not signatures against an operator who can rewrite
the whole store. This is neither multi-user authorization nor OS process isolation,
distributed scheduling or unrestricted repository ingestion. No private model
reasoning is stored or exposed.

## Validation

Default tests use synthetic decisions and loopback API fixtures without credentials.
They cover captured-source independence, fully observed citations, foreign tasks,
injection-shaped content, unsupported actions, unresolved conflicts, duplicate
proposals/conflicts, copied grants, expiry/revocation, active leases, interruptions,
uncertain registration, ambiguous usage, budget stops and changed final history.
Publication recovery must leave broker registration/dispatch counts unchanged.

These checks establish runtime boundaries. They do not establish semantic review
quality or a benefit from coordination. Follow-up execution and reconciliation are
validated separately. Controlled comparison with deterministic, single-agent and
independent-worker configurations remains later Milestone 4 work.

### Recorded two-case reviews, 2026-10-03

Two bounded live reviews used `gpt-5.6-terra` against the captured results of
`batch-a95d3763-aa04-4124-aab3-3ebf8b724c14`. Neither review started an investigator,
executed a case or dispatched a follow-up. The source batch result remained unchanged.

| Protocol | Recorded status | Calls | Input / output tokens | Unresolved usage slots |
| --- | --- | --- | --- | --- |
| v1 | `UNSUPPORTED_REVIEW` | 11 | 58,679 / 1,915 | 0 |
| v2 | `REVIEW_RECORDED` | 8 | 36,676 / 1,642 | 0 |

The v1 trial read only the first page of one reference, then used that reference in
a proposal and final summary. The host rejected the proposal and final citations;
one earlier valid inert proposal remains in the stopped history. The v2 protocol
makes eligibility explicit through `fullyReadReferences`; its trial fully read
seven references and finished with four eligible citations, no proposals and no
recorded possible conflicts. This does not prove that no substantive disagreement
exists or that the summary is semantically correct.

The original v1 review remains `review-8c13f95e-c85b-4b81-8bdd-384a02c58171`.
It validates under its original protocol without new registration or report changes.
The v2 review is `review-a8b6f45f-e0b4-46e9-a889-e23aff408339`; its result SHA-256 is
`75be4503c1d871ad0045bb60d9aff6e5ea209dce4470e889ce9c88a662bf572c`.
Repeating the completed v2 command preserved the report hash, all eight registered
calls and all eight dispatch markers. The underlying records remain in ignored local
storage. These two operational trials do not measure review accuracy, held-out
generalization, efficiency improvement or coordination benefit.
