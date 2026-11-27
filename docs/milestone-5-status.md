# Milestone 5 maintainer workflow status

**Acceptance met for the supported subscription-period repair.** A maintainer
completed the workflow on a selected GitHub installation using synthetic inputs
and the documented commands without manual database intervention.

| Supported path | Evidence exercised | Remaining boundary |
| --- | --- | --- |
| Invoice reconciliation | Selected GitHub repository and pinned POM reads, retained declarations and source, protected baseline and target runs, comparison, and a local review tied to the exact attempt records. | The host runs one registered synthetic reconciliation delivery per phase. Declared versions do not establish effective runtime configuration. This review supplies no subscription repair or permission to publish one. |
| Subscription-period repair | [Repository-bound verification](subscription-repository-verification.md) of exact committed adapter bytes and declared versions, protected testing on eight synthetic scenarios, scoped branch checks, approval, draft publication, PR and deployment observations, and export. Ambiguous publication reconciliation is verified separately with a local GitHub fixture. | The live run used prepared synthetic case revisions and an isolated worker. Its rollout is a GitHub deployment record for the sandbox result. |

Repository access and selected source reads were exercised separately with a
scoped GitHub App. The two paths above do not form one migration. The invoice
review and the subscription publication have different case and input identities.

## What the composed subscription exercise checks

The test starts with one verification and exact local Git objects. It validates
the selected repository, observes the matching branches and records approval for
the recomputed binding. A GitHub fixture returns an inconclusive draft creation
response. A new publisher instance finds the expected draft through read-only
reconciliation. Request counts and the saved response verify that a repeated
publication command did not submit a second draft or rewrite the original outcome.

The export then recomputes the same binding and verification receipt digest,
contains all eight scenario outcomes and retains both outcomes of the publication
attempt. Given fixture merge and deployment records, the export checks the
confirmed PR number and carries deployment status for the exact merge revision.
No database changes are part of the exercise. The default tests use synthetic
verification. Linux protected verification runs this sequence with a repair
verified by the isolated worker from the exact selected Git revisions and
version declarations. The export carries the original repository provenance.
GitHub responses are simulated in both.

## Selected installation exercise

1. Select the repository and pin the baseline and proposed repair revisions.
   Bind the declared API versions and committed adapter bytes to the protected
   verification. The repair passed all eight registered scenarios, including the
   four that regressed on the target version before the repair.
2. Check both remote branches against the verified binding and record approval
   for the exact draft operation. The publisher created [repair PR #1](https://github.com/NkToGo/api-compatibility-workbench/pull/1)
   and retained its publication receipt. Offline export confirmed it could reopen
   the same verification and publication record from disk.
3. Merge into the dedicated acceptance branch. The merge tree matched the
   verified repair tree and all eight repair scenarios passed again in the
   sandbox. Both CI jobs succeeded on the exact merge commit.
   [Merge verification](https://github.com/NkToGo/api-compatibility-workbench/actions/runs/46072631823)
4. Record the synthetic deployment in the acceptance environment. The workbench
   retrieved its `SUCCESS` status by the exact merge SHA and environment, then
   exported it alongside the original verification, eight scenario outcomes and
   publication record. The observation and export left the publication journal
   intact. [Deployment status record](https://api.github.com/repos/NkToGo/api-compatibility-workbench/deployments/8120489864/statuses)

The subscription workflow requires the exact base and proposed repair branches
to exist in the selected repository. Live draft creation needs **Pull requests:
write** on that installation and a fresh approval for the exact operation.
Deployment observation additionally needs **Deployments: read** and a record
for the exact merge revision in the selected environment. The workbench does
not provision access, merge the PR or deploy the repair. Those actions have
their own authorization. The recorded success describes synthetic sandbox
verification. Deployment health and production Stripe compatibility require
separate evidence.

See the [local invoice review](invoice-rollout-review.md),
[draft publication](draft-repair-publication.md) and
[evidence export](repair-evidence-export.md) for the implemented command boundaries.
