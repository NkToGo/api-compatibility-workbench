# Invoice comparison read boundary

The shared `CoordinationRuntime` can now register read-only invoice trials against
one exact [invoice input bundle](invoice-comparison-inputs.md). This connects that
input format to the existing durable scope, deadline, read accounting and trace
machinery. It does not yet execute an invoice comparison adapter or an agent.
The historical two-case input format, trial records and campaign remain separate.

An explicitly selected [invoice investigation entry point](invoice-single-agent.md)
now permits the bounded invoice decision protocol through the same trial ledger.
The `forInvoice` factory described below remains read-only. Its policies grant no
model access, and model-enabled registrations are rejected by that factory.

## Registration and shared limits

`CoordinationRuntime.forInvoice(state, bundleDirectory, expectedIdentity)` validates
the captured inventory and holds an immutable input snapshot. The expected
identity must come from the trusted capture caller or a later frozen registration,
not from an untrusted directory's own claimed receipt. Preserve the source bundle
for reopening after a process restart. A new runtime validates it again; changed
disk contents fail reopening instead of changing an active snapshot.

`invoiceReadPolicy(maxReads, participants)` declares fixed participant IDs, the
same joint invoice case, read-tool policy references and no model protocols.
Registration binds the definition, selected slot, policy, manifest identity and
receipt hash before work. Wrong bundle/case identities, undeclared participants
and model-enabled policies are rejected. Input hashes are integrity bindings,
not signatures or authentication of the producer.

State lives under `.workbench/invoice-comparison-trials/trial-UUID/`, separately
from the historical `.workbench/coordination-trials/` namespace. The first attempted
participant operation starts one whole-trial deadline. New participants and
runtime instances cannot renew it. All participants share one read ceiling.

## Available tools

| Tool | Available information | Bound |
| --- | --- | --- |
| `describe` | Captured identity, provenance references, artifact count and source execution-coverage metadata | One metadata object |
| `catalog` | Aliases, content hashes and character lengths | 40 artifacts per page |
| `readPage` | Captured text, full artifact hash and exact offsets | 4,000 UTF-16 characters per page |

Every successful unique participant/operation ID consumes one `usage.toolCalls`
slot, including metadata and catalog calls. Exact retries reuse the retained
record across restarts. Different tools or arguments cannot reuse an operation
ID. Invalid reads consume no slot, but do not reset the deadline. Concurrent
participants cannot exceed the shared ceiling. Returned pages are detached copies;
tool contents remain untrusted data and grant no permissions.

The unpaged `read` API is unavailable for invoice inputs. No arbitrary filesystem
path, protected expectation/grade, execution request or model call is exposed.
The read-only factory cannot create a model broker. Model-call accounting and
operational finalization reuse the common runtime and record zero calls and
zero new consumer executions here. Future model adapters need separately bound
protocols and policies before dispatch; this step grants none of that access.

Snapshots verify recorded pages against the opened bundle. Revocation, expiry,
changed grants, altered records and incomplete ledger publication stop admission.
Final receipts preserve the chosen operational outcome; later requests cannot
reinterpret it or admit new participant work. Missing completion receipts remain
incomplete evidence. Publication is not automatically overwritten or retried as
a new operation. These are trusted-host controls, not isolation against a
malicious local process rewriting the entire state store.

## Check the actual read workflow

After the README build, use an existing captured bundle and its independently
retained identity:

```powershell
./scripts/Test-InvoiceComparisonRuntime.ps1 `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY `
  -InputIdentity EXPECTED-MANIFEST-SHA256
```

The command registers a new read-only trial, reads metadata, one catalog page and
the task page, then finalizes its trace. It needs no Docker, credential or source
case checkout. Output contains summary metadata and usage, not evidence contents.
The trace retains all three read records. An incomplete source bundle remains
explicitly incomplete even when the tool check completes successfully.

This command uses an unreviewed check definition and unrun placeholders for other
arms; it is not a frozen campaign or a measured participant result. Operational
completion does not establish semantic acceptance, migration compatibility,
coordination benefit or a routing recommendation. The separate single-context
adapter performs investigation; other invoice arms and quality review remain
subsequent work.
