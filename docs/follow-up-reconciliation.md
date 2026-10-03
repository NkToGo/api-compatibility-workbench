# Follow-up response reconciliation

A separate, read-only coordinator review can assess completed
[follow-up investigations](follow-up-dispatch.md) against their questions, link
fresh claims to earlier claims and retain explicit possible-conflict assessments.
It captures a new evidence snapshot and records advice. It does not modify the
earlier review, follow-up report, investigator findings or execution observations.

## Register, run and resume

Build the backend using the README commands. Register a completed follow-up round;
registration refuses unfinished or changed results without starting child work.
Earlier permissions need not be renewed. Registration makes no paid call and
needs no API credential.

```powershell
$review = ./scripts/Reconcile-FollowUp.ps1 -Action register -FollowUpId follow-up-EXISTING-UUID
./scripts/Reconcile-FollowUp.ps1 -ReviewId $review
```

Replace the example reference with a completed round ID. `-StateDirectory`
defaults to `.workbench/`. Registration accepts `-Model`, defaulting to
`gpt-5.6-terra`; the exact model is then fixed by the new review grant. Run and
revoke do not accept model overrides. Running makes paid requests through the
host broker using the process `OPENAI_API_KEY` or ignored `.env.local`. The script
restores the previous environment and never prints the credential.

The review lives under `.workbench/coordination-reviews/review-UUID/`, using the
existing [coordinator runtime](coordinator-review.md) with a distinct
`reconciliation-v1` protocol and `reconciliation_decision` function. The host
captures the source review definition/result and handoff, original references,
selected original/fresh task definitions, fresh outcomes and investigator results,
and host-assigned question/claim/tool/conflict references. Source grants, broker
requests, credentials and active ownership markers are not imported. After
registration, changes or deletion in the source stores cannot change this snapshot.

Reference metadata distinguishes `ORIGINAL` from `FOLLOW_UP` and retains exact
task identities. Original reference IDs and authority remain intact. The host
checks that a fresh task's case description, excluding its advisory question,
and sealed case artifact inventory match its original task. It reports
`MATCHED_REGISTERED_CASE_INPUTS` for that pair. This does not establish equal
claim scope, semantic correctness or general comparability between consumers.

## Advisory actions and deterministic guards

| Action | Host requirement and result |
| --- | --- |
| `read_reference` | Read a host-listed reference in pages of at most 4,000 UTF-16 characters |
| `assess_response` | Cite the selected question and its fresh response; record `ANSWERED`, `PARTIAL` or `INSUFFICIENT_EVIDENCE` advice |
| `relate_claims` | Link one original claim to one fresh claim from that original task's follow-up as `SUPPORTS`, `QUALIFIES`, `DISAGREES` or `INDETERMINATE` advice |
| `assess_conflict` | Cite the earlier conflict record and both original claim references; record scope/disagreement/insufficient-evidence advice with state `UNRESOLVED` |
| `finish` | Record a cited summary; derive completeness from the retained response/conflict assessments |

A reference becomes eligible only after successful page reads cover its complete
contents without gaps. Each request includes the host-derived `fullyReadReferences`
list. A schema-valid action with unseen, partial, foreign or inappropriate support
is rejected and retained in the event history, consuming a decision.

`ANSWERED` and `PARTIAL` require a fresh child outcome of `INVESTIGATED`, a cited
fresh claim, and a successful cited tool event listed in that claim's original
`eventCitations`. A summary alone or an unrelated successful tool cannot satisfy
the guard. Abstained or operationally stopped children can receive only
`INSUFFICIENT_EVIDENCE` advice. That preserves their recorded child outcome; it
does not reclassify an operational failure as a model abstention. Unassessed
questions remain `NOT_REVIEWED` in the aggregate.

Non-`INDETERMINATE` claim relationships require successful observed tool support
listed by each cited claim. Relationships retain both nodes and never replace
their text or select a winning claim. There are at most four links, with duplicate
pairs refused. Each response and earlier conflict can be assessed only once.

`SCOPE_DIFFERENCE` is allowed only for a conflict already classified by the host
as `DIFFERENT_CASE_IDS`. Matching case IDs do not prove comparability; an agent
cannot invent a scope difference to dismiss disagreement. Even a scope-difference
assessment leaves the original possible conflict `UNRESOLVED`. Claim disagreement
and agreement remain model interpretations, not proven contradiction or execution.

