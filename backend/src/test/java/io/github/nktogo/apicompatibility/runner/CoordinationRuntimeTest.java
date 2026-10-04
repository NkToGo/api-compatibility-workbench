package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationRuntimeTest {
    @TempDir Path root;
    private final MutableClock clock = new MutableClock();
    private final FakeBrokers brokers = new FakeBrokers();
    private static ContentDigest digest(char value) { return new ContentDigest(String.valueOf(value).repeat(64)); }
    private CoordinationRuntime runtime() throws IOException { return new CoordinationRuntime(root, clock, brokers); }
    private PolicyFixture fixture(int calls, long millis, Arm arm) throws Exception {
        String bundle = CoordinationInputsTest.fixture(root); var identities = new CoordinationInputs(root).identities(bundle);
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm a : Arm.values()) implementations.put(a, digest((char) ('1' + a.ordinal())));
        var cases = identities.entrySet().stream().map(e -> new CaseTask(e.getKey(), e.getValue(), ExpectedResponse.ANSWER, List.of("observed-regressions"))).toList();
        Set<String> protocols = arm == Arm.DETERMINISTIC ? Set.of() : Set.of("investigation-v2", "coordination-v2", "reconciliation-v1");
        var policy = new CoordinationRuntime.Policy(2, 100, digest('6'), digest('8'), digest('9'), digest('a'), "complete-dossier", List.of(
                new CoordinationRuntime.Participant("worker", Set.of(CaseInputs.CASE_ID), protocols), new CoordinationRuntime.Participant("root", identities.keySet(), protocols)));
        implementations.put(arm, CoordinationRuntime.implementationIdentity(policy));
        var definition = new Definition("registered-comparison", Origin.SUPPLIED_RECORDS, "gpt-test", digest('7'), implementations, List.of(new Workload("two-case", cases)), 3, new Limits(calls, millis, 64000, 2048));
        return new PolicyFixture(bundle, definition, new Slot("two-case", 1, arm), policy);
    }
    private record PolicyFixture(String bundle, Definition definition, Slot slot, CoordinationRuntime.Policy policy) {
        String register(CoordinationRuntime runtime) throws IOException { return runtime.register(bundle, definition, slot, policy); }
    }
    private static ModelCallBroker.Request request(String input) { return new ModelCallBroker.Request("gpt-test", input, 2048, "investigation-v2"); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private Path run(String id) { return root.resolve("coordination-trials/" + id); }
    @Test void registrationsFreezeActualInputsAndAllPolicyFieldsWithoutStartingWork() throws Exception {
        var fixture = fixture(60, 1200000, Arm.SINGLE_AGENT); var runtime = runtime(); String id = fixture.register(runtime);
        assertFalse(Files.exists(run(id).resolve("started.json"))); assertEquals(0, brokers.registered.get());
        assertEquals(0, runtime.snapshot(id).getAsJsonObject("usage").get("reservedCalls").getAsInt());
        var changedCases = fixture.definition().workloads().getFirst().cases().stream().map(c -> new CaseTask(c.caseId(), digest('b'), c.expectedResponse(), c.criteria())).toList();
        var changed = new Definition("changed", Origin.SUPPLIED_RECORDS, "gpt-test", digest('7'), fixture.definition().implementations(), List.of(new Workload("two-case", changedCases)), 3, fixture.definition().limits());
        assertThrows(IllegalArgumentException.class, () -> runtime.register(fixture.bundle(), changed, fixture.slot(), fixture.policy()));
        assertThrows(IllegalArgumentException.class, () -> runtime.register(fixture.bundle(), CoordinationComparisonReport.examplePlan().definition(), fixture.slot(), fixture.policy()));
        var p = fixture.policy(); var changedPolicy = new CoordinationRuntime.Policy(1, p.maxReads(), p.adapterIdentity(), p.promptIdentity(), p.toolPolicyIdentity(), p.contextPolicyIdentity(), p.finalSelection(), p.participants());
        assertNotEquals(CoordinationRuntime.implementationIdentity(p), CoordinationRuntime.implementationIdentity(changedPolicy));
        assertThrows(IllegalArgumentException.class, () -> runtime.register(fixture.bundle(), fixture.definition(), fixture.slot(), changedPolicy));
    }
    @Test void oneAgentCanUseTheEntireCeilingAndAllStagesShareThatCeiling() throws Exception {
        var runtime = runtime(); String id = fixture(60, 1200000, Arm.SINGLE_AGENT).register(runtime);
        for (int i = 0; i < 60; i++) assertEquals("COMPLETED", runtime.call(id, i % 2 == 0 ? "worker" : "root", "decision-" + i, request("read data")).get("status").getAsString());
        assertEquals("CALL_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.call(id, "root", "extra", request("read data"))).getMessage());
        var usage = runtime.snapshot(id).getAsJsonObject("usage"); assertEquals(60, usage.get("reservedCalls").getAsInt()); assertEquals(60, usage.get("completedResponses").getAsInt()); assertEquals(180, usage.get("inputTokens").getAsInt());
    }
    @Test void restartReusesExactRegisteredCallAndDoesNotDispatchCompletedResponseAgain() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1200000, Arm.COORDINATED_TEAM).register(runtime); var first = runtime.call(id, "worker", "decision-1", request("fixed"));
        assertEquals(first, runtime().call(id, "worker", "decision-1", request("fixed"))); assertEquals(1, brokers.registered.get()); assertEquals(1, brokers.dispatched.get());
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "worker", "decision-1", request("changed")));
    }
    @Test void uncertainRegistrationConsumesBudgetAndCannotBeReplacedOnResume() throws Exception {
        var runtime = runtime(); String id = fixture(1, 1200000, Arm.COORDINATED_TEAM).register(runtime); brokers.failRegistration = true;
        assertThrows(IOException.class, () -> runtime.call(id, "root", "decision-1", request("fixed"))); brokers.failRegistration = false;
        assertEquals("CALL_REGISTRATION_UNCERTAIN", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(id, "root", "decision-1", request("fixed"))).getMessage());
        assertEquals("CALL_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.call(id, "worker", "replacement", request("fixed"))).getMessage());
        var usage = runtime.snapshot(id).getAsJsonObject("usage"); assertEquals(1, usage.get("reservedCalls").getAsInt()); assertEquals(1, usage.get("unresolvedUsageSlots").getAsInt()); assertEquals(0, brokers.dispatched.get());
        assertTrue(runtime.snapshot(id).getAsJsonObject("artifactSha256").keySet().stream().anyMatch(p -> p.startsWith("broker/") && p.endsWith("request.json")));
    }
    @Test void unknownAndKnownUndispatchedOutcomesHaveDistinctAccounting() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1200000, Arm.INDEPENDENT_WORKERS).register(runtime); brokers.remote = "UNKNOWN";
        runtime.call(id, "worker", "first", request("fixed")); brokers.remote = "NOT_DISPATCHED"; runtime.call(id, "root", "second", request("fixed"));
        var usage = runtime.snapshot(id).getAsJsonObject("usage"); assertEquals(1, usage.get("notDispatchedSlots").getAsInt()); assertEquals(1, usage.get("unresolvedUsageSlots").getAsInt()); assertEquals(0, usage.get("completedResponses").getAsInt());
    }
    @Test void scopesAndPerRequestLimitsRejectBeforeBrokerRegistration() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1200000, Arm.COORDINATED_TEAM).register(runtime);
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "unknown", "a", request("fixed")));
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "worker", "a", new ModelCallBroker.Request("gpt-other", "fixed", 2048, "investigation-v2")));
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "worker", "a", new ModelCallBroker.Request("gpt-test", "fixed", 4096, "investigation-v2")));
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "worker", "a", new ModelCallBroker.Request("gpt-test", "fixed", 2048, "repair-v2")));
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "worker", "../a", request("fixed")));
        assertThrows(IllegalArgumentException.class, () -> runtime.read(id, "worker", "a", SubscriptionPeriodCase.CASE_ID, "baseline-source"));
        assertEquals(0, brokers.registered.get());
        var fixture = fixture(2, 1200000, Arm.COORDINATED_TEAM); var d = fixture.definition(); var smaller = new Definition(d.experimentId(), d.origin(), d.model(), d.rubricIdentity(), d.implementations(), d.workloads(), d.repetitions(), new Limits(2, 1200000, 5, 2048));
        String small = runtime.register(fixture.bundle(), smaller, fixture.slot(), fixture.policy()); assertThrows(IllegalArgumentException.class, () -> runtime.call(small, "worker", "a", request("😀😀"))); assertEquals(0, brokers.registered.get());
    }
    @Test void deterministicArmHasNoModelPermissionButCanReadTheSameEvidence() throws Exception {
        var runtime = runtime(); String id = fixture(1, 1200000, Arm.DETERMINISTIC).register(runtime);
        assertEquals("untrusted-data", runtime.read(id, "root", "read-1", CaseInputs.CASE_ID, "baseline-source").get("contentTrust").getAsString());
        assertThrows(IllegalArgumentException.class, () -> runtime.call(id, "root", "call-1", request("fixed"))); assertEquals(0, brokers.registered.get());
        var usage = runtime.snapshot(id).getAsJsonObject("usage"); assertEquals(1, usage.get("toolCalls").getAsInt()); assertEquals(0, usage.get("caseExecutions").getAsInt());
    }
    @Test void readRetriesDoNotChangeCountsAndCannotSwapArgumentsOrExceedFrozenReadLimit() throws Exception {
        var f = fixture(2, 1200000, Arm.SINGLE_AGENT); var p = f.policy(); var limited = new CoordinationRuntime.Policy(1, 1, p.adapterIdentity(), p.promptIdentity(), p.toolPolicyIdentity(), p.contextPolicyIdentity(), p.finalSelection(), p.participants());
        var d = f.definition(); var implementations = new EnumMap<Arm, ContentDigest>(d.implementations()); implementations.put(Arm.SINGLE_AGENT, CoordinationRuntime.implementationIdentity(limited));
        var definition = new Definition(d.experimentId(), d.origin(), d.model(), d.rubricIdentity(), implementations, d.workloads(), d.repetitions(), d.limits());
        var runtime = runtime(); String id = runtime.register(f.bundle(), definition, f.slot(), limited);
        var first = runtime.read(id, "worker", "first", CaseInputs.CASE_ID, "baseline-source"); assertEquals(first, runtime().read(id, "worker", "first", CaseInputs.CASE_ID, "baseline-source"));
        assertThrows(IllegalArgumentException.class, () -> runtime.read(id, "worker", "first", CaseInputs.CASE_ID, "fixtures"));
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.read(id, "worker", "second", CaseInputs.CASE_ID, "fixtures")).getMessage());
        assertEquals(1, runtime.snapshot(id).getAsJsonObject("usage").get("toolCalls").getAsInt());
    }
    @Test void wholeTrialDeadlineSurvivesRestartAndBoundsBrokerPermissionLifetime() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1000, Arm.COORDINATED_TEAM).register(runtime); runtime.start(id); clock.now = clock.now.plusMillis(900);
        runtime.call(id, "root", "first", request("fixed")); assertEquals(Duration.ofMillis(100), brokers.lastLifetime);
        clock.now = clock.now.plusMillis(100); assertEquals("TRIAL_EXPIRED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(id, "worker", "second", request("fixed"))).getMessage());
        assertEquals(1, brokers.dispatched.get()); assertEquals(1000, runtime.snapshot(id).get("elapsedMillis").getAsLong());
    }
    @Test void revocationAndChangedCapturedInputsStopAllParticipantsAndKeepExistingFacts() throws Exception {
        var f = fixture(2, 1200000, Arm.COORDINATED_TEAM); var runtime = runtime(); String id = f.register(runtime); runtime.call(id, "worker", "first", request("fixed")); runtime.revoke(id); runtime.revoke(id);
        assertEquals("TRIAL_REVOKED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.call(id, "root", "second", request("fixed"))).getMessage());
        assertEquals(1, runtime.snapshot(id).getAsJsonObject("usage").get("completedResponses").getAsInt());
        String changed = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime); var spec = CaseInputs.object(Files.readAllBytes(run(changed).resolve("spec.json")));
        Files.writeString(root.resolve("coordination-inputs/" + spec.get("bundleId").getAsString() + "/" + CaseInputs.CASE_ID + "/fixtures"), "changed");
        assertThrows(IllegalArgumentException.class, () -> runtime.call(changed, "root", "first", request("fixed"))); assertEquals(1, brokers.dispatched.get());
    }
    @Test void onlyDeclaredNumberOfOperationsCanRunConcurrentlyAndActiveWorkCannotBeFinalized() throws Exception {
        var runtime = runtime(); String id = fixture(3, 1200000, Arm.COORDINATED_TEAM).register(runtime); var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        brokers.entered = entered; brokers.release = release;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runtime.call(id, "worker", "first", request("fixed"))); var second = pool.submit(() -> runtime.call(id, "root", "second", request("fixed")));
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS)); assertEquals("ACTIVE", runtime.call(id, "root", "third", request("fixed")).get("status").getAsString());
                assertEquals("RUN_ACTIVE", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.finish(id, CoordinationTrial.Status.COMPLETED)).getMessage()); assertEquals(2, brokers.registered.get());
            } finally { release.countDown(); }
            assertEquals("COMPLETED", first.get(20, TimeUnit.SECONDS).get("status").getAsString()); assertEquals("COMPLETED", second.get(20, TimeUnit.SECONDS).get("status").getAsString());
        }
    }
    @Test void terminalTraceIsImmutableAndDoesNotTurnUnknownUsageIntoAQualityVerdict() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime); brokers.remote = "UNKNOWN"; runtime.call(id, "root", "first", request("fixed"));
        var result = runtime.finish(id, CoordinationTrial.Status.FAILED); assertEquals("runtime-accounting-not-quality-review", result.get("authority").getAsString());
        assertEquals(result, runtime().retained(id)); assertEquals(result, runtime.finish(id, CoordinationTrial.Status.COMPLETED));
        clock.now = clock.now.plusSeconds(1201); assertEquals(result, runtime().snapshot(id));
        assertEquals("TRIAL_FINISHED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.call(id, "root", "second", request("fixed"))).getMessage()); assertEquals(1, brokers.dispatched.get());
        var altered = result.deepCopy(); altered.addProperty("status", "COMPLETED"); Files.writeString(run(id).resolve("result.json"), altered.toString()); assertThrows(IllegalArgumentException.class, () -> runtime.retained(id));
    }
    @Test void freshProcessCanReadCapturedIdentitiesAndFinishedTraceWithoutCalls() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime); runtime.read(id, "root", "first", CaseInputs.CASE_ID, "baseline-source"); runtime.finish(id, CoordinationTrial.Status.CANCELLED);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Controller.class.getName(), root.toString(), id).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); assertEquals("CANCELLED", new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void changedGrantOrMappedRequestAndPartialLedgerPublicationFailClosed() throws Exception {
        var runtime = runtime(); String denied = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime);
        Files.writeString(run(denied).resolve("grant.json"), "{}"); assertThrows(RuntimeException.class, () -> runtime.call(denied, "root", "first", request("fixed"))); assertEquals(0, brokers.registered.get());
        assertThrows(IllegalArgumentException.class, () -> runtime.snapshot(denied));
        String changed = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime); runtime.call(changed, "root", "first", request("fixed"));
        Path brokerRequest; try (var paths = Files.walk(run(changed).resolve("broker"))) { brokerRequest = paths.filter(p -> p.getFileName().toString().equals("request.json")).findFirst().orElseThrow(); }
        DurableExecution.publish(brokerRequest, request("changed")); assertThrows(IllegalArgumentException.class, () -> runtime().call(changed, "root", "first", request("fixed")));
        String partial = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime); Files.writeString(run(partial).resolve("calls/" + "0".repeat(64) + ".json.pending"), "{}");
        assertThrows(IllegalArgumentException.class, () -> runtime.call(partial, "root", "first", request("fixed"))); assertEquals(1, brokers.registered.get());
    }
    @Test void reportedOutputOverrunStopsTheStageWithoutErasingCompletedUsage() throws Exception {
        var runtime = runtime(); String id = fixture(2, 1200000, Arm.SINGLE_AGENT).register(runtime); brokers.outputTokens = 2049;
        assertEquals("OUTPUT_LIMIT_EXCEEDED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime.call(id, "root", "first", request("fixed"))).getMessage());
        var trace = runtime.snapshot(id); assertEquals(1, trace.getAsJsonArray("resourceIssues").size()); assertEquals(2049, trace.getAsJsonObject("usage").get("outputTokens").getAsInt());
        assertEquals(1, trace.getAsJsonObject("usage").get("completedResponses").getAsInt());
    }
    @Test void finalizationReconcilesMappedUnfinishedCallsWithoutDispatchOrRenewedPermission() throws Exception {
        for (boolean started : List.of(false, true)) {
            var runtime = runtime(); String id = fixture(2, 1200000, Arm.COORDINATED_TEAM).register(runtime); brokers.failExecute = true; brokers.recordStarted = started;
            assertThrows(IOException.class, () -> runtime.call(id, "root", "first", request("fixed"))); assertEquals(1, runtime.snapshot(id).getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
            clock.now = clock.now.plusSeconds(1201); runtime.revoke(id); var result = runtime.finish(id, CoordinationTrial.Status.CANCELLED); var usage = result.getAsJsonObject("usage");
            assertEquals(started ? 1 : 0, usage.get("unresolvedUsageSlots").getAsInt()); assertEquals(started ? 0 : 1, usage.get("notDispatchedSlots").getAsInt()); assertEquals(0, brokers.dispatched.get());
            assertEquals(result, runtime().retained(id));
        }
    }
    public static final class Controller {
        public static void main(String[] args) throws Exception { System.out.println(new CoordinationRuntime(Path.of(args[0])).retained(args[1]).get("status").getAsString()); }
    }
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T08:00:00Z"); public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
    }
    static final class FakeBrokers implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(); boolean failRegistration, failExecute, recordStarted; int outputTokens = 2; String remote = "RESPONSE_RECEIVED"; Duration lastLifetime; CountDownLatch entered, release;
        public CoordinationRuntime.Broker create(Path root, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String call = "model-" + UUID.randomUUID(); Files.createDirectory(root.resolve(call)); DurableExecution.publish(root.resolve(call + "/request.json"), request); registered.incrementAndGet(); lastLifetime = lifetime;
                    if (failRegistration) throw new IOException("Registration interrupted"); return call;
                }
                public JsonObject execute(String call) throws IOException {
                    Path result = root.resolve(call + "/result.json"); if (Files.exists(result)) return CaseInputs.object(Files.readAllBytes(result));
                    if (failExecute) { if (recordStarted) DurableExecution.publish(root.resolve(call + "/started.json"), Map.of("callId", call)); throw new IOException("Controller interrupted"); } dispatched.incrementAndGet();
                    if (entered != null) { entered.countDown(); try { if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Test release timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
                    assertTrue(permitted.getAsBoolean()); var value = json(Map.of("callId", call, "status", remote.equals("RESPONSE_RECEIVED") ? "COMPLETED" : "STOPPED", "remoteOutcome", remote, "inputTokens", remote.equals("RESPONSE_RECEIVED") ? 3 : 0, "outputTokens", remote.equals("RESPONSE_RECEIVED") ? outputTokens : 0));
                    DurableExecution.publish(result, value); return value;
                }
                public JsonObject recover(String call) throws IOException {
                    Path result = root.resolve(call + "/result.json"); if (Files.exists(result)) return CaseInputs.object(Files.readAllBytes(result));
                    var value = json(Map.of("callId", call, "status", "CLOSED", "remoteOutcome", Files.exists(root.resolve(call + "/started.json")) ? "UNKNOWN" : "NOT_DISPATCHED"));
                    DurableExecution.publish(result, value); return value;
                }
            };
        }
    }
}
