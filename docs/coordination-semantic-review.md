# Semantic review of the frozen coordination campaign

This separate review judges the recorded answers from
`campaign-8d49ba41-7cf4-4181-ad4b-fb20dd6c1915` against its unchanged
`coordination-known-cases-v1` rubric. The campaign ran three paired repetitions
of four configurations over two known synthetic cases. No participant was rerun
and no best retry was selected for this review.

The [structured review](evaluations/coordination-review-2026-10-07.json) binds
all 24 final dossiers and all 196 units in sealed input packet
`reviewpacket-fe2793c0-8aad-4377-b5d9-f1f15e77e441`. It contains 1,068 judgments:
visible passage clauses, original/fresh team claims and individual structured
diagnostic facts. This is an inventory of reviewed assertions, not a score or a
measure of how much useful information an arm produced. Repeated structured facts
and shared coordinator context are included; different output formats have
different numbers of assertions.

## Method and limits

The method is model-assisted claim-level inspection through Codex, with offline
recomputation of structured diagnostic facts. There was no independent human
adjudication, calibrated reviewer-accuracy measurement or additional provider API
call. Case and configuration labels were visible to the reviewer; this was not a
blinded assessment. The reviewer can make mistakes. Contrary judgments can be recorded separately
without editing the original campaign or this review.

The inspection covered each complete final dossier, the complete original/fresh
claim lineage, worker dispositions, coordinator decisions, inert follow-up questions,
conflict arrays and reconciliation decisions. Sentence/semicolon clauses separate
compound passages; their rationales check the stated observations, source properties,
policy attribution, causal qualifications and scope. Structured sections enumerate
each non-reference scalar or empty collection. Their scenario outcomes, membership,
completeness, absence counts and item-date comparisons were independently compared
with the captured observations, synthetic fixtures and separately declared expectations.
Nested hash/reference metadata is covered by the packet's artifact bindings.

Case input and output identities remain separate from evidence conclusions. Whole
dossier containers delegate material content to their reviewed sections. Host identity,
authority and inert selection fields do not assert compatibility. Reconciliation
delegation is allowed only when both terminal answers exactly match the reviewed
final dossiers. Shared coordinator context is retained in both case records, with
its other-case material covered by that case's review.

An empty recorded conflict array does not prove absence of semantic errors. A
successful citation or read does not satisfy a criterion. Earlier claims remain
reviewed even when not cited by the final answer. Semantic endorsement is a recorded
reviewer judgment, not inferred automatically from `CITED`/`NOT_CITED` labels.
Unsupported earlier claims still endorsed by a final answer block its acceptance;
explicitly unendorsed claims remain in the historical error inventory.

## Recorded judgments

Each case must meet every frozen criterion, answer with the expected disposition,
have complete declared review coverage and have no material unsupported or unresolved
endorsed claim. Omitting required reasoning is recorded as `NOT_SATISFIED`, separately
from an unsupported assertion. Correctly admitting an unknown cause is a supported
limitation, not a claim that the cause was established.

| Configuration | Customer criteria, each repetition | Subscription criteria | Cases meeting all criteria and claim gates |
| --- | --- | --- | --- |
| Deterministic diagnostics | Outcomes and absence preconditions satisfied; null-guard explanation missing | All three satisfied in all repetitions | 3 subscription cases |
| Single agent | Outcomes satisfied; absence and null-guard explanations incomplete | Outcomes satisfied; full selection/shortcut explanation missing; policy attribution also incomplete in repetition 1 | 0 |
| Independent workers | Outcomes satisfied; absence and null-guard explanations incomplete | Outcomes satisfied; full selection/shortcut explanation missing; policy attribution also incomplete in repetition 1 | 0 |
| Coordinated team | Outcomes satisfied; absence and null-guard explanations incomplete | Outcomes and policy scope satisfied; full selection/shortcut explanation missing | 0 |

