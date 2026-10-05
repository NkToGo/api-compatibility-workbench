# Durable captured-team investigations and review

`CoordinationTeamRunner` runs two isolated original case investigators and then
one coordinator review over the [common captured inputs](coordination-runtime.md).
It uses the Java caller-owned broker, existing case protocols and
[`captured-team-review-v1`](coordination-team-review.md). It does not run fresh
follow-ups, reconcile their answers or export a completed `CoordinationTrial`.
Its report explicitly leaves those stages and the whole comparison `PENDING`.

## Run a bounded stage check

After the normal preparation and build, use an existing captured two-case bundle:

```powershell
./scripts/Run-CoordinationTeamReview.ps1 -Action policy
./scripts/Run-CoordinationTeamReview.ps1 -Action check -InputBundleId inputs-<uuid>
./scripts/Run-CoordinationTeamReview.ps1 -Action read -TrialId trial-<uuid>
./scripts/Run-CoordinationTeamReview.ps1 -Action run -TrialId trial-<uuid>
```

`check` registers a new stage check with the product model `gpt-5.6-terra`, a
60-call whole-trial ceiling, 20-minute whole-trial deadline, 64,000 UTF-8 input
bytes per request and 2,048 output tokens per response. It prints the run ID before
dispatch. `run` resumes that same ID; do not start another check to recover it.
`read` verifies an existing final stage report without a credential or admission
renewal. `policy` inspects the current implementation binding. There is no paired
campaign registration/export command for this incomplete arm.

Only `check` and `run` can load the existing ignored `.env.local` when the process
does not already provide `OPENAI_API_KEY`. The script restores the prior process
environment, never prints the key and rejects linked credential files. All state
is in ignored `.workbench` by default. These trusted-host records may contain
captured source and generated advice; preserve them together and outside Git.

## Scheduling, permissions and stage budgets

Original workers run concurrently in fair rounds: one decision per active worker,
case-ID order, with alternating selection if only one opportunity remains. Each
worker sees only its own case metadata/pages and has only its case protocol/read
scope. A stopped peer does not remove the other worker's remaining opportunities.
Page boundaries retain complete Unicode characters within the UTF-16 character
ceiling; offsets inside a surrogate pair and limits too small for it are rejected.
No coordinator context, sibling dossier, evaluation criterion or grading feedback
is supplied to an original worker.

For 60 calls, original investigations may reserve at most 24. A terminal original
checkpoint, including failed and uncertain outcomes, is sealed before the review
begins. The coordinator can reserve up to the cumulative 30-call ceiling minus
actual original reservations. For example, eight original calls leave 22 review
opportunities. Unused opportunities carry forward; uncertain registration slots
also count as spent. A context rejected before reservation consumes no call.

All calls and reads use one registered parent runtime. Stage transitions never
create a fresh grant, 60-call pool or clock. The runtime rechecks model, protocol,
case, input/output limits, expiry, revocation and global concurrency. The runner
enforces original/review ordering and stage limits on a trusted host; the public
runtime API is not an authorization service for hostile independent callers.
Fresh-worker participants and execution tools are absent from this stage policy.

The coordinator sees every original summary/finding and worker disposition with
stable report/input bindings. It can progressively read captured evidence and
finish with cited advice, zero to two case-bound questions and possible unresolved
conflicts. Host guards reject unseen or foreign references and unsupported actions.
Selected questions remain inert data, even when review succeeds. `COMPLETED` here
describes a supported review decision; it is not semantic acceptance.

## Durable records and recovery

The runner stores a registration, round/turn descriptors, exact requests, sealed
action events, original-report checkpoint, coordinator outcome, frozen stage
trace and final stage receipt under `coordination-team-runs/trial-<uuid>`.
The parent reservations, broker journals and read artifacts remain under
`coordination-trials/trial-<uuid>`.

An owner lease covers both stages. Parallel tasks are drained before ownership is
released, including a peer's local I/O failure. Recovery reconstructs original
contexts and review contexts in their recorded order, checks request/event hashes,
verifies page content against the recorded parent read and reuses the original
broker operation. A persisted response is not dispatched twice. A reservation
whose broker mapping is missing remains `CALL_REGISTRATION_UNCERTAIN`; no guessed
registration or replay is allowed. `ACTIVE` preserves the existing run for resume.

Changed requests, events, input identities, parent records or stage checkpoints
fail closed. Incomplete pending publications are not overwritten. A missing final
stage receipt can be completed only when the retained result matches its exact
derived records, without new calls or reads. Underlying incomplete runtime journals
are not repaired by this command. Hashes detect local inconsistencies rather than
providing signed attestations or distributed storage.

The frozen trace records reservations, response/uncertainty counts, token usage,
reads and elapsed time at this boundary. Its own receipt binds all those values.
The parent remains unfinished; the prefix does not reset its original deadline.
Historical inspection verifies every frozen parent artifact and input identity;
later append-only operations or revocation do not erase the earlier prefix.
Changed or removed earlier artifacts still invalidate it. Completed stage inspection
and resume do not dispatch or require a key, even after the parent deadline.

## What the result means

`REVIEW_RECORDED` means a cited coordinator finish passed structural/evidence guards.
`REVIEW_STOPPED` retains the coordinator's failure, cancellation or uncertainty.
Worker statuses, raw completed dossiers, page histories, missing dossiers and
explicit abstentions remain separate. A successful review may coexist with an
uncertain original worker; accounting retains that uncertainty.

The report has `wholeTrialStatus: PENDING`, pending follow-up/reconciliation/export
stages and `qualityReview: NOT_PERFORMED`. It contains no `trial` object usable by
the comparison evaluator. There are zero new consumer executions. Model advice,
valid citations, questions and agreement prove neither diagnosis quality nor
compatibility or coordination benefit. The complete arm policy must be frozen
after all stages exist, before the fixed paired campaign and its separate bound
semantic reviews.

## Validation

Fake-broker tests exercise actual worker overlap, scope restrictions, the sealed
stage barrier, cumulative ceilings and carry-forward, stopped/uncertain workers,
coordinator reads, rejected references, expiry/revocation, interrupted registration
and persisted-response recovery, changed records, incomplete publications and
credential-free fresh-process inspection. Default tests make no paid API calls
or new external consumer executions.

## Recorded stage check — 2026-10-05

One `gpt-5.6-terra` check reused captured bundle
`inputs-6e4dfed9-be1a-479d-aa15-b40073041353` under the declared common bounds.
Trial `trial-b58e3f31-a1cd-40d3-a148-fe5377cbf43d` recorded its review in 50,969 ms,
with 17 reserved/registered/completed calls: 14 original investigation calls and
three review calls. Accounting retained 69,502 input tokens, 2,583 output tokens,
14 evidence reads, zero new case executions and zero unresolved usage slots.
The first original call intervals overlapped.

Both original dossiers declared `ANSWERED` and passed required baseline-source
and execution coverage/citation guards. The customer investigator also read
expectations; the subscription investigator read expectations, fixtures and
provenance. Other available artifacts were not read. The coordinator read each
case's captured execution artifact and selected two inert questions, with no
possible conflicts. This establishes neither complete diagnosis nor question
usefulness, semantic acceptance or coordination benefit. No paired campaign ran;
follow-up execution, reconciliation and the whole team comparison remained pending.

Fresh-process inspection and completed resume without a process credential left
all 206 non-lock artifacts across inputs, parent runtime and stage records unchanged.
Result SHA-256:
`d86b8beb94399bf759768ccc96caba53905ffc174fec8265dcd848167e3d15b3`.
