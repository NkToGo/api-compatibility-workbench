# Subscription investigation from retained evidence

The shared bounded investigator can now inspect a registered, completed subscription
verification. It adaptively selects input pages and recorded execution reads, then
returns cited advisory findings or explicitly abstains. It uses the existing
investigation-v2 decision schema, model broker, 12-decision ceiling, 20-minute
admission deadline and unavailable-resource retry limits.

After the backend build completes, register retained subscription evidence:

```powershell
$context = ./scripts/Register-SubscriptionInvestigation.ps1 -VerificationId verification-EXISTING-UUID
./scripts/Investigate-Consumer.ps1 -ContextId $context
./scripts/Investigate-Consumer.ps1 -Action resume -AgentId agent-EXISTING-UUID
```

Registration is local and makes no API calls. Investigation makes paid calls with
the configured host credential; the default model remains `gpt-5.6-terra`. Only
registered curated data and observed tool events enter the requests. The context
lives in `.workbench/subscription-investigations/`; sessions use `.workbench/agents/`.
Keep their referenced verification records and model-call records together.

## Read boundary

The host binds the context. Model arguments cannot select files, another execution,
commands, credentials or permissions. Available input IDs are:

- `baseline-source`: the source used for both baseline and unrepaired target.
- `provenance`: case documentation, including the declared application policy.
- `expectations`: named synthetic behavioral expectations.
- `fixtures`: synthetic provider projections.

Reads use UTF-16 offsets and at most 4,000 characters per page. The adapter checks
the original retained execution and input hashes before serving any tool result.
`run_case` and `read_run` are aliases for reading that completed evidence; neither
launches a container. The case description declares this retained-only behavior
and a zero new-execution budget. Both return only baseline and target observations.
Prior candidate source, candidate outcomes and aggregate repair conclusions are
not exposed. Unsupported input IDs in the shared schema are rejected by this adapter.

An investigation can inspect incomplete assertions within a completed execution,
but it cannot convert them into passing checks. `INVESTIGATED` means the existing
minimum source/execution access and citation checks passed, not that hypotheses are
correct. The runtime rechecks retained evidence before accepting that status.
Failed reads may support a limitation, never a behavioral hypothesis.

## Limits and next integration

This adds adaptive reading of previously recorded results, not live provider
access or an agent-selected test runner. Source and tool output remain untrusted.
The local store is trusted; hashes do not defend against an operator rewriting all
records. Existing completed agent results remain historical records, not renewed
verification of current inputs.

Subscription candidate generation still consumes retained execution directly.
Passing investigation findings to it with explicit identity binding remains a
separate integration step. Two-case semantic and abstention acceptance measurements
remain pending; this adapter alone does not close Milestone 3.
