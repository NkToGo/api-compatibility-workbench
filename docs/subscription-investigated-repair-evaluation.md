# Repeated investigated subscription repairs

This opt-in suite measures the full subscription workflow: a fresh bounded
investigator reads registered original source, application policy and retained
baseline/target observations; its captured advisory findings enter one candidate
generation; a separate protected verifier evaluates each ready candidate.
It complements the earlier [generation-only suite](subscription-repair-evaluation.md).
Historical suites and requests are not converted or replaced.

## Run and budgets

After the [backend verification](../README.md#build-and-test), build the repair
worker and select completed subscription verification with a passing baseline
and observed target failures:

```powershell
./scripts/Build-IsolatedWorker.ps1 -RepairWorker
./scripts/Evaluate-SubscriptionInvestigatedRepairs.ps1 -VerificationId verification-EXISTING-UUID -Repetitions 3
./scripts/Evaluate-SubscriptionInvestigatedRepairs.ps1 -Action resume -EvaluationId evaluation-EXISTING-UUID
```

The default model is `gpt-5.6-terra`, using the configured host API key. One to
five trials are permitted, default three. Before any dispatch the suite records
the model, image, harness identity, repetition count, original evidence inventory
and distinct registered contexts. Each investigator uses `investigation-v2`:
at most 12 decisions, 2,048 output tokens per response, 64,000 UTF-8 input bytes
per request and a 20-minute admission deadline. The generator uses the existing
`subscription-repair-v1` protocol: one request, 4,096 output tokens, 64,000 input
bytes. Existing broker timeouts and no-replay rules apply.

Three trials permit at most 39 registered requests and three new verifications.
Each verification executes eight baseline, eight unrepaired target and eight
candidate scenarios under the existing constrained repair-worker policy. There
is no repair-feedback loop or extra attempt for a failed trial.

The investigator's `run_case` and `read_run` both read the selected completed
evidence; neither dispatches execution. Only original baseline and target
observations are visible. Previous candidate source/results are excluded from
the investigation and generation prompts. Application expectations are visible,
so this is a known-case repeatability measurement, not hidden-test accuracy.

## Evidence, outcomes and recovery

Records stay in `.workbench/subscription-investigation-repair-evaluations/<id>/`.
The suite seals its configuration when started. Each inert stage registration is
mapped by ID and hash before model or container dispatch. Session context/model,
candidate investigation/evidence binding and verification manifest/image/protocol
are checked before the corresponding stage executes. Original evidence and
context inventories are rechecked across stage transitions.

The final report retains all trial and stage IDs, statuses, protected scenario
outcomes, child and model-record inventories, registered request counts and
completed-response token usage. Registered requests are distinct from known
dispatches; uncertain calls may incur charges absent from recorded token totals.
The raw `semanticReview` stays `PENDING`: protected repair outcomes do not grade
whether investigation explanations are supported.

`INVESTIGATION_ABSTAINED` and `INVESTIGATION_FAILED` do not register generation.
Successful investigations use the existing behavioral
[repair outcome classifications](subscription-repair-evaluation.md), keeping
generation abstention, invalid proposals, operational failures, remaining
regressions and incomplete verification separate. `COMPLETED` means every
planned trial has a recorded outcome; it does not mean every trial passed.

Resume retains the same child IDs. An uncertain model request is not replayed.
An interrupted verifier cleans its workers and records incomplete execution;
remaining unstarted trials may proceed within the declared budget. Interrupted
registration before mapping can leave an inert orphan, but cannot dispatch work
outside its mapping. A changed build stops unfinished suites; recover existing
children separately. Completed suites remain readable under later builds after
checking suite, original evidence, contexts, child artifacts and model records.
These hashes detect changes in a trusted local store; they are not signatures.

Semantic review and the combined two-case acceptance report remain required by
the [Milestone 3 checklist](milestone-3-acceptance.md).

## Recorded sufficient-evidence measurement

On 2026-10-01, `evaluation-6bf39751-1f36-48cf-bf62-a0c5c9562725` ran three fresh
`gpt-5.6-terra` investigations and one-shot generations under the declared budgets.
All three candidates passed all eight protected scenarios: four original
regressions fixed and four passing controls preserved. There were no unresolved
executions or failed trial stages in this suite. The 72 new scenario executions
used separate baseline, target and candidate jobs for every trial.

| Trial | Protected result | Registered requests | Recorded input/output tokens |
| --- | --- | --- | --- |
| 1 | All eight scenarios passing | 10 (nine investigation, one generation) | 55,828 / 1,173 |
| 2 | All eight scenarios passing | 9 (eight investigation, one generation) | 47,198 / 1,355 |
| 3 | All eight scenarios passing | 9 (eight investigation, one generation) | 46,671 / 1,033 |
| Total | Three complete repairs | 28 of at most 39 | 149,697 / 3,561 |

Stage identities, in trial order:

| Trial | Investigator | Candidate | Protected verification |
| --- | --- | --- | --- |
| 1 | `agent-6f982b4a-a5a6-4eb6-a77c-4b44a8626b73` | `candidate-9e944a78-d0bd-4cfc-92e6-d42777ff7105` | `verification-6c3f1ad3-d133-43eb-8f9f-5e67644a50ff` |
| 2 | `agent-cdf576f5-3c0c-4598-a3de-a9f94400ee5d` | `candidate-f8fd7c41-f7de-4e88-b724-8614971f5ea4` | `verification-fc64862f-32e6-41a7-b8aa-b0f62a371484` |
| 3 | `agent-4a4d44c3-1060-4806-b3d7-804a1f2bb262` | `candidate-c276ddc0-f15b-41d0-b0a4-3b2abfc840d8` | `verification-84fdc1ea-cba7-41b9-aa03-6cb99eebb9ab` |

The original retained evidence was `verification-e5839039-37d2-4ce8-bbfc-554088f63620`.
Its captured source/policy/fixtures were reused unchanged, including its historical
case-documentation bytes; later working-copy documentation edits were not substituted.
The suite stores the full original artifact inventory. Selected exact identities:

| Artifact | SHA-256 |
| --- | --- |
| Original verification specification | `6a164c54c1161a3c420a2b6dcc5776ec8f1e17cb88008f9ec8f965ed3a99057f` |
| Original verification result | `1565c4840429f77449f535ffa5b93b33859f12f63bd8dd8ba8decb6cf991ce70` |
| Captured baseline source | `0092774df675acd4cc4db59dc1fe280b89e9249893bbd011e33a847f79a04fdf` |
| Captured case documentation | `2c192ce824d4737094a163f743a327ce287743abf5332836f47107a95fa0ca84` |
| Declared expectations | `b3f42ded863d5004b8060b6f5e5fd215df14156440b84763e46687ca147a39e0` |
| Synthetic fixtures | `c31154b82f5fe320fc790261bd100c7a2481832aae953d7c2960204c014559f4` |
| Suite result | `172749b1391db1ff446aadb244a086ea54d1624b0283c933c636f8e280a29e32` |

The harness identity was `c04bf25b71d075a9cd6dff573d1c461571c7621a2ff694b876532f706852777e`;
the repair image was `sha256:83e5270f52b49c79414afe38383488f91fbdba62c44372c9cb0cb4dc88ba360d`.
Completed-suite resume preserved result bytes and left the model-call store count
unchanged at 255 registrations. No workbench worker containers remained.

This reaches the subscription case's numerical sufficient-evidence repair
threshold. Investigation explanations still require semantic review, and
Milestone 3 remains open until the combined acceptance report meets its criteria.
Three successes on this known synthetic case do not establish general model
accuracy, live-provider compatibility or multi-agent benefit. Token totals are
recorded usage, not an invoice or a dollar-cost estimate.
