# Invoice investigation v2 decision and context contract

Step 4e5d implements a pure prospective contract over the unchanged captured
invoice inventory. It adds no model permission, new participant run or measured
improvement. Version 1 investigators, completed records, the original campaign
and its negative review retain their original meaning.

## Decisions and public requirements

`InvoiceDecisionV2` defines distinct `invoice-investigation-v2`,
`invoice-webhook-worker-v2` and `invoice-reconciliation-worker-v2` protocol IDs.
They share the `invoice_investigation_decision_v2` function and four actions:

| Action | Boundary |
| --- | --- |
| `catalog` | Unchanged flat inventory; offset 0–768 and limit 1–40 |
| `catalog_prefix` | Literal prefix up to 300 characters, filtered offset 0–768 and limit 1–40 |
| `read_text` | Captured alias, offset 0–1,048,576 and page size 1–4,000 UTF-16 characters |
| `finish` | Existing bounded advisory answer/abstention, findings and citation wire format |

The strict schema has an object envelope with nested action alternatives, every
object field required and additional properties disabled, following the
[OpenAI function-calling strict-mode contract](https://developers.openai.com/api/docs/guides/function-calling#strict-mode).
Local parsing also rejects duplicate fields, trailing content, unsupported
actions, invalid literal prefixes, fractional/out-of-range numbers and malformed
citations. Schema validation alone does not establish citation ownership, answer
truth or runtime permission. Inventory membership and filtered offset bounds
remain host checks against the opened bundle.

All scopes receive the same versioned public questions about association and
version boundaries, application-policy refusals, joint ledger effects,
source/execution distinctions and evidence limits. They contain no expected
fixture values or grading feedback. Role instructions narrow investigation focus
without inventing sibling findings or delegation rights.

`taskIdentity()` hashes the exact UTF-8 public-task bytes. A scope-specific
`contractIdentity()` hashes canonical JSON containing the protocol, function,
instructions, task, schema and named context/readiness/navigation policies.
This is a declared contract identity, not the runtime build fingerprint or a
campaign registration. A future condition must also pin the actual build,
input, adapters and limits. Changing implementation requires that new binding.

The new public task is separate from the historical `task` capture artifact.
Neither its bytes nor the original manifest are overwritten. Reading that
artifact remains part of structural readiness. Every future comparison arm
must receive the same revised public requirements; freezing that common
condition remains separate work.

## Owned reads and bounded context

`InvoiceContextV2.build` takes a host-selected participant/input/scope subject,
an immutable opened bundle, one retained describe wrapper, ordered retained
read wrappers and a host scheduling snapshot. It checks:

- Exact wrapper and request fields, participant, case, input identity and
  untrusted-data label.
- Request kind and bounds; the describe wrapper cannot masquerade as a text read.
- Exact page equality with deterministic recomputation from the opened bundle,
  including catalogue rows, hashes, aliases, offsets, lengths and text.
- At most 100 supplied reads with unique increasing `read-N` IDs; gaps are valid.

The caller must supply only records admitted and integrity-verified by the
runtime for that participant's trial. Matching supplied wrapper fields and
capture bytes cannot authenticate a grant or prove that a read was charged in
the correct trial. The context builder performs no filesystem admission, budget
reservation, model dispatch or grant issuance.

The context contains the versioned task and contract identity, subject, all
read metadata, up to six recent page contents and derived
[structural readiness](invoice-readiness.md). Oldest recent contents are removed
until compact canonical UTF-8 JSON fits the supplied ceiling, at most 64,000
bytes. Fixed metadata and readiness are never silently dropped. If they cannot
fit, `CONTEXT_BUDGET_EXHAUSTED` stops preparation. Supplied objects are not mutated.

Text IDs alone are citable; flat and prefix catalogue IDs remain non-citable.
Complete minimum reads do not repair incomplete source execution coverage or
establish a good answer. The prospective finish rule still requires host-side
ownership/citation checks and structural readiness for `ANSWERED`; parsing a
finish alone does not accept it. The separate [single-context v2 adapter](invoice-single-agent-v2.md)
now derives that gate from the same validated owned reads; the pure context
component still grants no permission.

Historical disclosure flags live under `captureMetadata`, explicitly labelled
`historical-capture-not-current-runtime-capabilities`. For example, its
`modelCalls: 0` and `comparisonAdaptersAvailable: false` describe capture, not
the later investigation. `contractActions` names the prospective wire actions;
it does not claim that they are currently granted.

## Scheduling without an invented turn allowance

The host supplies the stage's shared remaining calls before the current fair
round and its one or two active participants. The current round reserves
`min(shared remaining, active participants)` calls, including exactly one
scheduled decision for this participant. If only one reservation remains, the
host must call this builder for the selected participant only.

Future full rounds and leftover partial-round reservations are calculated after
that round, assuming peers stay active. For example, two shared calls and two
active workers mean one current decision each and no later round. Three calls
and two workers leave one partial-round reservation, without promising which
worker receives it.
The partial-round status is explicitly `PARTIAL_ROUND_UNASSIGNED`; its
`lastScheduledDecisionCertainIfPeersStayActive: false` means that a last turn
cannot be determined, rather than promising another decision.
Every future-turn guarantee remains false: deadlines,
revocation, cancellation, failure or uncertain dispatch can stop work first.
A finish consumes a decision; there is no host-written answer or free final call.
The scheduling snapshot cannot override actual cumulative runtime/stage budgets.

## Admission status and next integration

Step 4e5d introduced this contract without model admission. Step 4e5e adds a
[separately identified v2 runtime/broker path](invoice-v2-admission.md) with exact
policy matching, retained request binding and restart behavior. The old factories
continue to reject v2 grants. The [prefix-navigation runtime](invoice-prefix-catalogue.md)
remains read-only and cannot dispatch a model. The broker can now recognize v2
requests; recognition alone is not permission to dispatch.

The [single-context v2 adapter](invoice-single-agent-v2.md) is now integrated.
Independent, team and deterministic adapter integration follows in reviewable
steps. Only afterwards may a new common condition be
frozen and measured under the unchanged ceilings, model, baselines and six review
criteria. No campaign retry, routing promotion or
[Milestone 4 acceptance](milestone-4-status.md) follows from these contract tests.

Run the README verification commands. `InvoiceDecisionV2Test` covers strict
wire parsing and scoped broker request binding. `InvoiceContextV2Test` covers owned
capture matching, altered wrappers/pages, scoped readiness, non-citable
catalogues, bounded retention, scheduling and a read-only runtime boundary.

A separate credential-free check constructed prospective contexts from the owned
read wrappers present before each of the fifteen original investigations' last
calls. All fit the unchanged byte ceiling; the largest was 42,929 bytes. This
checks retained-page/context compatibility, not a rerun of those participants,
better answers or a new semantic review. The original records remain unchanged.
