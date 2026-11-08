# Invoice reconciliation v2 contract

Step 4e5n adds the pure `InvoiceTeamReconciliationV2` and
`InvoiceReconciliationDecisionV2` boundaries. They prepare one final joint
advisory dossier from supplied original, coordinator and fresh histories. They
grant no permission, authenticate no runtime journal and dispatch no operation.
The `invoice-final-reconciliation-v2` protocol now has a separate
[runtime/broker admission path](invoice-reconciliation-v2-admission.md). A separate
[durable full-team runner](invoice-team-comparison-v2.md) now authenticates actual
source journals and integrates final execution. The existing public five-member
follow-up runner is unchanged.

## Validated source lineage

The caller supplies the exact opened invoice bundle, two typed original role
outcomes, a typed coordinator review and the fresh outcome rows produced by the
[follow-up runner](invoice-follow-up-runner-v2.md). The caller must first
authenticate their registration, journal, stage barriers and runtime ownership.
The pure contract validates supplied capture contents and structural consistency;
hashes here identify those values rather than proving provenance or permission.

Original role/task/protocol contracts and owned readiness are revalidated through
the [coordinator contract](invoice-team-review-v2.md). A completed review's
selection, questions and anchors are recomputed through the
[fresh contract](invoice-follow-up-v2-contract.md). Exactly one fresh outcome is
required for each selected role, in stable role order, with its exact participant,
question binding and recomputed accepted finish. Missing, extra, duplicated,
reordered, foreign or changed rows are rejected. A stored `accepted` field is
checked against reconstruction, never accepted as its own authority.

Stopped histories cannot supply completed dossiers. Their available bootstraps
and reads are validated even when their status is failed, cancelled or unknown.
Missing bootstraps cannot accompany reads. An unavailable review retains a null
selection, distinct from a completed review selecting zero questions. Neither
case permits fresh outcomes that were not selected.

The evidence inventory retains every original outcome and claim, the coordinator
disposition and selection identity, all selected questions, every fresh outcome
and claim, their exact question bindings, owned readiness and source identities.
Fresh claims cite worker-local page IDs as lineage only. Earlier unknown outcomes
remain unknown after a later answer. Possible conflicts remain `UNRESOLVED`;
neither final agreement, omission nor a changed answer closes them. Question
bindings establish which question a response belongs to, not that it answered the
question accurately or completely. Semantic coverage remains unreviewed.

## Owned context and shared limits

The final reviewer must have an identity distinct from the coordinator, both
original participants and both fixed fresh participants. Its own JOINT bootstrap
and read wrappers are validated against the opened capture. Earlier participants'
read labels do not count as final-reviewer reads or readiness.

Context includes the public maintainer task, historical capture metadata, owned
readiness, every source outcome and claim, unresolved conflicts, all own read
metadata and at most the latest six own page contents. Only recent contents may
be trimmed, oldest first, to the declared UTF-8 ceiling of at most 64,000 bytes.
If the required history and metadata still do not fit, preparation stops with
`CONTEXT_BUDGET_EXHAUSTED`; it does not silently drop earlier statements.

The canonical cumulative allocation remains 24 original, 6 review, 24 fresh and
6 reconciliation opportunities at a 60-call ceiling. Unused opportunities carry
forward; prior reservations are not reset. The context exposes remaining final
stage calls and one active participant, with a charged finish and no guaranteed
future turn. Exhaustion stops preparation. These supplied scheduling snapshots
are not runtime grants or proof of actual reservation accounting.

## Final advisory finish

The strict wire contract allows `catalog`, `catalog_prefix`, `read_text` and
`finish`, retaining the existing navigation bounds. Only original `worker-N` and
`claim-N`, fresh `freshworker-N` and `freshclaim-N`, and the final reviewer's own
inspected text `read-N` references can be cited. Catalogues, anchors, unseen IDs
and foreign reads cannot support a finish, including an abstention.

An `ANSWERED` dossier requires nonempty summary citations and findings, plus
either cited structurally answered claims covering both fixed roles, or complete
own JOINT readiness and a cited own text page. Outcomes alone and claims from
abstained workers cannot satisfy the claim route. Original and fresh claims may
jointly cover the two roles. The claim route does not transfer read ownership;
the own-read route still requires captured source-execution coverage.

An `ABSTAINED` dossier can retain partial cited advice without asserting complete
access. The accepted record preserves the full evidence inventory, final decision,
owned readiness and exact eligible reference identities. It is not a completed
runtime, comparison export, conflict resolution, semantic grade or release
approval. `qualityReview` remains `NOT_PERFORMED`.

## Validation and remaining work

Run the README preparation and Java verification commands. Offline controls cover
source/selection mismatches, unavailable versus empty selection, changed accepted
finishes, incomplete/unknown dispositions, foreign reads, invented and catalogue
citations, both structural answer routes, cumulative limits, context overflow and
defensive copies. Positive access controls use separately identified authored
coverage metadata, not newly observed consumer execution or live-model quality.
Broker/runtime controls require explicit admission and keep older host policies
closed to this protocol.

The separate full-team runner now supplies durable final execution, operational
closure and unreviewed comparison export. Deterministic integration, common-condition
registration and measurement remain separate work. Historical campaigns, model, limits,
baselines and the six review criteria remain unchanged.
[Milestone 4 acceptance](milestone-4-status.md) remains open.
