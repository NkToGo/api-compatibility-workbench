# Registered investigation tools

The host can register the curated Stripe case and expose four narrow operations
over it. These are application tools, not an agent runtime, MCP server or general
repository interface. They make no model calls and require no API key.

## Run locally

Build the backend and [immutable worker image](isolated-execution.md) first. From
the repository root, in PowerShell 7:

```powershell
$contextId = ./scripts/Investigation.ps1 -Action register
./scripts/Investigation.ps1 -Action describe_case -ContextId $contextId
./scripts/Investigation.ps1 -Action read_input -ContextId $contextId -InputId baseline-source
./scripts/Investigation.ps1 -Action run_case -ContextId $contextId -RequestId initial-check
./scripts/Investigation.ps1 -Action read_run -ContextId $contextId -RequestId initial-check
```

`run_case` waits for the bounded container execution. Reusing `initial-check`,
including from a new process, refers to the same recorded run. A completed
process does not mean compatibility: the curated case reports four target
regressions and two remaining regressions in the historical reference.

The default state root is `.workbench/`; `-StateDirectory` selects another trusted
local root. Preserve its `investigations/`, `executions/` and `evidence/` directories
together. Maven `clean` does not remove the default root.

## Tool contract

The host binds the context ID. Tool arguments cannot change that context, select
a filesystem path, alter the image, supply a command or receive credentials.
Unexpected fields are rejected. Responses have `formatVersion: 1` and either
`ok: true, value: ...` or `ok: false, error: ...`. This is a local tool contract,
not a stable public API.

| Tool | Arguments | Result |
| --- | --- | --- |
| `describe_case` | none | Case and image identities, input IDs/hashes, variants and limits |
| `read_input` | `inputId`, `offset`, `maxCharacters` | Hash-bound text page marked `untrusted-data` |
| `run_case` | `requestId` | Existing or newly registered fixed case execution |
| `read_run` | `requestId` | Status or verified result of that context's mapped request |

Input IDs are `case-index`, `provenance`, `fixtures`, `expectations`,
`baseline-source` and `reference-source`. Each captured file is at most 256 KiB.
Pages contain at most 4,000 UTF-16 code units; offsets use the same units and
`nextOffset` identifies the next page. No tool follows links or instructions
contained in source text. Returned text remains untrusted data when a model is
introduced; this boundary does not itself solve semantic prompt injection.

Request IDs contain 1–80 ASCII letters, digits, underscores or hyphens. Each
context allows two distinct test requests, including failed attempts. A retry
with the same ID consumes no additional test slot. The host's registration
operation creates the context and its budget; it must never be exposed as an
unrestricted model tool.

## Evidence and failure behavior

Registration validates the fixed case inventory, executable hashes and pinned
SDKs, then captures its input bytes. Later checkout edits do not change this
snapshot. Every read verifies the captured bytes; test requests verify all inputs.
An immutable image ID is necessary but is not proof that the image contains the
registered case. Before returning a completed assessment, the tool verifies the
retained receipt's run/image identity and all six input hashes against the actual
worker report. A mismatch returns `INPUT_IDENTITY_MISMATCH`, with no assessment.
The original run remains retained for diagnosis.

Successful tool results summarize the host-validated typed observations and
assessments, with receipt and report SHA-256 identities. Full input snapshots stay
in the retained report rather than being repeated for every scenario. Raw worker
stderr and HTML are not returned as tool results. Execution failures return
`assessment: NOT_AVAILABLE`; tool failures must not be interpreted as passing
checks. Source content and model interpretations cannot overwrite execution facts.

Mappings are published before dispatch. The existing [execution journal](execution-recovery.md)
owns the run lease and started marker. Repeated requests never replay an already
started attempt. `ACTIVE` and `RECOVERY_REQUIRED` are returned as statuses;
recovery and cancellation remain operator actions through `Execution.ps1`.
`BUSY` means context registration is briefly locked; retry the same request ID.
`TEST_BUDGET_EXHAUSTED` forbids another distinct run in that context.
`INVALID_REQUEST` rejects unsupported arguments or unavailable mapped inputs;
`UNAVAILABLE` requires diagnosis, not a new request ID that could duplicate work.

A crash between journal creation and mapping publication may leave an unstarted
queued run. It has performed no execution; an operator can inspect and cancel it.
This local design does not claim a transaction across the two journals or protect
against a malicious host user editing both data and its hashes. Store state on a
trusted local filesystem. Multi-user authorization and hostile repository intake
are not implemented.

## Scope and verification

Only the fixed curated case can run, under the existing container restrictions
and short-lived execution permission. These tools cannot edit code, install
dependencies, run arbitrary tests, browse the network or publish repairs.
Fixtures and the historical reference are deliberately readable here; this case
must not be described as a blind repair evaluation. Protected verification and
new repair candidates belong to later investigation steps.

Default tests cover bounded access, snapshot integrity, duplicate requests,
context isolation, exhausted budgets, uncertain dispatch and identity mismatches.
`InvestigationToolsContainerTest` exercises the complete real worker flow and
checks that repeated calls return the same retained evidence. Linux CI runs it
alongside the existing container tests.
