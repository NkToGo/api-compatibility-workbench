package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTeamRunnerTest {
    @TempDir Path root;
    private Path inputs; private ContentDigest identity;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceInvestigation(root, inputs, identity, clock, scripts); }
    private InvoiceTeamRunner adapter() throws IOException { return new InvoiceTeamRunner(root, runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private JsonObject execute(int calls) throws Exception { return execute(calls, 64000); }
    private JsonObject execute(int calls, int bytes) throws Exception { String id = fixture(calls, bytes); return adapter().run(id); }
    private String fixture(int calls, int bytes) throws Exception {
        if (inputs == null) {
            var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("capture"), Duration.ofSeconds(30), () -> true);
            inputs = root.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
        }
        var base = InvoiceTeamRunner.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, bytes, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM));
    }
    private Path run(String id) { return root.resolve("invoice-team-runs/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path file) throws IOException { return CaseInputs.object(Files.readAllBytes(file)); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static boolean review(JsonObject input) { return input.has("workers"); }
    private static String participant(JsonObject input) { return review(input) ? "invoice-coordinator" : input.getAsJsonObject("case").get("workerId").getAsString(); }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String original(JsonObject input, List<String> citations) {
        return decision(Map.of("action", "finish", "response", "ABSTAINED", "summary", "original-claim-" + participant(input), "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "The captured evidence does not establish release approval.", "citations", citations))));
    }
    private static String finishReview(JsonObject input, boolean questions) {
        var proposals = new ArrayList<Object>();
        if (questions) for (var value : input.getAsJsonArray("workers")) {
            var w = value.getAsJsonObject(); proposals.add(Map.of("workerId", w.get("workerId").getAsString(), "question", "Which recorded ledger effects remain unexplained?", "citations", List.of(w.get("referenceId").getAsString())));
        }
        return decision(Map.of("action", "finish", "summary", "Original outcomes remain advisory.", "citations", List.of("worker-1", "worker-2"), "followUps", proposals, "conflicts", List.of()));
    }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("stageTrace").getAsJsonObject("usage"); }

    @Test void concurrentRoleContextsReachASealedReviewWithOneLedgerAndNoWholeTrialExport() throws Exception {
        scripts.barrier = new CyclicBarrier(2); scripts.originalScript = (input, turn) -> turn == 1 ? read(InvoiceWorkerDecision.baseline(participant(input))) : original(input, List.of("read-1"));
        scripts.reviewScript = (input, turn) -> turn == 1 ? read("task") : finishReview(input, true);
        String id = fixture(60); var result = adapter().run(id);
        assertEquals("REVIEW_RECORDED", result.get("status").getAsString()); assertEquals("PENDING", result.get("wholeTrialStatus").getAsString()); assertFalse(result.has("trial"));
        assertEquals(2, scripts.maximum.get()); assertEquals(6, scripts.dispatched.get()); assertEquals(4, result.getAsJsonObject("stageReservations").get("investigation").getAsInt());
        assertEquals(2, result.getAsJsonObject("stageReservations").get("review").getAsInt()); assertEquals(5, usage(result).get("toolCalls").getAsInt()); assertEquals(0, usage(result).get("caseExecutions").getAsInt());
        assertFalse(Files.exists(root.resolve("invoice-comparison-trials/" + id + "/result.json")));
        var selected = result.getAsJsonObject("coordinatorReview").getAsJsonObject("selection"); assertEquals(4, selected.getAsJsonArray("originalClaims").size()); assertEquals(2, selected.getAsJsonArray("selectedFollowUps").size());
        for (var context : scripts.contexts) if (!review(context)) {
            assertFalse(context.has("originalClaims")); String sibling = InvoiceWorkerDecision.WORKERS.stream().filter(w -> !w.equals(participant(context))).findFirst().orElseThrow(); assertFalse(context.toString().contains("original-claim-" + sibling));
        } else { assertEquals(2, context.getAsJsonArray("workers").size()); assertEquals(4, context.getAsJsonArray("originalClaims").size()); }
        assertEquals(result, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void cumulativeStageCapsAndForwardCarryPreserveTheUnspentFuturePool() throws Exception {
        scripts.originalScript = (input, turn) -> read("task"); scripts.reviewScript = (input, turn) -> read("task");
        var exhausted = execute(60); assertEquals(24, exhausted.getAsJsonObject("stageReservations").get("investigation").getAsInt());
        assertEquals(6, exhausted.getAsJsonObject("stageReservations").get("review").getAsInt()); assertEquals(30, usage(exhausted).get("reservedCalls").getAsInt()); assertEquals(32, usage(exhausted).get("toolCalls").getAsInt());
        assertEquals("REVIEW_STOPPED", exhausted.get("status").getAsString()); assertTrue(exhausted.getAsJsonObject("coordinatorReview").get("selection").isJsonNull());
        scripts.originalScript = (input, turn) -> original(input, List.of()); var carry = execute(60);
        assertEquals(2, carry.getAsJsonObject("stageReservations").get("investigation").getAsInt()); assertEquals(28, carry.getAsJsonObject("stageReservations").get("review").getAsInt());
    }
    @Test void oddOriginalOpportunityGoesToTheDeclaredRoundParityWithoutPrivateWorkerCaps() throws Exception {
        scripts.originalScript = (input, turn) -> read("task"); var result = execute(8);
        assertEquals(3, result.getAsJsonObject("stageReservations").get("investigation").getAsInt()); assertEquals(1, scripts.turns.get("invoice-reconciliation").get()); assertEquals(2, scripts.turns.get("invoice-webhook").get());
        assertEquals(1, result.getAsJsonObject("stageReservations").get("review").getAsInt());
    }
    @Test void rejectedContextsChargeMetadataReadsButNoUnreservedModelOpportunity() throws Exception {
        var result = execute(6, 1); assertEquals(0, scripts.registered.get()); assertEquals(0, usage(result).get("reservedCalls").getAsInt()); assertEquals(2, usage(result).get("toolCalls").getAsInt());
        assertEquals("CONTEXT_BUDGET_EXHAUSTED", result.getAsJsonObject("coordinatorReview").get("stopReason").getAsString()); assertTrue(result.get("workers").toString().contains("CONTEXT_BUDGET_EXHAUSTED"));
    }
    @Test void uncertainRegistrationKeepsTheSlotAndCanAnchorReviewWithoutRedispatch() throws Exception {
        scripts.interruptRegistration.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
        var result = adapter().run(id); assertEquals(3, scripts.registered.get()); assertEquals(2, scripts.dispatched.get()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
        assertTrue(result.get("workers").toString().contains("CALL_REGISTRATION_UNCERTAIN")); assertTrue(result.get("workers").toString().contains("UNKNOWN_REMOTE_OUTCOME"));
        assertEquals(2, result.getAsJsonObject("coordinatorReview").getAsJsonObject("selection").getAsJsonArray("selectedFollowUps").size()); assertEquals(result, adapter().run(id)); assertEquals(3, scripts.registered.get());
    }
    @Test void unknownWorkerAndCoordinatorOutcomesNeverBecomeCompletedAnswers() throws Exception {
        scripts.unknownParticipant = "invoice-webhook"; var result = execute(6); assertEquals("REVIEW_RECORDED", result.get("status").getAsString());
        assertTrue(result.get("workers").toString().contains("MODEL_UNKNOWN_REMOTE_OUTCOME")); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
        scripts.unknownParticipant = "invoice-coordinator"; String id = fixture(6); var stopped = adapter().run(id); assertEquals("REVIEW_STOPPED", stopped.get("status").getAsString());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", stopped.getAsJsonObject("coordinatorReview").get("status").getAsString()); assertTrue(stopped.getAsJsonObject("coordinatorReview").get("selection").isJsonNull());
        assertEquals(stopped, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void responseInterruptionRecoversBothStagesWithoutNewCallsOrChangingTheBarrier() throws Exception {
        scripts.interruptParticipant = "invoice-reconciliation"; scripts.interruptAfterPersist.set(true); String id = fixture(6);
        assertThrows(IOException.class, () -> adapter().run(id)); assertEquals(2, scripts.dispatched.get()); assertEquals("REVIEW_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(3, scripts.dispatched.get());
        scripts.interruptParticipant = "invoice-coordinator"; scripts.interruptAfterPersist.set(true); String second = fixture(6); assertThrows(IOException.class, () -> adapter().run(second));
        byte[] barrier = Files.readAllBytes(run(second).resolve("originals-receipt.json")); assertEquals(6, scripts.dispatched.get()); assertEquals("REVIEW_RECORDED", adapter().run(second).get("status").getAsString());
        assertArrayEquals(barrier, Files.readAllBytes(run(second).resolve("originals-receipt.json"))); assertEquals(6, scripts.dispatched.get());
    }
    @Test void localWorkerErrorDrainsItsSiblingBeforeReleasingOwnership() throws Exception {
        scripts.blockWorker = "invoice-webhook"; scripts.entered = new CountDownLatch(1); scripts.release = new CountDownLatch(1);
        scripts.interruptParticipant = "invoice-reconciliation"; scripts.interruptAfterPersist.set(true); String id = fixture(6);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> adapter().run(id));
            try { assertTrue(scripts.entered.await(30, TimeUnit.SECONDS)); assertFalse(future.isDone()); assertNull(LocalFileLease.tryAcquire(run(id).resolve("owner.lock"))); }
            finally { scripts.release.countDown(); }
            assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        }
        assertEquals(0, scripts.concurrent.get()); assertEquals("REVIEW_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(3, scripts.dispatched.get());
    }
    @Test void unsupportedReadsActionsAndCitationsStopWithoutGrantingFollowUps() throws Exception {
        scripts.originalScript = (input, turn) -> participant(input).equals("invoice-webhook") ? read("../.env.local") : original(input, List.of());
        scripts.reviewScript = (input, turn) -> decision(Map.of("action", "finish", "summary", "Unsupported", "citations", List.of("claim-99"), "followUps", List.of(), "conflicts", List.of()));
        var result = execute(6); assertTrue(result.get("workers").toString().contains("UNSUPPORTED_MODEL_DECISION")); assertEquals(2, usage(result).get("toolCalls").getAsInt());
        assertEquals("UNSUPPORTED_MODEL_DECISION", result.getAsJsonObject("coordinatorReview").get("stopReason").getAsString()); assertTrue(result.getAsJsonObject("coordinatorReview").get("selection").isJsonNull());
        scripts.reviewScript = (input, turn) -> decision(Map.of("action", "delegate", "permission", "execute"));
        assertEquals("INVALID_MODEL_DECISION", execute(6).getAsJsonObject("coordinatorReview").get("stopReason").getAsString());
    }
    @Test void readBudgetStopDoesNotResetTheSharedBudgetOrEraseRecordedWorkerPages() throws Exception {
        String id = fixture(60); var runtime = runtime();
        for (int i = 0; i < 96; i++) runtime.describe(id, "invoice-coordinator", "earlier-" + i, InvoiceRenewalFixtures.CASE_ID);
        scripts.originalScript = (input, turn) -> read("task");
        var result = adapter().run(id); assertEquals(100, usage(result).get("toolCalls").getAsInt()); assertEquals(5, usage(result).get("reservedCalls").getAsInt());
        assertEquals("REVIEW_RECORDED", result.get("status").getAsString());
        for (var worker : result.getAsJsonArray("workers")) {
            assertEquals("READ_BUDGET_EXHAUSTED", worker.getAsJsonObject().get("stopReason").getAsString()); assertEquals(1, worker.getAsJsonObject().getAsJsonArray("pages").size());
        }
    }
    @Test void externalModelReservationsCannotBeHiddenByStageAccounting() throws Exception {
        String id = fixture(60); var request = new ModelCallBroker.Request(InvoiceTeamRunner.checkDefinition(identity).model(), "{\"workers\":[]}", 2048, InvoiceTeamDecision.PROTOCOL);
        runtime().call(id, "invoice-coordinator", "outside-prefix", request);
        assertThrows(IllegalArgumentException.class, () -> adapter().run(id)); assertFalse(Files.exists(run(id).resolve("stage-trace.json"))); assertFalse(Files.exists(run(id).resolve("result.json")));
    }
    @Test void revocationAndExpiryStopBeforeDispatchAndCannotRenewTheDeadline() throws Exception {
        String revoked = fixture(6); runtime().revoke(revoked); var cancelled = adapter().run(revoked); assertEquals(0, scripts.registered.get());
        assertEquals("CANCELLED", cancelled.getAsJsonObject("coordinatorReview").get("status").getAsString());
        String expired = fixture(6); runtime().start(expired); clock.now = clock.now.plusSeconds(1201); var result = adapter().run(expired);
        assertEquals(0, scripts.registered.get()); assertEquals("TRIAL_EXPIRED", result.getAsJsonObject("coordinatorReview").get("stopReason").getAsString());
        assertEquals(result, adapter().run(expired));
    }
    @Test void completedPrefixSurvivesAppendOnlyLaterOperationsAndRevocationWithoutChangingItsTrace() throws Exception {
        String id = fixture(60); var result = adapter().run(id); runtime().readPage(id, "invoice-coordinator", "later-read", InvoiceRenewalFixtures.CASE_ID, "task", 0, 4000); runtime().revoke(id);
        assertEquals(result, adapter().retained(id)); assertEquals(result, adapter().run(id)); assertEquals(3, scripts.dispatched.get());
    }
    @Test void changedRequestsMetadataEventsAndStageBarriersFailClosedBeforeRedispatch() throws Exception {
        scripts.interruptParticipant = "invoice-coordinator"; scripts.interruptAfterPersist.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
        for (String name : List.of("metadata-invoice-webhook.json", "request-1-invoice-webhook.json", "event-1-invoice-webhook.json", "originals.json", "originals-receipt.json")) {
            Path file = run(id).resolve(name); byte[] bytes = Files.readAllBytes(file); Files.writeString(file, "{}");
            assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(3, scripts.dispatched.get()); Files.write(file, bytes);
        }
        assertEquals("REVIEW_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(3, scripts.dispatched.get());
    }
    @Test void receiptRecoveryAndUnexpectedOrChangedArtifactsNeverOverwriteHistory() throws Exception {
        String id = fixture(6); var result = adapter().run(id); Files.delete(run(id).resolve("receipt.json")); assertThrows(RuntimeException.class, () -> adapter().retained(id)); assertEquals(result, adapter().run(id));
        Path review = run(id).resolve("review.json"); byte[] original = Files.readAllBytes(review); Files.writeString(review, "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(id)); Files.write(review, original);
        Path extra = run(id).resolve("unexpected.json"); Files.writeString(extra, "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(id)); Files.delete(extra);
        Files.delete(run(id).resolve("stage-trace-receipt.json")); assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(3, scripts.dispatched.get());
    }
    @Test void exclusiveControllerOrOccupiedModelLanesLeaveUnreservedCallsAvailable() throws Exception {
        String id = fixture(6); try (var lease = LocalFileLease.acquire(run(id).resolve("owner.lock"))) {
            assertNotNull(lease); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); assertEquals(0, scripts.registered.get());
        }
        Path parent = root.resolve("invoice-comparison-trials/" + id);
        try (var first = LocalFileLease.acquire(parent.resolve("lane-1.lock")); var second = LocalFileLease.acquire(parent.resolve("lane-2.lock"))) {
            assertNotNull(first); assertNotNull(second); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); assertEquals(0, scripts.registered.get());
        }
        assertEquals("REVIEW_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(3, scripts.dispatched.get());
    }
    @Test void inputOrPolicyMutationCannotReopenAnUnfinishedPrefix() throws Exception {
        String id = fixture(6); var current = InvoiceTeamRunner.policy();
        var changed = new CoordinationRuntime.Policy(1, current.maxReads(), current.adapterIdentity(), current.promptIdentity(), current.toolPolicyIdentity(), current.contextPolicyIdentity(), current.finalSelection(), current.participants());
        assertThrows(RuntimeException.class, () -> new InvoiceTeamRunner(root, runtime(), changed).run(id)); assertEquals(0, scripts.registered.get());
        Path file = inputs.resolve("0001.txt"); Files.writeString(file, "changed"); assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(0, scripts.registered.get());
    }
    @Test void freshJvmPolicyAndCompletedInspectionRequireNoCredentialOrWrites() throws Exception {
        String id = fixture(6); var result = adapter().run(id); var before = inventory();
        for (String action : List.of("policy", "read", "run")) {
            var arguments = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), InvoiceTeamRunner.class.getName(), action, root.toString()));
            if (!action.equals("policy")) arguments.addAll(List.of(inputs.toString(), identity.sha256(), id));
            Path output = root.resolve(action + ".log"); var builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()); builder.environment().remove("OPENAI_API_KEY");
            var process = builder.start(); try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); } finally { process.destroyForcibly(); }
            if (action.equals("policy")) assertEquals(CoordinationRuntime.implementationIdentity(InvoiceTeamRunner.policy()).sha256(), record(output).getAsJsonObject("implementationIdentity").get("sha256").getAsString());
        }
        assertEquals(before, inventory()); assertEquals(result, adapter().retained(id)); assertEquals(3, scripts.dispatched.get());
    }
    private Map<String, String> inventory() throws IOException {
        var hashes = new TreeMap<String, String>(); for (String folder : List.of("inputs", "invoice-comparison-trials", "invoice-team-runs")) try (var files = Files.walk(root.resolve(folder))) {
            for (var file : files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) hashes.put(root.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file)));
        } return hashes;
    }
    private final class Scripts implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrent = new AtomicInteger(), maximum = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> originalScript = (input, turn) -> original(input, List.of());
        BiFunction<JsonObject, Integer, String> reviewScript = (input, turn) -> finishReview(input, true);
        String interruptParticipant, unknownParticipant, blockWorker; CyclicBarrier barrier; CountDownLatch entered, release;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return record(file); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(record(store.resolve(id + "/request.json")).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String participant = participant(input); int turn = turns.computeIfAbsent(participant, ignored -> new AtomicInteger()).incrementAndGet(); int active = concurrent.incrementAndGet(); maximum.accumulateAndGet(active, Math::max);
                    try {
                        if (barrier != null && !review(input) && turn == 1) try { barrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        if (participant.equals(blockWorker)) { entered.countDown(); try { if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("Blocked worker timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
                        boolean unknown = participant.equals(unknownParticipant); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                                "inputTokens", 5, "outputTokens", 2, "outputText", (review(input) ? reviewScript : originalScript).apply(input, turn))); DurableExecution.publish(file, response);
                        if (participant.equals(interruptParticipant) && interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { concurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return record(store.resolve(id + "/result.json")); }
            };
        }
    }
}
