# Check a proposed repair commit

Match a retained [protected subscription repair](subscription-repair-evaluation.md)
to the exact base and proposed head commits in a
[pull request intent](repair-pull-request-lookup.md). This check covers the
standalone `PeriodConsumer.java` adapter verified by the subscription-period
scenario suite.

After [building the backend](../README.md#build-and-test), supply the verification
ID, a trusted local repository containing both Git objects, an intent using the
full commit IDs, and the repository-relative adapter path.

```powershell
./scripts/Check-RepairCommit.ps1 -VerificationId verification-EXISTING-UUID `
    -Repository ./consumer-repository -Intent ./local-repair-intent.json `
    -SourcePath src/main/PeriodConsumer.java
```

By default the command reads verifications from the workbench's `.workbench`
directory. Use `-StateDirectory` for another trusted local store. Use Git with
`--no-lazy-fetch` support and SHA-1 object IDs. The source path may have up to
seven ASCII directory components and must end with `PeriodConsumer.java`.

The check verifies the retained receipt and artifact hashes and recomputes the
repair evaluation from the recorded observations. It requires an authorized,
completed subscription verification that fixed every observed regression in
the registered suite. The proposed head must be a single direct child of the
declared base. On both revisions the adapter must be a regular, non-executable
file. Its base and repaired bytes must match the two retained source digests.
All sibling entries at every level of the tree must match, including modes and
subtree IDs, so a change to an unrelated file or directory blocks the binding.

`MATCHED` returns the verification receipt hash, publication intent, source
path, tree and blob identities, and a deterministic `bindingIdentity` for
that proof. `BLOCKED` gives a reason without creating a binding. Invalid
or unavailable retained records, unresolved repairs, unavailable objects,
unexpected ancestry, and mismatched source or tree entries are kept distinct.
The output excludes source bytes and Git diagnostics.

The command reads local Git objects by their exact IDs with replacement
objects and lazy fetching disabled. Uncommitted edits are irrelevant to
this check. The result establishes source and tree correspondence for the
supported synthetic case. A [repository-bound verification](subscription-repository-verification.md)
must also match the repository, exact revisions and source path retained at
registration. GitHub repository identity, current remote branch
tips, maintainer authorization, and publication are checked separately. Use the
[repair branch check](repair-branch-check.md) to compare a reviewed binding with
GitHub when the branches are available.
GitHub access and model calls are unnecessary for this command.
[Git object reads](https://git-scm.com/docs/git-cat-file)

Java returns exit code `0` for a match, `3` for a blocked or interrupted check,
and `2` for an invalid invocation. The PowerShell wrapper warns for exit `3`
and raises an error for exit `2`.
