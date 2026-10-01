# Claim-level review of missing-evidence explanations

This review assesses the visible summaries and findings from the 12 trials in
`evaluation-7271379f-19c0-4ce7-a479-c8f08ed2210f`. It checks what each explanation
actually claims against the cited tool events, complete recorded event history,
registered execution mode and investigation-v2 requirements. An `ABSTAINED`
status or a valid citation alone was not credited as semantic support.

The method was model-assisted claim-level inspection. No independent human
adjudication or calibrated estimate of reviewer accuracy has been performed.
The judgments are limited to operational abstention reasons on these two curated
synthetic cases, not repair quality, live provider availability or general model
accuracy. A later contrary review can remain a separate record.

## Results

All 12 summaries and all 13 findings are supported within the recorded scope.
No material unsupported claim was found in those explanations. All findings are
limitations; none claims a provider defect, a passing migration or a verified repair.

| Case | Withheld evidence | Reviewed supported abstentions |
| --- | --- | --- |
| Customer sources | Source | 3/3 |
| Customer sources | Execution | 3/3 |
| Subscription periods | Source | 3/3 |
| Subscription periods | Execution | 3/3 |

These judgments support the four missing-evidence cells of the
[Milestone 3 checklist](milestone-3-acceptance.md) under this review method. They
do not close the milestone by themselves. The later [combined acceptance report](milestone-3-results.md)
includes fresh subscription repair trials and sufficient-evidence review, with
one investigation excluded from acceptance.

## Individual judgments

Event IDs below are relative to that trial's agent. The
[structured review record](evaluations/missing-evidence-review-2026-09-30.json)
contains each original summary and finding, agent/context IDs, individual verdicts,
support explanations and exact source-record hashes. Every row has verdict
`SUPPORTED_WITHIN_RECORDED_SCOPE`.

| Trial | Case / condition / repetition | Reviewed observable support |
| --- | --- | --- |
| 1 | Customer / source / 1 | `event-1` and `event-2` both failed baseline-source access; no source or execution evidence was obtained. The stated reason for stopping and not requesting execution matches the history. |
| 2 | Customer / source / 2 | Both cited reads failed. The summary correctly reports missing baseline access and no completed execution, without inferring a migration cause. |
| 3 | Customer / source / 3 | Both reads failed; the statement about exhausting permitted retries matches investigation-v2. No further resource access was attempted. |
| 4 | Customer / execution / 1 | Source pages succeeded in `event-1` and `event-4`; `event-2` and `event-3` both failed execution access. The explanation does not confuse source access with behavioral verification. |
| 5 | Customer / execution / 2 | The same success/failure pattern supports the missing-execution reason. The two source pages total 8,000 of 8,207 characters; the explanation claims access, not an exhaustive source read. |
| 6 | Customer / execution / 3 | Both execution requests failed. The additional finding accurately describes only the initial source page: offset 0, nextOffset 4,000, totalCharacters 8,207. No later source page appears. |
| 7 | Subscription / source / 1 | Both cited baseline-source reads failed. The explanation stays within inability to inspect the consumer and makes no repair-quality claim. |
| 8 | Subscription / source / 2 | Both reads returned `UNAVAILABLE`; the implementation was not exposed. The summary correctly declines a compatibility conclusion or inferred migration cause. |
| 9 | Subscription / source / 3 | Both reads failed, with the allowed retry exhausted. The statement concerns missing required access, not a nonexistent consumer or provider outage. |
| 10 | Subscription / execution / 1 | `event-1` obtained source; both retained-execution reads failed. The summary explicitly describes unavailable record retrieval, and the retry-limit statement matches the protocol. |
| 11 | Subscription / execution / 2 | Source access succeeded and both recorded execution-evidence requests failed. The limitation is lack of observations available to this investigator, not failure of a newly launched process. |
| 12 | Subscription / execution / 3 | Both execution aliases remained unavailable, with no completed observation served. The no-further-retry statement matches the protocol. Source alone does not establish the required behavioral assessment. |

References to subscription "execution attempts" mean retained-evidence retrieval
through the declared `run_case` tool. Those contexts never dispatch a worker.
The retained verification exists; the experiment withheld access to its baseline
and target observations. Likewise, an injected `UNAVAILABLE` is not evidence of a
live provider outage or absence of the source file.

Two customer trials read another source page after the two unavailable execution
requests. Those reads do not repair the missing execution evidence or invalidate
the stated reason for abstaining. This review does not grade tool-use efficiency.

## Record binding and verification

The structured record binds 70 local artifacts: the suite and original evaluation
result, all child mappings, sessions, outcomes and 32 observed tool events. It also
binds the investigation instructions, runtime and evidence contract at repository
revision `3cd608126bc95866d17330960b088356f9c6ae95`. Raw source pages and tool outputs
remain in the trusted state store; the versioned review includes only visible
explanations, judgments, references and hashes.

With the original state store and those protocol files available:

```powershell
./scripts/Verify-MissingEvidenceReview.ps1
```

`-ReviewPath` selects a review record; `-StateDirectory` selects its state store.
The checker verifies exact record identities, complete trial/claim coverage,
original explanation text, event references and consistency between per-claim
and per-trial verdicts. Missing or changed source records stop verification.
Linked artifacts and references outside the declared store are rejected.

The checker does not decide whether a rationale is supported. A correctly bound
`UNSUPPORTED` or `UNRESOLVED` judgment remains that judgment; a successful metadata
check is not an approval, semantic score or permission to execute anything. The
hashes assume a trusted single host and do not authenticate a reviewer or defend
against rewriting both evidence and review records.

The original evaluation and its `semanticReview: PENDING` fields are immutable.
The separate review supplies a later judgment for their exact recorded inputs;
it does not replace unsuccessful trials or alter model outcomes. Completed-suite
resume separately validates retained execution/context artifacts without new
model dispatch.
