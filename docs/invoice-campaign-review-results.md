# Semantic review of the frozen invoice campaign

The [bound review](evaluations/invoice-campaign-review-2026-10-27.json) inspects all
twelve original slots in the [measured invoice campaign](invoice-campaign-measurement.md)
against its unchanged `invoice-joint-evidence-v1` rubric. All 521 review units and
72 criterion entries are reviewed, with no pending entries. **No trial satisfies
every declared review gate.**

The original five completed trials, six failed trials and one uncertain remote
outcome remain present. Rejected finish responses and accepted partial role
reports are inspected without replacing failed runs with successful answers.
Neither this review nor operational completion establishes useful coordination,
routing promotion or migration/release approval. The paired quality/resource
report remains separate work; [Milestone 4 acceptance](milestone-4-status.md) stays
open.

## Method and evidence

This is unblinded model-assisted offline inspection, with zero new provider calls,
no independent human adjudication and no calibrated reviewer-accuracy claim.
The rubric and contract were frozen before these twelve trials; the older
[four-trial inspection](invoice-review-results.md) remains descriptive and post-hoc.
Its grades are not copied into this campaign.

The packet binds 4,647 original source artifacts. Its five retained packet files
bring the independently checked evidence/state inventory to 4,652 files; every
file remained byte-identical. Original trial, claim, trace and usage records,
the earlier pending review and the historical post-hoc review remain unchanged.
Protected business expectations were used only for review, not inserted into the
participants' earlier contexts. The oracle is authored application policy held
separately from implementations, without external business-owner adjudication.

The inspection covers 67 distinct public prose passages and their 158 recorded
occurrences, decomposed into 314 clause judgments. It includes all fourteen public
finish responses, including rejected responses, coordinator summaries and final
reconciliation. The other 298 public model responses are strictly parsed catalog
or text-read requests. Only public advisory output is inspected; private model
reasoning is neither requested nor exposed.

Each of the three deterministic runs selected the same 69 artifacts from the
444-artifact bundle, using 86 of 87 available text pages. The complete catalog,
mandatory prefix, alias-hash stratum order, whole-file page costs, selected
inventory and first budget exclusion were independently reconstructed. The next
two-page artifact did not fit; 375 artifacts remained unread.

Their 189 recorded diagnostic entries contain 3,483 scalar/empty-collection
assertion occurrences. These were independently recomputed from original
observations and order receipts without calling the diagnostic implementation
or executing a consumer. They distinguish availability, admission, execution and
transport status, identity/version matches, incomplete positions, receipt
outcomes and effect counts. A recognized diagnostic shape is not protected
business correctness or a semantic acceptance verdict.

There are **3,826 judgment records**: 3,483 diagnostic assertions, 314 prose clauses
and 29 structured scope/metadata judgments. Fourteen complete dossiers delegate
to their exact sections; 408 operational/identity or strict-read units are context.
All 209 evidence references bind original bytes and pointer values. These counts
include repeated outputs and are neither independent observations nor a quality
score. Mechanical coverage is not exhaustive semantic decomposition, authenticated
reviewer accuracy or proof of truth.

## Criterion results

Every trial leaves at least one part of each of the first five requirements unmet:
guarded old/Basil association handling, independent version boundaries, complete
application-policy refusals, joint identity-level ledger behavior and complete
source-flow/execution/causal scope. Correct selected observations do not fill an
omitted explanation; reading a source is not the same as explaining it.

| Slot | Arm | Repetition | Original status | Response | Evidence and uncertainty criterion | Full review gate |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Deterministic | 1 | Completed | Answered | Satisfied | False |
| 2 | Single agent | 1 | Failed | Not provided | Not satisfied | False |
| 3 | Independent workers | 1 | Completed | Answered | Not satisfied | False |
| 4 | Coordinated team | 1 | Failed | Abstained | Satisfied | False |
| 5 | Single agent | 2 | Failed | Not provided | Not satisfied | False |
| 6 | Independent workers | 2 | Completed | Answered | Satisfied | False |
| 7 | Coordinated team | 2 | Failed | Abstained | Satisfied | False |
| 8 | Deterministic | 2 | Completed | Answered | Satisfied | False |
| 9 | Independent workers | 3 | Failed | Not provided | Satisfied | False |
| 10 | Coordinated team | 3 | Failed | Abstained | Satisfied | False |
| 11 | Deterministic | 3 | Completed | Answered | Satisfied | False |
| 12 | Single agent | 3 | Unknown remote outcome | Not provided | Not satisfied | False |

