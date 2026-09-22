# Local evidence storage

The isolated-run command retains finalized execution records in
`.workbench/evidence/` by default. This directory is ignored by Git and is outside
Maven's `target` tree. Successful, failed, timed-out and output-limited executions
are retained without changing their recorded status. A completed execution can
still contain observed compatibility regressions.

Use `-EvidenceDirectory` on `Run-IsolatedStripeCase.ps1` and `Evidence.ps1` to select
another location. Use a trusted local filesystem with file-lock and atomic-rename
support. The directory must be private to the service/operator; storage permissions
and backups remain the operator's responsibility. This is a single-host backend,
not an organization authorization system or a network-filesystem service.

## Inspect and export

After building the backend:

```powershell
./scripts/Evidence.ps1 -Action list
./scripts/Evidence.ps1 -Action verify -RunId acw-00000000-0000-0000-0000-000000000000
./scripts/Evidence.ps1 -Action export -RunId acw-00000000-0000-0000-0000-000000000000 -Destination ./saved-run
```

Replace the example identifier with the `Stored run` identifier printed by the
isolated-run command or an identifier from `list`. Export requires a new directory
whose parent already exists. It never merges with an existing directory. Open
its `report.html` when present and consult `execution.json` for the execution
status. An exported failed run may correctly have no report.

An existing finalized host-export directory can be imported explicitly:

```powershell
./scripts/Evidence.ps1 -Action import -Receipt ./path/to/run/execution.json
```

Import accepts the controller's known artifact filenames only. It checks every
file against the receipt's hash inventory, rejects missing/extra files and linked
artifact files, and requires a final `execution.json`. Each file is bounded to
4 MiB. Re-importing identical evidence is idempotent; different evidence under the
same run identity is rejected. Older controller receipts can be retained, but
import cannot recover provenance or workspace files they never exported.

## Storage and publication

- `objects/<sha256>` stores exact bytes by content hash, including run manifests.
  Identical content is reused after verification.
- `runs/<run-id>.json` references a manifest hash. The manifest binds artifact names,
  sizes and hashes and declares `host-exports-only` retention.
- `writer.lock` serializes cooperating writers across processes. In-process
  competing store instances wait for the same operating-system lock.

The writer captures and verifies source bytes, writes new objects using exclusive
temporary files, forces their contents to disk and renames them atomically. The
run reference is published last. Readers see either no finalized run or a reference
whose complete set of objects must verify. Temporary reference files are ignored.
An interruption before publication may leave unreferenced objects or `.pending`
files; a retry can reuse verified objects. Automatic garbage collection is not
implemented, so do not delete objects merely because their purpose is unclear.

`list`, `verify` and `export` verify the manifest and all retained artifacts. Missing
or changed bytes fail the operation rather than yielding a successful verification.
An export verifies everything before creating its destination and writes its
execution receipt last; a directory lacking that receipt is incomplete.

Atomic visibility and forced file writes do not establish an unconditional
power-loss guarantee for every filesystem/controller. Directory-entry persistence,
hardware failure and disk loss require tested backups. A hostile actor with write
access can replace both data and references: these hashes detect inconsistency,
not authorship. Stored observations and source references do not independently
prove that an execution occurred. Do not treat integrity verification as release
approval, a signature or validation of an untrusted producer.

## Retained provenance and remaining boundaries

Current successful case exports retain the worker report, rendered view, exact
expectations, upstream consumer revisions and excerpt digests, provider/SDK
provenance, harness and environment manifests, host request, diagnostics and
execution receipt. The controller checks the additional manifests against the
worker's input identities or artifact inventory before storing them.

The full container workspace, compiled consumer classes, synthetic fixture bundle
and dependency JARs are not exported by this step. The original worker inventory
can reference those discarded files; it is not the store's retained inventory.
The store manifest describes only bytes actually retained. An image digest is a
reference, not an archived image. Exact offline re-execution still requires the
corresponding image/input artifacts to remain available elsewhere.

The store records final attempts, not running workflow state. A host killed before
receipt finalization can still leave an incomplete attempt. Durable scheduling,
restart reconciliation, cancellation, retention policies, backup automation and
multi-user authorization are separate work. A storage failure does not rewrite
the execution result: the CLI fails and the temporary finalized receipt can be
imported after the storage problem is resolved.

Tests cover source removal followed by reopening/export, conflicting history,
interrupted publication, competing writers, missing/modified/oversized input,
unsafe artifact names and damaged stored objects. Actual container tests verify
that successful reports, provenance and failed execution outcomes reach the store.
