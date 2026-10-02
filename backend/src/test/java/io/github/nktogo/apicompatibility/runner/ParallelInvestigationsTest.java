package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ParallelInvestigationsTest {
    @TempDir Path root;
    private final Runtime runtime = new Runtime();
    private final MutableClock clock = new MutableClock();
    private ParallelInvestigations scheduler() throws IOException { return new ParallelInvestigations(root, clock, runtime); }
    private ConsumerTasks tasks() throws IOException { return new ConsumerTasks(root, clock, runtime); }
    private Path task(String id) { return root.resolve("tasks/" + id); }
    private Path batch(String id) { return root.resolve("parallel-investigations/" + id); }
    private String customer() throws IOException {
        Path source = InvestigationToolsTest.caseRoot();
        return tasks().registerCustomer(source, source.resolve("target/dependencies"), RepairVerificationTest.IMAGE, "gpt-test");
    }
    private String subscription() throws Exception {
        Path source = root.resolve("source"); var verifier = new RepairVerification(source, new SubscriptionRepairEvaluationTest.Sandbox());
        String evidence = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "bound\n", RepairVerificationTest.IMAGE);
        verifier.execute(evidence); return tasks().registerSubscription(source, evidence, "gpt-test");
    }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static String status(JsonObject result, String task) { return result.getAsJsonObject("children").getAsJsonObject(task).get("status").getAsString(); }

    @Test void heterogeneousTasksOverlapButKeepHistoriesAndUsageSeparate() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = subscription();
        runtime.barrier = new CountDownLatch(2);
        String id = scheduler.register(List.of(first, second), 2, 24); var result = scheduler.run(id);
        assertEquals("COMPLETED", result.get("status").getAsString());
        assertEquals("ABSTAINED", status(result, first)); assertEquals("INVESTIGATED", status(result, second));
        assertEquals(2, runtime.peak.get()); assertEquals(5, runtime.dispatched.get());
        assertEquals(5, result.getAsJsonObject("usage").get("reservedSlots").getAsInt());
        assertEquals(50, result.getAsJsonObject("usage").get("inputTokens").getAsInt());
        assertEquals(0, result.getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        for (var entry : runtime.requests.entrySet()) {
            String own = entry.getKey().startsWith(task(first)) ? CaseInputs.CASE_ID : SubscriptionPeriodCase.CASE_ID;
            assertTrue(entry.getValue().stream().allMatch(r -> r.input().contains(own)));
            assertFalse(entry.getValue().stream().anyMatch(r -> r.input().contains(own.equals(CaseInputs.CASE_ID) ? SubscriptionPeriodCase.CASE_ID : CaseInputs.CASE_ID)));
        }
        scheduler.revoke(id); clock.now = clock.now.plus(Duration.ofHours(1));
        assertEquals(result, scheduler().run(id)); assertEquals(5, runtime.dispatched.get());
    }

    @Test void serialAdmissionAndOneSharedSlotCannotBeExceededByRacingTasks() throws Exception {
        for (int parallelism : List.of(1, 2)) {
            var scheduler = scheduler(); var ids = List.of(customer(), customer(), customer(), customer());
            String id = scheduler.register(ids, parallelism, 1); var result = scheduler.run(id);
            for (String task : ids) assertEquals("BATCH_CALL_BUDGET_EXHAUSTED", status(result, task));
            assertEquals(1, result.getAsJsonObject("usage").get("reservedSlots").getAsInt());
            assertEquals(1, result.getAsJsonObject("usage").get("completedResponses").getAsInt());
        }
        assertEquals(2, runtime.registered.get()); assertEquals(2, runtime.dispatched.get());
        assertEquals(1, runtime.peak.get());
    }

    @Test void assignedTasksCannotRunIndependentlyOrBeAssignedAgain() throws Exception {
        String first = customer(), second = customer(); var scheduler = scheduler();
        String id = scheduler.register(List.of(first, second), 1, 24);
        assertEquals("TASK_ASSIGNED_TO_BATCH", tasks().run(first).get("status").getAsString());
        assertEquals("TASK_ASSIGNED_TO_BATCH", tasks().invoke(first, "describe_case", new JsonObject()).get("error").getAsString());
        assertFalse(Files.exists(task(first).resolve("result.json")));
        assertThrows(RuntimeException.class, () -> scheduler.register(List.of(first, second), 1, 24));
        assertEquals("ABSTAINED", status(scheduler.run(id), first)); assertEquals(4, runtime.dispatched.get());
    }

    @Test void invalidBudgetsUsedTasksAndActiveTasksAreRejectedBeforeClaiming() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer();
        for (int p : List.of(0, 3)) assertThrows(IllegalArgumentException.class, () -> scheduler.register(List.of(first, second), p, 24));
        for (int n : List.of(0, 25)) assertThrows(IllegalArgumentException.class, () -> scheduler.register(List.of(first, second), 2, n));
        assertThrows(IllegalArgumentException.class, () -> scheduler.register(List.of(first, first), 2, 24));
        assertThrows(IllegalArgumentException.class, () -> scheduler.register(List.of(first), 1, 12));
        try (var lease = LocalFileLease.acquire(task(first).resolve("owner.lock"))) {
            assertNotNull(lease); assertThrows(IllegalArgumentException.class, () -> scheduler.register(List.of(first, second), 2, 24));
        }
        tasks().run(first);
        assertThrows(IllegalArgumentException.class, () -> scheduler.register(List.of(first, second), 2, 24));
        assertFalse(Files.exists(task(second).resolve("parallel-owner.json")));
    }

    @Test void activeRootAndActiveChildResumeTheSameMappedRequest() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer();
        String id = scheduler.register(List.of(first, second), 1, 24);
        try (var lease = LocalFileLease.acquire(batch(id).resolve("owner.lock"))) {
            assertNotNull(lease); assertEquals("ACTIVE", scheduler.run(id).get("status").getAsString());
            assertEquals(0, runtime.registered.get());
        }
        runtime.activeOnce.set(true);
        var active = scheduler.run(id); assertEquals("ACTIVE", active.get("status").getAsString());
        assertEquals("ACTIVE", status(active, first)); assertEquals("ABSTAINED", status(active, second));
        assertEquals(3, runtime.registered.get());
        // Simulate death after the root mapping but before the agent's call mapping was published.
        String agent = record(task(first).resolve("agent.json")).get("agentId").getAsString();
        Files.delete(task(first).resolve("state/agents/" + agent + "/call-1.json"));
        var result = scheduler().run(id);
        assertEquals("ABSTAINED", status(result, first)); assertEquals(4, runtime.registered.get());
        assertEquals(4, runtime.dispatched.get()); assertEquals(result, scheduler().run(id));
    }

    @Test void rootRevocationAndExpiryStopQueuedAndInflightAdmission() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer();
        String id = scheduler.register(List.of(first, second), 1, 24);
        String revoked = id;
        runtime.afterResponse = () -> { try { scheduler.revoke(revoked); } catch (IOException e) { throw new IllegalStateException(e); } };
        var result = scheduler.run(id);
        assertEquals("BATCH_REVOKED", status(result, first)); assertEquals("BATCH_REVOKED", status(result, second));
        assertEquals(1, runtime.dispatched.get()); assertEquals(1, result.getAsJsonObject("usage").get("completedResponses").getAsInt());
        first = customer(); second = customer(); id = scheduler.register(List.of(first, second), 1, 24);
        clock.now = clock.now.plus(Duration.ofMinutes(20)); result = scheduler().run(id);
        assertEquals("BATCH_EXPIRED", status(result, first)); assertEquals("BATCH_EXPIRED", status(result, second));
        assertEquals(1, runtime.dispatched.get());
    }

    @Test void changedTaskInputsAndIndividualRevocationDoNotStopTheOtherTask() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer();
        String id = scheduler.register(List.of(first, second), 2, 24);
        tasks().revoke(first); var result = scheduler.run(id);
        assertEquals("TASK_REVOKED", status(result, first)); assertEquals("ABSTAINED", status(result, second));
        first = customer(); second = customer(); id = scheduler.register(List.of(first, second), 2, 24);
        String context = record(task(first).resolve("task.json")).get("contextId").getAsString();
        Files.writeString(task(first).resolve("state/investigations/" + context + "/inputs/baseline-source"), "changed");
        result = scheduler.run(id);
        assertEquals("TASK_INPUT_IDENTITY_MISMATCH", status(result, first)); assertEquals("ABSTAINED", status(result, second));
    }

    @Test void copiedRootGrantAndChangedTaskClaimCannotAuthorizeDispatch() throws Exception {
        var scheduler = scheduler(); var a = List.of(customer(), customer()); var b = List.of(customer(), customer());
        String first = scheduler.register(a, 1, 24), second = scheduler.register(b, 1, 24);
        Files.copy(batch(second).resolve("grant.json"), batch(first).resolve("grant.json"), StandardCopyOption.REPLACE_EXISTING);
        var result = scheduler.run(first); for (String task : a) assertEquals("BATCH_AUTHORIZATION_DENIED", status(result, task));
        Files.copy(task(b.get(1)).resolve("parallel-owner.json"), task(b.get(0)).resolve("parallel-owner.json"), StandardCopyOption.REPLACE_EXISTING);
        result = scheduler.run(second);
        assertEquals("BATCH_TASK_IDENTITY_MISMATCH", status(result, b.get(0))); assertEquals("ABSTAINED", status(result, b.get(1)));
        assertEquals(2, runtime.dispatched.get());
    }

    @Test void brokerRegistrationGapConsumesASlotAndIsNeverRepeated() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer();
        String id = scheduler.register(List.of(first, second), 1, 24); runtime.registrationGap.set(true);
        var result = scheduler.run(id);
        assertEquals("BATCH_CALL_REGISTRATION_UNCERTAIN", status(result, first)); assertEquals("ABSTAINED", status(result, second));
        assertEquals(3, result.getAsJsonObject("usage").get("reservedSlots").getAsInt());
        assertEquals(2, result.getAsJsonObject("usage").get("registeredCalls").getAsInt());
        assertEquals(1, result.getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        assertEquals(result, scheduler().run(id)); assertEquals(3, runtime.registered.get());
        // Simulate a controller dying after the reservation: the same payload cannot register another broker call.
        Files.delete(batch(id).resolve("result.json")); Files.delete(batch(id).resolve("children/" + first + ".json"));
        Files.writeString(batch(id).resolve("calls/" + first + "-1-mapping.json.pending"), "{}");
        result = scheduler().run(id); assertEquals("BATCH_CALL_REGISTRATION_UNCERTAIN", status(result, first));
        assertEquals(3, runtime.registered.get());
    }

    @Test void ambiguousProviderOutcomeIsRetainedWithoutRetry() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer();
        String id = scheduler.register(List.of(first, second), 1, 24); runtime.unknownOnce.set(true);
        var result = scheduler.run(id);
        assertEquals("MODEL_UNKNOWN_REMOTE_OUTCOME", status(result, first)); assertEquals("ABSTAINED", status(result, second));
        assertEquals(3, runtime.registered.get()); assertEquals(3, runtime.dispatched.get());
        assertEquals(1, result.getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        assertEquals(result, scheduler().run(id)); assertEquals(3, runtime.dispatched.get());
    }

    @Test void interruptedControllerDrainsWorkersAndResumesMappedCallsWithoutExtraRegistration() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer(); String id = scheduler.register(List.of(first, second), 2, 24);
        runtime.block = new CountDownLatch(1); runtime.entered = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>(); var restoredInterrupt = new AtomicBoolean();
        Thread controller = new Thread(() -> {
            try { scheduler.run(id); } catch (Throwable e) { failure.set(e); restoredInterrupt.set(Thread.currentThread().isInterrupted()); }
        });
        controller.start(); assertTrue(runtime.entered.await(10, TimeUnit.SECONDS));
        controller.interrupt(); controller.join(10000); assertFalse(controller.isAlive()); assertInstanceOf(IOException.class, failure.get());
        assertTrue(restoredInterrupt.get()); assertEquals(0, runtime.inflight.get());
        assertFalse(Files.exists(batch(id).resolve("result.json"))); assertEquals(2, runtime.registered.get());
        runtime.block.countDown(); var result = scheduler().run(id);
        assertEquals("ABSTAINED", status(result, first)); assertEquals("ABSTAINED", status(result, second));
        assertEquals(4, runtime.registered.get()); assertEquals(4, runtime.dispatched.get()); assertEquals(2, runtime.peak.get());
    }

    @Test void changedCompletedChildAndAccountingCannotBeSilentlyReused() throws Exception {
        var scheduler = scheduler(); String first = customer(), second = customer(); String id = scheduler.register(List.of(first, second), 2, 24);
        scheduler.run(id);
        Path child = batch(id).resolve("children/" + first + ".json"); byte[] original = Files.readAllBytes(child);
        var value = record(child); value.addProperty("status", "INVESTIGATED"); CaseInputs.write(child, value);
        assertThrows(IllegalArgumentException.class, () -> scheduler().run(id)); Files.write(child, original);
        String call = runtime.responses.keySet().iterator().next(); Path response = runtime.paths.get(call).resolve("model-calls/" + call + "/result.json");
        value = record(response); value.addProperty("inputTokens", 999); CaseInputs.write(response, value);
        assertThrows(IllegalArgumentException.class, () -> scheduler().run(id)); assertEquals(4, runtime.dispatched.get());
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private static final class Runtime implements ConsumerTasks.Backends {
        final Map<Path, List<ModelCallBroker.Request>> requests = new ConcurrentHashMap<>();
        final Map<String, JsonObject> responses = new ConcurrentHashMap<>(); final Map<String, Path> paths = new ConcurrentHashMap<>();
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), inflight = new AtomicInteger(), peak = new AtomicInteger();
        final AtomicBoolean activeOnce = new AtomicBoolean(), registrationGap = new AtomicBoolean(), unknownOnce = new AtomicBoolean();
        volatile CountDownLatch barrier, block, entered; volatile Runnable afterResponse;
        public ConsumerInvestigator.Models models(Path state) throws IOException {
            var broker = new ModelCallBroker(state.resolve("model-calls"));
            return new ConsumerInvestigator.Models() {
                public String register(ModelCallBroker.Request request) throws IOException {
                    var history = requests.computeIfAbsent(state, ignored -> new ArrayList<>()); int turn = history.size(); history.add(request);
                    boolean subscription = request.input().contains(SubscriptionPeriodCase.CASE_ID);
                    var decision = turn == 0 ? json(Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000))
                            : subscription && turn == 1 ? json(Map.of("action", "read_run"))
                            : json(Map.of("action", "finish", "outcome", subscription ? "INVESTIGATED" : "ABSTAINED", "summary", "Scoped review.",
                            "findings", List.of(Map.of("kind", subscription ? "HYPOTHESIS" : "LIMITATION", "statement", "Bounded finding.", "citations", List.of("event-1")))));
                    String id = broker.register(request, Duration.ofMinutes(2)); registered.incrementAndGet(); paths.put(id, state);
                    responses.put(id, json(Map.of("status", "COMPLETED", "remoteOutcome", "RESPONSE_RECEIVED", "inputTokens", 10,
                            "outputTokens", 10, "outputText", json(Map.of("decision", decision)).toString())));
                    if (registrationGap.getAndSet(false)) throw new IOException("Simulated crash between broker and root mappings");
                    return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path result = state.resolve("model-calls/" + id + "/result.json"); if (Files.exists(result)) return record(result);
                    if (activeOnce.getAndSet(false)) return json(Map.of("status", "ACTIVE"));
                    peak.accumulateAndGet(inflight.incrementAndGet(), Math::max);
                    try {
                        if (entered != null) entered.countDown();
                        if (barrier != null) { barrier.countDown(); if (!barrier.await(10, TimeUnit.SECONDS)) throw new IOException("No concurrent peer"); }
                        if (block != null && !block.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for release");
                        var response = unknownOnce.getAndSet(false) ? json(Map.of("status", "UNKNOWN_REMOTE_OUTCOME", "remoteOutcome", "UNKNOWN")) : responses.get(id);
                        DurableExecution.publish(result, response); dispatched.incrementAndGet();
                        Runnable callback = afterResponse; if (callback != null) { afterResponse = null; callback.run(); }
                        return response;
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted before dispatch", e); }
                    finally { inflight.decrementAndGet(); }
                }
            };
        }
    }
}
