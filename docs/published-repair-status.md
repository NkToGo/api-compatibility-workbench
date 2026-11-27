# Check a published repair's status

Read the current state of a repair pull request confirmed by a retained
[publication or reconciliation record](draft-repair-publication.md). The check
uses the recorded PR number and target repository. Use the same intent file and
local state directory as the publication.

```powershell
./scripts/Get-RepairPullRequestStatus.ps1 -Intent ./local-repair-intent.json
```

By default the command reads the workbench's `.workbench` state directory. Use
`-StateDirectory` for another trusted local store. An unconfirmed attempt stops
before credential loading. A confirmed attempt uses the existing
[read-only GitHub App configuration](github-repository-connection.md) with
Contents and Metadata read access to the selected repository. The numbered
PR read does not require the original base commit to be reachable.
[GitHub pull request permissions](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#get-a-pull-request)

The report keeps `publication`, the retained journal projection, separate from
`current`, the fresh GitHub observation. Current metadata includes whether the
PR is open, closed, or merged and whether GitHub reports it as a draft. It shows
the current base and head commit IDs and flags differences in repository, ref,
commit or operation marker. An exact creation response remains historical
evidence even if the PR changes later. A merged observation reports the GitHub
merge time and commit ID when available. When REST omits the merge commit ID for
an otherwise matching merged PR, a fixed [GitHub GraphQL query](https://docs.github.com/en/graphql/reference/pulls#pullrequest)
tries to read it using the same repository-scoped token. The returned repository,
PR number, refs, commit IDs and merge time must agree with the REST observation.
An unavailable or inconsistent answer preserves the observed merge, leaves the
merge commit ID empty and records the failure separately. PR status carries no
deployment conclusion.
To observe GitHub's deployment records for that revision in a specific
environment, use the [separate deployment status check](repair-deployment-status.md).

| Result | Meaning |
| --- | --- |
| `OBSERVED` | GitHub returned a usable observation for the selected repository and PR number. Inspect the mismatch list before using it as current evidence. |
| `NOT_CONFIRMED` | The journal contains no confirmed PR for this intent. Reconcile an uncertain publication first. |
| `INCOMPLETE` | Access failed, the returned identity differed, or metadata was inconsistent. A missing or inaccessible response does not imply a closed PR. |

The command writes neither to GitHub nor to the local journal. The result is a
point-in-time observation. It excludes titles, bodies, returned URLs, and
credentials. Java exits `0` for an observation, `3` for an incomplete,
unconfirmed, or interrupted check, and `2` for an invalid invocation or
configuration. The PowerShell wrapper warns for exit `3` and raises an error
for exit `2`.
