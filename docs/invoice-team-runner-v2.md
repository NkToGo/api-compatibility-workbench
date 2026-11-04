# Durable invoice workers and coordinator review v2

Step 4e5j adds `InvoiceTeamRunnerV2`: two original role investigations, a sealed
outcome barrier and coordinator review under the separately admitted
[v2 policy](invoice-team-v2-admission.md). It retains a recoverable workflow
prefix, not a completed team comparison. Selected questions remain inert;
follow-up dispatch, reconciliation and comparison export remain pending.

The [v1 runner](invoice-team-runner.md), its namespaces and all historical
campaign records retain their original interpretation. An existing v1 or
investigation-only registration cannot be upgraded through this entry point.

## Fixed membership and one budget

The host registers exactly `invoice-reconciliation`, `invoice-webhook` and
`invoice-coordinator`, each with one corresponding v2 protocol. Registration
binds the opened input identity, coordinated-arm definition and slot, admitted
policy, runtime specification and public-task identity before participant work.
Changed inputs or active implementation require a new registration.

Original workers execute concurrently in fair rounds. Their contexts contain
their own full captured read wrappers, scoped readiness and public task; neither
receives generated sibling claims or pages. Persisted round snapshots state the
shared allowance and active participant count. Round parity selects a worker
when only one opportunity remains. A surviving worker can use the remaining
original-stage allowance. All submitted tasks are joined before ownership is
released, including when one fails locally.

The unchanged allocation at sixty total calls remains:

| Stage | Initial opportunities | Cumulative ceiling |
| --- | ---: | ---: |
| Original workers | 24 | 24 |
| Coordinator review | 6 | 30 |
| Future follow-ups | 24 | 54 |
| Future reconciliation | 6 | 60 |

Unused opportunities carry forward without renewing the trial. Two immediate
original abstentions leave twenty-eight review opportunities; twenty-four
original reservations leave six. Known-not-dispatched and uncertain reservations
still consume opportunities. A context rejected before reservation does not.
This runner cannot use the final thirty opportunities as another review pool.

Both original bootstrap reads, the coordinator's own bootstrap and all selected
flat/prefix catalogue or text reads share the one-hundred-read ceiling. Review
gets no free bootstrap or copy of worker page contents. All operations share one
twenty-minute deadline, at most two concurrent calls, 64,000 input bytes and
2,048 output tokens per call. Definitions may impose lower limits; the team
allocation requires at least six call opportunities.

## Owned histories and the original-outcome barrier

Workers use [v2 owned context and readiness](invoice-v2-contract.md). A structurally
unsupported `ANSWERED` finish, metadata citation, invalid read or unknown action
stops that worker. Parsed rejected candidates and applicable readiness remain in
sealed events. They are not accepted dossiers. Missing, failed, cancelled and
uncertain outcomes retain explicit status, reason, optional bootstrap and reads.
Model transport completion establishes neither answer acceptance nor quality.

The barrier seals both role/task/contract-bound histories, actual reservations,
allocation, original action artifacts and the captured runtime inventory before
review starts. Every bootstrap and selected page is matched to its actual runtime
participant, operation, bundle and page request. The pure review component also
checks contents against the opened immutable bundle. Rehashing a substituted
barrier does not make it agree with the original sealed action history.

The coordinator uses the [v2 review contract](invoice-team-review-v2.md): every
original outcome and claim remains available, with each worker's readiness and
separate coordinator-owned reads. Its context discloses the actual cumulative
review-stage snapshot. Strict selection accepts only owned text references or
original worker/claim anchors. Questions stay at depth one and conflicts remain
`UNRESOLVED`. No returned decision grants permission or starts another worker.

## Recovery and the retained prefix

Ignored `invoice-team-v2-runs/trial-UUID/` holds the prefix; its shared runtime is
under `invoice-team-v2-trials/`. Keep both stores and the input bundle together on
a trusted filesystem. Records can contain supplied source and model claims.

One controller lease owns the runner. Requests and events are sealed against
registration, round or review turn, participant bootstrap and durable reservation.
Resume reconstructs the same owned contexts. Exact completed responses and reads
reuse their original operations; unmapped registrations and unknown remote
outcomes are not guessed or redispatched. `ACTIVE` means resume the same trial.

Read-only inspection reconstructs both stages from existing sealed events and
compares the derived histories with the barrier and selected review. Missing
events cannot start participant work during this inspection. Changed requests,
foreign wrappers, substituted outcomes, missing seals and unexpected files fail
validation. Publication is create-only, atomic and limited to one MiB per record;
incomplete pending publication requires inspection rather than overwrite.

`REVIEW_RECORDED` and `REVIEW_STOPPED` describe only this prefix. The result keeps
`wholeTrialStatus: PENDING`, original histories, coordinator disposition, optional
selection, inclusive stage reservations and a frozen runtime trace. It does not
finish the shared runtime or export a `CoordinationTrial`. A completed review can
coexist with failed or uncertain original workers. Later append-only operations
and revocation leave the sealed prefix readable; earlier captured artifacts
cannot change. A missing result receipt is recoverable only when the unchanged
result derives from its original evidence and frozen trace.

These are trusted-host integrity and recovery controls, not signed attestation,
distributed persistence or tenant isolation. Finding quality remains separately
`NOT_PERFORMED`; there is no migration approval or coordination-benefit claim.

## Run and inspect

After the README preparation and build commands:

```powershell
./scripts/Run-InvoiceTeamReviewV2.ps1 -Action policy
./scripts/Run-InvoiceTeamReviewV2.ps1 -Action check `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

`check` performs a new bounded model investigation with `gpt-5.6-terra` using the
existing process key or ignored local configuration. It makes model requests;
it is not an offline replay or registered campaign. It does not recapture inputs
or start Docker. Console output is status, usage and paths, not captured contents.

For a separately supplied plan binding this exact prefix policy:

```powershell
$trial = ./scripts/Run-InvoiceTeamReviewV2.ps1 -Action register `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -PlanPath plan.json -WorkloadId invoice-joint -Repetition 1
./scripts/Run-InvoiceTeamReviewV2.ps1 -Action run -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceTeamReviewV2.ps1 -Action read -TrialId $trial `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
```

Policy, registration and read-only inspection do not load a credential. The Java
entry point can inspect or resume an already completed prefix without credentials
or new participant work. A prefix-only registration cannot acquire additional
protocols or a renewed deadline for later stages.

Run the README verification commands. Offline tests cover actual worker overlap,
owned context isolation, strict finishes, prefix navigation, cumulative/odd/carry
limits, counted bootstrap reads, uncertainty, interruption, lifecycle gates,
barrier tampering, receipt recovery and credential-free process inspection.
A pure [follow-up v2 contract](invoice-follow-up-v2-contract.md) now binds selected
questions and anchors to fresh own contexts without adding runtime grants.
A separate [follow-up admission path](invoice-follow-up-v2-admission.md) now registers
original, review and fresh protocols; it does not expand this prefix registration.
Durable fresh dispatch and reconciliation integration, the deterministic baseline, new common
conditions and separately reviewed measurement remain subsequent work.
[Milestone 4 acceptance](milestone-4-status.md) remains open.
