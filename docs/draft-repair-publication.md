# Publish an approved repair draft

Open a draft pull request for a verified subscription-period repair. Prepare
the selected base and repair branches in the same repository and record
[local publication approval](repair-publication-approval.md) for the exact
repair binding. Use the same intent file and trusted state directory throughout
the operation.

The [GitHub App](github-repository-connection.md) needs Contents and Metadata
read access and *Pull requests* write access to the selected repository. The
publisher requests a token scoped to that one repository and verifies the
effective permissions. GitHub must support drafts in the selected repository.
[Create a pull request](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#create-a-pull-request)

```powershell
./scripts/Publish-RepairDraft.ps1 -Publish -Intent ./local-repair-intent.json `
    -VerificationId verification-EXISTING-UUID -Repository ./consumer-repository `
    -SourcePath src/main/PeriodConsumer.java
```

By default the command uses the workbench's `.workbench` state directory. Use
`-StateDirectory` for another local store. The command recomputes the binding
and checks the exact GitHub branches and approval. It saves the intent,
binding and exact request digest under `repair-publications/<operationId>/`
before the create request. A final local approval check catches expiry or
revocation while that record is being written.

The request uses a fixed title and description with the intent marker. Draft
publication operates on the pre-existing branch names. Branch checks are
sequential and GitHub can observe a branch move in the gap before creation.
The response must confirm an open draft and match both refs, commits, repository
identities and marker for the workbench to report `CREATED`. The output contains
safe metadata, such as the PR number and recorded outcome.

## Reconcile an interrupted request

```powershell
./scripts/Publish-RepairDraft.ps1 -Reconcile -Intent ./local-repair-intent.json
```

Once `publication-started.json` exists, `-Publish` returns the retained outcome.
If GitHub's response was lost, `-Reconcile` uses a separate read-only token and
the [exact PR lookup](repair-pull-request-lookup.md). It can run after local
approval expires or is revoked and needs only the intent and journal.
Reconciliation preserves the original response and records a matching PR
separately. A conflict, incomplete search or empty page leaves the result
unknown. An inconclusive lookup cannot trigger another create request.
Respect any reported retry time before running another read.

Once publication is confirmed, use the [published repair status check](published-repair-status.md)
to see current PR metadata without changing the saved publication record.

| Result | Meaning |
| --- | --- |
| `CREATED` | GitHub's create response confirmed the exact open draft. |
| `RECONCILED` | Read-only lookup found an exact PR match for a previously uncertain attempt. |
| `UNKNOWN` | The journal records an attempt without a confirmed match. Inspect the response and lookup metadata. |
| `NOT_SENT` | The attempt was journaled, but the final local approval check refused dispatch. |
| `BLOCKED`, `BUSY`, `NOT_STARTED` | Approval or branch checks failed, another invocation holds the lease, or the requested journal does not exist. |

Preserve the journal to retain the once-per-operation dispatch guarantee.
Authorization covers draft creation. Branch creation, editing, merging and
deployment require separate operations.

Java exits `0` for a confirmed or reconciled result, `3` for an incomplete or
blocked operation, and `2` for an invalid invocation or inaccessible local
configuration. The PowerShell wrapper warns for exit `3` and raises an error
for exit `2`.
