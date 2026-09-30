# Repeated customer-sources investigation and repair

This opt-in suite repeats the complete supported customer-sources workflow: a fresh
registered context, a bounded investigation, at most one generated candidate and
independent protected verification. Each trial has separate context, agent, candidate
and verification identities. The inputs are one known synthetic case, not independent
migrations or a held-out benchmark. Semantic finding review and missing-evidence
conditions remain separate acceptance work.

Build the backend and both worker images, then run:

```powershell
./scripts/Build-IsolatedWorker.ps1
./scripts/Build-IsolatedWorker.ps1 -RepairWorker
./scripts/Evaluate-CustomerRepairs.ps1 -Repetitions 3
./scripts/Evaluate-CustomerRepairs.ps1 -Action resume -EvaluationId evaluation-EXISTING-UUID
```

The default model is `gpt-5.6-terra`. This makes paid OpenAI calls with the configured
host credential and sends curated source, application expectations and observed
tool results. Target SDK signatures come from the existing checksum-pinned `javap`
inspection; consumer code is never run for signature discovery. No live model
evaluation runs in CI.

## Bounds and durable stages

One to five repetitions are permitted, default three. Each investigator retains
its existing 12-decision, 2,048-output-token-per-decision and 20-minute admission
limits. Generation permits one request with at most 4,096 output tokens. Model
requests retain the existing 64,000-byte input bound and no-replay rules. Thus
three trials permit at most 39 model calls, without a repair-feedback loop.

Every investigator uses its own registered fixed-case execution budget. Each ready
candidate receives a new protected verification with five baseline, five target
and five candidate scenario executions. Container permissions, expiry, revocation,
timeouts and cleanup retain the existing runtime rules. The suite runs sequentially;
it does not implement multiple cooperating investigators.

State lives in `.workbench/customer-repair-evaluations/<evaluation-id>/`, with child
records in the existing investigation, agent, candidate, model-call, execution,
evidence and verification directories. Registration is inert. Every child ID is
mapped before dispatch, and its context or preceding-stage binding is checked
before reuse. The started suite is sealed against changing its model, images,
inputs or trial count. Preserve the whole state store for recovery.

Resume uses existing child IDs. An ambiguous model outcome is not replayed; an
interrupted verifier retains an incomplete result after its normal cleanup. Remaining
unstarted trials can then proceed. A changed build stops unfinished suite execution;
recover already-started children separately with their recorded commands and IDs.
Registration interrupted before mapping may leave an inert registration, never an
unmapped dispatched action.

## Read the result

The report retains all planned trials and separates investigation status, generation
status, verification status and behavioral repair outcome. An investigator's
`ABSTAINED` becomes `INVESTIGATION_ABSTAINED`, while technical or invalid investigation
results become `INVESTIGATION_FAILED`. Neither starts generation. Candidate abstention,
invalid proposals and generation failures use the existing repair-suite outcomes.
Protected verification records fixed, remaining or unresolved regressions; a
successful process alone is not a passing repair.

`COMPLETED` means every planned trial has an outcome, including failures. It does
not mean all trials succeeded. Per-trial scenario outcomes, child IDs, artifact hashes
and completed-response token usage remain available. Unknown request charges remain
unknown, and the report contains no general accuracy percentage or correctness grade
for abstention. Review semantic claims separately against their cited evidence.

Completed reports can be reused after validating suite mappings and retained child
artifacts, including original source, manifests, event records and verification logs.
New dispatch still follows the individual runtimes' input and permission checks.
The store assumes a trusted single host; hashes are integrity checks, not signatures.
