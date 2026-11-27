# Export a repair evidence summary

Export one subscription-period repair as a versioned JSON summary for review or
handoff. Select the same verification, local Git repository, source path and intent
used to [bind the repair commit](repair-commit-binding.md). The destination must be
a new directory under an existing trusted local directory.

```powershell
./scripts/Export-RepairEvidence.ps1 -VerificationId verification-EXISTING-UUID `
    -Repository ./consumer-repository -Intent ./local-repair-intent.json `
    -SourcePath src/main/PeriodConsumer.java -Destination ./repair-evidence
```

By default the command reads `.workbench` at the workbench root. Supply
`-StateDirectory` for another trusted local store. The default export needs no
GitHub access, credentials, Docker or model calls. The command publishes
`evidence.json` only after gathering the evidence. It prints the file's SHA-256
and local binding state. An existing destination is never replaced. After a
failed write, a directory without `evidence.json` is incomplete and a retry
needs a new destination.

## What the summary records

| Section | Contents |
| --- | --- |
| `selection` | Repository identity, verification and operation IDs, source path and exact proposed commits. |
| `verification` | The retained receipt digest, recorded execution outcome, exact input digests, recomputed scenario results and comparison issues when available. A [repository-bound verification](subscription-repository-verification.md) also includes its declared versions, repository and source objects. |
| `binding` | The recomputed commit binding or the reason it is blocked. A matched binding includes the tree and source blob IDs. |
| `historicalApproval` | The status and timestamps of the local publication approval for this binding, including expiry or revocation. Current applicability is not checked. |
| `publication` | The attempt and response timestamps, request digest, original response and any reconciliation receipt, linked only when the journal matches the recomputed binding. |
| `github` | Whether fresh observations were requested, the PR and optional deployment observations, and any gaps. |

If verification is unavailable or the commit binding is blocked, the export
keeps that result and leaves dependent evidence absent. A publication for a
different binding, an unreadable journal and an attempt that never started are
shown separately. An uncertain original response remains visible even after
successful reconciliation.

## Include fresh GitHub observations

Add `-ObserveGitHub` to check the published PR. Add `-Environment production`
(substitute the actual environment name) to also look for deployment records
for the reported merge revision. The export only queries GitHub for a confirmed
publication linked to the recomputed binding. It uses the same scoped App
permissions and bounds as the [PR status check](published-repair-status.md) and
[deployment status check](repair-deployment-status.md).

Missing access, absent records, drift and incomplete reads stay visible in the
summary. GitHub observations are point-in-time records. The command creates no
PRs or deployments and changes no approval or publication journal.

The file includes repository and environment metadata, which may be private.
Share it only with the intended reviewers. It excludes credentials, source
contents, raw payloads and tool logs. Its digest identifies the exported bytes.
Independent re-verification still requires the retained evidence and Git objects.
An exit code of `0` means the file was written, including exports that document
unresolved evidence. It establishes no approval, deployment health or compatibility
beyond the recorded scenarios. Java uses exit `2` for invalid input or write
failure and `3` for interruption.
