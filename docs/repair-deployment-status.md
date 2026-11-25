# Observe a merged repair's deployment status

Look up GitHub deployment records for a selected environment and the exact
merge revision reported by a [confirmed repair PR](published-repair-status.md).
Use the same intent file and trusted state directory as the publication.

```powershell
./scripts/Get-RepairDeploymentStatus.ps1 -Intent ./local-repair-intent.json -Environment production
```

By default the command reads the workbench's `.workbench` directory. Use
`-StateDirectory` for another trusted local store. Supply the actual deployment
environment name from GitHub. The check compares names case-insensitively.

The command first runs the numbered PR status check using the existing
[GitHub App configuration](github-repository-connection.md). It requires a
confirmed publication and a fresh merged PR observation with a full merge SHA.
Changes to the proposed head, branch identities, repositories or operation marker
block the deployment lookup. A changed target branch tip remains visible and does
not block reading the merge revision. GitHub reports the effective merge SHA for
merge commits, squash merges and rebases.
[GitHub pull request metadata](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#get-a-pull-request)

For an eligible merged PR, the command requests a separate installation token
limited to the same repository with **Deployments: read-only** and **Metadata:
read-only**. The installation administrator must grant Deployments read access
before the deployment query can succeed. The command cannot change installation
permissions. Only token issuance uses POST. All PR, deployment and status
operations are reads.
[GitHub Deployments API](https://docs.github.com/en/rest/deployments/deployments?apiVersion=2026-03-10#list-deployments)

| Result | Meaning |
| --- | --- |
| `OBSERVED` | GitHub returned matching deployment records and the bounded status reads completed. A deployment can have no status entries. |
| `NONE_OBSERVED` | GitHub returned a complete empty page for that merge revision and environment. Deployments recorded elsewhere, or under a different revision, are outside this lookup. |
| `NOT_READY` | The publication is unconfirmed or the PR has not merged. |
| `INCOMPLETE` | PR identity, permissions, response metadata, pagination or status reads left a gap. Inspect the reason and any partial records. |

The report includes the PR observation and its historical publication. Each
matching deployment includes its ID and creation time, so an earlier deployment
of the same revision remains distinguishable from a record created after merge.
The status history includes reported states, times and whether each status names
the selected environment. An omitted or empty environment is recorded as unknown.
The command projects the newest reported state for
each deployment only when the status page fits the bound, the latest entries
agree and the latest status belongs to the selected environment. An empty
history has no current state. A later `INACTIVE` status remains visible even
when the deployment previously reported `SUCCESS`.
[GitHub deployment statuses and retention](https://docs.github.com/en/rest/deployments/statuses?apiVersion=2026-03-10#list-deployment-statuses)

The command reads up to ten deployments and one page of up to ten statuses each.
A full page or pagination header leaves the result incomplete. A failed status
request preserves earlier observations and marks the remaining reads as
unattempted. The command neither follows response URLs nor prints descriptions,
payloads, credentials or URLs. It writes no journal entries or deployment state.

These are separate, point-in-time reports from GitHub. They cannot establish
which revision currently serves traffic, whether a later commit includes the
repair, or whether the deployed system is healthy. Compatibility evidence
applies to the inputs that were tested and release approval remains separate.
Java exits `0` for a completed observation or empty search, `3` for an
incomplete, unready or interrupted check, and `2` for invalid input or local
configuration. The PowerShell wrapper warns for exit `3` and raises an error
for exit `2`.
