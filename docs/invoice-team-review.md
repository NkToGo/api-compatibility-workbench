# Invoice coordinator review boundary

`InvoiceTeamDecision` and `InvoiceTeamReview` define the coordinator's review
boundary for the captured invoice case. They preserve the original webhook and
reconciliation reports, validate references and select bounded, inert follow-up
questions. The broker can transport the strict decision protocol through an
explicit invoice participant grant. These pure components make no calls on their
own. A separate [durable invoice runner](invoice-team-runner.md) now uses them for
original investigations and review. Follow-up dispatch, reconciliation and
completed comparison export remain separate work.

The [independent workers](invoice-independent-workers.md),
[single-context investigator](invoice-single-agent.md) and
[deterministic baseline](invoice-diagnostics.md) remain distinct arms. A review
of their reports is not a paired measurement or evidence of coordination benefit.

## Original worker outcomes and identities

The caller supplies both fixed role outcomes from host-verified histories, using
the same exact invoice bundle. The component copies incoming JSON. A completed
worker must supply a finished dossier satisfying the existing role-specific
citation and access guards. An answered dossier needs its task, configuration,
own baseline source, a business artifact for that role, an order artifact and
complete source execution coverage. These are access checks, not semantic review.

A completed abstention preserves its summary and findings. Failed, cancelled and
unknown workers supply no completed dossier and remain `NOT_PROVIDED`. A stopped
worker's recorded outcome can anchor a follow-up question without inventing an
answer. Partial source pages remain part of the supplied history's identity.

The coordinator receives every original summary and finding, including limitations
and abstention explanations. Stable role-sorted references distinguish:

| Reference | Meaning |
| --- | --- |
| `worker-N` | Host-recorded original disposition, role, response and bundle/report identity |
| `claim-N` | Original summary or finding with its source position and worker-page lineage |
| `read-N` | Text page read by the coordinator, within its own read history |

An original worker's `read-N` is not a coordinator citation. Claim access proves
only that the model statement was disclosed. Canonical report identities bind
the input bundle, role, disposition data, decision, source metadata and exact
read-page identities. Unknown metadata is not disclosed as grading feedback.

## Paged context and integrity limits

The context retains all original claims and outcomes, all coordinator read
metadata, and its latest six catalog/text page contents. Oldest contents are
removed first to meet the registered UTF-8 byte limit. Claims and metadata are
never silently replaced by a generated summary or selected best subset. If they
cannot fit, construction stops with `CONTEXT_BUDGET_EXHAUSTED`.

Page guards check exact shape, unique IDs, integer offsets, the 40-alias catalog
and 4,000-character text bounds, consistent artifact identities and overlapping
text. Completely reassembled text must match the artifact's SHA-256. Metadata
also binds each complete page object, including partial content. Catalog entries
are discovery data and cannot supply a text citation.

These are pure guards over caller-supplied objects. A partial page's claimed
artifact hash cannot authenticate its contents. The caller must verify shared
runtime origin, bundle membership and durable receipts before importing pages.
Neither the pure guard nor a content hash proves that a producer is trustworthy.
The [runtime](invoice-comparison-runtime.md) remains responsible for allowed
aliases, counted reads, fixed participant scopes and the original deadline.

## Strict decisions and host selection

`invoice-team-review-v1` uses the forced `invoice_team_review_decision` function.
It permits the existing bounded `catalog` and `read_text` actions or a finished
review. The strict parser rejects extra fields, duplicate JSON keys, trailing
content, unsupported actions, malformed references and excessive structures.
Model output contains advisory claims and questions, not private reasoning.

A finished review contains a cited summary, zero to two questions and zero to two
possible conflicts. No question or conflict is mandatory. Every question targets
one fixed worker ID, at most once, and must cite an original claim or outcome
from that target role. Additional claims or pages from the other role are allowed:
both consumers belong to the same joint case and affect the same renewal ledger.
Reusing the older two-case restriction here would incorrectly hide that relation.

The host accepts all valid questions once, sorts by worker ID and assigns stable
`follow-up-N` IDs, the unchanged case/bundle identity and delegation depth one.
The selected record is inert data. It cannot grant permission, dispatch a worker,
add tools, change a model or budget, renew a deadline or create recursive work.
Question usefulness still needs separate semantic review.

Each possible conflict links two distinct existing original claims. Duplicate
pairs, including reversed pairs, are rejected. Cross-role pairs receive
`SAME_CASE_DIFFERENT_ROLES_SCOPE_UNESTABLISHED`; same-role pairs receive
`SAME_ROLE_SCOPE_UNESTABLISHED`. Both remain `UNRESOLVED`. Role, version or
assumption differences may explain the statements. Model agreement and valid
references cannot establish a contradiction, resolve it or approve a migration.
The plan preserves all original claims, the complete review decision, selected
questions, conflict records and reference identities.

## Budget and permission boundary

The coordinated invoice arm will reuse `CoordinationTeamBudget` unchanged:

| Stage | Initial opportunities | Cumulative ceiling at 60 calls |
| --- | ---: | ---: |
| Original investigations | 24 | 24 |
| Coordinator review | 6 | 30 |
| Fresh follow-ups | 24 | 54 |
| Reconciliation | 6 | 60 |

Unused reservations move forward under the original total ceiling. Counts include
uncertain and known-not-dispatched reservations, not only completed responses.
For example, 11 original calls leave up to 19 for review. Four review calls then
leave up to 39 for follow-ups and, if none run, 45 for reconciliation. The shared
100-read ceiling and 20-minute whole-trial deadline are not renewed by a stage.
The pure arithmetic does not itself enforce stage transitions around dispatch;
the durable runner derives original/review counts from sealed operations and the
runtime. Future follow-up/reconciliation stages must retain that same ceiling.

The explicit `forInvoiceInvestigation` entry point now accepts this review
protocol in a participant with exactly one declared protocol. A participant
cannot select its sibling's or a historical protocol. `forInvoice` remains
read-only and rejects model-enabled registration. The broker forces only this
function, rejects a worker function or multiple functions, retains usage and
excludes private reasoning. This adds no shell, external-network tool, source
edit, fresh consumer execution or permission-management tool.

## Validation and remaining work

Offline tests cover joint-role lineage, absent/abstained workers, all-claim
retention, explicit target anchors, unseen citations, discovery-only catalogs,
strict transport and parser rejection, page integrity, byte trimming, defensive
copies, explicit grants, shared call/read accounting and the unchanged stage
arithmetic. They use synthetic local objects and loopback HTTP, with no paid
model calls or new consumer execution.

There is no standalone command for these pure components. The durable runner
provides original/review execution and recovery. The separate
[complete invoice adapter](invoice-team-comparison.md) now executes selected
fresh follow-ups and final joint reconciliation under a full policy registered
before any work. Its final trial remains unreviewed; that policy must be fixed
before a separately frozen campaign. Semantic
acceptance, measured benefit and Milestone 4 acceptance remain open. The historical
two-case campaign and its negative coordination result remain unchanged.

A recorded-history check on 2026-10-22 imported both dossiers and 40 read pages
from the completed independent-worker check. It preserved all 13 original claims
and validated two synthetic inert questions with cross-role references. The
457 prior worker/runtime files and 446 original input files remained unchanged.
This was a host-boundary inspection with zero new model calls or consumer
executions; no coordinator generated those questions and no semantic review was
performed.
