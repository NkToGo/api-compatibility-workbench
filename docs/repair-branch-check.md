# Check repair branches on GitHub

Compare GitHub's target and proposed repair branches with the exact commits in
a [local repair binding](repair-commit-binding.md). The result is a read-only
observation tied to that binding.

Run the local check with the retained subscription verification, trusted local
Git repository, publication intent and source path. Review its output and pass
its `bindingIdentity` alongside the same selection.

```powershell
./scripts/Check-RepairBranches.ps1 -VerificationId verification-EXISTING-UUID `
    -Repository ./consumer-repository -Intent ./local-repair-intent.json `
    -SourcePath src/main/PeriodConsumer.java -BindingIdentity REVIEWED_BINDING_ID
```

By default the command reads verifications from `.workbench`. Use
`-StateDirectory` for another trusted local store. It recomputes the local
binding before any credential load or network request. A blocked or changed
binding stops the check.

The [GitHub App configuration](github-repository-connection.md) uses the existing
read-only Contents and Metadata permissions. The command checks the App ID
from the intent and verifies the installation, account, single-repository scope
and exact base commit. It also checks the base tree identity against the local
binding. [GitHub reference permissions](https://docs.github.com/en/rest/git/refs?apiVersion=2026-03-10#get-a-reference)

For the same repository it resolves the exact target branch, then the proposed
repair branch, then the target branch again. The repair branch name is derived
from the [publication intent](repair-pull-request-lookup.md). Both branches must
already exist. The check stops at the first blocker or incomplete read, retaining
earlier observations. A successful response must contain the selected full
reference, a commit object type and a complete commit ID.

| Result | Meaning |
| --- | --- |
| `MATCHED` | All three observations matched the exact commits in the binding. |
| `BLOCKED` | The local binding was blocked or changed, the remote base tree differed, or an observed branch pointed to a different commit. |
| `INCOMPLETE` | GitHub access or an exact branch read could not be confirmed. A 404 is reported as missing or inaccessible. |

Results include expected and observed commit IDs and observation times when
available. They exclude returned URLs, provider response bodies and credentials.
The sequential reads can detect a target move between reads. They provide no
atomic snapshot or guarantee that either branch still points to the same commit
afterward. Publication requires a fresh check and separate authorization.

Java returns exit code `0` for a match, `3` for a blocked, incomplete or
interrupted check, and `2` for an invalid invocation or configuration. The
PowerShell wrapper warns for exit `3` and raises an error for exit `2`.
