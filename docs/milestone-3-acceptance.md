# Milestone 3 acceptance checklist

Milestone 3 requires useful repairs and justified abstentions measured on the two
existing curated cases. This is a bounded acceptance exercise, not a general
migration benchmark, release certification or evidence of multi-agent benefit.
The checklist below is planned work unless explicitly marked implemented.

## Current evidence boundaries

| Capability | Customer sources | Subscription periods |
| --- | --- | --- |
| Authored baseline and repair controls | Implemented, five scenarios | Implemented, eight scenarios |
| Adaptive investigation | Implemented with fixed execution tool | Implemented over retained execution with identity-bound generation handoff |
| Scoped generated candidate | Implemented | Implemented |
| Separate protected candidate execution | Implemented | Implemented |
| Repeated generated-repair suite | Pending | Implemented on one known case |
| Controlled evidence-availability investigation trials | Implemented | Pending |
| Semantic review of findings and abstention rationale | Pending | Pending |

Automated tests of the evaluator establish runtime behavior, not model quality.
The existing availability suite checks encountered tool failures and decision
statuses; it does not establish that a written explanation is supported.
The existing subscription suite measures repeated behavioral results but does not
judge abstention correctness. Neither suite is a held-out evaluation.

## Remaining implementation sequence

1. **Implemented — subscription investigation and handoff:** bounded registered
   source, policy and retained execution reads reuse the existing investigator.
   Completed findings can enter generation with captured agent, context and event
   identities. Advisory findings remain separate from execution conclusions.
2. **Two-case acceptance trials:** add repeatable customer-sources repair trials
   and explicit insufficient-evidence conditions for both cases. Reuse the existing
   candidate and verification boundaries. Register conditions, expected decisions,
   repetitions, model, execution identities and budgets before dispatch.
3. **Acceptance review:** run the fixed trials, review findings and abstention
   rationales against their cited observations, and publish a bounded results
   summary. Preserve unsuccessful trials and unresolved review findings. A failed
   criterion requires a targeted correction and a separately identified rerun;
   it must not disappear by replacing the original report.

These are capability boundaries; each may require multiple cohesive changes.
Additional provider families, arbitrary repositories, a repair-feedback loop and
statistical estimates of general accuracy are not prerequisites for this milestone.
They must not be described as implemented or validated by this acceptance exercise.

## Fixed trial design

Use three repetitions per condition per case, declared before the first dispatch:

| Condition | Required evidence and expected behavior |
| --- | --- |
| Sufficient evidence | Original source, declared application policy and resolved baseline/target observations are available. Investigate and attempt a scoped repair. |
| Required source unavailable | A required source read is deliberately unavailable. An explicit abstention must identify that encountered limitation; no ready repair is credited. |
| Required execution unavailable | Required execution evidence is deliberately unavailable. An explicit abstention must identify that encountered limitation; no ready repair is credited. |

This gives 18 planned trials, including six repair opportunities. Expected labels
and grading instructions stay outside model inputs. Withheld evidence must not
reappear in prompts, cached findings or a downstream generator. An injected failure
is distinguishable from an unrelated infrastructure failure in the retained record.
The sufficient-evidence trials may expose application expectations; their results
must be labeled known-case measurements, not hidden-test accuracy.

Use the existing bounded investigation and one-shot generation limits. A trial
does not gain extra attempts because it fails. An uncertain dispatch is a technical
outcome, not a correct abstention. Each proposed repair receives independent
three-way verification under the same declared scenarios. A passing baseline is
required; unresolved verification cannot count as a successful repair.

## Exit criteria and report

The acceptance report must retain all planned trials and distinguish:

- Generated repairs with all observed regressions fixed and no new failures.
- Repairs with remaining or new failures, and invalid proposals.
- Explicit abstentions, with a reviewed supported or unsupported rationale.
- Unfinished or unresolved technical outcomes, including unknown model dispatches.

For this bounded milestone, require at least two of three sufficient-evidence
trials on **each case** to produce a protected, fully passing repair. Require all
three trials in **each withheld-evidence condition on each case** to abstain with
a rationale supported by encountered evidence. Technical failures leave that
condition unestablished rather than earning credit or being silently excluded.
These are acceptance thresholds for these cases, not production reliability targets
or statistically reliable estimates of model accuracy.

Review every sufficient-evidence investigation for material unsupported claims.
The review record must cite observable source/execution events, state the judgment
and explain any unresolved disagreement briefly. Reference validity alone is not
semantic support. No hidden reasoning is requested or recorded. Material unsupported
claims must be corrected or explicitly blocked from the accepted workflow.

Report per-case counts, planned and actual calls, recorded tokens, declared budgets,
model and input identities, verifier identity and remaining limitations. Unknown
charges remain unknown. Demonstrate resume without extra dispatched work and
rejection of changed input evidence using the existing automated checks and the
new integrations' regression tests.

Milestone 3 remains open until this report and its supporting records exist and
meet the criteria. Milestone 4 then evaluates coordination across the two distinct
consumer behaviors; this checklist makes no claim that coordination already works.
