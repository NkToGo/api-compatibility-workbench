# Invoice consumer source capture

This command saves the registered source for the invoice webhook and reconciliation
case from an exact GitHub revision. It starts from a [retained planning snapshot](migration-input-retention.md)
and a matching host repository selection. The scope is the existing
`consumer-sources-v1` catalog for the invoice subscription case. It includes the
build registration, four POMs, shared Java policy and both Java versions of each
consumer. The companion webhook adapter is outside this catalog.

## Capture and inspect

After [building the backend](../README.md#build-and-test), configure the read-only
App and selection using the [connection guide](github-repository-connection.md).
In PowerShell 7, supply the retained input ID and the original selection:

```powershell
./scripts/ConsumerSources.ps1 -Action capture -InputId INPUT_ID -Selection ./local-repository-selection.json
./scripts/ConsumerSources.ps1 -Action inspect -CaptureId CAPTURE_ID
./scripts/ConsumerSources.ps1 -Action list
```

Replace `INPUT_ID` with the planning snapshot's 64-character ID and `CAPTURE_ID`
with the returned capture ID. Use the same `-EvidenceDirectory` for the capture
and its parent snapshot. The default is `.workbench/evidence/`, outside Maven
build output. Inspection and listing work offline without App settings.

The parent must declare the invoice case, match its observed repository, and
select the catalog's root, webhook and reconciliation POMs. The core POM is
optional in planning and required in the source capture. Unknown configuration
remains visible through the parent declaration state. The supplied selection must
match the retained installation, account, repository name, repository ID and
commit. Capture verifies current App access and compares the App and root tree
with the historical snapshot before reading Git objects.

## What the result establishes

The reader follows fixed paths through nonrecursive Git trees. It checks tree
object hashes and verifies the bytes of each selected regular blob against its
Git identity and size. The host already knows the registration checksum and file
list. The captured registration must match that checksum before any of its file
digests are used. Its declarations cannot choose additional files, dependencies
or commands. Retained planning POM bytes that overlap the new read are compared
directly.

`CAPTURED_REGISTERED_SOURCE` means all ten files were retained and match their
registered contents, the visited catalog directories contain no unexpected
entries, and any overlapping planning POM bytes agree. `CAPTURED_WITH_GAPS`
preserves successful reads and records missing or unsupported entries, size
limits, catalog or planning conflicts, unexpected directory entries and stopped
reads. Unexpected entries inside the catalog are counted without fetching their
contents or returning their names. Unrelated entries outside the catalog are
outside the assessment. Symlinks and submodules are not followed.

The source phase allows up to 64 Git object requests in addition to App and
revision checks, 64 KiB per file and 256 KiB of source in total. A 180-second
deadline is checked at source-read boundaries, with the existing 30-second
per-response limit. Failed or malformed responses stop further reads. A retry
hint from GitHub is recorded where available. There is no automatic retry.
Authentication or revision failure before the source phase produces no capture.
An interruption before local publication leaves no finalized capture reference.

Capture prints metadata and exits `0` for a matching catalog or `3` for a retained
capture with gaps. Connection failures before reading and interruption exit `1`.
Invalid scope, local configuration or evidence integrity exits `2`. Offline
inspection returns historical states and exits `0` when their records verify.
The PowerShell launcher raises an error on nonzero status.

## Retention and limits

The evidence store publishes a `consumer-sources/<capture-id>.json` reference
after recording the observation and verified blobs. This namespace is separate
from planning inputs and execution receipts. The ID identifies the full
observation, including its parent and timestamps. Keep the parent snapshot and
capture in the same store. Offline inspection revalidates stored byte hashes,
Git blob identities, the retained parent binding and the catalog comparison.
GitHub access and tree traversal are historical observations. Offline inspection
does not authenticate the remote again or reconstruct the original tree responses.

The store retains full bytes for selected files that were read, including files
whose contents differ from the registered digests. Keep it private to the
operator or service account and protect its backups. The command outputs no file
contents, credentials or provider response bodies. The store relies on a trusted
local filesystem as described in the [evidence storage guide](evidence-storage.md).

Capture does not resolve effective Maven or Stripe settings, download consumer
dependencies, materialize a checkout, compile the captured code or grant
execution. Any later verification needs separate admission, a host-owned
execution policy and independently controlled assertions. A source capture
alone says nothing about compatibility or readiness to deploy.

- [GitHub Git trees](https://docs.github.com/en/rest/git/trees#get-a-tree)
- [GitHub Git blobs](https://docs.github.com/en/rest/git/blobs#get-a-blob)
