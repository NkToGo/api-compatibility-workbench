# Scoped repair candidates

Candidate generation consumes a completed investigation and its still-applicable
execution receipt. It produces an inert source-only checkout, the original source,
a unified patch and a manifest tying those artifacts to the input and model call.
It does not compile, execute, merge or publish generated code.

## Generate and inspect

Build the backend and prepare the pinned SDK dependencies first. Complete an
[investigation](consumer-investigator.md), then supply its printed agent ID:

```powershell
./scripts/Generate-RepairCandidate.ps1 -AgentId agent-EXISTING-UUID
```

The default model remains `gpt-5.6-terra`. This is an opt-in paid API request using
the existing host credential. The request includes the registered source,
expectations, recorded execution summary, advisory findings and public signatures
from the pinned target SDK. The host reads signatures with JDK `javap`; it does
not initialize SDK classes or execute the consumer. The JAR digest is checked
before inspection. API signatures establish method availability, not provider
behavior or repair correctness.

Each command prints a candidate ID and a result path under
`.workbench/candidates/<candidate-id>/`. A ready candidate contains:

| Artifact | Purpose |
| --- | --- |
| `baseline.java.txt` | Exact original extracted source |
| `checkout/consumer/baseline.java.txt` | Proposed source, isolated from case inputs |
| `candidate.patch` | Full-file unified diff for review/application to the extracted source |
| `proposal.json` | Model's exact replacements and advisory summary |
| `registration.json` | Investigation, context, source hash, receipt hash and model-call identity |
| `result.json` | Status and artifact hashes; explicitly `executed: false` |

This checkout contains the curated method-excerpt file only. It is not a full
upstream repository clone or a Git worktree. The patch is for that extracted file,
not directly for an upstream repository layout. A future repository adapter must
map edits back to a supported full consumer checkout.

`CANDIDATE_READY` means the edit and artifact boundaries passed, not that the
candidate compiles or fixes a regression. Treat its code and summary as untrusted
model output. Protected execution and repair evaluation are the next capability;
the existing fixed-case worker still rejects unreviewed executable inputs.

## Scope enforcement

The model submits one structured function response: either `PROPOSED` with one
to four before/after replacements, or `ABSTAINED` with no edits. It cannot provide
paths, commands, dependency changes or test expectations.

Each replacement must match a unique, non-overlapping span in the original
`getPaymentMethods` body. Method signature, surrounding source, license and other
methods are preserved. No-op, missing, duplicate and overlapping replacements are
rejected. Scope is enforced by original text spans, not a Java semantic proof:
replacement text could still contain invalid or malicious Java. Generated code
must not run outside an appropriately isolated verifier.

The original source is limited to 32,000 characters, expectations to 16,000 and
each replacement field to 12,000. The resulting source is bounded, and patches use
LF source with a final newline. Source reads verify captured input hashes across
pages. Generation checks the execution receipt before dispatch and again before
publishing a candidate. Fixtures, expectations and SDKs are never copied into the
editable checkout. A modified-derivative notice is added to the candidate.

## Failures, retention and limits

One candidate registration allows one model request, at most 4,096 output tokens
and 64,000 UTF-8 input bytes. The existing broker enforces its five-minute grant,
90-second transport limit, response-size bound and no-replay rule. Unknown or
incomplete outcomes can still incur charges. Recorded usage is not an invoice.

Use the same ID after an interruption:

```powershell
./scripts/Generate-RepairCandidate.ps1 -Action resume -CandidateId candidate-EXISTING-UUID
```

A completed result is reused after checking its artifact hashes. A changed or
partial artifact is rejected rather than overwritten. An already-started model
call is never dispatched again. A registration interrupted before its manifest
may leave an unstarted broker record; it performs no remote work. State must be
kept on a trusted local filesystem; these manifests do not defend against a
malicious host operator rewriting all records and hashes.

`ABSTAINED` records insufficient evidence. `INVALID_PROPOSAL` rejects unsupported
edits. `INPUT_IDENTITY_MISMATCH` stops stale work. `MODEL_*` statuses retain the
broker's failure distinction. None of these outcomes creates a ready checkout or
approves a repair. Original evidence and failed attempts remain available.

Tests cover edit scope, overlap, no-op rejection, abstention, materialization,
artifact tampering, stale evidence and uncertain model outcomes. The model/API
contract follows [Responses function calling](https://developers.openai.com/api/docs/guides/function-calling).
