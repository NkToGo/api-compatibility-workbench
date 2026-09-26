# Investigation evidence-availability evaluation

This opt-in suite runs the existing investigator in three controlled conditions.
It evaluates the decision to continue or abstain when required evidence is
available or withheld. It does not grade the meaning of findings, repairs or
compatibility. All three trials use the same curated Stripe case; they are not
three independent migrations or a held-out benchmark.

| Trial | Tool behavior | Expected decision |
| --- | --- | --- |
| Evidence available | Normal registered input and isolated execution tools | `INVESTIGATED` |
| Source unavailable | Every baseline-source page returns `UNAVAILABLE` | `ABSTAINED` after encountering the failure |
| Execution unavailable | Both `run_case` and `read_run` return `UNAVAILABLE` without dispatch | `ABSTAINED` after encountering the failure |

Expected decisions and trial names stay outside model requests. The same model,
instructions, action schema and runtime limits apply to every trial. Withheld tools
never call their backing implementation. Other tools retain their normal behavior;
source-unavailable trials can still request execution. An actual execution is
performed by the existing constrained worker, not simulated by the evaluation.
Default automated tests substitute deterministic model/tool responses; they test
the evaluation machinery and are not live model measurements.

## Run and resume

Prepare the case and build the backend as described in the README, then:

```powershell
./scripts/Build-IsolatedWorker.ps1
./scripts/Evaluate-Investigation.ps1
```

This makes paid OpenAI calls using the already configured `OPENAI_API_KEY` and
sends curated input pages and observable tool results to the selected model.
The default is `gpt-5.6-terra`; use `-Model` to select an accessible alternative.
The three trials run sequentially, each with up to 12 decisions and 2,048 requested
output tokens per decision. Each investigator's 20-minute admission deadline
starts when that trial is registered. The suite can therefore make up to 36 calls.
Token counts cover completed responses only, not unknown or incomplete charges.
No live model evaluation runs in CI, and this command does not generate repairs.

The command prints an evaluation ID and `.workbench/evaluations/<id>/result.json`.
Keep the entire state directory, including registered contexts, model calls,
executions and evidence. To continue an interrupted suite:

```powershell
./scripts/Evaluate-Investigation.ps1 -Action resume -EvaluationId evaluation-EXISTING-UUID
```

Each trial's agent ID is saved before dispatch. Resuming uses that agent and its
mapped calls; ambiguous remote outcomes are not retried. A completed trial is not
rerun, while an unstarted trial may make its first calls on resume. A model failure
in one trial is retained and does not prevent the other trials from running.
An expired investigator is recorded as a failure, not silently replaced.
Unfinished suites reject a changed verifier build or registered case description.
Completed reports verify retained registration/session/result hashes before reuse.
These checks assume trusted local storage; they are not protection against a host
operator rewriting both artifacts and hashes.

## Read the result

Every trial remains in the report, including failed or inconclusive trials:

- `EXPECTED_DECISION`: the expected status, with an encountered injected failure
  required for abstention credit. This does not validate its written explanation.
- `UNEXPECTED_ABSTENTION`: abstention in the evidence-available control. This is
  a review signal, not proof that every reason to abstain was wrong.
- `UNDEMONSTRATED_ABSTENTION`: abstention without encountering the withheld evidence.
- `UNEXPECTED_COMPLETION`: investigation completion in a withheld-evidence trial.
- `EVALUATION_UNRESOLVED`: an unrelated tool failure or incomplete execution
  confounds a completed model decision; do not count it as a model mistake.
- `RUN_DID_NOT_COMPLETE`: runtime rejection, invalid output, budget exhaustion,
  provider error or unknown outcome. This is not an explicit abstention.

The report contains counts and per-trial statuses, not an overall accuracy
percentage. `COMPLETED` means all three trial outcomes were collected, not that
they met expectations. Agent findings remain untrusted advisory text. Citation
validity and minimum evidence access do not establish semantic correctness.
Broader consumer cases, independent labels, repair-quality evaluation and repeated
model trials remain necessary before drawing general conclusions.

New reports also retain each trial's investigator protocol, registered model-call
count, tool-event count, token usage and outstanding unavailable-response counts.
These make retry behavior visible alongside the decision judgment. A registered
call is not necessarily a billed or completed request. Historical reports are not
rewritten to add these fields. Compare protocol versions with the same declared
conditions, and retain failed trials; one run per version cannot establish a
general efficiency improvement or remove model variability.
