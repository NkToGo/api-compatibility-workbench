# Subscription-period verification protocol

The host-side `SubscriptionPeriodOracle` checks a consumer result against one
separately supplied expectation. This is a protocol component for the second
curated case. The repair sandbox now connects it to container-only compilation
and execution. Durable candidate registration and agent integration remain pending.

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
runs on the host. This package-level entry point is not yet a public candidate CLI.

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
The subscription entry point itself has no durable registration or restart command
yet; it is available only to the internal harness. Do not use it as an unattended
candidate service. No AI-generated repair is evaluated by these controls.
