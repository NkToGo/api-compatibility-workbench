# Retained migration planning inputs

An opt-in capture command retains the exact selected POM bytes and the
[source-bound configuration result](migration-configuration.md). Snapshots use the
existing [local evidence store](evidence-storage.md) and survive Maven `clean`,
source-server unavailability and application restart. Incomplete reads and
configuration gaps remain incomplete after reopening.

These are **planning inputs**, not a complete consumer source checkout, dependency
archive, runnable build, verified runtime configuration or execution permission.
Only explicitly selected POMs are fetched; modules, parents and arbitrary source
files are not followed. A separate [durable planning job](maintainer-jobs.md) can
refresh a retained snapshot without turning its input ID into an execution grant.
For the supported invoice layout, [consumer source capture](consumer-source-capture.md)
uses one such input as its parent and retains the registered source separately.

## Capture and inspect

Configure the read-only App, exact repository selection, POM plan and non-secret
declarations using the [configuration guide](migration-configuration.md). After
[building and verifying](../README.md#build-and-test), run in PowerShell 7:

```powershell
./scripts/MigrationInputs.ps1 -Action capture -Selection ./local-repository-selection.json -Plan ./local-pom-plan.json -Configuration ./local-migration-configuration.json
```

The default store is `.workbench/evidence/`, ignored by Git and outside Maven build
output. Use `-EvidenceDirectory` for another trusted private local directory. The
command repeats the existing App scope, exact-revision, tree and blob checks,
retaining verified bytes in the same bounded read operation. It neither downloads
each POM twice nor runs Maven, consumer code, Stripe requests or models.

Stdout contains a summary with `inputId`, the separate `bindingIdentity`, recorded
declaration/inventory states, retained POM count and byte count, and limitations.
It contains no raw POM text, token or key. Preserve the complete 64-character
lowercase input ID for later operations:

```powershell
./scripts/MigrationInputs.ps1 -Action list
./scripts/MigrationInputs.ps1 -Action inspect -InputId INPUT_ID
```

Replace `INPUT_ID` with the capture's ID. These commands work offline without App
credentials, repository selection or configuration-file arguments. They verify
every retained artifact and recompute the configuration binding before returning
the summary. They do not query current repository access or renew credentials.
The trusted Java API can also return defensive copies of retained POM bytes for
later workflow stages; the CLI deliberately exposes summaries only.

Capture exits `0` for complete operator declarations, `3` when a snapshot was saved
with gaps, `1` for connection/interruption and `2` for configuration, storage or
verification failure. A gap capture still prints its retained input ID. Offline
inspect/list exit `0` when stored inputs verify, even if their recorded states have
gaps. Inspect success does not change those states. The PowerShell launcher raises
an error on nonzero exit status. A failure before a verified repository snapshot
is available produces no finalized input snapshot.

## Retention and publication

The store retains a `binding.json` object plus zero to eight `pom-N.bin` objects.
The numeric names are internal artifact names; original repository paths remain
in the binding's ordered inventory and never become local output paths. The
binding includes the normalized non-secret configuration actually used, selection,
repository observation, original per-file outcomes and configuration issues. It
does not archive the original configuration file's whitespace or field ordering.

Each retained POM is at most 64 KiB, with 256 KiB total POM bytes. The binding is
limited to 4 MiB; larger reports fail storage explicitly. POMs rejected by the XML
scanner are retained when their Git object bytes were verified. Missing, denied,
oversized and unrequested POMs have no invented source object.

The existing `objects/<sha256>` content store, writer lease, forced file writes
and atomic publication are reused. A separate `inputs/<input-id>.json` reference
is published last, after all objects and the input manifest exist. Execution
receipts remain under `runs/`; an input ID cannot be opened as an execution run.
Existing execution storage and CLI behavior are unchanged.

The input ID is the SHA-256 identity of the manifest over all retained artifact
names, sizes and byte hashes, including the binding report. Republishing the same
captured result is idempotent. A fresh capture has a new observation and can
produce a different input ID even when the semantic binding identity is unchanged.
Changed source/configuration produces another snapshot and preserves earlier
facts and failures.

An interruption before reference publication leaves no finalized input entry.
Verified unreferenced objects can be reused when the same captured input is
republished. Orphan objects and `.pending` files are not automatically deleted.
This is atomic local snapshot publication, not a journal for resuming an
interrupted GitHub fetch or an unconditional filesystem/power-loss guarantee.
After process loss without a completed reference, a new capture is a new
observation. The separate planning job retains its selected input ID and owns its
state transitions; recovery finalizes an already-published matching snapshot or
records interruption without repeating the fetch.

## Revalidation and trust

Reopening first verifies the manifest and every object's size and digest. It then
checks each retained POM's Git blob ID and SHA-256, reruns the bounded XML scanner,
reconstructs the recorded inventory, and recomputes the complete configuration
result. Missing/extra objects, altered declarations, invented successful reads,
changed digests and inconsistent issue/status records fail verification. A
different current binding policy also fails rather than rewriting history.

The prior read's observation time, token expiry, failures and retry hints remain
historical facts. An expired installation token does not prevent offline inspection
and does not establish current repository access. New network or execution actions
must independently validate their own authorization and current inputs.

Integrity checks detect inconsistency; they do not authenticate a hostile producer
who can replace both artifacts and references. The low-level evidence-store input
methods validate artifact storage, while `MigrationInputCapture` performs semantic
revalidation. No external snapshot-import trust path, signature, multi-user
authorization or execution grant is supplied here. Unknown effective versions and
advisory declarations retain their original limitations.

## Source privacy and operation

**All selected POM bytes are retained**, including comments and unrelated
properties that the metadata report omits. Keep the store private to the operator
or service account, protect backups and avoid selecting files containing secrets.
There is no automatic source redaction or at-rest encryption in this local store;
redacting bytes would change their source identity. App keys and `.env` files are
not selected or copied. Do not log raw artifact maps or publish the store.

Retention, deletion, backup automation and organization isolation remain release
work. The local filesystem and store directories must be trusted; cooperating
writers use the existing lease, but hostile concurrent filesystem replacement is
not a supported security boundary.

- [JDK atomic move semantics](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))
