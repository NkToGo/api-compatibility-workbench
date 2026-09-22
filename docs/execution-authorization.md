# Expiring execution permission

The durable runner records permission for one exact attempt, image ID and mode
before publishing its specification. Permission lasts five minutes by default.
It grants only `execute-curated-case`; it is neither a provider credential nor a
token that can be passed to a worker. The offline worker still receives no secrets
and has no external network access.

## Run and revoke

```powershell
./scripts/Run-IsolatedStripeCase.ps1 -AuthorizationSeconds 300
./scripts/Execution.ps1 -Action revoke -RunId acw-00000000-0000-0000-0000-000000000000
```

Replace the example ID with the registered run ID. The lifetime must be between
1 and 3,600 seconds and starts at registration, not container startup. Revocation
can be issued from a second terminal using the same execution/evidence directories.
It creates a persistent marker and attempts reconciliation. An active owner returns
`REVOCATION_REQUESTED` while it observes the request and stops the worker. Queued or
abandoned attempts are reconciled immediately when cleanup is available.

Permission is checked before Docker access, before container creation/start, during
attached execution, before accepting the worker report and before finalizing a
completed receipt. Missing, unreadable, oversized, malformed or mismatched grants
fail closed. Expiry is exclusive: execution is denied at the expiry instant. A clock
earlier than the recorded issue time is invalid. Once observed, a stop condition
is latched and cannot turn into success if the grant file subsequently changes.

Denied execution has status `AUTHORIZATION_DENIED` with an `authorizationDecision`
of `EXPIRED`, `REVOKED`, `UNAVAILABLE` or `INVALID`. Cancellation retains its own
status. Cleanup failure takes precedence as `CLEANUP_FAILED` and requires recovery.
If permission is lost just after worker output was exported, those files can remain
for diagnosis, but the final receipt is denied. Always check the receipt rather
than treating the presence of HTML as successful execution or release approval.

Completed receipts remain immutable if permission expires or is revoked afterward.
Recovery may publish already finalized evidence without authorization to execute;
it cannot run the worker again or renew permission. A new attempt receives a new
identity and explicit local permission. Older finalized receipts remain readable;
an older queued attempt without a grant cannot execute through the durable runner.

## Storage and limits

The journal contains `authorization.json` with non-secret scope, issue time and
expiry, plus `authorization.revoked` after revocation. Receipt status records the
last observed decision. The full grant is retained in the journal rather than the
evidence export; preserve both directories for operational history.

This is a local operator control, not user authentication or a hostile-host security
boundary. Anyone who can change the journal or invoke Docker directly remains a
trusted operator. The low-level executor used by boundary tests is not an authorized
service endpoint. Restrict filesystem and Docker access; do not expose these Java
entry points as a public API. No external credential is read, brokered or delivered
by this feature. Credential brokering remains a separate implementation step.

Revocation is cooperative, not instantaneous: Docker control calls have bounded
timeouts and an already issued action may finish before the next check. Completion
can win a race with revocation. If the controller is killed or the Docker daemon
is unreachable, this polling cannot stop a worker immediately; explicit recovery
and the existing 185-second in-container deadline are the remaining safeguards.
This mechanism must not be treated as immediate revocation of provider access.
Wall-clock correctness depends on the trusted host clock; clock administration and
power-loss durability have the same operational limits as the execution journal.

Tests cover scope mismatch, exact expiry, clock rollback, missing/corrupt grants,
latched denial, queued revocation, live-container revocation and live-container
expiry, repeated recovery and preservation of completed results.