No complete two-case bundle meets the frozen acceptance requirements. These are
descriptive judgments on this packet; the [paired resource/latency report](coordination-results.md)
retains them separately from operational measurements. A scoped routing decision
remains subsequent work. They do not establish general superiority,
held-out accuracy or production readiness.

The common customer omission is substantive: retaining the two reference failures
does not explain why the historical null guard skips source synchronization while
leaving a known legacy ID for the later deletion loop. Most answers also do not
distinguish omitted embedded data from presence and separately established complete-list
absence. The deterministic customer dossier covers that distinction, but explicitly
does not analyze source control flow.

The deterministic subscription matrix supplies actual first-item/minimum/maximum
counterexamples and membership/completeness checks. Merely mentioning item-level
periods or naming passing safeguards does not fulfill that complete requirement.

### Material claim issues retained

- **Slot 10, customer, original `claim-3`:** its assertion that the baseline flow
  aligns with an evidence-before-deactivation gate is unsupported as a source
  property. Listing collections and deleting unmatched records does not itself
  establish completeness. Five passing baseline fixtures cannot supply that property.
- **Slot 10, customer, original `claim-5`:** the description of the baseline as
  “evidence-gated” is likewise unsupported. The final answer still cites both
  original claims and does not explicitly reject those assertions. Their supported
  source-flow observations and qualified target hypothesis are retained separately.
- **Slot 9, subscription, final hypothesis:** its definite “therefore defer” clause
  remains unresolved as an actual target return. Source plus field placement supports
  that prediction, but captured execution records assertion outcomes and dispositions,
  not the target's actual returned value. The `HYPOTHESIS` label does not establish it.

The campaign reports these operational outcomes without these later semantic
judgments. Its original `qualityReview: NOT_PERFORMED` fields, final outputs,
registration, rubric and all prior evidence remain unchanged.

## Verify the bindings

With the original trusted state store retained:

```powershell
./scripts/Verify-CoordinationSemanticReview.ps1
```

`-ReviewPath` selects another review; `-StateDirectory` selects the local evidence
store. The command reads the existing sealed packet/campaign, verifies source hashes,
case and unit coverage, exact pointer values, claim references, criterion membership,
reviewer provenance and acceptance-gate consistency, then returns the exact review
byte digest. Missing or changed evidence
stops verification. All sources must belong to the packet; traversal and linked
paths are refused. Review files are limited to 1 MiB. No credential is loaded, no
participant is dispatched, and no new comparison report or execution permission is
created.

`SUPPORTED_WITHIN_RECORDED_SCOPE`, `UNSUPPORTED` and `UNRESOLVED` judgments remain
as supplied. The checker does **not** decide semantic truth or prove the reviewer's
decomposition is exhaustive. `coverageComplete` is the reviewer's explicit coverage
attestation; mechanical coverage prevents absent units from being disguised as a
complete review. The binding hashes assume a trusted host and are not signatures
against replacing the entire store or evidence/review together.

The synthetic control suite runs on Windows and Linux:

```powershell
./scripts/Test-CoordinationSemanticReview.ps1
```

Its controls reject altered/missing/duplicate bindings, missing criteria, hidden
material claims, omitted container coverage, divergent reconciliation and excluded
nonempty conflicts. Both material `UNSUPPORTED` and `UNRESOLVED` verdicts block
acceptance while remaining valid review records. These are integrity/gate tests,
not evidence of semantic accuracy.

## Subsequent comparison export

The paired report must reference the exact review bytes and preserve these
criterion judgments and unsupported/unresolved counts. The existing comparison
metadata type has an unsupported-claim count but no unresolved-material-claim
count. An exporter must carry unresolved material claims as an unresolved quality
gate; it must not pass `claimAuditComplete: true` and silently treat them as zero
errors. Completing this inspection does not resolve that evidence uncertainty.

Usage, elapsed times and all planned denominators still come from the original
operational records. Review work is separate from participant usage. No routing
rule or coordination-benefit claim is selected by the binding checker.