The deterministic answers preserve useful refusal, conflict, duplicate and
incomplete-execution facts, while explicitly leaving source control flow and
protected business correctness unestablished. Effect counts do not establish
preservation of an existing effect's identity.

The accepted independent-worker reports explain selected successful source,
policy and append/duplicate behavior. They omit required guarded Basil-parent,
old-delivery, refusal or conflict coverage. The second repetition distinguishes
the inspected Acacia baseline from a captured Basil build, but its webhook answer
does not supply the full relevant source-flow explanation. In the third repetition,
the accepted webhook report survives alongside a failed reconciliation worker;
rejected reconciliation prose does not complete the joint answer.

All three teams retain two original workers that exhausted their stage-call
budgets without accepted reports. Their honest final abstentions preserve
uncertainty but do not satisfy the rubric's expected `ANSWERED` task disposition.
The first coordinator's source observations remain historical evidence rather
than a substituted completed joint answer. The other coordinators' provider
reference reads supply no execution proof.

## Errors and interpretive uncertainty

Four material unsupported occurrences remain in history. Three are copies of
one accepted worker limitation in slot 3 claiming that no comparison adapters
were available. The fourth is the analogous assertion in slot 9's rejected
reconciliation finish. The false availability flag belongs to earlier disclosure
metadata; later comparison adapters and broker records exist. A literal captured
flag cannot establish later unavailability. Only the three slot-3 occurrences
remain endorsed in their own accepted output.

Four material unresolved occurrences are retained separately. Three copies in
slot 3 make the broad source-derived hypothesis that differing version, shape,
association, binding or state necessarily prevents a renewal. Source supports
refusal on violated checks, but a difference is not necessarily a violation;
the wording does not delimit the conditional adequately. The fourth is a
metadata-qualified availability statement in slot 2's rejected finish: its
qualification could refer to the literal metadata flag, so it is not forced into
a definite unsupported verdict. The first three remain endorsed; the rejected
statement does not become a final answer.

Repeated passages are not independent failures. Absence of another recorded
error is not proof of exhaustive inspection. Omitted coverage is recorded as an
unmet criterion rather than invented as an unsupported assertion.

The final uncertain slot retains its received HTTP 429 and unresolved usage.
This review infers neither the cause of that response nor zero consumption,
and does not retry the call or invent an absent answer.

## Recheck this exact revision

With the original trusted common root and its evidence/state trees retained:

```powershell
./scripts/Review-InvoiceCampaignClaims.ps1 -ReviewAction verify `
  -CampaignId invoicecampaign-c86bfdd4-8bc2-4fd3-bd44-ab80761d30d6 `
  -ExpectedReceiptSha256 4481be329dffa6c3d8ee07ab0db642ee4bc5eae3f6f2aa008fb4f7e53724a0c1 `
  -ReviewPacketId reviewpacket-8fb56f51-6a26-44ae-9a79-72ed85a115a7 `
  -ExpectedPacketSha256 666b667993833f0855b70ac01905885469d093101be9955ec69ddc85b975ca13 `
  -ReviewEvidenceDirectory /trusted/invoice-run `
  -EvidenceDirectory /trusted/invoice-run/evidence `
  -StateDirectory /trusted/invoice-run/state `
  -ReviewPath ./docs/evaluations/invoice-campaign-review-2026-10-27.json `
  -ReviewSha256 0981e5b5f5c9a1964c4f0337391a5311b8127824e24855ccb42c1561870875bf
```

The compact review file is 2,056,046 UTF-8 bytes, within the existing 2 MiB bound.
The host checks original campaign/packet bindings before and after validation,
all claim/evidence anchors and the unchanged twelve-slot/72-criterion membership.
The supplied complete judgments leave every `satisfiesDeclaredReview` false;
`campaignAcceptance` remains `NOT_ESTABLISHED`.

A fresh checkout can inspect this review and run validator controls. Rechecking
these historical bindings requires the original retained evidence: fixtures alone
cannot recreate the original public responses. Missing or changed evidence stops
verification. Hashes establish byte consistency, not reviewer identity, semantic
truth or exhaustive decomposition. The paired quality/resource assessment must
preserve these negative and incomplete results without selecting replacement runs.
