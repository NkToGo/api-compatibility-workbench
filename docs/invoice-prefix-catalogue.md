# Bounded invoice catalogue navigation

A separately admitted read-only host API can now filter the captured invoice
catalogue by a literal alias prefix. This implements the navigation boundary in
the [protocol follow-up](invoice-protocol-follow-up.md). It uses the same captured
inventory and retained read accounting. Version 1 investigator schemas, prompts
and contexts do not expose this tool. The separate
[single-context v2 investigator](invoice-single-agent-v2.md) now uses prefix
navigation through its model-enabled admission path.

## Explicit admission

Open `CoordinationRuntime.forInvoiceNavigation(state, directory, expectedIdentity)`
against one deliberately selected captured input. Register an explicit
supplied-record definition using `invoiceNavigationReadPolicy(maxReads, participants)`
and its `implementationIdentity`, following the [runtime registration contract](invoice-comparison-runtime.md).
This registers a navigation trace, not an adaptive comparison campaign or accepted
answer. The policy allows 1–100 shared evidence reads and no participant model
protocol. The factory cannot create a model broker and needs no credential.

Navigation trials live under `invoice-navigation-trials/`. The old factories
continue to use `invoice-comparison-trials/` and their original tool identity.
Neither tool identity can register under the other boundary. The new boundary
also revalidates its retained policy on every specification load: copying an old
registration into the new directory cannot silently expand its tool grant.
The boundary captures the current build fingerprint when opened and checks it
at registration, start and active admission. An older build reference can remain
inspectable but cannot authorize new work under a different opened runtime.
The host must keep that runtime artifact immutable while it is in use.
Original inspection behavior is unchanged.

After registration, the host operation is:

```java
runtime.catalogPrefix(runId, participantId, operationId, caseId, "source/", 0, 40);
```

The separate tool-policy identity is derived from
`invoice-describe-catalog-prefix-300-40-32768-text-4000-utf16-read-only-v1`.
The existing `PageRequest` record keeps its shape. Only the new `catalog_prefix`
kind interprets `PageRequest.alias` as a prefix; the retained page also echoes it
as `prefix`. Old `catalog` requests still require an empty alias, and `read_text`
still requires an exact captured alias. Old decision schemas refuse the new action.

## Query and page semantics

Filtering is case-sensitive `startsWith` over the closed captured inventory,
followed by lexical ordering and pagination within that filtered list. It is not
substring, wildcard, regular-expression or semantic search. For example,
`source/webhook-bas` includes both baseline and Basil source groups.

| Condition | Behavior |
| --- | --- |
| Prefix | Empty for the full inventory, or at most 300 ASCII logical-alias characters; a partial component or trailing slash is allowed |
| Traversal and other syntax | Leading slash, backslash, empty interior components, `.`/`..` components, control characters and wildcard syntax are rejected |
| Offset | Zero through the number of matching aliases, inclusive; it is not an offset into the unfiltered catalogue |
| Page size | 1–40 rows; an offset at the filtered end returns an empty page |
| No matches | An empty page describing no matching captured alias; it says nothing about files outside the capture |
| Payload bound | At most 32 KiB of compact UTF-8 JSON; the existing 64 KiB retained-page-record bound also applies |

The page's version is 1 and policy is `invoice-prefix-catalogue-v1`. It includes
the exact `bundleIdentity`, query prefix, filtered offset/next offset,
`matchingArtifacts`, `inventoryArtifacts` and rows containing only alias,
artifact SHA-256 and UTF-16 length. Empty-prefix rows match the legacy catalogue.
No source text, input payload, protected expectation or grade is disclosed.
Results are detached objects over the immutable open capture. Changed disk input
cannot alter that open view and fails reopening under the original expected hash.

Catalogue authority is `catalogue-metadata-not-content-evidence`. Finding an
alias does not constitute reading its content. These pages remain non-citable
catalogue metadata; the [readiness component](invoice-readiness.md) and future
adapters must preserve that distinction. Existing capture-time capability flags
are not live statements about which later adapters are running.

## Accounting, replay and failures

Describe, flat catalogue, prefix catalogue and text pages all use the same
registered read allowance and durable deadline. An empty result consumes a read.
The participant, operation ID, case, bundle, kind, prefix, offset and limit are
retained together. An exact retry reuses one operation after restart without
another charge; changing any query field under that operation ID is rejected.
Concurrent participants cannot exceed the shared limit. Invalid queries and
rejected scope checks do not consume a read, although an attempted operation can
start the existing whole-trial clock.

Revocation, expiry and terminal-state checks apply even to cached retries.
Changed retained prefixes or rows and incomplete publication remain visible as
errors. Completed inspection verifies the retained hashes. These are trusted-host
integrity checks, not signed protection against an operator rewriting every
record and expected identity.

Adding code changes the whole-build fingerprint used by admission. Preserve the
archived original runtime and records; unfinished work cannot silently resume
under a different build. No original grant, campaign result, rubric, review or
input artifact is rewritten by introducing this boundary.

## Verification and next boundary

Run the README verification commands. `InvoicePrefixCatalogueTest` covers
literal/empty/partial prefixes, stable pagination, metadata limits, shared and
concurrent accounting, restart/retry identity, scope rejection, old-tool refusal,
model denial, retained-policy checks, tampering, immutable views and terminal
controls. Those controls establish navigation behavior, not better model answers.

The separate [v2 contract](invoice-v2-contract.md), [admission path](invoice-v2-admission.md)
and [single investigator](invoice-single-agent-v2.md) are now implemented.
Independent-worker and remaining adapter integration follow before any newly
frozen participant measurement. All original comparison criteria, budgets and negative
results remain intact. [Milestone 4 acceptance](milestone-4-status.md) stays open.
