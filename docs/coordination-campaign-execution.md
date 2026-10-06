# Ordered coordination campaign execution

The trusted-host controller executes an existing frozen campaign in its declared
order. Each slot receives one fresh trial ID through the existing arm adapter;
all participant stages use that adapter's original common budget and deadline.
The controller retains operational outcomes and unreviewed comparison records.
It neither grades claims nor selects a preferred configuration.

## Run and inspect

Use the README build, an existing captured input bundle and a
[frozen campaign registration](coordination-campaign.md). Run:

```powershell
./scripts/Run-CoordinationCampaign.ps1 -CampaignId campaign-EXISTING-UUID
```

The model arms reuse the existing host-side credential configuration. Registration,
deterministic work and inspection do not load `.env.local`. The controller invokes
only the four allowlisted adapters; it exposes no model-controlled shell or path.
It checks all current compiled policies against the frozen snapshots before
unfinished work, and binds its own script and adapter wrappers before dispatch.
Changed policies or dispatch code stop unfinished work instead of changing the
campaign's conditions. Historical inspection still uses the retained bindings.

For a bounded batch, add `-MaximumTrials 1`. This limits newly completed or resumed
slots in that invocation, retaining the full twelve-slot plan. Repeat the same
campaign ID to continue. Completed slots do not consume this invocation limit.
There is no rerun, replacement-trial or renewed-deadline option.

Inspect without dispatching participants:

```powershell
./scripts/Run-CoordinationCampaign.ps1 -Action read -CampaignId campaign-EXISTING-UUID
```

State defaults to ignored `.workbench/`, with `-StateDirectory` available. Execution
metadata lives under `coordination-campaign-executions/campaign-UUID/`, outside the
immutable registration. Preserve the registration, captured inputs, comparison
definition, execution metadata, runtime trials and arm stores together.

## Durable ordering and recovery

An exclusive controller lease prevents overlapping owners. Each slot publishes
an intent before trial registration, then a mapping before participant work.
The mapping binds the exact runtime and adapter specifications, input receipt,
slot, compiled policy and admission grant. A newly discovered registration must
postdate its intent and must not already have started without a mapping.

If registration completes but its response or mapping is lost, one complete
matching orphan registration can be reused. Multiple matching registrations,
partial runtime/adapter metadata or an unmapped started trial stop admission.
Recovery never selects a best attempt or starts another trial to replace one.

Mapped unfinished trials resume through their original adapter. Completed adapter
receipts are read rather than replayed. The original whole-trial clock remains
fixed, including time spent interrupted. Expired, failed, cancelled and uncertain
outcomes remain part of the planned denominator. A later slot cannot begin before
the preceding slot has a validated outcome and receipt.

Atomic create-only publication, linked-path rejection, bounded artifact reads and
strict inventories prevent partial files from appearing complete. A pending
publication remains an explicit interruption requiring reconciliation; it is not
permission to replace an operation. Missing final or slot receipts can be completed
by resuming the same campaign after validating the existing bytes. Inspection
rejects an incomplete terminal seal. These are trusted local filesystem integrity
checks, not authentication against an operator rewriting the entire store.

## Retained measurements

Each slot outcome binds the exact adapter result/receipt and runtime trace/receipt.
It retains both case records, their answer identities, operational disposition,
start/deadline/finish times and resource-limit issues. Separate participant rows
count reservations, mapped calls, completed responses, known-not-dispatched slots,
unknown outcomes, known input/output tokens and reads. Their totals must equal the
validated inclusive runtime trace. Team review, fresh workers and reconciliation
are included; uncertain reservations are not free calls.

The initial retained-evidence campaign permits zero new consumer executions.
The deterministic arm has zero model use. Known token totals are not an invoice;
unknown outcomes prevent complete cost attribution. Summed trial elapsed time is
not campaign wall time, a causal speedup estimate or a semantic quality measure.

`PENDING` inspection reports sealed-trial totals only, with explicit incomplete
slots and the unchanged planned denominator. Unsealed activity may have additional
usage and is not silently counted as zero. Once all twelve terminal outcomes are
sealed, `records.json` contains all twelve unreviewed records, `result.json` retains
the aggregate metadata and a final receipt binds both. `FINISHED` means the
planned operational records are sealed; it does not mean every trial succeeded.

Step 4c3 will audit complete final dossiers and earlier endorsed claims against the
frozen rubric, then report paired quality and resources. Valid JSON, citations,
successful execution and agent agreement do not establish semantic acceptance.

## Verification

Run `./scripts/Test-CoordinationCampaignExecution.ps1` after building. The controller
tests use real Java policies and trial registrations with simulated participant
completion and retained reads. They exercise full denominators, inclusive unknown
usage, lost registration/completion responses, original-clock recovery, concurrent
ownership, changed dispatch code, publication failures, ambiguous registrations,
out-of-order slots and immutable completed replay. They make no external API or
consumer-execution call and do not measure model quality. Both CI platforms run
these checks alongside the Java suite; a real campaign uses the actual adapters.
