# Captured-team review boundary

The coordinated comparison arm needs a review boundary that uses the same captured
inputs and whole-trial budget as the other arms. `CapturedTeamDecision`,
`CapturedTeamReview` and `CoordinationTeamBudget` implement that boundary's decision
format, host guards and allocation arithmetic. The [durable runner](coordination-team-runner.md)
now uses them for original investigations and coordinator review. Fresh follow-up
execution, reconciliation and comparison export are not implemented yet. These
pure components make no calls or completed team trial on their own.

The older [coordinator](coordinator-review.md), [follow-up dispatcher](follow-up-dispatch.md)
and [reconciliation](follow-up-reconciliation.md) remain separate product commands.
Their independent permissions and session limits cannot replace the common
[comparison runtime](coordination-runtime.md) or supply a fair team measurement.

## Original reports and review context

The host supplies both case workers' terminal outcomes from verified histories.
Each completed dossier must satisfy the existing single-case decision and citation
guards. An answered dossier requires complete baseline-source and execution page
coverage. A failed, cancelled or uncertain worker supplies no completed dossier;
its missing answer remains `NOT_PROVIDED`. A completed abstention remains
`ABSTAINED`, with its summary and findings preserved.

The component copies incoming JSON and produces stable case-sorted references:

| Reference | Meaning |
| --- | --- |
| `worker-N` | Recorded original worker disposition, response and input/report identity |
| `claim-N` | One original summary or finding, including kind, source position and worker page citations |
| `read-N` | One successful page read by the coordinator, not an imported worker page ID |

Every original summary and finding appears in the context; there is no best-answer
selection, grading feedback or generated compression. Worker page IDs record
lineage but do not become eligible coordinator read citations. A claim citation
means access to that model statement, not proof that it is true. Report/reference
identities use canonical JSON and include exact case inputs and read metadata.

Coordinator contexts contain all its prior page metadata and the latest six page
contents. The oldest content is removed first to meet the registered UTF-8 byte
ceiling; metadata and original claims remain. If those cannot fit, construction
stops with `CONTEXT_BUDGET_EXHAUSTED`. This never creates an additional summarizing
call. Captured pages are checked for scope, shape, content hash, offsets, unique
read IDs and consistent artifact identity/length. The caller must still verify
their shared-runtime origin and durable receipts; these pure guards do not
authenticate a caller-supplied page or prove source semantics.

## Decisions and host-selected questions

`captured-team-review-v1` uses one forced strict function,
`captured_team_review_decision`, with exactly one of two actions:

| Action | Host boundary |
| --- | --- |
| `read_evidence` | A listed case/input alias, UTF-16 offset and at most 4,000 characters; no path, execution or delegation action |
| `finish` | Cited summary, zero to two follow-up questions and zero to two possible conflicts |

The broker extracts only that function's arguments and usage, ignoring model
reasoning. A different function, multiple functions or an invalid response is
rejected. The parser rejects duplicate JSON fields, trailing input, unexpected
properties, unavailable actions, invalid types and excessive structures.

Each follow-up requires at least one original claim or worker outcome from its
own case. All its references must exist and belong to that case, including any
coordinator page citations. A stopped worker's recorded outcome can anchor a
question without inventing an original answer. There may be only one question
per case. The host selection rule accepts all valid questions once, sorts them
by case ID, assigns `follow-up-N` IDs and binds each to its unchanged input
identity. It does not choose questions using evaluation answers.

The selected questions are inert data at delegation depth one. They cannot add
tools, change the model, renew deadlines, grant permission or create recursive
work. The future runner must separately admit their fresh workers through the
already registered case scopes and common runtime. No such dispatch happens in
this component or the current runner.

A possible conflict must link two distinct existing original claims; duplicate
pairs are refused, including reversed pairs. Different cases receive
`DIFFERENT_CASE_IDS`; same-case pairs receive `SAME_CASE_SCOPE_UNESTABLISHED`.
Both remain `UNRESOLVED`. Matching inputs, valid references, agreement and the
coordinator's wording cannot establish contradiction, resolve a conflict or
approve a migration. The full original claims, review decision, selected questions,
conflicts and reference bindings remain separately visible in the returned plan.

## Whole-trial stage allocation

For the declared 60-call ceiling, the allocation is:

| Stage | Initial opportunities | Cumulative ceiling |
| --- | ---: | ---: |
| Original case investigations | 24 | 24 |
| Coordinator review | 6 | 30 |
| Fresh follow-up investigations | 24 | 54 |
| Reconciliation | 6 | 60 |

Unused opportunities move forward. If investigations reserve 11 calls, review
can reserve up to 19; if review uses four, fresh workers have up to 39. If no
follow-up is selected, reconciliation can use the remaining 45. Its maximum is
always the original ceiling minus all earlier reservations. No stage receives
another 60-call pool or inherits an unrelated 12-decision session cap.

The allocation supports ceilings from six to 500: review and reconciliation
each receive `max(1, floor(ceiling / 10))`; the remainder is divided between the
original and fresh workers, with an odd extra opportunity assigned to fresh work.
Counts are reservations, including uncertain or known-not-dispatched slots,
not just successful responses. Cumulative overruns and counts from a later stage
are rejected. The arithmetic neither starts nor renews a clock or grant.

This pure component does not enforce stage transitions around actual dispatch.
The durable runner derives original/review counts from sealed operations and the
shared runtime, retains the original-report barrier, handles interruption and
enforces their cumulative limits before each new reservation. The whole-trial deadline and per-request bounds
continue to belong to that runtime. All prompt, context, allocation, selection,
reconciliation and final-output rules must be frozen in the final arm identity
before the paired campaign begins.

## Validation and remaining work

Tests use synthetic local objects, fake runtime brokers and loopback HTTP. They
cover case-bound selection, stopped outcomes, abstentions, complete original
coverage, all-claim retention, unknown/foreign references, duplicate questions and
conflicts, unresolved comparability, immutable imports, changed pages, context
trimming, strict transport extraction, explicit participant permission and shared
call accounting. They make no paid API or new consumer execution call.

These checks establish host-guard behavior, not question usefulness, explanation
quality or coordination benefit. Review remains `NOT_PERFORMED`. Original-worker
and review execution now exist in the durable runner. The next step is fresh
investigations, reconciliation and unreviewed comparison export. The fixed paired campaign and
its separate semantic acceptance reviews remain unrun.
