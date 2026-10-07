# Development roadmap

The milestones below describe planned capabilities and their acceptance criteria.

## Milestone 1 — Establish a trustworthy compatibility case

1. **Implemented:** define supported input shapes and evidence conclusions; implement
   immutable identities and deterministic assessment rules with focused tests.
2. **Curated:** [Stripe customer-sources transition](../cases/stripe-customer-sources-2020-08-27/README.md)
   with source rights, pinned versions and expected behavior reviewed against
   provider and consumer sources separately from the historical repair.
3. **Implemented:** [execute the isolated consumer methods](stripe-case-runner.md)
   against baseline, unrepaired target and upstream reference; retain local
   evidence, exact input identities and derived assessments.
4. **Implemented:** [offline case/result view](case-report.md) showing both
   comparisons, per-scenario coverage, unresolved issues and expandable evidence.

Acceptance: an actual demonstrated difference or an honest unresolved result for
the curated case. Domain unit tests alone do not meet this milestone's acceptance.

The runner demonstrates four failing upgrade scenarios and two remaining failures
in the historical patch, with all five baseline checks passing. The generated
view exposes these recorded outcomes and their coverage limits.

## Milestone 2 — Make execution safe and recoverable

1. **Implemented for the curated case:** [constrained container execution](isolated-execution.md)
   with immutable image inputs, blocked egress, resource limits, bounded output
   and cleanup verification. Arbitrary consumer code remains unsupported.
2. **Implemented locally:** [append-only evidence storage](evidence-storage.md)
   for final execution records, exported artifacts and provenance, with integrity
   verification, atomic publication and explicit retention limits.
3. **Implemented on one host:** [durable attempt registration, cancellation and recovery](execution-recovery.md)
   with ownership leases, orphan cleanup and idempotent finalization without replay.
4. Add credential brokering and test interruptions, expiry and revoked access:
   - **Implemented locally:** [scoped execution permission](execution-authorization.md),
     expiry and persistent revocation, with live-worker stop checks and preserved history.
   - **Implemented for model calls:** a [host-side credential broker](model-credential-broker.md)
     with exact request grants, bounded transport, sanitized results and no automatic
     replay after ambiguous dispatch. Workers never receive the API credential.

Acceptance: interrupted execution and revoked authorization recover or stop without
losing evidence, leaking permissions or repeating unsafe actions.

The current local boundary meets this acceptance for the curated offline worker
and bounded model calls. Tests cover live revocation, expiry and controller death.
Multi-user authorization, managed secret storage and provider-specific action
adapters remain part of their later integration and operational milestones.

## Milestone 3 — Add adaptive consumer investigation

1. **Implemented for the curated case:** [narrow read and test tools](investigation-tools.md)
   over captured registered inputs, with bounded access, deduplicated test requests
   and verified input-to-result identity.
2. **Implemented for the curated case:** [one investigating agent](consumer-investigator.md)
   with adaptive tool selection, cited advisory findings, abstention and bounded
   model requests. Repair-quality evaluation remains part of step 4.
3. **Implemented for the extracted consumer:** [scoped repair candidates](repair-candidates.md)
   in isolated source-only checkouts, with exact replacements, receipt binding and
   unverified artifact manifests. Full repository checkout support remains later work.
