# Single-context invoice investigator v2

Step 4e5f connects one adaptive investigator to the
[v2 runtime](invoice-v2-admission.md), public task, prefix catalogue,
[owned-read context](invoice-v2-contract.md) and structural finish checks.
It selects captured evidence pages and produces one cited advisory dossier or
an explicit abstention. The [v1 investigator](invoice-single-agent.md) remains
separate; original trials and their negative review are unchanged.

## Registered execution

`InvoiceSingleAgentV2` uses participant `invoice-single-agent-v2` with the joint
`invoice-investigation-v2` protocol. Adapter records live in
`invoice-single-agent-v2/trial-UUID`, linked by exact specification hash to the
shared `invoice-v2-trials/trial-UUID`. Registration binds the captured input,
comparison definition/slot, exact v2 policy and public-task SHA-256. It cannot
use a v1 or read-only runtime, a different input or another comparison arm.

The check definition retains `gpt-5.6-terra` and the existing maximum sixty
model calls, one hundred reads, twenty minutes, 64,000 input bytes and 2,048
output tokens per request. A supplied definition can declare lower ceilings.
The describe operation costs one read. Every model-selected catalogue, prefix
catalogue or text page costs another. Every model opportunity, including a
finish, uses the shared reservation ledger. No unbudgeted finalization call,
fresh consumer execution, shell, network tool, edits or delegation is available.

## Context and decisions

The adapter retains complete owned runtime wrappers. Context construction checks
their participant, case, input, request and exact captured contents. The same
preparation derives both displayed readiness and the host finish gate. Context
still retains every read's metadata and at most six recent page contents, with
oldest contents trimmed to the declared byte ceiling. An insufficient fixed
context stops before creating a new model reservation.

Each request contains the revised public maintainer questions and their identity,
explicitly historical `captureMetadata`, structural readiness, citable own text
IDs and current single-participant scheduling. The final opportunity is labelled
as such; a read on that opportunity does not produce a host-authored answer.
Future turns remain conditional on the active grant and deadline.

The host parses exactly one bounded v2 decision. Valid read actions use stable
`read-N` operation IDs. Unknown aliases, invalid queries, unsupported actions and
malformed responses stop the trial with their recorded failure. They do not
trigger a hidden repair or new model attempt.

## Finish and authority

`InvoiceFinishV2` checks a parsed finish against host-derived readiness for the
same participant, input and scope. Every summary/finding citation must name that
participant's inspected text page. Flat and prefix catalogue pages cannot
support claims. `ANSWERED` additionally requires:

- Complete task, configuration and both baseline consumer-source reads.
- A complete business artifact, a complete order artifact and captured source
  execution coverage.
- A cited summary and at least one cited finding.

`ABSTAINED` may have no inspected text or preserve cited partial findings.
Neither disposition establishes semantic truth. Valid minimum access does not
prove complete scenario inspection, migration compatibility or release approval.
An authored but unsupported finish is retained as `REJECTED_FINISH` with its
candidate claims and readiness; it cannot silently become an accepted dossier.
Malformed decisions and invalid reads also retain explicit rejection events.
The original bounded broker response remains linked through the runtime trace.

Accepted structural output records the public-task identity, protocol and
readiness. The version 2 result exports the existing `CoordinationTrial` with
inclusive usage and `review: null`; quality remains `NOT_PERFORMED`. Operational
completion, explicit abstention, failed work, absent output and remote uncertainty
remain distinct. A retained dossier accompanying later operational failure does
not override the failed trial status.

## Commands and recovery

Build with the README commands, then supply a captured input directory and its
independently retained manifest identity:

```powershell
./scripts/Run-InvoiceSingleAgentV2.ps1 -Action policy
./scripts/Run-InvoiceSingleAgentV2.ps1 -Action check `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceSingleAgentV2.ps1 -Action read `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -TrialId trial-EXISTING-UUID
```

`check` creates one new operational trial with an unreviewed definition and unrun
placeholders for other arms. It is not a registered paired campaign. `register`
accepts `-PlanPath`, `-WorkloadId` and `-Repetition`, with an exact single-agent
policy identity. `run` resumes an existing trial; use the same input arguments
and `-TrialId`. `read` inspects a completed result without dispatching work.

Registration and inspection require no credential. `check`/`run` use the existing
process `OPENAI_API_KEY` or ignored `.env.local`, restoring the prior process
environment afterwards. Console output contains status, usage and report
location, without evidence payloads or model statements. A zero process exit
alone does not establish trial completion or answer quality.

Preserve both trial directories, the exact input bundle and runtime together.
Restart reconstructs identical requests/read IDs and reuses completed operations
without new dispatch or charges. `ACTIVE` leaves the trial open. Missing mappings
and unknown remote outcomes remain uncertain; no new call is guessed. Revocation,
expiry and changed active implementations cannot be bypassed by cached work.
Partial result-receipt publication can recover after checking exact retained
bytes. Changed requests, events, dossiers and receipts remain errors.

Run the README verification commands. `InvoiceSingleAgentV2Test` covers the
adaptive prefix/text loop, last-opportunity disclosure, exhaustion, restart,
uncertainty, rejection retention, byte limits, scope, tampering, fresh-process
inspection and a real-broker loopback exchange. `InvoiceFinishV2Test` covers
structural answers, honest abstention, gaps, incomplete captures and citation
ownership. These controls establish behavior rather than finding quality.

Independent workers, team stages and the deterministic baseline still require
their own v2 integration before a new common condition is frozen and measured.
Keep the original model, ceilings, baselines, six review criteria and negative
campaign. [Milestone 4 acceptance](milestone-4-status.md) remains open.
