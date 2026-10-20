# Single-context invoice investigator

One adaptive investigator inspects both invoice consumers and their shared ledger
through the [invoice runtime](invoice-comparison-runtime.md). It selects captured
pages and returns a cited advisory dossier or explicit abstention. Independent
workers, coordinated review and paired measurement remain separate work.

## Scope and limits

The host explicitly selects `CoordinationRuntime.forInvoiceInvestigation` with
the original bundle and its independently retained expected identity. This entry
point permits only the invoice decision protocol. `forInvoice` stays read-only;
model-enabled registrations are rejected by that factory. No fresh consumer
execution, shell, network tool, edits, delegation or protected grades are exposed.

The check definition uses the common 60-call, 20-minute ceiling, 64,000 input
bytes and 2,048 output tokens per request. The compiled policy allows 100 reads.
Bootstrap metadata costs one read; each model-selected catalog/text action costs
another. Every request uses the shared reservation ledger, including uncertain
and known-not-dispatched slots. There are no hidden summary or aggregation calls.
A registered comparison definition binds its own declared common limits.

The forced Responses function uses a strict schema with parallel calls disabled,
following the [OpenAI function-calling contract](https://developers.openai.com/api/docs/guides/function-calling).
The host independently validates fields, actions, ranges, aliases and citations.
Context includes all read metadata and the six latest page contents, trimmed
oldest first to the UTF-8 byte ceiling. Older catalog rows/text stay retained;
requesting them again costs another read/call. Private reasoning is not retained.

## Dossiers and authority

Each finding cites text pages actually read by this participant. Catalog pages
and invented IDs cannot support claims. `ANSWERED` additionally requires a cited
summary, a finding, complete task/configuration reads and both baseline consumer
sources, one complete business artifact, one complete order artifact and complete
captured execution coverage. Paged gaps cannot satisfy those checks. Incomplete
captures can produce `ABSTAINED` with cited partial findings and limitations.

These are minimum access checks, not semantic grading or inspection of every
scenario. A valid citation can still support an incorrect claim. Outputs have
`review: null`. Explicit abstention, model failure, denied reads, unsupported output
and remote uncertainty remain distinct. Operational completion establishes no
migration certification, repair approval, routing rule or coordination benefit.
Source metadata such as `modelCalls: 0` and `comparisonAdaptersAvailable: false`
describes disclosure; current model activity belongs to the separate trial trace.

## Commands

Run the README build first. Supply an existing captured bundle and an identity
retained independently from that directory:

```powershell
./scripts/Run-InvoiceSingleAgent.ps1 -Action policy
./scripts/Run-InvoiceSingleAgent.ps1 -Action check `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256
./scripts/Run-InvoiceSingleAgent.ps1 -Action read `
  -InputDirectory CAPTURED-BUNDLE-DIRECTORY -InputIdentity EXPECTED-MANIFEST-SHA256 `
  -TrialId trial-EXISTING-UUID
```

`check` starts one new operational trial with `gpt-5.6-terra`, an unreviewed
definition and unrun placeholders for other arms. It is not a frozen campaign.
Use `-Action run -TrialId trial-EXISTING-UUID` with the same input arguments to
resume. `register` also accepts `-PlanPath`, `-WorkloadId` and `-Repetition`; its
single-agent implementation must match the compiled policy identity.

Registration and inspection load no credential. `check`/`run` use the existing
process `OPENAI_API_KEY` or ignored `.env.local` and restore the prior environment.
Output contains status, usage and report location, without evidence payloads or
model statements. A zero process exit alone does not establish trial completion.

## Retention and recovery

Preserve `.workbench/invoice-single-agent/trial-UUID/`, its shared
`.workbench/invoice-comparison-trials/` trial and the original input bundle
together. Registration binds implementation, prompt/schema, tools, context and
final selection. Changed implementations cannot resume unfinished work under an
old policy; completed inspection remains available.

Resume reconstructs the same requests and operation IDs, reusing completed
responses without redispatch. `ACTIVE` keeps the current trial open. Unknown
dispatch or missing mappings stop without guessed retries. Restarts renew no
deadline or permission. Terminal inventories preserve usage and partial artifacts.
The adapter can restore its own interrupted receipt from a matching result;
incomplete shared-runtime receipts fail closed. Changed requests, pages or
dossiers cannot silently replace retained evidence. Fresh-process completed
inspection/resume requires no credential or new work.

These are trusted-host integrity checks, not tenant isolation, signed attestations
or protection against a malicious process rewriting the store. Keep potentially
sensitive evidence/claims on a trusted filesystem and outside Git.

## Recorded operational check — 2026-10-20

One `gpt-5.6-terra` trial, `trial-1b0028ba-6740-4c17-8c3c-ca93cd07f6ac`,
used the existing complete invoice capsule without recapturing or dispatching
consumers. It completed in 59,411 ms with 22 reserved/registered/completed model
calls, 22 evidence reads including bootstrap metadata, zero new consumer
executions and zero unresolved usage slots. Recorded usage was 240,059 input
tokens and 2,719 output tokens. The dossier declared `ANSWERED`, with no review.

Reads included both baseline consumer sources, the webhook renewal policy, task
and build configuration, one delivery's two consumer observations and one order.
This was a selected subset, not inspection of every available source or scenario.
No semantic acceptance, paired comparison or routing conclusion was established.
Credential-free fresh-process inspection and completed resume preserved all 656
non-lock artifacts across the input capsule, runtime and adapter stores.

Result SHA-256: `c91323fc4f0a9c1b5d5e88e160ec3dc3df6a7b0738a324b5444f5e7bd6e0790e`.
Dossier SHA-256: `4ca7097687e7c4aaf39caedbdda9aa0a414a11135c35f6d46e670b440715d07e`.
