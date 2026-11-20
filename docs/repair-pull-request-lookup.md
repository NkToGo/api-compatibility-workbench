# Look up a repair pull request

Check whether GitHub shows a pull request that matches a particular publication
intent. A write response can be lost after GitHub has created the pull request.
The lookup separates an exact match, conflicting metadata, an empty observation,
and an incomplete search.

## Describe the intent

Copy the [example intent](../examples/repair-pull-request-intent.json) to a local
file. Its IDs and commit SHAs are placeholders. The intent pins the App,
installation, account, repository, base branch and exact base and proposed head
commits. A fresh publication gets a fresh UUID. Keep the same intent for later
lookups of that publication.

After [building the backend](../README.md#build-and-test), inspect the local plan.

```powershell
./scripts/Inspect-RepairPullRequest.ps1 -Describe -Intent ./local-repair-intent.json
```

The output contains the planned head branch and a marker derived from the exact
intent. The marker must appear as a standalone line in a matching pull request
body. Read the marker from parsed JSON, for example with PowerShell's
`ConvertFrom-Json`. This local command requires no credentials or network.
The intent contains metadata only.

## Look up the matching metadata

Use the [GitHub App configuration](github-repository-connection.md) with
**Pull requests: read-only** as well as read-only contents and metadata. The
lookup requests one token scoped to the selected repository and verifies its
effective permissions. The existing source read commands keep their current
token scopes. [GitHub list permissions](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#list-pull-requests)

```powershell
./scripts/Inspect-RepairPullRequest.ps1 -Lookup -Intent ./local-repair-intent.json
```

The command reads open and closed pull requests for the exact head/base branch
filter. An exact match requires the same repository on both sides, the declared
refs and commits, and a single matching marker. It projects only pull request
numbers, states, draft status when supplied, and mismatch categories. The command
does not follow returned URLs or include pull request bodies or titles in output.

| Result | Meaning |
| --- | --- |
| `MATCHED` | One pull request matched the intent. The reported state is open, closed, or merged at the time of this read. |
| `NOT_OBSERVED` | The bounded lookup returned an empty page without pagination. An earlier create request may still be unresolved. |
| `CONFLICT` | More than one pull request was returned, or metadata did not match. Branch movement can cause a conflict even when an earlier match existed. |
| `INCOMPLETE` | The lookup failed, returned invalid metadata, or reached its search bound. |

The lookup stops when the first page fills its ten-result limit or GitHub
indicates pagination. It makes no automatic retry. An empty or incomplete
observation provides no permission to submit another create request. The marker
correlates metadata with the intended operation. It is not proof of authorship,
patch verification, or authority to publish. The lookup creates no branch or
pull request. [GitHub pagination](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)

Java returns exit code `0` for a successful description or exact match, `3`
for a match that could not be established, and `2` for invalid commands or
configuration. The PowerShell wrapper warns for exit `3` and raises an error
for exit `2`.
