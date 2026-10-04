# Single-agent comparison adapter

One adaptive context investigates both captured curated cases through the
[shared trial runtime](coordination-runtime.md). It chooses evidence reads and
returns one complete two-case dossier. This is the `SINGLE_AGENT` arm of the
[comparison protocol](coordination-comparison.md), not two sequential isolated
investigator sessions. Independent/team adapters, paired measurement and semantic
acceptance review remain separate work.

## Context, tools and output

The participant sees both case IDs, exact input identities and available aliases.
Each response selects one `read_evidence` or `finish` action through a strict forced
Responses API function, `bundle_investigation_decision`, using the
[OpenAI function-calling contract](https://developers.openai.com/api/docs/guides/function-calling).
The host validates the
decision independently, including duplicate JSON fields, unsupported actions,
per-case aliases, integer offsets and bounded strings/arrays. Existing investigation
and repair protocols retain their original behavior.

`read_evidence` selects a captured case/alias and at most 4,000 UTF-16 characters.
The response records a page ID, artifact/page hashes, offset, next offset and total
characters. All content remains untrusted data. There is no model tool for shell,
network, edits, fresh consumer execution, delegation or permissions. Prior answers,
grading judgments and candidate repairs are absent from the common input bundle.
Application expectations are available evidence, not quality-review judgments.

Every request retains metadata for all previous reads and content from the six
most recent pages across both cases. Content is removed oldest first if necessary
to meet the registered UTF-8 input ceiling. If metadata alone exceeds that ceiling,
the trial stops explicitly. Older page content stays in the ledger and can be read
again; metadata is not treated as that content. There is no generated memory summary,
hidden compression call or private reasoning transcript. This context policy is
frozen before registration and applies throughout the trial.

The loop can use the entire registered model-call allowance: the common check uses
60 reservations, a 20-minute whole-trial deadline, 64,000 input bytes and 2,048 output
tokens per request. It does not inherit the existing investigator's 12-decision
limit. Actual spending can be lower. Every call and read passes through the runtime;
the adapter cannot refresh its deadline or bypass admission. The policy permits one
active call and at most 100 registered reads. Pagination counts registered read
operations, while the underlying runtime retains the complete captured artifact.
No new consumer execution occurs.

`finish` must supply exactly one dossier per case. Each declares `ANSWERED` or
`ABSTAINED`, a concise summary, summary citations and observation/hypothesis/limitation
findings. Every finding cites previously read pages from its own case. An answered
dossier additionally requires summary citations, at least one finding, and complete
baseline-source and execution page coverage. Invented or cross-case citations and
unread required pages are rejected. These checks establish access and structural
consistency, **not that cited evidence supports a claim**. Accuracy, completeness
and unsupported material claims still require separately bound semantic review.

The selected output is the complete final dossier, without grading feedback or a
best-attempt choice. There are no earlier draft findings to cherry-pick. All earlier
read decisions remain retained. Budget exhaustion, invalid decisions and remote
failures preserve the ledger and explicit absent outputs; they do not manufacture
successful abstentions. A missing case output is `NOT_PROVIDED`, distinct from an
agent's explicit abstention. Completed outputs export `review: null`.

## Register, execute and inspect

After the normal README build, with `OPENAI_API_KEY` in the process environment or
ignored `.env.local`:

```powershell
./scripts/Run-CoordinationSingleAgent.ps1 -Action policy
./scripts/Run-CoordinationSingleAgent.ps1 -InputBundleId inputs-EXISTING-UUID
```

The second command creates a **live adapter check**, using `gpt-5.6-terra` and the
common ceilings. Other arms and the rubric have explicit unrun check placeholders.
This is not the three-repetition paired campaign, a quality acceptance result or a
routing recommendation. A repeated check creates a new trial; it cannot replace an
earlier outcome. The default script does not recapture evidence or start Docker.

For an actual frozen comparison plan, use the returned policy implementation
identity for `SINGLE_AGENT` and supply the exact model, inputs and common limits:

```powershell
$trial = ./scripts/Run-CoordinationSingleAgent.ps1 -Action register `
  -InputBundleId inputs-EXISTING-UUID -PlanPath plan.json `
  -WorkloadId sufficient-evidence-bundle -Repetition 1
./scripts/Run-CoordinationSingleAgent.ps1 -Action run -TrialId $trial
./scripts/Run-CoordinationSingleAgent.ps1 -Action read -TrialId $trial
```

`policy`, `register` and `read` do not load a credential. Execution loads the existing
credential without printing it and restores the prior process environment afterwards.
The CLI prints only trial status, usage and report location, not evidence payloads
or model statements. Inspect retained dossiers locally when performing quality review.

## Durability and authority

Ignored `.workbench/coordination-single-agent/trial-UUID/` stores registration,
requests, decisions/pages, selected case dossiers, terminal inventory, result and
receipt beside the corresponding shared runtime trial. Registration binds the
actual compiled application, Gson and JDK identities, prompt/schema, context/tool
policy, final-selection rule and runtime specification. Changed implementations
cannot resume unfinished work under the old policy. Completed historical inspection
does not require the current build to have the same identity.

The adapter retains each exact request before calling the runtime. Resume reconstructs
the same context and stable operations, verifies retained events, and reuses completed
broker responses without redispatch. `ACTIVE` retains the existing trial for resume.
Unmapped registrations and unknown remote outcomes stop without guessed retries.
Expiry/revocation cannot be reset. Hash-bound terminal inventories preserve partial
artifacts; final reads recompute the exported record and reject changes or missing
receipts. A matching adapter result whose own receipt publication was interrupted
can be finalized by resuming the same trial, without model work. An incomplete
shared-runtime journal or receipt still fails closed; the adapter does not repair
or bypass that underlying integrity boundary.

These are trusted local host integrity checks, not signed attestations, distributed
workflow storage, tenant isolation or a hostile-process sandbox. Captured evidence
and generated dossiers can contain sensitive content: keep the state directory on a
trusted filesystem and exclude it from Git. No semantic acceptance, general provider
certification or coordination benefit follows from operational completion.

## Recorded adapter check — 2026-10-04

One `gpt-5.6-terra` check used captured bundle
`inputs-6e4dfed9-be1a-479d-aa15-b40073041353` under the declared common ceilings.
Trial `trial-e059d78a-454d-47f5-af3c-e80b94c4e521` completed in 27,407 ms, with
seven reserved/registered/completed calls, 27,615 recorded input tokens, 966 output
tokens, six registered evidence reads, zero new case executions and no unresolved
usage slots. It returned two `ANSWERED` dossiers, each with `review: null`.

The reads covered complete baseline-source and execution artifacts for both cases;
other available artifacts were not read. These are operational records, not a
claim of complete diagnosis or semantic acceptance. No paired campaign ran.

Result SHA-256:
`607e13c0130d75a138d17ed4758c904a89a2cab71ece554d901b9fa6e780ee8f`.
Selected customer dossier SHA-256:
`53361459315a8942f659134e3516734a75a0a043bfdc510aafa793240cc05f8c`.
Selected subscription dossier SHA-256:
`4aa728624f11edc94e1fab7a8336ac00d25fc50bd57e8293c2b3b32d47042282`.
A fresh-process read and resume preserved all 90 non-lock files across captured
inputs, runtime and adapter stores, without another call or input recapture.