4. Verify against protected expectations and establish an evaluation baseline:
   - **Implemented:** [repair evaluation rules](repair-evaluation.md) for three-way
     evidence, with a historical reference baseline and explicit unresolved results.
   - **Implemented for the extracted case:** [isolated candidate execution](protected-repair-execution.md)
     with host-owned observations, adversarial checks and interruption recovery.
   - **Implemented:** an authored complete-repair control using explicit source-list
     retrieval, alongside historical controls. This validates the test adapter's
     ability to observe a complete repair; it does not measure model accuracy.
   - **Implemented:** [controlled evidence-availability trials](investigation-evaluation.md)
     for investigation completion and abstention, with operational failures kept
     separate. These use one case and do not measure semantic or repair accuracy.
   - **Implemented:** bounded unavailable-tool retries with preserved legacy sessions
     and per-trial usage counts for evaluating evidence-handling efficiency.
   - **Complete for the declared exercise:** the [two-case acceptance report](milestone-3-results.md)
     meets the repair and supported-abstention criteria with one investigation
     blocked from acceptance for an unsupported executor-scope explanation.
   - **Implemented:** a distinct [subscription-period case](../cases/stripe-subscription-periods-2025-07-30/README.md)
     with independently declared application expectations and executable authored
     repair controls. Broader generated repair measurement remains pending.
   - **Implemented:** a [host-side subscription result protocol](subscription-period-protocol.md)
     that keeps assertions outside candidate output and rejects invalid exchanges.
   - **Implemented:** subscription consumer compilation and protocol transport inside
     the existing constrained repair container, with host-owned assertions.
   - **Implemented:** durable subscription verification of supplied source pairs,
     including captured identities, three-way evaluation, cancellation, revocation
     and no-replay recovery.
   - **Implemented:** [bounded subscription candidate generation](subscription-candidates.md)
     from retained execution evidence, with abstention, inert source artifacts and
     separate manifest-bound verification. Repair-quality/abstention trials across
     cases remain pending.
   - **Implemented:** [repeated subscription repair trials](subscription-repair-evaluation.md)
     with fixed budgets, durable child mappings, separate operational/behavioral
     outcomes and token counts. These repeat one known case and do not establish
     held-out accuracy or abstention correctness.
   - **Implemented:** [adaptive subscription investigation](subscription-investigation.md)
     over retained source, application policy and baseline/target observations,
     reusing the bounded runtime without exposing previous candidate results or
     dispatching new execution.
   - **Implemented:** subscription investigation-to-generation handoff with captured
     session, findings, context and event identities; changed records stop unfinished
     generation or new verification. Abstained and failed investigations are blocked.
     Advisory findings do not replace protected execution conclusions.
   - **Implemented:** [repeated full subscription workflow trials](subscription-investigated-repair-evaluation.md)
     with fresh retained-evidence investigations, identity-bound one-shot generation,
     separate protected verification and retained model-record inventories.
     Sufficient-evidence findings have bound review and blocked-trial acceptance
     handling in the combined report.
   - **Implemented:** [repeated customer-sources trials](customer-repair-evaluation.md)
     with fresh bounded investigations, one-shot generation and separate protected
     verification. Durable stage mappings retain failed trials and completed-response
     usage; sufficient-evidence findings have bound review in the combined report.
   - **Implemented:** complete registered synthetic response evidence in customer
     repair generation, with pinned SDK source-list implementation evidence and
     expectation/fixture identity checks before and after dispatch. Earlier
     candidate requests and trial outcomes remain unchanged.
   - **Implemented:** [repeated two-case missing-evidence investigations](missing-evidence-evaluation.md)
     with fresh contexts, blocked source/execution aliases, sealed trial inputs and
     retained operational failures. Decision checks do not establish supported
     explanations.
   - **Implemented:** [bound claim-level review of missing-evidence explanations](missing-evidence-review.md)
     with per-summary/finding judgments against recorded tool evidence, complete
     claim coverage and an offline metadata checker. All 12 reviewed abstention
     reasons are supported within the recorded scope. The combined acceptance
     report also completes sufficient-evidence review, retaining rejected claims.

Acceptance: useful repairs and correct abstentions measured against curated cases.

The [acceptance checklist](milestone-3-acceptance.md) defines the completed sequence,
fixed trial conditions and exit thresholds. Milestone 3 is complete for that bounded
exercise under the recorded model-assisted review and explicit exclusion. Further
provider families and automatic repair-feedback loops remain outside its scope.

## Milestone 4 — Coordinate multiple investigations

1. **Implemented for the two curated cases:** [isolated consumer tasks](consumer-tasks.md)
   with sealed input snapshots, separate agent/broker/execution stores, exact-model
   admission grants, expiry/revocation and interruption recovery. Subscription
   tasks remain retained-only; customer tasks reuse the constrained container.
   This is a trusted-host boundary, not multi-user or host-process isolation.
2. **Implemented on one trusted host:** [bounded parallel adaptive investigations](parallel-investigations.md)
   with fixed membership, at most two concurrent investigators, shared durable call
   reservations, root admission/revocation and no-replay child recovery. Aggregate
   completion is an orchestration outcome, not a semantic or compatibility verdict.
3. Add coordinator follow-up and explicit contradiction handling:
   - **3a implemented on one trusted host:** [adaptive coordinator review](coordinator-review.md)
     of captured completed investigations, cited inert follow-up proposals and
     unresolved possible-conflict records. The coordinator cannot grant execution
     access, dispatch work or turn model agreement into a compatibility verdict.
   - **3b1 implemented on one trusted host:** [dispatch selected follow-up questions](follow-up-dispatch.md)
     in fresh tasks with independent permissions, a shared bounded call budget,
     controlled child ownership and retained proposal/response lineage. Recorded
     child outcomes do not resolve earlier claims or conflicts.
   - **3b2 implemented on one trusted host:** [read-only follow-up reconciliation](follow-up-reconciliation.md)
     with cited question-coverage advice, original/fresh claim links and preserved
     uncertain comparability and unresolved conflicts. Semantic acceptance remains
     separate from valid citations and model agreement. A recorded live review
     completed in eight calls; its answer-completeness wording was blocked by
     a separate semantic spot review. This does not measure coordination benefit.
