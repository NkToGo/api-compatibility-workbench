# Invoice investigation protocol follow-up

The [frozen invoice comparison](invoice-campaign-results.md) produced no accepted
joint answer. Before measuring another condition, a read-only structural diagnosis
checks where the existing investigators stopped. It changes no participant record,
claim judgment, rubric, resource count or routing decision.

The [diagnosis record](evaluations/invoice-protocol-diagnosis-2026-10-29.json)
covers all fifteen original investigation contexts in the nine adaptive trials:
three single agents, six independent role workers and six original team workers.
The three deterministic trials remain in the original twelve-slot comparison.
Coordinator, follow-up and reconciliation advice is not regraded here. No new
model call or consumer execution was made.

## Recorded structural failures

The three rejected finishes have different, reconstructable access/citation gaps:

| Trial | Rejected `ANSWERED` finish | What the retained metadata establishes |
| --- | --- | --- |
| Single agent, repetition 1 | Both required baseline consumer sources were unread | Business/order access and valid citation IDs do not substitute for the required sources |
| Single agent, repetition 2 | `read-33` and `read-35` cite catalogue pages | Required access was complete, but catalogue rows cannot support prose claims |
| Independent reconciliation worker, repetition 3 | No complete reconciliation business artifact was read | Source and order reads do not substitute for the role-specific observation |

This reconstructs structural rejection, not semantic truth. An admissible finish
can still omit required explanations or make unsupported assertions, as the
[complete semantic review](invoice-campaign-review-results.md) already records.
The uncertain final single-agent outcome and unknown usage remain unchanged.

Every original team worker received twelve model opportunities. All six spent
their last scheduled call on another text read and produced no accepted finish.
Their final requests displayed `decisionsRemaining: 2`: the shared allowance for
two workers, not two private future turns. The observed original-stage schedule
had one current call per worker and no later turn. This establishes the schedule
and disclosure, not that the displayed count caused the model's choice.

| Repetition | Original role | Catalogue / text reads at stop | Remaining structural access gap at stop |
| --- | --- | --- | --- |
| 1 | Reconciliation | 9 / 3 | Task and complete order artifact |
| 1 | Webhook | 6 / 6 | None; no finish was returned |
| 2 | Reconciliation | 7 / 5 | Complete order artifact |
| 2 | Webhook | 6 / 6 | None; no finish was returned |
| 3 | Reconciliation | 7 / 5 | None; no finish was returned |
| 3 | Webhook | 8 / 4 | Complete order artifact |

These are original-worker reads, excluding bootstrap metadata, coordinators and
final reconciliation. A structural gap is not a semantic criterion judgment.
All six have an optimistic six-call access-and-finish floor when aliases are
already known and maximum-size pages suffice. That floor omits discovery and
substantive investigation; it neither proves an adequate twelve-call allowance
nor establishes that the task was mathematically impossible within it.

## Navigation and task disclosure

The 444-alias catalogue is sorted and paged at forty rows. In version 1,
catalogue rows are omitted from `readMetadata`; only up to six recent page contents
remain in each request, with further trimming to the byte ceiling. There is no
prefix lookup or persistent catalogue index in that context. Re-reading can be
necessary after rows age out. Across the fifteen original contexts, 157 catalogue
and 127 text reads were retained, including 21 exact repeated text ranges.
Repeated reads can be intentional; these counts do not establish wasted work or
predict a speedup.

The captured public task artifact contains 591 UTF-16 characters, including its
metadata and limits. Its task statement broadly requests a joint investigation;
the version 1 context and access instructions do not enumerate the six semantic
coverage requirements. Those requirements remain protected review material.
Reading the minimum structural inputs does not explain which source/version,
application-policy and joint-ledger questions a useful maintainer answer must
cover. This is a disclosure/design gap to address prospectively, not grounds to
change the original review or excuse its unsupported claims.

The disclosure metadata's `comparisonAdaptersAvailable: false` describes the
earlier capture boundary. It cannot establish that the later investigation
adapter does not exist. The historical review already retains the unsupported
availability assertions; this diagnosis does not remove or reclassify them.

## Revised condition design — not implemented or registered

Use the same joint maintainer problem, synthetic consumer evidence, protected
oracle and six review criteria. Do not choose a new workload to obtain a team
winner. First implement a separately versioned evidence/navigation protocol:

1. **Structural readiness disclosure.** Derive complete/missing required reads,
   role-specific business/order access, source execution-coverage metadata and
   citable own text-page IDs from retained records. Label this as access readiness,
   never answer quality, business correctness or migration approval. Catalogue
   IDs remain non-citable. The host still validates the actual finish and may
   reject it; readiness cannot fabricate a dossier or grant a new read.
