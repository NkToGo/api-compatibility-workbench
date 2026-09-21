# Development roadmap

Each numbered item is a reviewable step. Complete its implementation, validation,
review and commit before advancing. Adjust boundaries when necessary to keep the
change understandable in one sitting.

## Milestone 1 — Establish a trustworthy compatibility case

1. **Ready for review:** define supported input shapes and evidence conclusions; implement
   immutable identities and deterministic assessment rules with focused tests.
2. Curate a real provider/consumer transition, documenting rights, exact versions,
   scenario coverage and independently reviewed expected behavior.
3. Execute the baseline and changed scenarios deterministically and record
   artifacts with provenance.
4. Add a readable case/result view showing coverage, observations and open issues.

Acceptance: an actual demonstrated difference or an honest unresolved result for
the curated case. Domain unit tests alone do not meet this milestone's acceptance.

## Milestone 2 — Make execution safe and recoverable

1. Define and enforce isolated worker inputs, egress and resource limits.
2. Persist artifact provenance and execution records.
3. Add durable execution, cancellation and restart recovery.
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
