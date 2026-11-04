# Durable selected invoice follow-ups v2

Step 4e5m adds `InvoiceFollowUpRunnerV2`, an opt-in runner for original v2 role
investigations, coordinator review and selected fresh follow-ups. It retains a
recoverable stage prefix under one trial. Reconciliation, whole-trial completion,
comparison export and answer-quality review remain pending.

## Registration and authenticated selection

The runner uses the [follow-up admission policy](invoice-follow-up-v2-admission.md)
and registers all five fixed participants before execution: original webhook and
reconciliation workers, `invoice-coordinator`, `fresh-invoice-webhook` and
`fresh-invoice-reconciliation`. Their scopes, exact contracts, public task, runtime
build, common input and limits contribute to the registered implementation.
The original model and maximum 60 calls, 100 reads, two concurrent calls,
20-minute deadline, 64,000 input bytes and 2,048 output tokens remain unchanged.

The runtime lives in `invoice-team-follow-up-v2-trials`. An internal mode of the
[prefix engine](invoice-team-runner-v2.md) retains originals and review under
`invoice-team-follow-up-v2-prefix-runs`; the fresh controller stores its records
under `invoice-follow-up-v2-runs`. The existing public prefix-only constructor,
policy, command and directories keep their three-member scope. Earlier trials
are not upgraded, copied into new authority or assigned another deadline.

Before fresh dispatch, the controller verifies the actual runtime registration
and replays the sealed original/review histories. It reconstructs the unique
accepted coordinator finish and recomputes the [follow-up selection](invoice-follow-up-v2-contract.md)
from those authenticated histories. The selection must match the retained review.
`prefix.json` binds the exact prefix result; `selection.json` retains the source
policy, selection identity, questions and exact selected anchors.

A failed or unknown review supplies no selection and dispatches no fresh worker.
A completed review with zero questions also dispatches none, while retaining the
known empty selection separately from an unavailable selection. One selected role
does not bootstrap its sibling. Questions remain untrusted data, with at most one
question per role at depth one.

## Owned execution and inclusive limits

Selected workers receive their own counted bootstrap and captured reads. The
context includes only their selected question and anchors, public requirements,
owned readiness and shared scheduling snapshot. Parent and sibling reads do not
become their text evidence. A structurally answered finish requires the fresh
role's own required reads and citations; abstention can preserve partial work.
Unsupported answers keep the candidate and readiness as rejected evidence.

The controller uses fair rounds with at most two calls in flight. It records the
participants and shared allowance before each round. A final odd opportunity
follows round parity; no private per-worker cap or free finish is introduced.
Unused earlier opportunities carry forward under the canonical 24/6/24/6
allocation. Original, review and fresh calls together cannot pass 54 at a
60-call ceiling. The remaining reconciliation allowance is not spent here.
Lower declared ceilings use the same existing allocation rule.

Every model reservation, describe, catalogue, prefix and text read uses the
original trial's pool and deadline. Unknown registrations remain charged. Missing
bootstrap access can stop a worker without reserving a model call. The controller
does not dispatch consumers, run a model to summarize history or renew limits.
Unexpected model reservations cannot be hidden in stage accounting.

## Durable records and recovery

Each action retains the exact request, bounded returned decision or stop,
observed page, reservation flag and receipt binding the step and bootstrap.
Exact completed responses recover without another dispatch. Uncertain remote
outcomes and interrupted registrations remain unknown; they are not retried as
new calls. All submitted siblings are joined before controller ownership is
released, including local failures. An occupied owner or model lane returns
`ACTIVE` for resuming the same trial.

`follow-ups.json` and its receipt seal fresh outcomes, owned reads, validated
advisory finishes, reservations and source/runtime inventories. A frozen stage
trace and receipt retain inclusive resource use at that boundary. Inspection
recomputes selection, replays sealed events and revalidates context/finish and
accounting rather than accepting stored verdict fields. Missing published source
markers or changed sealed artifacts are rejected before participant recovery;
missing event seals after a barrier cannot be recreated by dispatching work.
A published result with only its final receipt missing can be checked and sealed
without a new call.

The result reports `FOLLOW_UP_RECORDED` or `FOLLOW_UP_SKIPPED` and
`wholeTrialStatus: PENDING`, with reconciliation and comparison export explicit.
Failed, cancelled, unknown, unsupported and budget-stopped workers remain visible.
Original claims, outcomes, review and unresolved conflicts remain in the bound
prefix; an accepted structural finish does not resolve them or establish quality.
There is no whole-trial `finish` or comparison trial output in this component.

Completed `read` and `run` inspection require no credential or new participant
operations. Runtime inventories are checked as historical subsets, allowing a
stage trace to remain evidence about its recorded boundary. It does not claim
to include later work. Policy and artifact hashes are trusted-host integrity
checks, not signatures or protection against an operator rewriting all records.

## Run and inspect

Build with the README preparation and verification commands first. Policy is
credential-free and dispatches nothing:

```powershell
./scripts/Run-InvoiceFollowUpsV2.ps1 -Action policy
```

`-Action register` accepts `InputDirectory`, independently retained `InputIdentity`,
`PlanPath`, `WorkloadId` and `Repetition`. `-Action run` and `-Action read` accept
the captured input and `TrialId`. `-StateDirectory` selects retained state.
`-Action check` explicitly starts a new operational check with the same captured
inputs. Unfinished `check`/`run` can make provider calls and require the existing
`OPENAI_API_KEY` configuration. Registration, policy and completed inspection do
not resolve the credential. A check is not a registered comparison campaign.

Run the README verification commands. Scripted offline tests cover actual fresh
overlap, selected-role isolation, source barriers, shared budgets and reads,
rejected decisions, uncertainty, interruption, sibling draining, lifecycle stops,
tampering, publication recovery and credential-free process inspection. The
scripted outputs are authored controls, not live-model or semantic evidence.
One positive control uses separately identified authored coverage metadata and
complete owned reads; it is not newly observed consumer execution and preserves
the original worker's unknown outcome.
The historical campaigns, original model, baselines and six review criteria remain
unchanged. [Milestone 4 acceptance](milestone-4-status.md) remains open.