2. **Bounded catalogue lookup.** Add deterministic alias-prefix filtering with
   stable lexical pagination over the same captured inventory. Limit rows and
   request/response bytes, retain the prefix in each operation's identity, and
   charge the existing read budget. Do not search protected expectations, rank
   evidence by the desired answer or inject an ever-growing full index into
   model context. A catalogue result still reveals no unread content.
3. **Explicit scheduling information.** Distinguish shared remaining reservations
   from the current participant's next scheduling opportunity, including whether
   this is its last scheduled decision if peers remain active. Make the need for
   a model-authored finish explicit. Do not promise a future turn after failure,
   revocation, uncertain dispatch or expiry, allocate free finalization calls,
   retry a rejected answer, or replace missing output with a host-written answer.
4. **Public task coverage.** Version the public task to request association and
   version boundaries, application-policy refusals, both consumers' joint ledger
   effects, source/execution distinctions and evidence limitations. These are
   maintainer questions, not expected answer values. Keep protected fixture
   expectations, judgments and acceptance feedback outside participant context.
   Give every arm the same revised input and public requirements.
5. **Separate capture and policy identities.** Identify capture-time capability
   metadata explicitly. New instructions, tool schemas, context format, task bytes
   and adapters require new identities. Version 1 historical reads and completed
   records retain their original meaning; unfinished work cannot resume under
   the revised condition.

These changes are candidate remedies, not measured improvements. Do not increase
the original 60-call, 100-read, twenty-minute, 64,000-input-byte and 2,048-output-token
ceilings, two-call concurrency or 24/6/24/6 team allocation in this condition.
Keep the original model selection for the first revised condition; a later model
change would require its own predeclared condition. The original deterministic,
single-agent and independent-worker baselines stay present. All adaptive roles
receive the same navigation/readiness semantics within their authorized scope.

## Reviewable implementation boundary

Step 4e5a completes the diagnosis and prospective design. Step 4e5b implements a
[pure readiness boundary](invoice-readiness.md) with versioned output and controls
for missing/gapped reads, role access, catalogue citations and honest scope.
It is not connected to version 1 investigators and does not change their behavior.
Step 4e5c implements [bounded prefix catalogue navigation](invoice-prefix-catalogue.md)
under a separately admitted read-only boundary, including restart, exact query
identity and shared-budget controls. Step 4e5d implements a [pure versioned
decision/context contract](invoice-v2-contract.md), validating owned capture pages
and declaring public requirements and scheduling without granting model access.
Separately identified runtime/broker admission is next; concrete adapter
integration follows separately.

Before new participant measurement, freeze the implemented policy/tool/task
identities, exact runtime, model, unchanged rubric, common input, budgets and all
ordered slots in a new campaign. Use the same three repetitions and rotations;
retain the known small-sample/order limitations. Registration is a later explicit
boundary, not something this design document performs.

One complete planned revised campaign is a comparison, not a search for favorable
retries. Review every material claim, retain failed/abstained/missing/uncertain
slots and inclusive costs, and report against the unchanged criteria. Compare
within the new condition; old and new conditions differ in task/protocol and
cannot isolate one causal improvement or support a general accuracy claim.
No automatic additional campaign, routing promotion or Milestone 5 transition
follows a negative result. Useful coordination must still be established under
the [Milestone 4 acceptance criterion](milestone-4-status.md).

## Reproduction and limits

The diagnosis pins the original input manifest, operational records, inspected
implementation sources, and each participant's last request and public response.
For a retained trusted campaign tree:

1. Check those paths and SHA-256 identities against the original retained inventory.
2. Group original worker requests/events by arm, trial and participant, preserving
   all fifteen contexts. Parse the public function decision without publishing
   raw input payloads or private model reasoning.
3. Reconstruct contiguous text coverage by alias from offset, next offset and
   total length. Check required source/task/configuration reads, a complete
   role-appropriate business artifact, a complete order artifact and the captured
   execution-coverage flag. This reproduces the access floor only.
4. Check finish citations against that participant's own text-page IDs. Compare
   structural admissibility with the original accepted/rejected finish status;
   retain catalogue IDs as non-citable.
5. Count catalogue/text reads, repeated exact text ranges and final scheduling
   actions. Verify every inspected original file remains byte-identical.

The reconstruction read and pinned 1,127 historical evidence/state files, the
published measurement and six implementation source files. All were unchanged
after inspection. A separate full-inventory check confirmed that all 4,652
original evidence/state files remain unchanged. The public record contains
metadata and identities, not source payloads or dossier prose.
It requires the retained original tree to independently reconstruct the requests;
a fresh checkout alone cannot regenerate those historical responses. Hashes are
integrity checks on one trusted host, not authenticated provenance, calibrated
reviewer accuracy or semantic evidence of useful coordination.