All captured source text, questions, findings and observations are untrusted data.
They cannot add actions, change budgets, authorize another investigator or select
a different model. No execution, repair, external lookup, publication or permission
tools exist in this review. No private model reasoning is retained or exposed.

## Results and authority

| Final advisory status | Meaning |
| --- | --- |
| `RECONCILIATION_RECORDED` | Every selected response was assessed; no earlier possible conflicts were listed |
| `RECONCILIATION_WITH_UNRESOLVED_CONFLICTS` | Every response/conflict was assessed; all earlier possible conflicts remain open |
| `RECONCILIATION_INCOMPLETE` | A cited summary finished with at least one unassessed response or conflict |

The command reports incomplete reconciliation as a failure while preserving its
partial advice. Existing runtime stops also apply: expiry/revocation, changed
identity/permission, unsupported final citations, invalid decisions, exhausted
context/decision budgets, uncertain registration and non-complete provider outcomes.

The new aggregate includes a `reconciliation` ledger binding the source round ID
and result digest. It retains original/fresh claim references, proposal-to-response
lineage, assessments, optional relationships, every earlier conflict and the
source review's original status. Its authority is `reconciliation-model-advice`,
with `semanticAcceptance: NOT_ESTABLISHED`. A stopped source review does not become
successful because a later question was investigated.

Question coverage is a model judgment even when host checks accept its citations.
`ANSWERED` is advisory, not independent semantic acceptance. Model agreement does
not establish API compatibility, approve a repair or authorize a release. Existing
protected execution conclusions remain separate. The historical follow-up report
still records `reconciliation: PENDING`; this new ledger supplies later advice
without rewriting that earlier record.

## Limits and recovery

The review has 12 decisions, 64,000 UTF-8 bytes per model input, at most 2,048 output
tokens per call and a 20-minute admission deadline. Registration captures at most
220 artifacts, 200 references and 16 MiB total; each artifact is at most 1 MiB.
Large references or numerous questions/conflicts can exceed the read/context budget.
The coordinator must preserve partial coverage rather than claim complete review.
Optional claim links do not take priority over required response/conflict coverage.

The existing review lease, expiring grant, durable reservations and broker mappings
apply. Repeat the same command and review ID after interruption or `ACTIVE`.
Unmapped reservations and ambiguous paid outcomes remain counted and are not
automatically replaced. Completed reads and aggregate-publication recovery do not
dispatch new requests, even after expiry or revocation.

```powershell
./scripts/Reconcile-FollowUp.ps1 -Action revoke -ReviewId $review
```

Revocation is permanent. An in-flight request can finish within its broker deadline;
its usage is retained and no subsequent action is admitted. Usage belongs to this
review only, with completed token totals and unresolved usage slots; earlier batch,
review and follow-up usage are not overwritten. Token totals are not an invoice.
Historical reads reconstruct actions, ledger, summary, record inventory and usage
and reject altered conflicts, claims, coverage, authority or semantic-acceptance flags.

Keep the entire review directory together on a trusted local filesystem. Hashes
bind retained records but are not signatures against an operator rewriting the
whole store. This is neither multi-user authorization nor distributed scheduling.

## Validation scope

Synthetic tests exercise fully observed question/claim/tool support, foreign claim
pairs, unproven scope differences, duplicate assessments, failed-tool and stopped-child
coverage, preserved conflicts, partial coverage, captured-source independence,
grants/expiry/revocation, active calls, unknown usage, registration gaps, publication
recovery and changed final ledgers. Loopback API tests reject other function names
and exclude reasoning items. Tests use no OpenAI credentials.

These verify runtime boundaries. The recorded spot review below addresses one
answer under its declared evidence. Later controlled comparisons address a
different question; this component alone does not measure coordination benefit
or held-out review accuracy.

### Recorded reconciliation and semantic spot review, 2026-10-03

One live `reconciliation-v1` review captured the completed customer-sources round
`follow-up-31eb258e-f12d-4f73-82c0-b3518a4f7c17`. It used the fixed
`gpt-5.6-terra` model and completed without a new investigator or case execution:

