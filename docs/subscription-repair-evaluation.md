# Repeated subscription repair evaluation

This opt-in suite runs a declared number of independent one-shot candidate attempts
against the same retained subscription verification. It reports behavioral repair
outcomes, abstentions and operational failures separately. It does not score whether
an abstention was justified, measure general accuracy or provide a held-out test.
The generator sees application expectations and case documentation.

After building the backend and repair worker, supply a completed subscription
verification with a passing baseline and observed target failures:

```powershell
./scripts/Evaluate-SubscriptionRepairs.ps1 -VerificationId verification-EXISTING-UUID -Repetitions 3
./scripts/Evaluate-SubscriptionRepairs.ps1 -Action resume -EvaluationId evaluation-EXISTING-UUID
```

The default model is `gpt-5.6-terra`; the suite reuses the configured host API key.
It permits one to five trials, default three. Each trial permits one model request,
up to 64,000 UTF-8 input bytes and 4,096 output tokens. Every ready candidate receives
a new 24-scenario verification: eight baseline, eight unrepaired target and eight
candidate executions. Generation and execution keep their existing timeouts,
permissions and resource limits. There is no automatic repair-feedback loop.

Results stay in `.workbench/repair-evaluations/<evaluation-id>/`. The suite records
its model, image, verifier identity, source evidence identity and planned trial count.
Before dispatch it durably maps each trial to a candidate and, when ready, a verification.
The final report includes those IDs, result hashes, per-scenario repair outcomes,
outcome counts and recorded token usage. Unknown/incomplete model outcomes may incur
charges not represented in token totals.

| Outcome | Meaning |
| --- | --- |
| `OBSERVED_REGRESSIONS_FIXED` | Protected verification found all observed regressions fixed within the declared scenarios |
| `REGRESSIONS_REMAIN` | Protected evaluation retained failures; scenario outcomes expose remaining and new failures |
| `ABSTAINED` | The model returned no proposed edits; correctness of this decision is not graded |
| `INVALID_PROPOSAL` | The model output failed scoped proposal validation |
| `GENERATION_FAILED` | Generation did not yield a proposal or abstention; its original status is retained |
| `VERIFICATION_INCOMPLETE` | Execution was interrupted, stopped or did not finalize an evaluation |
| `VERIFICATION_UNRESOLVED` | Execution produced an assessment that cannot establish a complete repair |

`COMPLETED` means all planned trials have a reported outcome, not that they passed.
The report retains failures in its denominator; it does not silently retry them or
report success only among completed candidates.

Resume uses the same child IDs. The model broker does not replay uncertain calls;
the verifier cleans interrupted workers and records incomplete execution without
rerunning them. Remaining unstarted trials can then proceed within the original
budget. Starting a new suite is an explicit new set of attempts. Registration interrupted
before a child mapping can leave an inert orphan registration, but cannot leave a
dispatched model call or worker outside the mapping.

A started suite's configuration is sealed by hash. A changed build prevents further
suite execution; recover already-started child verifications separately using their
recorded IDs. Finalized reports remain readable after validating suite mappings,
candidate artifacts, original evidence and verification artifacts. The files assume
a trusted single-host store; their hashes are integrity checks, not signatures.

Repeated success on one known case establishes only repeatability under those inputs.
Additional consumers, hidden scenarios and specifically curated abstention conditions
remain necessary before making broader repair-quality claims.
