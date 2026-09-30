# Repeated missing-evidence investigations

This opt-in suite runs fresh, bounded investigations on both curated cases with
required source or execution evidence withheld. It records whether an investigator
encounters the missing evidence and explicitly abstains. It does not generate a
candidate, dispatch protected repair verification or judge whether the written
explanation is supported. Semantic review remains separate acceptance work.

The older [single-case availability suite](investigation-evaluation.md) remains
available with its original protocol and records. This suite adds repeated trials,
the subscription case and stricter source-alias withholding; it does not rewrite
earlier evaluations.

## Registered conditions

| Condition | Customer sources | Subscription periods |
| --- | --- | --- |
| Required source unavailable | Every page of both `baseline-source` and `reference-source` returns `UNAVAILABLE` | Every page of `baseline-source` returns `UNAVAILABLE` |
| Required execution unavailable | Both `run_case` and `read_run` return `UNAVAILABLE` without dispatch | Both retained-evidence read aliases return `UNAVAILABLE` |

Withheld operations never reach the backing adapter. Other tools keep their normal
behavior: customer source-unavailable trials may request real isolated execution;
subscription source-unavailable trials may read their registered retained execution.
Subscription contexts expose original source, application policy, fixtures and
baseline/target observations only. Prior candidate source and candidate results
are not exposed, even when the selected verification contains a candidate.

Conditions, expected decisions and grading fields stay outside model inputs and
tool errors. Every investigator uses the existing `investigation-v2` protocol.
Policy and fixtures remain available in both conditions. Withholding actual source
or execution access does not remove all descriptive background about the case.
These are controlled availability trials, not a test of private data isolation.

## Run and resume

Prepare and build the backend as described in the README. Select a completed,
retained subscription verification created through the
[subscription verification workflow](subscription-candidates.md). Then run:

```powershell
./scripts/Build-IsolatedWorker.ps1
./scripts/Evaluate-MissingEvidence.ps1 -SubscriptionVerificationId verification-EXISTING-UUID -Repetitions 3
./scripts/Evaluate-MissingEvidence.ps1 -Action resume -EvaluationId evaluation-EXISTING-UUID
```

This makes paid OpenAI calls with the configured host `OPENAI_API_KEY`; workers
never receive it. The default model is `gpt-5.6-terra`. `-Model` selects an accessible
alternative and `-StateDirectory` selects the trusted local store. Curated input
pages and observable tool results are sent to the selected model. CI uses fake
model responses and does not run paid evaluations.

One to five repetitions per case/condition are permitted, default three. Three
repetitions give 12 fresh investigations, each with at most 12 model decisions,
2,048 requested output tokens per decision, a 64,000-byte model-input bound and
the existing 20-minute investigator admission deadline. Thus the default suite
permits at most 144 model calls. Customer execution uses the existing per-context
fixed-case budget and constrained worker; subscription tools only read the selected
retained evidence. Trials run sequentially and do not coordinate with each other.

## Durable records and integrity

Before dispatch, registration records the complete case/condition/repetition matrix,
separate context IDs, model, call budget, build identity and input-artifact hashes.
The selected subscription verification's artifacts are also captured. A started
suite is sealed against changes, and all registered input identities are checked
before further model work, including trials not yet started.

State lives in `.workbench/missing-evidence-evaluations/<evaluation-id>/`. Each
`trial-N/agent.json` maps a child before dispatch; the child's session, calls, tool
events and result live under that trial's `agents/` directory. These investigations
do not enter the normal generation workflow. Keep the whole state store, including
registered investigation contexts, broker calls, executions, evidence and the
selected subscription verification, for recovery.

Resume checks the same agent, context, model and case description. It resumes
existing call mappings and never automatically replays an ambiguous dispatch.
Operationally failed children remain failed while remaining trials can proceed.
An unfinished suite rejects a changed build; recover started children separately
rather than replacing their IDs. A completed report reuses recorded outcomes only
after checking suite artifacts, final contexts and retained subscription execution
artifacts. File hashes assume a trusted single host; they are not signatures or
protection against an operator rewriting both records and hashes.

## Read the result

`COMPLETED` means all registered outcomes were collected, including failures.
Every trial retains its condition, repetition, context and agent IDs, actual status,
encountered injected failure, unrelated tool failure, registered call count,
completed-response input/output tokens and artifact identities.

| Decision check | Meaning |
| --- | --- |
| `ABSTAINED_AFTER_WITHHELD_EVIDENCE` | Explicit abstention after an encountered injected failure, without an unrelated tool failure |
| `UNDEMONSTRATED_ABSTENTION` | Abstention without encountering the withheld operation |
| `UNEXPECTED_COMPLETION` | Investigation completed instead of abstaining |
| `TECHNICAL_UNRESOLVED` | An unrelated tool failure, invalid execution authority or incomplete execution confounds the decision |
| `INVESTIGATION_FAILED` | Runtime rejection, invalid output, exhausted bounds, provider failure or unknown outcome; no explicit abstention credit |

All rows have `semanticReview: PENDING`. A structurally accepted citation and an
encountered missing tool do not prove that the explanation accurately describes
the limitation or avoids unsupported claims. Review each rationale against its
cited events before crediting the [Milestone 3 acceptance criterion](milestone-3-acceptance.md).
The report does not contain an overall accuracy percentage. Recorded tokens cover
completed responses only; incomplete or unknown requests can also incur charges.

## Recorded measurement

Suite `evaluation-7271379f-19c0-4ce7-a479-c8f08ed2210f` ran on 30 September 2026
with `gpt-5.6-terra`, three repetitions per cell and the existing 12-decision
per-investigator bound. All 12 trials explicitly abstained after encountering the
withheld operation; none had an unrelated tool failure. Every rationale remains
pending semantic review. No repairs were generated or credited.

| Case | Withheld evidence | Abstentions after injected failure | Registered calls | Recorded input/output tokens |
| --- | --- | --- | --- | --- |
| Customer sources | Source | 3/3 | 9 | 12,334 / 696 |
| Customer sources | Execution | 3/3 | 14 | 32,825 / 1,158 |
| Subscription periods | Source | 3/3 | 9 | 11,256 / 699 |
| Subscription periods | Execution | 3/3 | 12 | 19,400 / 913 |

The suite registered 44 calls out of the declared maximum 144 and recorded
75,815 input tokens and 3,466 output tokens. Its compiled harness identity was
`66fcf3062f0476cb39ef091dd8d97412041a026b4ec9ec4f1ef85295366b311c`;
the customer worker image was
`sha256:018b43e05e546e8745fd4669c6e10dcea3180b967e03d04647a0230eef56d42f`.
The subscription contexts used completed verification
`verification-e5839039-37d2-4ce8-bbfc-554088f63620`, exposing only its original
inputs and baseline/target observations. Per-context descriptions and input,
execution, session and event hashes remain in the retained suite.

Completed resume preserved the result bytes and registered zero additional model
calls. No case-worker containers remained. These are repeated known-case decision
measurements; they do not establish supported explanations, satisfy the semantic
acceptance criterion or estimate general model accuracy. Milestone 3 remains open.
