# Invoice final reconciliation v2 admission

Step 4e5o adds an explicit trusted-host admission path for both original v2 roles,
coordinator review, both fresh roles and the [final joint review contract](invoice-reconciliation-v2-contract.md).
It binds model/read permissions and retained accounting. It does not execute team
stages, authenticate source barriers or export a completed team comparison.

## Separate host policy and namespace

`CoordinationRuntime.forInvoiceReconciliationV2` opens one exact captured invoice
bundle under `invoice-team-reconciliation-v2-trials`.
`invoiceReconciliationV2Policy` binds the current runtime build, canonical map of
all six prompt contracts, separate bounded-tool identity, worker/review/fresh/final
context references, both source-lineage policies and the shared team-budget policy.
Its selection label is `invoice-team-reconciliation-v2-advisory-contract`, a policy
identity rather than a completed final review.

Allowed protocols are the two original role protocols, `invoice-team-review-v2`,
both `invoice-fresh-*-v2` role protocols and `invoice-final-reconciliation-v2`.
Each host-declared participant gets exactly the invoice case and one protocol.
Joint-investigator, historical, empty, mixed and foreign-case grants are rejected.
Generic admission does not infer a role from a participant name, require six
members, order stages or decide which fresh questions were selected.

Earlier five-protocol follow-up, three-protocol review, investigation, navigation,
read-only, v1 and legacy hosts keep their earlier scopes. They reject final-review
grants at registration and retained loading, including policies carrying older
labels. Copying a registration in either direction cannot upgrade it or reset its
deadline. Old registrations and evidence are not migrated.

## Exact broker routing

The final protocol selects `InvoiceReconciliationDecisionV2.INSTRUCTIONS`, its
exact strict schema and the forced `invoice_reconciliation_decision_v2` function.
The request retains `parallel_tool_calls: false`, `store: false` and foreground
execution. Explicit function selection and strict parameters follow the current
[OpenAI function-calling contract](https://developers.openai.com/api/docs/guides/function-calling).

The extractor requires exactly one matching function response. Historical final,
original, fresh, coordinator, unknown, duplicate, absent and plain-text responses
cannot substitute. Reasoning items are omitted. A matching function with malformed
arguments remains transport evidence for explicit host rejection; transport
completion does not validate a dossier or establish semantic quality.

A changed retained protocol invalidates the broker request grant before credential
resolution. The trial also checks the participant's exact protocol, model, input
bytes and output-token scope before reserving work. A valid input string is not
proof that the caller authenticated the earlier stages.

## Shared ceilings and recovery

The unchanged whole-trial ceilings apply across all admitted participants: at most
60 model calls, 100 reads, two calls in flight, 1,200,000 milliseconds, 64,000 UTF-8
input bytes and 2,048 output tokens per call. Declared limits may be lower. Counted
describe, catalogue, prefix and text reads share one pool; unpaged reads are denied.

Exact completed calls and reads recover without dispatching or charging again.
Changing a request or prefix is rejected. Interrupted registrations retain their
charged uncertain slot; unknown remote outcomes are not retried as fresh calls.
Completed broker results survive controller interruption. Expiry, revocation and
terminal trial state block cached participant operations as well as new ones.

After policy and artifact validation, a stale-build trial can be inspected and
closed, but cannot start or resume work under the current build. Operational
closure is not an accepted answer, reviewed question coverage or release approval.

## Separate execution integration

The [durable full-team runner](invoice-team-comparison-v2.md) now integrates the
following execution requirements. Admission itself does not order or run stages.

A concrete final-stage runner must pre-register its fixed six participants,
authenticate the actual original/review/fresh registrations and sealed journals,
reconstruct selected questions and accepted fresh finishes, and give the final
reviewer only its own admitted reads. It must retain all outcomes and claims,
unresolved conflicts, rejected candidates, unknown work and exact final lineage.

It must use actual reservations under cumulative 24/6/24/6 allocation at 60 calls,
one unchanged deadline and the same read pool. Admission enforces whole-trial
limits; it does not itself enforce the original, review, fresh and final stage
barriers. The existing [prefix](invoice-team-runner-v2.md) and
[selected follow-up](invoice-follow-up-runner-v2.md) runners remain limited to their
three- and five-participant policies and cannot execute this final protocol.
The full runner uses separate six-member modes of those journal engines and its
own final controller. Operational closure and comparison export occur there.

Run the README preparation and Java verification commands. Offline controls cover
all six scoped protocols, namespace copying in both directions, older-label
smuggling, changed identities/builds/ceilings/requests, shared pools, interruption,
uncertainty and lifecycle stops. Loopback HTTP uses the actual broker and owned
final contexts with a synthetic credential, retaining source uncertainty and a
cited partial abstention. Its source outcomes are authored controls, not observed
original-stage execution or live-model quality. No test calls the provider.

Deterministic integration, common-condition registration and measurement remain
separate work. The original model, limits, baselines,
six review criteria and historical failures/unknowns remain unchanged.
[Milestone 4 acceptance](milestone-4-status.md) remains open.
