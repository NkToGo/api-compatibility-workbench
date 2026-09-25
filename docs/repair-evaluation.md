# Repair evaluation

`RepairEvaluation` compares three sets of behavioral observations: the original
baseline, the unrepaired target and a repaired target. It reuses the compatibility
contract for coverage, identity, baseline validity and incomplete execution.
It does not execute code, authenticate records or approve deployment.

## Conditions and outcomes

One definition fixes the complete required scenario list for all three sides.
The repaired target may change source only. Its case, provider, effective version,
configuration, expectations, fixture bundle, harness and environment must match
the unrepaired target. Even a change that produces passing assertions is unresolved
if those conditions differ. An unchanged source is accepted as a no-change control
and receives no credit for failures that remain.

For each scenario with valid, complete evidence and a passing baseline:

| Unrepaired target | Repaired target | Outcome |
| --- | --- | --- |
| Failed | Passed | `FIXED` |
| Failed | Failed | `STILL_FAILING` |
| Passed | Failed | `NEW_FAILURE` |
| Passed | Passed | `STILL_PASSING` |

Missing, stale, duplicate, incomplete or invalid-baseline evidence produces
`UNRESOLVED`. Known results in other scenarios remain visible. Undeclared extra
observations also block the aggregate, even when all required scenarios passed.
The underlying two comparisons retain the reasons and affected sides.

The aggregate is unresolved if any comparison or repair boundary is unresolved.
Otherwise, any repaired-target failure means `REGRESSIONS_REMAIN`, including new
failures introduced while fixing another scenario. `ALL_OBSERVED_REGRESSIONS_FIXED`
requires at least one original regression and all required assertions passing
after repair. `NO_REPAIR_NEEDED` distinguishes an already-passing transition from
a demonstrated fix. None is an unqualified compatibility or release approval.

Assessments retain their definition and immutable scenario outcomes. `appliesTo`
requires exact definition equality, including source identities and scenario
selection. Historical evidence is not rewritten when current inputs change.

## Curated evaluation baseline

The existing case runner writes `historicalRepairEvaluation` into `report.json`
and displays its counts in the offline HTML report. The container controller
recomputes this evaluation from recorded observations when exporting the report.

For the five curated Stripe scenarios, the historical reference patch fixes two
of the four observed upgrade regressions, leaves two failing and preserves the
one passing control. It introduces no new failure in this scenario set. The
remaining failures concern retaining a legacy method and recording evidence
before deactivation. This is one historical comparison, not a statistical estimate
of model repair quality. No model produced these historical reference changes.

Run the normal build and `./scripts/Run-StripeCase.ps1` to regenerate the report.
This executes only the previously allowlisted curated sources.

## Generated-code execution

Candidate generation itself remains inert. The original fixture harness shares a
process and mutable bookkeeping with the curated consumer. Its allowlist is
essential: filesystem isolation alone would not stop generated code from forging
stdout or interfering with in-process assertion data. This evaluator must receive
observations from a trusted adapter, never directly from candidate output or an
agent's claimed result.

The separate [protected execution adapter](protected-repair-execution.md) now keeps
expectations and authoritative service state on the host, compiles and runs candidates
in containers, and retains interrupted or invalid outcomes without replay. It reruns
all three variants under its own harness and records new identities. Tests cover
forged output and attempts to mutate test bookkeeping. A broader curated suite and independently reviewed
abstention labels are also needed before reporting repair or abstention accuracy.
