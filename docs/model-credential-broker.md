# Host-side model credential broker

The broker supports one bounded OpenAI Responses request per registered call. It
keeps `OPENAI_API_KEY` in the trusted host process and does not return credentials
to callers, put them in command arguments or mount them into a worker. This is
the credential boundary used by the [consumer investigator](consumer-investigator.md), not an investigating
agent, a general HTTP proxy or a migration verdict.

## Connectivity check

After building the backend, configure `OPENAI_API_KEY` in the host environment or
the ignored `.env.local` file. Never commit its value. Then run:

```powershell
./scripts/Model-Broker.ps1 -Action probe
./scripts/Model-Broker.ps1 -Action revoke -CallId model-00000000-0000-0000-0000-000000000000
./scripts/Model-Broker.ps1 -Action recover -CallId model-00000000-0000-0000-0000-000000000000
```

The probe is an opt-in, billable API request containing only a fixed readiness
instruction. It sends no repository files or execution evidence. Its default model
is `gpt-5.6-terra`; `-Model` selects a different supported model for the check.
Account access is verified by the actual request. This default is a connectivity
choice, not the investigator's future model-selection or quality evaluation result.

`-BrokerDirectory` defaults to ignored `.workbench/model-calls/`. The script uses
an existing process key first, otherwise a single `OPENAI_API_KEY` entry in
`.env.local`, and restores the previous process environment on exit. The broker
resolves the credential only after validating permission. Neither recovery nor
revocation needs the key. The script prints a call identifier and sanitized status;
inspect `result.json` under that identifier for recorded output and usage.

## Scope and durable dispatch

Trusted host code registers a `Request(model, input, maxOutputTokens)`. Input is
bounded to 16,000 UTF-8 bytes and output to 16–4,096 tokens. Registration approves
that exact serialized body for at most five minutes. The request hash uses stable
JSON ordering across JVM restarts. Changing the input, model or budget invalidates
permission. No caller-supplied endpoint, header, tool, remote file, conversation,
background job or provider storage flag is accepted.

The fixed `investigation-v1` protocol raises the input ceiling to 64,000 UTF-8
bytes and adds host-defined instructions and one forced function schema. Parallel
function calls are disabled. Callers cannot supply tool definitions or arbitrary
instructions through this option. Schema and instructions are included in the
exact request hash; changing them invalidates an outstanding grant.

The only production destination is `https://api.openai.com/v1/responses`, with
redirects and proxies disabled, a ten-second connection timeout, a 90-second
whole-response deadline and a 256 KiB response limit. A loopback HTTP endpoint is
available only through the package-private test constructor. The HTTP request sets
`store: false` and `background: false`. These fields do not promise zero provider
retention; applicable account data controls and abuse-monitoring policies still
apply. See OpenAI's [text generation guide](https://developers.openai.com/api/docs/guides/text)
and [data controls](https://developers.openai.com/api/docs/guides/your-data).

An operating-system lease prevents concurrent dispatch. `started.json` is forced
and atomically published before the HTTP request. After a crash, a started call
without `result.json` becomes `UNKNOWN_REMOTE_OUTCOME`; the broker never dispatches
that ID again. The marker can precede a request that never reached the provider,
so unknown is deliberately conservative. The client request ID aids correlation;
it is not an idempotency guarantee. There are no application-level retries.

Revocation, expiry or invalid permission before dispatch prevents credential use.
During a request, permission is checked repeatedly and again before accepting its
output. If access ends, the local request is cancelled and its result discarded.
The provider may still process or bill a request it already received. Cancellation
and a completed result can race; completed historical results are never rewritten.
Recovery can close unfinished calls but cannot renew permission or repeat them.

## Outcomes and retained data

- `COMPLETED`: message text (probe) or exactly one expected function's arguments
  (investigation protocol), plus input/output token counts, were read.
  Output remains untrusted model content, separate from compatibility evidence.
- `AUTHORIZATION_DENIED` or `CREDENTIAL_UNAVAILABLE`: no dispatch was attempted.
- `CREDENTIAL_REJECTED`: HTTP 401/403; `RATE_LIMITED`: HTTP 429. Numeric Retry-After
  is retained when present, but a retry needs a new explicitly registered call.
- `REDIRECT_REJECTED`, `PROVIDER_ERROR`, `INCOMPLETE`, `REFUSED`, `INVALID_RESPONSE`
  and `UNSAFE_RESPONSE`: no successful model result is claimed.
- `ACCESS_ENDED`, `INTERRUPTED` or `UNKNOWN_REMOTE_OUTCOME`: a dispatched call's
  remote outcome or cost may remain unknown. Do not infer failure to bill.
- `CLOSED_WITHOUT_DISPATCH`: recovery or revocation closed an unstarted call.

The journal retains approved input, a scoped grant, optional revocation/start
markers and a sanitized result. Successful results contain extracted text or function arguments, never
reasoning items. Provider error bodies, raw headers and exception messages are
discarded. A bounded request ID and numeric retry delay may be retained. Exact key
echoes in selected response content are rejected, including JSON-escaped echoes;
this is defense in depth, not a general sensitive-data classifier.

The caller must authorize disclosure of its input and must not put credentials
into prompts. The journal is not encrypted; restrict host filesystem access and
include it in backup/retention procedures. Stored token counts support usage
visibility, not an invoice; no monetary estimate is invented when usage is missing.
Provider errors follow the [official error categories](https://developers.openai.com/api/docs/guides/error-codes).

## Limits and verification

This is a trusted single-host component, not a public endpoint, user-authentication
service, secret vault or organization permission system. A hostile local operator
can alter records or read process memory. Worker network isolation remains
unchanged. GitHub App installation and provider-specific operations need their own
narrow adapters; arbitrary Stripe/GitHub requests are not supported here.

Ordinary tests use a local HTTP server and a fixture credential. They exercise
fixed request shape, scope mutation, expiry, revocation during a live request,
401/403/429/redirect/error handling, oversized/malformed responses, secret echoes,
stalled response bodies, concurrent ownership and actual controller process death
after the server receives a request. Reopening never sends the same call again.
CI does not run billable requests or need an API key. The readiness probe verifies
connectivity only; task-quality evals belong with the upcoming investigator.
