# Scoped coordination routing

The recorded two-case comparison does not establish a benefit from automatically
using a coordinated team. The local routing policy therefore selects deterministic
diagnostics for initial evidence inspection when all supplied conditions match
that exact comparison. Maintainer review remains required. Changed conditions
have no automatic route.

This is an implemented read-only recommendation command, not an automatic
dispatcher, a general model selector or a migration approval system. The existing
adaptive adapters remain available through their explicit bounded commands;
their permissions, budgets and evidence checks are unchanged.

## Recorded decision

The [versioned policy](evaluations/coordination-routing-2026-10-07.json) binds the
decision to the exact [paired report](coordination-results.md), binding manifest
and semantic review. The command pins the policy digest and verifies those source
digests before returning a recommendation. It uses the recorded full comparison
definition as its scope: case/input identities, workload and criterion membership
and order, response expectations, exact model, all four implementation identities,
rubric, repetitions, origin, experiment ID and common resource limits.

The decision is conservative rather than a quality-winner claim:

- No configuration supplied an accepted complete two-case bundle.
- Deterministic subscription diagnostics supplied three accepted cases; adaptive
  configurations supplied none under this recorded model-assisted review.
- The team did not improve accepted-case counts, used more aggregate recorded
  tokens and summed elapsed time, and was slower in all three matched repetitions.
  Its first repetition used fewer input tokens than the single agent.
- Two unsupported endorsed team claims and one unresolved independent-worker
  claim remain visible. The overall comparison remains `UNRESOLVED`.

The three repetitions, two known synthetic cases and unblinded model-assisted
review do not establish general superiority, held-out accuracy, independent human
adjudication or production readiness. Partial diagnostics are useful initial
evidence, not a complete accepted answer. The separate comparison evaluator still
reports `routingDecision: NOT_ESTABLISHED`; the policy does not rewrite it.

## Command

PowerShell 7 is required; this command needs no Java build, Docker, network or key.
Supply a complete `formatVersion: 1` comparison plan envelope, as described in
the [comparison protocol](coordination-comparison.md#local-command):

```powershell
./scripts/Get-CoordinationRoute.ps1 -CurrentPlanPath current-plan.json
```

For the unchanged retained campaign, the registered comparison's `plan.json` is
a matching input. The command can also run from a fresh clone: all fixed policy
and evidence references it reads are versioned in `docs/evaluations/`.

| Result | Selected arm | Required next action |
| --- | --- | --- |
| `DIAGNOSTICS_FIRST_REVIEW_REQUIRED` | `DETERMINISTIC` | Inspect diagnostics and address incomplete reasoning through maintainer review. |
| `OUT_OF_SCOPE` | `null` | Re-establish current conditions and choose a separately supported investigation; do not reuse this preference. |
| Validation error | No output recommendation | Repair the invalid/missing input or evidence; do not fall back to another arm. |

Successful JSON output includes the current plan's byte digest, policy/report/review
references, recorded quality counts and preserved unsupported/unresolved counts.
`qualityWinnerEstablished`, `automaticAdaptiveEscalation`, `executionAuthorized`
and `compatibilityApproved` are always false; `maintainerReviewRequired` is always
true. These flags are boundary statements, not grant objects. The command creates
no trial, journal, permission, model request, repair or comparison report.

Scope equality is structural, using exact ordinal member names and string values.
Invisible Unicode characters cannot alias a model or input digest. Object member order and JSON
whitespace do not matter; array order and numeric/string distinctions do. Unknown
or omitted definition fields cannot match. Duplicate JSON members, invalid UTF-8,
malformed/deep input, files larger than 1 MiB and linked file/parent paths are
rejected. The read stops at one byte beyond the size bound even if a file grows.
The pinned evidence uses the LF bytes retained by Git attributes on both platforms.

## Authority and remaining acceptance work

The current plan is supplied metadata. The command does not inventory a live
repository, recompute its source identities or discover that an operator supplied
an obsolete plan. A future host dispatcher must obtain current conditions from
trusted inventory and enforce separate admission and execution grants. A matching
plan cannot approve publication or release. Trusted local files and digest equality
do not authenticate a producer or protect a compromised host.

The [Milestone 4 status](milestone-4-status.md) keeps useful-coordination acceptance
open. Any future promotion must use separately frozen conditions and a workload
with a real shared consumer assumption, retain simpler baselines and complete
semantic review, and demonstrate useful coordination within declared budgets.
Missing quality evidence must not be converted into an automatic team fallback.
Changing this policy requires a separately reviewed version and evidence binding.

## Verification

```powershell
./scripts/Test-CoordinationRoute.ps1
```

The controls exercise actual versioned evidence and modified plan fixtures,
checking scope changes, preserved uncertainty, forbidden authorization/coercion,
duplicate/malformed JSON, byte bounds, altered artifacts and linked paths. They
make no model or consumer execution call and do not establish semantic accuracy.
