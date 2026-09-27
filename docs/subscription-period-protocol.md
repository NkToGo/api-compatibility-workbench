# Subscription-period verification protocol

The host-side `SubscriptionPeriodOracle` checks a consumer result against one
separately supplied expectation. This is a protocol component for the second
curated case; container transport, candidate compilation and agent integration
are not connected yet. It does not execute candidate code.

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
in-process assertion boundary. They do not establish process isolation or generated
repair quality. The future transport must bound raw message bytes before parsing,
enforce deadlines, and supply authoritative execution status after termination
and cleanup; the oracle itself is not a transport or an authorization mechanism.
