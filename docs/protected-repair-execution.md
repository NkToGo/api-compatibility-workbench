# Protected repair execution

The repair verifier compiles and executes a registered candidate for the curated
Stripe case. Provider fixtures, payment-service state, expectations and assertion
decisions remain on the host. Each scenario and variant receives a fresh,
network-disabled Linux container; no repository or host directory is mounted.

## Run a candidate

After the normal backend build, prepare the separate repair image and supply an
existing ready candidate:

```powershell
./scripts/Build-IsolatedWorker.ps1 -RepairWorker
./scripts/Verify-RepairCandidate.ps1 -CandidateId candidate-EXISTING-UUID
```

This requires Docker with Linux cgroup v2 and seccomp. It makes no model or provider
API call and does not read `.env.local`. Image preparation can contact the registry;
scenario execution never pulls an image or falls back to host execution.

The command prints a verification ID and result path under
`.workbench/verifications/<verification-id>/`. A completed execution can still
produce `REGRESSIONS_REMAIN` or `UNRESOLVED`; inspect the evaluation, not just the
process exit status. The evaluator compares newly executed baseline, unrepaired
target and candidate observations under the same harness and protected conditions.
It does not mix new candidate results with older observations from another harness.

## Evidence boundary

The repair image includes the pinned JDK, compiler, SDK dependencies and service
adapter. Unlike the curated-case image, it contains no fixture bundle, expectations
or consumer inputs. The host sends one captured source and SDK selection through
stdin. Compilation has annotation processing disabled and happens inside the
container. The fixed adapter invokes the extracted consumer method in a child JVM.

Docker's [interactive create](https://docs.docker.com/reference/cli/docker/container/create/)
and [attached start](https://docs.docker.com/reference/cli/docker/container/start/)
connect bounded stdin/stdout streams without a network endpoint. Every worker
message is untrusted. The host accepts only a small protocol:

- Read local payment-method identifiers.
- Request an allowlisted synthetic Stripe GET response with exact version and parameters.
- Synchronize/create an observed provider object or deactivate a known local object.
- Return method identifiers or report the supported consumer exception.

The host records service operations and maintains authoritative state. A worker
cannot clear deletion history, assert that remote sources were enumerated, modify
expectations or submit a passing assertion. Returned identifiers are merely the
consumer's return value and are compared with host state and expectations. An
unsafe deletion remains a failure even if the consumer subsequently returns the
expected identifiers. Unknown operations, extra fields, malformed output and
messages after a terminal response invalidate the observation.

This establishes observed behavior at the service boundary, not proof of internal
algorithm correctness. Candidate code could implement the protocol directly or
overfit known scenarios. The fixtures are public synthetic cases, not secret test
data. Broader and held-out evaluations remain necessary. The container boundary
also assumes a trusted host, Docker daemon and prepared image; it is not a hardened
public service for arbitrary hostile repositories.

## Bounds and recovery

The verifier uses the existing fixed container controls: non-root user, read-only
root, no network, no host mounts, no capabilities, no privilege escalation, bounded
CPU/memory/processes, and limited scratch space. The worker receives no API key or
Docker socket. A wall-clock backstop terminates the container after 185 seconds;
the host normally stops each scenario after 60 seconds. Protocol limits are 100
operations, 16 KiB per line, 256 KiB total request bytes, 16 KiB per reply and
64 KiB stderr. Unsupported or incomplete execution produces no evaluated assertion.
Worker exit code 4 denotes a bootstrap compilation failure; this is a diagnostic,
not trusted evidence of any successful behavior. Raw compiler output is currently
container-local and is not retained after cleanup.

A registration captures source, fixtures, expectations, their hashes, candidate
manifest identity, verifier identity, image ID and every planned container name
before dispatch. It gets a 30-minute local permission. A host lease prevents
concurrent execution. Use the same verification ID after an interruption:

```powershell
./scripts/Verify-RepairCandidate.ps1 -Action resume -VerificationId verification-EXISTING-UUID
./scripts/Verify-RepairCandidate.ps1 -Action cancel -VerificationId verification-EXISTING-UUID
./scripts/Verify-RepairCandidate.ps1 -Action revoke -VerificationId verification-EXISTING-UUID
```

Resuming a finalized attempt validates and returns retained evidence. Resuming a
started but unfinished attempt reconciles its owned containers and records
`INTERRUPTED`; it never reruns scenarios or infers a successful result from partial
output. A fresh attempt requires a new registration. Cleanup failures leave the
attempt unfinished for recovery. Cancellation and authorization loss are checked
during execution and before finalization. A running lease can return `ACTIVE` while
a concurrent cancel/revoke request is taking effect.

Captured inputs, per-scenario host events, observations, the final evaluation and
artifact hashes stay in the verification directory, outside Maven build output.
This is a separate local journal, not an entry in the older fixed-case evidence
store. Preserve it with the referenced candidate and investigation records. Its
hashes detect accidental changes; they do not defend against a host operator
rewriting all records. Candidate-generation history is unchanged by verification.

## Validation and current scope

The provider adapter supports the explicit `GET /v1/customers/cus_curated/sources`
request with an empty query, in addition to customer and payment-method retrieval.
It returns the existing `sourceListResponse` fixture only when requested. An omitted
embedded collection never implies an empty list. A complete source read must precede
deactivation; reading afterward cannot erase unsafe deletion history. Pagination
(`has_more: true`) and unsupported source-list queries remain unresolved.

An authored positive control uses the pinned target SDK to fetch the explicit source
list before synchronization. It must pass all five declared scenarios, including
confirmed removal, while historical controls retain their existing outcomes. This
checks that a complete repair is attainable under the adapter. It is not a generated
repair, a held-out case or a measurement of model repair accuracy.

Container tests reproduce all fifteen historical variant/scenario outcomes under
the new service boundary. Adversarial tests cover forged assertions, direct access
to old fixture bookkeeping, output floods, deadlines, live stop requests, protected
file writes, missing expectations, blocked network access and actual controller
death. Unit tests cover authoritative host state, protocol limits, artifact changes,
revocation, cancellation and no-replay recovery.

Only the extracted Stripe consumer, two pinned SDKs and declared synthetic scenarios
are supported. There is no live Stripe verification, full repository execution,
automatic repair retry, PR publication or release approval. Passing this suite alone
does not establish general repair or abstention accuracy.
