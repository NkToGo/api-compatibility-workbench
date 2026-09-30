# Subscription repair candidates

The subscription candidate generator makes one bounded model request from retained
verification evidence, optionally including a completed subscription investigation.
It produces an inert Java source file and patch, or abstains. Investigation and
generation remain separate bounded stages; coordinated investigators are not
implemented.

## Generate and verify

First complete a [supplied-source verification](subscription-period-protocol.md).
All original baseline assertions must pass, target assertions must be evaluated,
and at least one target assertion must fail. Then run:

```powershell
./scripts/Generate-SubscriptionCandidate.ps1 -VerificationId verification-EXISTING-UUID
./scripts/Generate-SubscriptionCandidate.ps1 -Action resume -CandidateId candidate-EXISTING-UUID
./scripts/Verify-SubscriptionCandidate.ps1 -CandidateId candidate-EXISTING-UUID
```

To use a completed [subscription investigation](subscription-investigation.md):

```powershell
./scripts/Generate-SubscriptionCandidate.ps1 -AgentId agent-EXISTING-UUID
```

Supply either `AgentId` or `VerificationId`, never both. The host derives the
verification identity from the investigator's registered context. Resume accepts
the existing candidate ID only. The direct-evidence command and its historical
records retain their existing behavior.

The investigation path requires `INVESTIGATED`, valid observed citations and a
still-matching subscription context. Abstained, unfinished or failed investigations
do not register a generation request. Successful source pages and execution reads
are checked against the retained evidence again; reference validity does not prove
that a hypothesis is semantically correct. Findings and their observable tool events
enter generation as untrusted advisory data, alongside the original source, policy
and baseline/target observations. They cannot authorize actions or assert a repair.

Generation uses the existing host-side `OPENAI_API_KEY` and defaults to
`gpt-5.6-terra`. One registration permits one model call, up to 4,096 output tokens
and 64,000 UTF-8 input bytes, under the existing broker's five-minute permission
and no-replay rule. The request contains the original source, captured case
documentation, application expectations and original baseline/target observations.
It does not send the prior candidate source or its outcomes. Model input includes
declared expectations, so this is not a held-out evaluation. The current curated
inputs are synthetic; introducing private customer cases requires a separate data
handling review before sending their contents to a model provider.

Generation stores records in `.workbench/subscription-candidates/<candidate-id>/`:
captured inputs, registration with evidence/model-call identities, proposal,
`checkout/PeriodConsumer.java`, a full-file patch and a result with artifact hashes
and token usage. Only a ready candidate receives a checkout. `executed: false`
remains part of the historical generation record even after separate verification.

For the investigation path, an additional `investigation/` directory captures the
session, final findings, context registration and observed event files. Registration
binds the agent/context identities and hashes of these records to the candidate's
original verification. Previous candidate source and its outcomes stay out of the
request. These hashes assume trusted local storage; they are not signed attestations.

## Boundaries and recovery

The model can submit one to four exact, unique, non-overlapping replacements in
the supplied source file, or abstain with no edits. It cannot choose file paths,
commands, fixtures or expectations. Imports and helpers in this one file are
editable; the host does not claim a semantic proof that the entry-point signature
or behavior is preserved. Invalid Java and malicious source remain possible and
must be handled by the isolated verifier. Source must use LF with a final newline.

Host validation rejects missing spans, duplicate/overlapping edits, no-op proposals,
oversized source and unexpected response fields. Changed captured inputs or retained
evidence stop unfinished generation and new verification. Captured records are
checked before historical candidate reuse. A completed result remains a record
about its original inputs; reading it does not validate current inputs. Partial
artifacts must match exactly; they are not overwritten. A model request with an uncertain outcome is not automatically
dispatched again. Abstention and model/transport failures remain separate statuses.

The investigation path checks the original session, findings, context and events
before dispatch and after the model response. A changed original record produces
`INPUT_IDENTITY_MISMATCH` without publishing a source checkout. A changed captured
record is rejected rather than overwritten. Unknown model outcomes remain distinct
from explicit abstention and cannot gain a speculative retry.

Verification requires a finalized ready candidate and does not trigger generation.
It checks artifact hashes, reconstructs the proposal from the captured original,
revalidates the original execution evidence and any bound investigation, then
registers a separate durable verification with the candidate ID and generation-result hash. The existing
resume/cancel/revoke commands operate on the new verification ID. Each explicit
new verification command creates a fresh attempt; retain its printed ID for recovery.

This workflow proposes and tests one candidate. It does not automatically revise a
failed repair, publish a PR or approve deployment. Public case knowledge can be
overfit; a passing synthetic case is not general compatibility evidence. Original
evidence, failed candidates and verification records remain available.

The request uses a forced strict function response with parallel calls disabled,
following the [OpenAI function-calling contract](https://developers.openai.com/api/docs/guides/function-calling).
