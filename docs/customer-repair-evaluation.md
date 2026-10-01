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
host credential and sends curated source, application expectations, registered
synthetic fixture responses and observed tool results. Target SDK signatures and
the selected public source-list implementation come from checksum-pinned `javap`
inspection; consumer code is never run for evidence discovery. No live model
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

## Recorded sufficient-evidence measurements

Three separately registered suites used `gpt-5.6-terra`, three repetitions each,
the unchanged five scenarios and the existing one-shot generation budget. Each
permitted at most 39 model calls. The source/fixture/expectation/dependency bundle
identity was `f12b86c6eacedf02fed8f1841ebbab36c236597e4cb1456500d8e6122390d897`.
No candidate received an authored complete-repair control or verifier source.

| Generation evidence | Fully passing repairs | Other outcomes | Actual model calls | Recorded input/output tokens |
| --- | --- | --- | --- | --- |
| Original signatures and outcome summary | 0/3 | Three repairs passed 4/5; confirmed removal remained failing | 30 | 154,181 / 6,317 |
| Complete registered synthetic responses added | 0/3 | Two repairs passed 4/5; one generator abstained | 31 | 172,536 / 6,064 |
| Selected pinned source-list implementation added | 2/3 | One repair passed 1/5; all four original regressions remained | 29 | 155,071 / 6,169 |

The suite IDs, in row order, are:

- `evaluation-0374e04b-580e-437f-bb06-41398e77d93c`
- `evaluation-e1907a9d-c38c-48d0-b009-0788595229bb`
- `evaluation-34cd92c5-a5c9-4ac1-a853-720e3b20801d`

Each retains its exact build/image identities, input hashes, child mappings,
requests, findings, candidate sources and protected results in the local state
store. The final suite's failed candidate constructed a collection but iterated
it without first fetching the collection contents. It was not credited as a repair.
Completed report reuse preserved result bytes and registered no new model calls;
no worker containers remained after completion.

The final suite reaches the customer's sufficient-evidence repair threshold.
The later [bound acceptance report](milestone-3-results.md) adds semantic review
and combines these results with subscription and missing-evidence trials. The
separate availability suite does not itself grade semantic support. These small
known-case measurements are not a controlled
estimate of general model accuracy or live-provider compatibility. Recorded tokens
cover completed responses, not an invoice or unknown request charges. Earlier
unsuccessful suites remain retained rather than being replaced by the later result.
