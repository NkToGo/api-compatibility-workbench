# Invoice v2 runtime and broker admission

Step 4e5e adds a separately admitted runtime for the
[v2 investigation contract](invoice-v2-contract.md). It supports the joint,
webhook and reconciliation investigation protocols. It does not run a concrete
comparison adapter, select follow-ups, execute returned decisions or accept an
advisory dossier. No new participant measurement or quality improvement is
established by these operational controls.

## Host-selected boundary

`CoordinationRuntime.forInvoiceInvestigationV2` opens one exact immutable input
bundle under `invoice-v2-trials`. The input manifest and capture artifacts stay
unchanged. `invoiceInvestigationV2Policy` constructs the required host policy:

| Reference | Required binding |
| --- | --- |
| Adapter | Current opened runtime build fingerprint |
| Prompt | Canonical map of the three scoped v2 contract identities, including the shared public task |
| Tools | `invoice-describe-catalog-prefix-300-40-32768-text-4000-utf16-investigation-v2` |
| Context | SHA-256 of `invoice-owned-read-context-v2` |
| Final selection label | `invoice-v2-advisory-contract`; this does not implement a selection algorithm |
| Participant scope | Exactly the invoice case and one explicitly selected v2 protocol per participant |

The policy retains the existing two-call concurrency ceiling. Reads are limited
to 100. A definition may impose lower limits, but cannot exceed 60 shared model
calls, twenty minutes, 64,000 input bytes per call or 2,048 output tokens per call.
The arm implementation must match the complete declared policy identity.
Deterministic-arm registration cannot receive these model protocols.

Registration rejects changed prompt, tool, context, selection and build
references before publishing a trial. Retained specification inspection also
checks policy identities and definition ceilings. Active work additionally
checks the opened current build. Runtime artifacts must remain immutable while
open. Hashes and local records are trusted-host integrity checks, not signed
protection against an operator rewriting the code and every expected identity.

The legacy, invoice read-only, v1 investigation and prefix-navigation factories
reject v2 protocol grants at registration and retained-state loading. Copying
registration files between namespaces cannot activate the new protocols through
an old factory, or promote an old read-only grant through the v2 factory.
Existing v1 records retain their original behavior and interpretation.

## Model transport and request identity

The broker recognizes the three scoped v2 protocol IDs. It uses the exact
scope instructions, strict v2 schema and forced
`invoice_investigation_decision_v2` function with parallel function calls
disabled, `store: false` and `background: false`. Response extraction accepts
exactly one call of that function. Historical/unknown function names, multiple
calls and absent function output are rejected; private reasoning items are not
retained. Existing v1 function/schema routing stays separate.

A host-supplied request must match the participant's granted protocol, declared
model, input-byte limit and output-token limit. Its complete generated body is
bound into the durable reservation and broker grant. Changing input or role
under the same operation ID is rejected. Changing the request after broker
approval invalidates its grant before credential lookup or transport.

The generic runtime binds approved request bytes; it does not reconstruct the
request's context or decide whether its contents are a good investigation.
Concrete adapters must build the typed v2 context from their admitted owned
reads, validate returned decisions and enforce their finish/stage rules. Those
loops are separate integration work. A correct function response with malformed
decision arguments remains available as a transport result and fails the
decision parser; it is not silently converted into accepted findings.

## Accounting, recovery and closure

Describe, catalogue, prefix catalogue and text pages share the registered read
pool and whole-trial deadline. Model reservations share the declared call pool
across participants. Exact completed calls and exact prefix reads survive restart
without new dispatch or charges. Prefix rows remain non-citable metadata.

An interrupted registration consumes its durable reservation even if the
controller never retained the broker mapping. It stays uncertain and is not
guessed or redispatched. Completed broker outcomes survive controller
interruption; unknown remote outcomes remain unknown on retry. Revocation,
expiry and terminal-state checks apply even to otherwise cached operations.
Historical failed/missing work and unresolved usage remain visible.

A supported retained policy from another build can be inspected and closed
without granting new work. It cannot start or resume calls/reads under the
current build. Completed inspection verifies retained identities and hashes;
closing an operational trial establishes no semantic acceptance.

## Verification and next boundary

Run the README verification commands. `InvoiceV2AdmissionTest` covers exact
policy/definition admission, namespace separation in both directions,
participant scope, shared budgets, prefix reads, interruption, restart,
uncertainty, tampering and terminal controls. Its loopback HTTP check sends a
typed owned context through the real credential broker, verifies restart and
closure, and makes no provider request or consumer dispatch. Additional
`ModelCallBrokerTest` controls exercise actual v2 wire requests, function
rejection, role changes and retained malformed decisions without publishing
credentials or private reasoning.

Next, integrate the single-context invoice investigator with v2 navigation,
readiness and its host-validated finish gate. Independent workers, team stages
and the deterministic baseline follow in separate reviewable changes before
freezing a new common condition. Keep the original model, ceilings, baselines,
six review criteria and historical negative results. No campaign registration,
routing promotion or [Milestone 4 acceptance](milestone-4-status.md) follows from
this admission step.
