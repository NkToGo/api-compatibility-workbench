# Bounded consumer investigation

The local Java investigator uses a model to choose input reads, request the fixed
case execution and produce cited hypotheses or abstain. It cannot edit code or
publish repairs. This command runs one investigator. Separate [parallel tasks](parallel-investigations.md)
and a [coordinator review](coordinator-review.md) now cover bounded team scheduling
and advisory review; coordinator follow-up dispatch remains planned.

## Run

Prepare the case, build the backend and build the isolated worker as described in
the README. Configure `OPENAI_API_KEY` in the host environment or ignored
`.env.local`. This command makes paid OpenAI API calls and sends the registered
curated input pages and tool results to OpenAI. Never register confidential data
without appropriate authorization.

```powershell
$contextId = ./scripts/Investigation.ps1 -Action register
./scripts/Investigate-Consumer.ps1 -ContextId $contextId
```

The default model is `gpt-5.6-terra`; `-Model` selects another accessible model that
supports Responses structured outputs. No automatic model fallback is performed.
The command prints an agent ID and a local JSON result path. Results contain a
short summary, typed findings, citation IDs and the recorded tool events those IDs
refer to. Model text is untrusted advisory content, not test evidence, a validated
repair or release approval. Render it as escaped text if adding a viewer.

`INVESTIGATED` means the model supplied structurally valid findings after reading
baseline source and receiving completed execution evidence. It does not certify
the accuracy of its hypotheses. `ABSTAINED` is an explicit model abstention.
The independent execution report still owns all compatibility conclusions.

## Runtime and evidence boundary

This runtime preserves the existing Java broker and worker boundary. It uses
Responses with one forced `investigation_decision` function call, a strict JSON
schema and parallel calls disabled. It does not use Agents SDK, hosted Agents API
sessions or MCP. Each call is a standalone decision over observable history; the
runtime does not chain provider conversations. The local dispatcher validates each decision again and
maps it to an existing application tool. Choosing what to inspect and adapting to
the returned results is model-driven. Permissions, budgets, persistence, execution
and conclusion validation remain deterministic.

This choice reuses the existing exact-request grants, sanitized response handling
and execution journal without introducing another language runtime. The cost is
application-owned orchestration and explicit history reconstruction. It is a
case-specific runtime, not a general agent framework. Later coordination must
reassess that tradeoff rather than add orchestration features by default.

The model receives the registered case description and observed tool events.
Actions are `read_input`, `run_case`, `read_run` and `finish`. Test request identity
is assigned by the host from the agent ID; the model cannot choose a different
context or repeatedly create new attempts. Input text cannot authorize additional
tools, commands, access or external requests.

Final findings are `HYPOTHESIS` or `LIMITATION`. Hypothesis citations must refer to
successful tool events the model actually received. In protocol v2, limitations
may also cite observed failed events to explain unavailable evidence; an error
does not thereby support a hypothesis about consumer behavior. The host rechecks completed
execution against captured inputs before accepting `INVESTIGATED`. These checks
establish reference validity and minimum evidence coverage; they do not prove that
a citation semantically supports a statement. A curated repair-quality evaluation
and broader case coverage remain outstanding.
The [evidence-availability evaluation](investigation-evaluation.md) now exercises
completion and abstention under controlled tool failures; it does not grade the
meaning of findings or the quality of repairs.

## Limits and interruptions

New investigations use protocol `investigation-v2`. The host supplies consecutive
`UNAVAILABLE` counts by input ID or by the shared execution resource. An unavailable
resource permits one retry. Page offsets do not create new allowances; `run_case`
and `read_run` share one allowance. A successful tool response resets its resource's
count. Other inputs remain accessible, and failed optional reads do not force an
abstention when required evidence is already available.

The model is instructed to finish with an explicit abstention if required evidence
remains unavailable. A third model-directed request for an exhausted resource is rejected before
tool dispatch and ends with `TOOL_RETRY_BUDGET_EXHAUSTED`, not `ABSTAINED`. Counts are
reconstructed from retained events on resume. This bounds tool retries; it does not
add automatic retries to model requests or uncertain execution attempts. The normal
12-decision budget still applies. Other error types retain their existing behavior.

Sessions without a protocol field retain `investigation-v1` instructions and retry
behavior; saved model grants are not reinterpreted with the new prompt. Completed
historical results remain unchanged. The new guidance also prioritizes required
evidence and avoids redundant reads of an already completed execution response.

Each registered agent has these fixed ceilings:

- 12 model decisions, each requesting at most 2,048 output tokens.
- 64,000 UTF-8 bytes of state per model request, plus fixed instructions/schema.
- 20 minutes from registration; no new action starts after that deadline is checked.
- Existing broker transport deadline of 90 seconds per request, and the existing
  bounded worker deadline for a test. An in-flight operation may finish after the
  agent deadline; this is an admission deadline, not instantaneous cancellation.
- One stable test request per agent, additionally subject to the registered case's
  two-request ceiling across callers.

Token usage is recorded for completed model responses. Unknown or incomplete
requests may incur additional charges; those are not presented as zero-cost work.
The limits bound calls and payloads, not a fixed currency amount. Provider billing
is authoritative. Rate limits, refusals, incomplete output and ambiguous remote
outcomes stop the run with a distinct status; none is silently retried.

State lives in `.workbench/agents/<agent-id>/`, alongside the existing broker,
investigation, execution and evidence directories. Each model-call mapping is
published before dispatch. Each tool event is recorded before the next decision.
Full provider responses and private model reasoning are not retained; the broker
retains only extracted output text and sanitized metadata. No reasoning transcript
is requested. The model receives observable events on each new request rather
than a persisted provider conversation (`store: false`). Provider data-retention
policies still apply; this flag is not a zero-retention guarantee.

After an interruption, use the existing ID:

```powershell
./scripts/Investigate-Consumer.ps1 -Action resume -AgentId agent-EXISTING-UUID
```

A finished investigation returns its existing result. An unfinished investigation
reuses mapped model calls and recorded tool events. If a model dispatch has an
unknown outcome, recovery stops rather than paying for a speculative replay.
An interrupted test remains subject to operator recovery through `Execution.ps1`;
an agent cannot grant itself recovery privileges. An expired agent stops without
new work. Starting a new agent is a new authorized run and can incur new charges.
A crash between registration and mapping can leave an unstarted broker call;
it has not been dispatched. State must remain on a trusted local filesystem.

## Validation

Default tests use deterministic model responses and local HTTP fixtures. They
exercise action validation, citation rejection, minimum execution coverage,
abstention, decision/time budgets, retained call identity and unknown outcomes.
Container tests cover the actual tool/execution boundary. Live investigation is
opt-in and excluded from CI; no credential is needed for automated tests.

Protocol references: [Agents integration choices](https://developers.openai.com/api/docs/guides/agents)
and [Responses function calling](https://developers.openai.com/api/docs/guides/function-calling).
