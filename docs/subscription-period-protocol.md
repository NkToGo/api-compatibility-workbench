# Subscription-period verification protocol

The host-side `SubscriptionPeriodOracle` checks a consumer result against one
separately supplied expectation. This is a protocol component for the second
curated case. The repair sandbox now connects it to container-only compilation
and execution. Supplied source pairs support durable registration and recovery;
[bounded model-generated candidates](subscription-candidates.md) use the same verifier.

For each scenario the host supplies the selected baseline or target subscription
projection, configured item binding and expected result. The oracle copies these
values. Only the projection and binding are returned to the worker.

The accepted exchange is exactly one `{"op":"read"}` followed by one
`{"op":"return","result":...}`. The result is either `{"status":"DEFERRED"}`
or `{"status":"RESOLVED","periodEnd":1769904000}`. A resolved timestamp must be
a positive integral number representable by a Java long. Extra fields, unknown
operations, repeated reads, returns before reads and messages after a return
invalidate the observation. The worker cannot submit an assertion outcome.

The host compares the returned value with its expectation only when execution
has completed and the protocol remains valid. A valid but wrong date or incorrect
deferral fails the assertion. Missing results, invalid messages, cancellation,
timeouts and infrastructure failures leave it unevaluated, even if an earlier
message contained the expected value. A correct deferral means the consumer
declined to choose a date as required; it is not an agent abstention.

Tests run all five target-side authored controls through this exchange, preserving
the existing pass counts, and exercise forged verdicts, malformed values, ordering,
interrupted execution and mutation of retained data. These tests validate the
in-process assertion boundary; they do not establish generated repair quality.

## Container adapter

`RepairSandbox.runSubscription` accepts at most 32,000 source characters defining
`PeriodConsumer.consume(JsonObject subscription, String binding)`, returning a
`JsonObject`. The fixed adapter performs the read/return exchange. Compilation uses
JDK 21, Gson 2.14.0 and disabled annotation processing inside the existing repair
image. No Stripe SDK is used for these reduced JSON projections. Compilation never
runs on the host.

The transport reuses the repair sandbox's network-disabled, read-only container,
resource limits, bounded protocol streams, deadline, cancellation and verified
cleanup. It supplies execution status only after worker termination. Expectations
are never sent to the worker. Source can bypass the adapter and write protocol
messages directly, but cannot submit trusted assertions. Public synthetic inputs
can still be overfit; passing does not establish general correctness.

Linux CI compiles baseline, unrepaired, first-item and bound-item controls over all
eight scenarios and retains host result records. Additional tests exercise invalid
Java, forged verdicts, nontermination, cancellation and absent host expectations.
The existing repair tests continue to exercise shared isolation and recovery controls.
No AI-generated repair is evaluated by these controls.

## Durable supplied-source verification

After building the backend and `./scripts/Build-IsolatedWorker.ps1 -RepairWorker`,
supply two UTF-8 Java source files implementing the adapter signature:

```powershell
./scripts/Verify-SubscriptionCandidate.ps1 -BaselineSource ./original/PeriodConsumer.java -CandidateSource ./repair/PeriodConsumer.java
./scripts/Verify-SubscriptionCandidate.ps1 -Action resume -VerificationId verification-EXISTING-UUID
./scripts/Verify-SubscriptionCandidate.ps1 -Action cancel -VerificationId verification-EXISTING-UUID
./scripts/Verify-SubscriptionCandidate.ps1 -Action revoke -VerificationId verification-EXISTING-UUID
```

The command registers an attempt under `.workbench/verifications/`, prints its ID,
then executes eight baseline, eight unrepaired-target and eight candidate scenarios.
The original source is used for both baseline and unrepaired target. The command
makes no model or provider API calls. Sources are supplied by the operator; their
hashes identify the exact code, not an upstream repository revision or a model
generation receipt. A failing baseline keeps the repair assessment unresolved.

Registration captures the source pair, fixture bundle, expectations, case documentation,
their hashes, worker image, verifier identity and every container name before dispatch.
The existing single-host journal supplies a 30-minute permission, ownership lease,
live cancellation/revocation checks, per-scenario records, observations and a three-way
repair evaluation. `COMPLETED` describes execution, not repair correctness.

Resume reuses a finalized result only after checking retained hashes. A started but
unfinished attempt cleans its registered containers and becomes `INTERRUPTED` without
replaying scenarios. Existing job evidence remains available, but no complete assessment
is inferred from partial work. Cleanup failure leaves the attempt recoverable. A changed
verifier requires a new registration before dispatch; recovery of old started attempts
still performs cleanup. Changed captured inputs or evidence prevent result reuse.

This remains a trusted single-host workflow over synthetic cases. Preserve the whole
verification directory; hashes cannot defend against an operator rewriting the records
and hashes together. Separate bounded generation is available; there is no approval
or publication workflow.
