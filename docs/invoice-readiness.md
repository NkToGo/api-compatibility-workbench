# Structural invoice read readiness

`InvoiceReadiness` is a pure, package-local Java component for disclosing access
progress over supplied read metadata. Its output is version 1 with policy
`invoice-readiness-v1`. It implements the first boundary in the
[protocol follow-up](invoice-protocol-follow-up.md); no investigator currently
receives this output. Navigation and version 2 runtime admission remain separate
work. Existing prompts, tools, finish guards and stored trials are unchanged.

## Inputs and ownership

A `Subject` names one participant, one exact captured input identity and one
host-selected scope: `JOINT`, `WEBHOOK` or `RECONCILIATION`. Supply that subject,
typed `TextPage`/`CataloguePage` metadata, and the captured
`sourceExecutionCoverageSatisfied` flag to `inspect`.

Every page must match the entire subject. A sibling participant's reads, another
input or a changed role are rejected instead of pooling their citations. Page
IDs must be unique `read-1` through `read-999`. Repeated ranges with different
page IDs are permitted; repeated IDs, even across text and catalogue pages, are
ambiguous and rejected. The component accepts at most 100 pages. This bound is
not permission to exceed the runtime's aggregate read allowance.

Text metadata includes the logical alias, declared artifact SHA-256 identity,
UTF-16 start/next offsets and total length. A page spans at most 4,000 characters;
the total is bounded at 1,048,576. Invalid ranges, malformed logical aliases and
conflicting identities or lengths for one alias are rejected. Distinct aliases
with identical bytes remain distinct artifacts. No filesystem path is opened.

These are supplied metadata contracts, not authenticated receipts. The component
does not read content, calculate artifact hashes, fetch evidence or validate the
capture's execution assertions. A future adapter must bind the subject to its
authorized runtime registration and supply verified retained reads; accepting an
agent-authored readiness snapshot would bypass that boundary.

## Output and conservative coverage

`inspect` produces an immutable snapshot. `toJson()` produces a detached JSON
object, including explicit nulls for unknown values, and refuses compact UTF-8
JSON above 16 KiB. Additional formatting can expand that representation.
Output order is stable regardless of supplied page order; page IDs use
numeric ordering. No source contents, payloads, model prose or private reasoning
are included.

| Field | Meaning |
| --- | --- |
| `requiredReads` | Required task, configuration and baseline sources with `UNREAD`, `PARTIAL` or `COMPLETE` coverage |
| `coveredRanges` / `missingRanges` | Union of observed text intervals and its known gaps; end offsets are exclusive |
| `businessEvidence` | Counts of observed/complete eligible business artifacts and the first complete alias in lexical order |
| `orderEvidence` | The analogous summary for order artifacts |
| `citableTextPageIds` | This participant's supplied text-page IDs; ID eligibility is not semantic support |
| `nonCitableCataloguePageIds` | Catalogue IDs, which cannot support prose claims |
| `structuralAccessReady` | Whether the supplied access floor is met, including the supplied execution-coverage flag |
| `qualityReview` | Always `NOT_PERFORMED` |

Unseen required artifacts keep null identity and total length. Their empty range
arrays mean length is unknown, not that no content is missing. A supplied page
for an actually empty artifact is distinct: it has known zero length and complete
structural coverage. An empty read at the end of a nonempty artifact cannot
invent coverage of its preceding content. Overlapping, adjacent and repeated
intervals merge without hiding gaps.

Joint scope requires both baseline consumer sources. A role requires its own
baseline source. Every scope requires complete task/configuration reads, one
complete eligible business artifact, one complete order artifact and a true
supplied source-execution-coverage flag. Role business aliases must end with the
matching `/webhook` or `/reconciliation` suffix. Only aggregate counts and one
lexical witness are exposed for these groups, keeping disclosure bounded rather
than injecting an expanding artifact inventory into context. The witness is not
a ranking or claim that it supplies sufficient business evidence.

## Authority and integration boundary

`structuralAccessReady: true` means access readiness only. It does not establish
that a summary or finding exists, its citations support it, the six review
criteria are satisfied, or the migration is safe. The existing finish validator
still checks the actual submitted dossier. An honest abstention remains possible
without the answered-task access floor; readiness does not fabricate that answer.

The fixed authority is `supplied-read-metadata-not-semantic-acceptance`. The
component has no runtime, broker, evidence-store, scheduler or credential handle.
It dispatches nothing, consumes no extra model opportunity and grants no tool,
execution or publication permission. When integrated later, its metadata must
fit the existing context-byte ceiling rather than adding an uncounted channel.
Version 1 investigation contexts and historical results are not rewritten.

Adding compiled classes changes the whole-build fingerprint used by admission,
even though the version 1 investigation logic is untouched. This build cannot
silently resume unfinished work admitted under another runtime identity. Retain
the archived original runtime for historical inspection and register future
measurements under their own exact build and policy identities.

## Verification

Run the README's full Java verification commands. `InvoiceReadinessTest` covers
unknown/empty input, role and joint scope, partial/overlapping/repeated reads,
identity/length conflicts, cross-subject reads, catalogue citations, duplicate
IDs, count/range/output bounds and immutable deterministic output. It also checks
valid access against the existing finish guards and preserves the version 1
context fields. These are structural controls, not a quality evaluation.

No revised participant condition or improved coordination has been measured.
[Milestone 4 acceptance](milestone-4-status.md) remains open. The next boundary
is the versioned decision/context and host-admission contract. A separate
[prefix catalogue boundary](invoice-prefix-catalogue.md) now supplies counted
navigation; agent integration and new campaign registration follow separately.