4. Compare the team with deterministic, single-agent and independent-worker
   configurations, then route workloads according to observed benefit.
   - **4a implemented:** [frozen comparison contract and offline evaluator](coordination-comparison.md)
     with all planned paired slots, separate semantic review, inclusive usage,
     immutable reports and unresolved missing/ambiguous records. Registration and
     evaluator implementation establish no routing conclusion.
   - **4b implemented on one trusted host:** arm adapters with shared inputs and end-to-end budgets.
     **4b1 implemented on one trusted host:** [common captured inputs and trial runtime](coordination-runtime.md)
     with policy-bound registration, whole-trial deadlines, shared durable call
     reservations and trace accounting.
     **4b2 implemented:** [deterministic diagnostics](coordination-diagnostics.md)
     through the shared runtime, with recorded outcomes, synthetic projection
     checks, retained partial/unresolved results and no model calls. Source
     control-flow analysis and semantic acceptance are not established by these rules.
     **4b3 implemented:** [one multi-case adaptive agent](coordination-single-agent.md)
     through the shared runtime, with bounded page disclosure, a full-budget loop,
     same-case citations, retained no-replay recovery and unreviewed final dossiers.
     **4b4a implemented:** [independent case workers](coordination-independent-workers.md)
     with concurrent fair rounds, isolated case contexts, a shared full-budget
     pool, sealed no-replay recovery and deterministic partial-output aggregation.
     **4b4b implemented:** coordinated-team comparison adapter under the
     same pool. **4b4b1 implemented:** [captured-team review boundary](coordination-team-review.md)
     with case-bound inert question selection, retained original claims, unresolved
     conflicts and cumulative stage-allocation arithmetic.
     **4b4b2 implemented:** [durable original-worker and coordinator-review execution](coordination-team-runner.md)
     through the shared runtime, including sealed stage transitions, cumulative
     budget enforcement and no-replay recovery. The retained result is a prefix;
     its pending stages remain visible in historical prefix reports.
     **4b4b3 implemented:** [fresh scoped follow-ups, reconciliation and unreviewed
     comparison export](coordination-team-comparison.md), with original/fresh lineage,
     unresolved conflicts and a complete implementation policy. No paired campaign
     or semantic acceptance is established by adapter completion.
   - **4c implemented for the known-case campaign:** fixed paired measurement and bound semantic acceptance review.
     **4c1 implemented:** [frozen campaign registration](coordination-campaign.md)
     with four compiled policies, the exact rubric/input snapshots, common limits,
     all twelve ordered slots, sealed metadata and explicit interruption recovery.
     **4c2 implemented:** [ordered fresh-trial execution](coordination-campaign-execution.md)
     with retained mappings, no-replay recovery, inclusive participant accounting
     and sealed unreviewed records. **4c3a implemented:** [sealed review-input inventories](coordination-review-packets.md)
     for every final dossier and all original/fresh team lineage, preserving conflicts
     and uncited claims. **4c3b implemented:** [bound claim-level semantic reviews](coordination-semantic-review.md)
     of all 24 outputs under the unchanged rubric, preserving unsupported earlier
     claims, unresolved assertions and incomplete criteria. **4c3c implemented:**
     [report paired outcomes, latency and resource use](coordination-results.md)
     with bound reviewed exports and preserved unresolved claim gates.
     Review inventories alone establish no quality or coordination benefit.
   - **4d planned:** select a scoped routing rule from the observed comparison.

Acceptance: useful coordination on heterogeneous consumers under declared budgets;
no permission leakage or unbounded delegation.

## Milestone 5 — Complete the maintainer workflow

1. Register selected repositories through a scoped GitHub App installation.
2. Inventory provider, SDK, request and webhook versions and configuration.
3. Bind review decisions to exact evidence and invalidate stale approvals.
4. Publish repair PRs with reconciliation after ambiguous external outcomes.
5. Observe PR/deployment states and support evidence export.

Acceptance: a maintainer completes an authorized supported migration workflow
without manual database intervention.

## Milestone 6 — Release and operate

1. Establish organization isolation, retention, deletion and security checks.
2. Expose costs, recorded actions, operational metrics and alerts.
3. Complete deployment automation, backups and restoration exercises.
4. Document installation, updates and incident handling.
5. Publish an approved case gallery and complete an external installation trial.

Acceptance: a supported external installation works, operational procedures have
been exercised and product limitations are visible.
