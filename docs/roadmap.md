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
4. Add credential brokering and test interruptions, expiry and revoked access.

Acceptance: interrupted execution and revoked authorization recover or stop without
losing evidence, leaking permissions or repeating unsafe actions.

## Milestone 3 — Add adaptive consumer investigation

1. Expose narrow read and test tools over registered inputs.
2. Add one investigating agent with typed findings and bounded resource use.
3. Generate scoped repair candidates in isolated checkouts.
4. Verify against protected expectations and establish an evaluation baseline.

Acceptance: useful repairs and correct abstentions measured against curated cases.

## Milestone 4 — Coordinate multiple investigations

1. Isolate each consumer task's environment, context and permissions.
2. Run bounded parallel adaptive investigations.
3. Add coordinator follow-up and explicit contradiction handling.
4. Compare the team with deterministic, single-agent and independent-worker
   configurations, then route workloads according to observed benefit.

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