| Status | Response assessments | Claim links | Listed conflicts | Calls | Input / output tokens | Unresolved usage slots |
| --- | --- | --- | --- | --- | --- | --- |
| `RECONCILIATION_RECORDED` | 1 | 0 | 0 | 8 | 51,927 / 824 | 0 |

Six successful reference reads preceded the response assessment and final summary.
The model classified `follow-up-8` as `ANSWERED`, citing the question `ref-27`,
the fresh contract claim `ref-31` and its completed case observation `ref-35`.
Those references were fully observed and the tool event belonged to that claim.
No original claim was replaced. Zero listed conflicts does not establish an
absence of disagreement; unresolved-conflict retention is exercised separately
by the synthetic tests.

**Semantic result: the assessment and summary are not accepted as a complete
answer.** The review checked the specific question about response conditions,
all four fresh findings and their summary against the captured observations,
the curated expectations/fixtures and the historical source. It did not use
model agreement as support:

| Fresh response component | Evidence check |
| --- | --- |
| Finding 1, `ref-30` | Supported as a bounded hypothesis: the baseline dereferences `getSources()` and omitted-field target scenarios fail. The summarized execution does not establish the exact exception mechanism. |
| Finding 2, `ref-31` | Partly supported, but incomplete as an explanation of response conditions. Omission alone cannot authorize deletion; it also does not prohibit deletion once absence is established separately. The target fixtures omit `sources` in both the retained-legacy and confirmed-removal scenarios. Their separate source-list responses distinguish presence from absence; explicitly empty embedded collections occur in the baseline, not those target responses. |
| Finding 3, `ref-32` | Its statement that the historical null guard is consistent with preserving state is unsupported for the known legacy method. After skipping null sources, the code still deletes remaining local methods. The finding correctly acknowledges the two remaining failed scenarios and does not claim a validated repair. |
| Finding 4, `ref-33` | Supported limitation: the evidence is synthetic, scoped to the declared scenarios, and supplies no production API observations or confirmed runtime failure mechanism. |
| Fresh summary; coordinator assessment and final summary | Their omission-versus-explicit-absence distinction is useful, but the blanket retention wording needs the qualification above. Valid citations and correct baseline/target outcome counts do not establish a fully answered question. |

The completed observation records baseline 5/5 passing, target 1/5 passing and
historical reference 3/5 passing. These counts concern authored synthetic
scenarios. A complete condition explanation would distinguish omission from
affirmative presence and from absence established through a complete source
listing, including the separate listing when the embedded field is omitted.
It must also explain why a null check alone cannot protect a known legacy method.

This review block is a separate, evidence-based judgment about one recorded answer.
The historical model classification remains `ANSWERED`; the immutable ledger
remains `semanticAcceptance: NOT_ESTABLISHED`. Neither is rewritten to suggest
the coordinator detected the semantic problem. No repair or release is approved.
Broader review quality and coordination benefit remain unmeasured.

The reconciliation is `review-559df59d-8547-4965-b748-68e4405f6879`, with result
SHA-256 `b59fe419edf5b02d52dd3103f34a1394d766d7e9c28cf7ca68cace431c5b9e2f`.
The source round result remains
`9d0cf9bf34ce8df6f81bd3d918f00003bddd9722ab76615195338e9bcb1b1c74`;
the fresh agent result is
`3605b42b1e91bb9c55c2c201efe7f0abddc6c1d9686b6dd6c2d50c5d45f8af3e`.
Its completed case report is
`12b60ca4ec0cfecd18d9a311b165f33abbd38caeafd4b634f808cdb7a05dbd88`.
The checked expectation, fixture and historical source digests are respectively
`21c7456708d03e236dd909fbd4b76e4ad1ec6ec8d7af85d0784df6117b9bc273`,
`fe657ee4a5cc4707a1d16938059267852bc0003602e3a0f0d0ae9209a573008b`, and
`92e6e05bfca15ad3b7af3a72f164c79b2d7c56ae004168ad5a1cbbfcf282fcc0`.

Repeating the completed command preserved its result digest and the full broker
file inventory, registering and dispatching no replacement calls. The original
batch and follow-up digests also remained unchanged. The earlier completed v2
coordinator review still validates under its original protocol without changes
to its result or broker inventory. Underlying records remain in ignored local
storage; these hashes identify evidence and are not semantic-validation checksums.
