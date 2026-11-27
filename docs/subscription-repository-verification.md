# Verify a subscription repair from repository commits

Use the exact base and proposed revisions in a
[repair intent](repair-pull-request-lookup.md) to verify a source-only
subscription-period repair. Supply a trusted local Git repository containing
both objects and the repository-relative path to the standalone
`PeriodConsumer.java` adapter.

This case uses the declared transition from `2025-02-24.acacia` to
`2025-07-30.basil` with the registered synthetic fixtures and application
policy. The adapter runs on the fixture's JSON projection. It uses no Stripe
SDK or webhook in this execution. Billing mode, account settings and live API
behavior remain outside its evidence.
[Case and scenario contract](../cases/stripe-subscription-periods-2025-07-30/README.md)

After [building the backend](../README.md#build-and-test), preview the selection:

```powershell
$selection = @{
    Repository = './consumer-repository'
    Intent = './local-repair-intent.json'
    SourcePath = 'src/main/PeriodConsumer.java'
    BaselineApiVersion = '2025-02-24.acacia'
    TargetApiVersion = '2025-07-30.basil'
}
./scripts/Verify-SubscriptionRepairCommits.ps1 @selection
```

The preview shows the selected repository identity and the exact commits,
trees, blobs and source digests. It needs no credentials, worker or state
directory. Both commits must exist locally. The proposed commit must be a
direct child of the base and may replace only the selected regular,
non-executable adapter. Unsupported versions, source paths or unrelated
committed edits stop the selection. Working-tree changes have no effect.
The [GitHub branch check](repair-branch-check.md) verifies remote identity and
branch tips separately before publication.

To run protected verification, start a supported Linux Docker engine and build
the [repair worker](protected-repair-execution.md). Then run:

```powershell
./scripts/Verify-SubscriptionRepairCommits.ps1 @selection -Execute
```

Execution repeats the Git checks and captures the committed bytes. It records
both version declarations and the repository input identities in the
verification specification and binds them to the scenario snapshots. The
worker receives the captured adapter and host-controlled synthetic fixtures.
Use the existing subscription verifier's `resume`, `cancel` or `revoke` action
with the returned verification ID and the same state directory. The default
state directory is `.workbench`.

The [repair commit check](repair-commit-binding.md) matches this verification
only against its selected repository, source path and exact revisions. Starting
a new publication operation for the same verified inputs still requires its
own approval. A [repair evidence export](repair-evidence-export.md) shows the
retained repository provenance. Verifications made with supplied source files
retain their original scope and can still be checked against exact local commits.
