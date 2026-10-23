# Complete invoice team investigation

`InvoiceTeamComparison` runs the original role investigations, coordinator
review, selected fresh follow-ups and one final joint reconciliation over an
existing [invoice input bundle](invoice-comparison-inputs.md). It exports a
`CoordinationTrial` with inclusive usage, exact output identity and no semantic
judgments. It does not execute consumers, edit code, approve a migration or
establish that a team is better than the other comparison arms.

## Registration and shared limits

The full policy is registered before original work. Six fixed participants have
one declared protocol each: the two original invoice roles, the coordinator,
`fresh-invoice-reconciliation`, `fresh-invoice-webhook`, and
`invoice-final-reconciler`. Questions cannot create participants, extend scopes,
choose protocols or renew permissions. A historical prefix-only trial cannot be
adopted by this adapter; register a new full trial instead.

All stages use the [existing cumulative allocation](invoice-team-runner.md):
24 original calls, six review calls, 24 follow-up calls and six reconciliation
calls at the default 60-call ceiling. Unspent opportunities carry forward;
there are no additional per-worker pools. Both follow-ups use fair concurrent
rounds, with round parity choosing a role when only one opportunity remains.
Every submitted sibling is drained before releasing ownership after a local
failure. The shared runtime enforces at most two concurrent calls, 100 total
reads, one 20-minute deadline, 64,000 UTF-8 input bytes and 2,048 output tokens
per call. The original and selected fresh metadata reads are counted too.
Known-not-dispatched and uncertain reservations consume opportunities.

The same input manifest and available inventory are used throughout. No new
source capture, consumer execution, protected expectations or grading feedback
is supplied. The read-only invoice runtime remains closed to model access.

## Fresh questions and preserved lineage

The original workers and review use the sealed
[prefix runner](invoice-team-runner.md). The host checks the selected questions
against that review, preserving exact question text, target role, bundle,
references, deterministic order and depth one. There can be zero, one or two
questions; zero does not trigger invented work or a replacement run.

A fresh worker sees its selected question, explicitly cited anchors, the source
metadata and its own new read history. It receives neither the full original
histories nor sibling follow-up output. The anchors are untrusted advice, not
verified facts. Coordinator text pages used as anchors were already counted in
review. Their local IDs cannot serve as the fresh worker's own citations.
Fresh workers must read evidence themselves and satisfy the existing role
access guard before returning `ANSWERED`. Abstentions may retain partial cited
findings. Failed, cancelled and uncertain work retains its stop reason, metadata
and pages without inventing a dossier.

The reconciliation inventory retains every original and fresh summary/finding,
each worker disposition, selected question, report identity and possible
conflict. `claim-N`, `worker-N`, `freshclaim-N` and `freshworker-N` identify this
advice; worker-local `read-N` IDs are retained as lineage, not silently promoted
to the reconciler's own reads. All possible conflicts stay `UNRESOLVED`. Neither
agreement, a later summary nor a missing statement closes them.

## One final joint dossier

The reconciler can use counted catalog/text reads or finish with one joint
invoice dossier. Citations must name disclosed advice references or its own
text pages; catalog entries and unseen IDs are rejected. `ANSWERED` requires
findings, summary citations and either:

- Cited claims from structurally supported `ANSWERED` workers covering both
  roles, across originals and fresh workers; or
- Its own complete task/configuration, both baseline sources, a business artifact
  and an order artifact, with captured source execution coverage and at least
  one own text-page citation.

These are access and provenance checks. They cannot establish that a cited claim
supports an explanation, that the explanation is correct or that the migration
is safe. Otherwise the model must abstain. A completed abstention is distinct
from a failed reconciliation with no final output. There is no fallback to an
earlier dossier, grading-driven retry or selection of a best attempt.

Contexts retain all claims/outcomes and all own read metadata. Only the latest
six own page contents are included, trimmed oldest first to the UTF-8 byte
ceiling. If the remaining inventory does not fit, the stage explicitly stops
with `CONTEXT_BUDGET_EXHAUSTED`; it is not silently summarized or truncated.

## Durable publication and inspection

Records are create-only under `invoice-team-comparisons/trial-UUID/`, with the
original prefix under `invoice-team-runs/` and the common runtime under
`invoice-comparison-trials/`. Registration binds the exact definition, slot,
full policy, bundle and runtime specification. Requests, round/turn records,
events and receipts are stable across restart. Completed responses are reused;
uncertain unmapped registrations remain uncertain instead of being redispatched.

Publication seals the prefix identity, follow-up histories, reconciliation
inventory/outcome and optional dossier. All stage reservations must match the
inclusive runtime total. Extra unowned reservations cannot disappear from the
export. Terminal priority is unknown outcome, cancellation, then failure, with
`COMPLETED` only when all scheduled stages completed. A final advisory dossier
may coexist with earlier failed work; that failure remains in the trial.

The exported trial contains one joint invoice case with `ANSWERED`, `ABSTAINED`
or `NOT_PROVIDED`, exact output/trace identities, all tokens/reads/calls, elapsed
time and zero new consumer executions. `qualityReview` is `NOT_PERFORMED` and
every case review is null. These records are suitable for later bound review,
not automatic routing promotion or an acceptance claim.

Completed `read` and `run` inspection need no credential or redispatch. A missing
final publication receipt can be recovered only when the derived result is
unchanged. Changed inputs, records or implementation policy fail closed;
unfinished work cannot resume under a newly compiled policy. Frozen historical
prefixes retain their original trace rather than absorbing later-stage usage.
Integrity assumes a trusted local host/filesystem; these are not signed,
distributed or tenant-isolated records. Do not copy private captures into a
public repository.

## Run

After the README preparation and build commands, retain the identity supplied
by the trusted input capture caller independently of the input directory:

```powershell
./scripts/Run-InvoiceTeamComparison.ps1 -Action policy
./scripts/Run-InvoiceTeamComparison.ps1 -Action check `
  -InputDirectory /trusted/captured-invoice-inputs `
  -InputIdentity <independently-retained-manifest-sha256> `
  -StateDirectory /trusted/invoice-comparison-state
```

`check` registers a fresh single unreviewed operational trial, not a paired
campaign. Preserve every result, including failures or no questions. To resume
or inspect the same trial:

```powershell
./scripts/Run-InvoiceTeamComparison.ps1 -Action run `
  -InputDirectory /trusted/captured-invoice-inputs `
  -InputIdentity <independently-retained-manifest-sha256> `
  -StateDirectory /trusted/invoice-comparison-state -TrialId trial-UUID
./scripts/Run-InvoiceTeamComparison.ps1 -Action read `
  -InputDirectory /trusted/captured-invoice-inputs `
  -InputIdentity <independently-retained-manifest-sha256> `
  -StateDirectory /trusted/invoice-comparison-state -TrialId trial-UUID
```

For a separately frozen comparison plan, `-Action register` also requires
`-PlanPath`, `-WorkloadId` and `-Repetition`; it does not execute work. All arms
must use the same independently bound invoice bundle and aggregate limits.
The wrapper rejects a stale JAR. Only `check`/`run` may load an existing ignored
local API credential; `policy`, `register` and `read` do not load it. Joint
semantic review and the separately frozen paired campaign remain outstanding.
