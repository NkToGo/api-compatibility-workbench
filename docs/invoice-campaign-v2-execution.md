# Ordered invoice v2 execution

`Run-InvoiceCampaignV2.ps1` connects the four v2 adapters to the
[frozen v2 condition](invoice-campaign-v2.md). It admits one trial per declared
slot, preserves the twelve-slot order and retains inclusive accounting and
unreviewed outcomes. The existing v1 controller and its records are unchanged.

## Select the runtime and bound the invocation

Build using the README commands and select an existing v2 registration. Supply
its receipt identity retained independently by the trusted freeze caller:

```powershell
./scripts/Run-InvoiceCampaignV2.ps1 `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -RuntimeDirectory TRUSTED-RUNTIME -MaximumTrials 1
```

`RuntimeDirectory` defaults to `backend/target`. A selected archive has the same
layout: `api-compatibility-backend-0.1.0-SNAPSHOT.jar` and a `dependency/` directory
containing its runtime JARs. Metadata, registration, execution and retained
inspection all use that selected classpath. The current-policy gate checks the
compiled application, JSON library and Java runtime version against the frozen
condition before admission. An identical archive can be selected at another
path; changing its bytes cannot silently change an existing execution.
The dependency inventory accepts one to 32 JARs; each hashed artifact is bounded
at 8 MiB.

`MaximumTrials` defaults to **one**, with an explicit upper bound of twelve.
It counts newly completed or resumed slots in this invocation. Sealed slots do
not consume the bound. Reusing the same campaign ID continues unfinished work;
there is no replacement-trial or favorable-retry selection. Invocations reaching
model arms can make paid calls through the existing host-side key configuration.
The deterministic arm and metadata commands perform no credential lookup.

## Routing and ownership

| Arm | Runtime ledger | Adapter results |
| --- | --- | --- |
| Deterministic | `invoice-navigation-trials` | `invoice-diagnostics-v2` |
| Single agent | `invoice-v2-trials` | `invoice-single-agent-v2` |
| Independent workers | `invoice-v2-trials` | `invoice-independent-workers-v2` |
| Coordinated team | `invoice-team-reconciliation-v2-trials` | `invoice-team-comparisons-v2` |

The two arms sharing a runtime namespace are distinguished by their exact slot
and policy. Trial discovery checks namespace membership and all frozen slots.
Mappings verify the input receipt, definition, arm, repetition, compiled policy,
adapter registration and current public-task identity. Team accounting includes
original workers, coordinator review, fresh roles and final reconciliation from
their shared parent ledger. Protected grading material is not participant input.

## Publication and recovery

Execution metadata is separate from immutable registration, under
`invoice-campaign-v2-executions/invoicecampaign-v2-UUID/` in the state root.
One owner lease guards publication. Execution registration binds the campaign
receipt, driver bytes and a relative-path/SHA-256 inventory of the selected
application and dependency JARs. The driver invokes Java directly; standalone
adapter wrappers are not execution dependencies of this controller.

A slot intent precedes trial registration, and an authenticated mapping precedes
participant work. One complete orphan registration can be recovered after a lost
registration response. Ambiguous, incomplete, foreign-namespace or already-started
unmapped registrations stop admission. Mapped work resumes its original adapter,
start and deadline. Completed results are inspected instead of dispatched again.
Later slots wait for validated predecessor outcomes and receipts.

```powershell
./scripts/Run-InvoiceCampaignV2.ps1 -Action read `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -RuntimeDirectory TRUSTED-RUNTIME
```

`read` requires existing execution metadata and performs no participant dispatch
or credential lookup. Retained inspection checks the bound runtime before loading
its metadata reader. A missing derived seal can be completed by `run` after
validating the retained bytes. Pending publication, linked paths, unexpected
artifacts, duplicate fields, invalid UTF-8 and oversized records fail closed.
Changed driver bytes prevent further admission. Runtime archives are trusted
host configuration, not paths supplied by assessed code or model output.

Hashes are trusted-store integrity checks, not signatures against an operator
rewriting the whole store and all expected identities. Preserve the frozen
registration, source evidence, selected runtime, execution journals and adapter
stores together for recovery.

## Outcomes and validation

`PENDING` retains the unchanged denominator, sealed-trial totals and explicit
unsealed activity outside those totals. Failed, cancelled, uncertain, abstained
and absent outputs remain in their slots. Participant rows must sum exactly to
the retained whole-trial usage; uncertain reservations are not treated as free
calls. New consumer executions remain zero. Known tokens are not a monetary
invoice, and summed trial times are not campaign wall time or a causal speedup.

All twelve terminal outcomes seal generic `records.json` plus version 2
controller metadata and receipts. `FINISHED` describes operational closure,
including unsuccessful trials; it establishes no semantic acceptance. Quality
review remains `NOT_PERFORMED`. Measured trials, campaign-bound semantic review
and [Milestone 4 acceptance](milestone-4-status.md) remain separate work.

After building, run `./scripts/Test-InvoiceCampaignV2Execution.ps1`. It uses real
compiled v2 policies and registrations with explicitly simulated participant
completion. Controls cover all twelve slots, six-member accounting, failure and
uncertainty retention, strict parsing, immutable replay, copied/tampered runtimes,
public-task binding, namespace isolation, interrupted publication, orphan recovery,
the original clock, dummy-credential restoration and exclusive ownership. No model/provider or consumer call
is made by these fixtures. Both CI platforms run this controller suite alongside
the original v1 controls. Synthetic completions are not measured model findings.
