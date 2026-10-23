package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
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

class InvoiceTeamComparisonTest {
    @TempDir Path root;
    private Path inputs; private ContentDigest identity;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceInvestigation(root, inputs, identity, clock, scripts); }
    private InvoiceTeamComparison adapter() throws IOException { return new InvoiceTeamComparison(root, runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int bytes) throws Exception {
        if (inputs == null) {
            var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("capture"), Duration.ofSeconds(30), () -> true);
            inputs = root.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
        }
        var base = InvoiceTeamComparison.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, bytes, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM));
    }
    private JsonObject execute(int calls) throws Exception { String id = fixture(calls); return adapter().run(id); }
    private Path run(String id) { return root.resolve("invoice-team-comparisons/" + id); }
    private Path runtimePath(String id) { return root.resolve("invoice-comparison-trials/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path file) throws IOException { return CaseInputs.object(Files.readAllBytes(file)); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static String participant(JsonObject input) {
        if (input.has("freshWorkers")) return "invoice-final-reconciler";
        if (input.has("delegatedQuestion")) return "fresh-" + input.getAsJsonObject("case").get("workerId").getAsString();
        return input.has("workers") ? "invoice-coordinator" : input.getAsJsonObject("case").get("workerId").getAsString();
    }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String finish(String summary, String... citations) {
        return decision(Map.of("action", "finish", "response", "ABSTAINED", "summary", summary, "summaryCitations", List.of(citations),
                "findings", citations.length == 0 ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "The captured evidence is advisory.", "citations", List.of(citations)))));
    }
    private static String review(boolean questions) {
        var proposals = new ArrayList<Object>(); if (questions) for (int i = 0; i < 2; i++) proposals.add(Map.of("workerId", InvoiceWorkerDecision.WORKERS.get(i), "question", "Which recorded ledger effects remain unexplained?", "citations", List.of("worker-" + (i + 1))));
        return decision(Map.of("action", "finish", "summary", "Original outcomes remain advisory.", "citations", List.of("worker-1", "worker-2"), "followUps", proposals, "conflicts", List.of()));
    }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonObject("usage"); }
    private static String status(JsonObject result) { return result.getAsJsonObject("trial").get("status").getAsString(); }

    @Test void fullTrialUsesFreshConcurrentContextsAndExportsOneJointAdvisoryDossier() throws Exception {
        scripts.freshBarrier = new CyclicBarrier(2); scripts.freshScript = (c, n) -> n == 1 ? read("task") : finish("fresh-advice-" + participant(c), "read-1");
        scripts.finalScript = (c, n) -> finish("Joint partial advice.", "freshclaim-1", "freshclaim-3"); String id = fixture(60); var result = adapter().run(id);
        assertEquals("COMPLETED", status(result)); assertEquals(8, scripts.dispatched.get()); assertEquals(2, scripts.maximumFresh.get());
        assertEquals(6, usage(result).get("toolCalls").getAsInt()); assertEquals(0, usage(result).get("caseExecutions").getAsInt());
        assertEquals(4, result.getAsJsonObject("stageReservations").get("followUp").getAsInt()); assertEquals(1, result.getAsJsonObject("stageReservations").get("reconciliation").getAsInt());
        var output = result.getAsJsonObject("trial").getAsJsonArray("cases"); assertEquals(1, output.size()); assertEquals("ABSTAINED", output.get(0).getAsJsonObject().get("response").getAsString());
        assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString()); assertTrue(Files.exists(runtimePath(id).resolve("result.json")));
        for (var c : scripts.contexts) if (c.has("delegatedQuestion")) {
            assertEquals(1, c.get("delegationDepth").getAsInt()); assertEquals(1, c.getAsJsonArray("questionAnchors").size()); assertFalse(c.has("originalClaims"));
            assertFalse(c.toString().contains("original-advice-")); String sibling = c.getAsJsonObject("case").get("workerId").getAsString().equals("invoice-webhook") ? "fresh-invoice-reconciliation" : "fresh-invoice-webhook";
            assertFalse(c.toString().contains("fresh-advice-" + sibling));
        }
        assertEquals(2, result.getAsJsonObject("evidence").getAsJsonArray("originalClaims").size()); assertEquals(4, result.getAsJsonObject("evidence").getAsJsonArray("freshClaims").size());
        var prefix = new InvoiceTeamRunner(root, runtime()).retained(id); assertEquals("PENDING", prefix.get("wholeTrialStatus").getAsString());
        assertEquals(result, adapter().run(id)); assertEquals(8, scripts.dispatched.get());
    }
    @Test void noQuestionsDoesNotInventFollowUpsAndStillReconcilesOnce() throws Exception {
        scripts.reviewScript = (c, n) -> review(false); var result = execute(60);
        assertEquals("COMPLETED", status(result)); assertEquals(4, scripts.dispatched.get()); assertTrue(result.getAsJsonObject("evidence").getAsJsonArray("freshWorkers").isEmpty());
        assertEquals(0, result.getAsJsonObject("stageReservations").get("followUp").getAsInt()); assertEquals(2, usage(result).get("toolCalls").getAsInt());
    }
    @Test void cumulativeStagesShareSixtyReservationsAndOneReadLedger() throws Exception {
        scripts.originalScript = (c, n) -> read("task"); scripts.freshScript = (c, n) -> read("task"); scripts.finalScript = (c, n) -> read("task"); var result = execute(60);
        assertEquals(json(Map.of("investigation", 24, "review", 1, "followUp", 29, "reconciliation", 6)), result.get("stageReservations"));
        assertEquals(60, scripts.dispatched.get()); assertEquals(63, usage(result).get("toolCalls").getAsInt()); assertEquals("FAILED", status(result));
        assertEquals("NOT_PROVIDED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
    }
    @Test void unusedOpportunitiesCarryForwardWithoutAnExtraReconciliationPool() throws Exception {
        scripts.finalScript = (c, n) -> read("task"); var result = execute(60);
        assertEquals(55, result.getAsJsonObject("stageReservations").get("reconciliation").getAsInt()); assertEquals(60, usage(result).get("reservedCalls").getAsInt());
    }
    @Test void tinyContextsFailBeforeReservationsWithoutInventingResults() throws Exception {
        String id = fixture(6, 1); var result = adapter().run(id); assertEquals("FAILED", status(result)); assertEquals(0, scripts.registered.get());
        assertEquals(0, usage(result).get("reservedCalls").getAsInt()); assertTrue(record(run(id).resolve("reconciliation.json")).toString().contains("CONTEXT_BUDGET_EXHAUSTED"));
    }
    @Test void unknownFreshAndFinalOutcomesRemainUnknownWithoutRedispatch() throws Exception {
        scripts.unknownParticipant = "fresh-invoice-webhook"; String id = fixture(6); var result = adapter().run(id); assertEquals("UNKNOWN_REMOTE_OUTCOME", status(result));
        assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt()); assertTrue(result.getAsJsonObject("evidence").getAsJsonArray("freshWorkers").toString().contains("NOT_PROVIDED"));
        assertEquals(result, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
        scripts.unknownParticipant = "invoice-final-reconciler"; String second = fixture(6); var unknown = adapter().run(second); assertEquals("UNKNOWN_REMOTE_OUTCOME", status(unknown)); assertFalse(Files.exists(run(second).resolve("dossier.json")));
    }
    @Test void inventedFreshAndFinalCitationsCannotBecomeDossiers() throws Exception {
        scripts.freshScript = (c, n) -> finish("Invented page.", "read-99"); scripts.finalScript = (c, n) -> finish("Invented claim.", "freshclaim-99"); String id = fixture(6); var result = adapter().run(id);
        assertEquals("FAILED", status(result)); assertTrue(record(run(id).resolve("follow-ups.json")).toString().contains("UNSUPPORTED_MODEL_DECISION"));
        assertEquals("UNSUPPORTED_MODEL_DECISION", record(run(id).resolve("reconciliation.json")).get("stopReason").getAsString()); assertFalse(Files.exists(run(id).resolve("dossier.json")));
    }
    @Test void completedFreshAndFinalResponsesRecoverAfterControllerInterruption() throws Exception {
        scripts.interruptParticipant = "fresh-invoice-reconciliation"; scripts.interruptAfterPersist.set(true); String id = fixture(6);
        assertThrows(IOException.class, () -> adapter().run(id)); assertEquals(5, scripts.dispatched.get()); byte[] barrier = Files.readAllBytes(root.resolve("invoice-team-runs/" + id + "/receipt.json"));
        assertEquals("COMPLETED", status(adapter().run(id))); assertEquals(6, scripts.dispatched.get()); assertArrayEquals(barrier, Files.readAllBytes(root.resolve("invoice-team-runs/" + id + "/receipt.json")));
        scripts.interruptParticipant = "invoice-final-reconciler"; scripts.interruptAfterPersist.set(true); String second = fixture(6); assertThrows(IOException.class, () -> adapter().run(second));
        assertEquals(12, scripts.dispatched.get()); assertEquals("COMPLETED", status(adapter().run(second))); assertEquals(12, scripts.dispatched.get());
    }
    @Test void unmappedFreshRegistrationConsumesItsSlotAndIsNeverRedispatched() throws Exception {
        scripts.interruptRegistrationParticipant = "fresh-invoice-webhook"; String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
        var result = adapter().run(id); assertEquals("UNKNOWN_REMOTE_OUTCOME", status(result)); assertEquals(6, scripts.registered.get()); assertEquals(5, scripts.dispatched.get()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
    }
    @Test void failedFreshWorkerDrainsItsSiblingAndKeepsTheFullOwnerLease() throws Exception {
        scripts.blockWorker = "fresh-invoice-webhook"; scripts.entered = new CountDownLatch(1); scripts.release = new CountDownLatch(1);
        scripts.interruptParticipant = "fresh-invoice-reconciliation"; scripts.interruptAfterPersist.set(true); String id = fixture(6);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> adapter().run(id));
            try { assertTrue(scripts.entered.await(30, TimeUnit.SECONDS)); assertFalse(future.isDone()); assertNull(LocalFileLease.tryAcquire(run(id).resolve("owner.lock"))); }
            finally { scripts.release.countDown(); }
            assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        }
        assertEquals("COMPLETED", status(adapter().run(id))); assertEquals(6, scripts.dispatched.get());
    }
    @Test void originalFailureSurvivesACompletedFinalAbstention() throws Exception {
        scripts.originalScript = (c, n) -> participant(c).equals("invoice-webhook") ? decision(Map.of("action", "grant")) : finish("Original partial advice."); var result = execute(6);
        assertEquals("FAILED", status(result)); assertTrue(result.getAsJsonObject("evidence").getAsJsonArray("workers").toString().contains("NOT_PROVIDED"));
        assertEquals("ABSTAINED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
    }
    @Test void revocationAndDeadlineAreNotRenewedAtFollowUpAdmission() throws Exception {
        String id = fixture(6); new InvoiceTeamRunner(root, runtime(), InvoiceTeamComparison.policy()).run(id); runtime().revoke(id);
        var result = adapter().run(id); assertEquals("CANCELLED", status(result)); assertEquals(3, scripts.dispatched.get());
        String second = fixture(6); new InvoiceTeamRunner(root, runtime(), InvoiceTeamComparison.policy()).run(second); clock.now = clock.now.plus(Duration.ofMinutes(21));
        assertEquals("FAILED", status(adapter().run(second))); assertEquals(6, scripts.dispatched.get()); assertTrue(record(run(second).resolve("reconciliation.json")).toString().contains("TRIAL_EXPIRED"));
    }
    @Test void sealedResultsRecoverOnlyTheirMissingReceiptAndReadWithoutDispatch() throws Exception {
        String id = fixture(6); var result = adapter().run(id); var before = hashes(); assertEquals(result, adapter().retained(id)); assertEquals(before, hashes());
        Files.delete(run(id).resolve("receipt.json")); assertThrows(RuntimeException.class, () -> adapter().retained(id)); assertEquals(result, adapter().run(id)); assertEquals(before, hashes());
        Path evidence = run(id).resolve("evidence.json"); var changed = record(evidence); changed.addProperty("qualityReview", "ACCEPTED"); Files.writeString(evidence, CaseInputs.JSON.toJson(changed));
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void fullPolicyMustBeRegisteredBeforeOriginalWorkAndCannotAdoptAPrefixTrial() throws Exception {
        String id = fixture(6); var policy = record(runtimePath(id).resolve("spec.json")).getAsJsonObject("policy"); assertEquals(6, policy.getAsJsonArray("participants").size());
        var prefix = new InvoiceTeamRunner(root, runtime()); var base = InvoiceTeamRunner.checkDefinition(identity); String old = prefix.register(identity.sha256(), base, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); prefix.run(old);
        assertThrows(RuntimeException.class, () -> adapter().run(old)); assertFalse(Files.exists(root.resolve("invoice-team-comparisons/" + old)));
        try (var held = LocalFileLease.acquire(run(id).resolve("owner.lock"))) { assertNotNull(held); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); assertEquals(3, scripts.dispatched.get()); }
    }
    @Test void sharedReadExhaustionIncludesBothFreshMetadataReadsAndNoFreshReadIsFree() throws Exception {
        String id = fixture(6); var runtime = runtime();
        for (int i = 1; i <= 96; i++) runtime.catalog(id, "invoice-coordinator", "prefill-" + i, InvoiceRenewalFixtures.CASE_ID, 0, 1);
        scripts.freshBarrier = new CyclicBarrier(2); // Both metadata reads precede either returned read action.
        scripts.freshScript = (c, n) -> read("task"); var result = adapter().run(id);
        assertEquals("FAILED", status(result)); assertEquals(100, usage(result).get("toolCalls").getAsInt()); assertEquals(6, usage(result).get("reservedCalls").getAsInt());
        var workers = record(run(id).resolve("follow-ups.json")).getAsJsonArray("workers");
        for (var entry : workers) { var w = entry.getAsJsonObject().getAsJsonObject("worker"); assertEquals("READ_BUDGET_EXHAUSTED", w.get("stopReason").getAsString()); assertTrue(w.getAsJsonArray("pages").isEmpty()); }
    }
    @Test void unownedReservationsCannotDisappearFromFinalTeamAccounting() throws Exception {
        String id = fixture(60); new InvoiceTeamRunner(root, runtime(), InvoiceTeamComparison.policy()).run(id);
        var context = scripts.contexts.stream().filter(c -> participant(c).equals("invoice-coordinator")).findFirst().orElseThrow();
        runtime().call(id, "invoice-coordinator", "outside-adapter", new ModelCallBroker.Request("gpt-5.6-terra", CaseInputs.JSON.toJson(context), 2048, InvoiceTeamDecision.PROTOCOL));
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertFalse(Files.exists(run(id).resolve("result.json")));
    }
    @Test void oneSelectedRoleDoesNotScheduleItsSiblingOrExpandProtocolPermissions() throws Exception {
        scripts.reviewScript = (c, n) -> {
            var d = JsonParser.parseString(review(true)).getAsJsonObject(); d.getAsJsonObject("decision").getAsJsonArray("followUps").remove(1); return CaseInputs.JSON.toJson(d);
        };
        String id = fixture(6); var runtime = runtime();
        assertThrows(RuntimeException.class, () -> runtime.call(id, "fresh-invoice-webhook", "wrong-protocol", new ModelCallBroker.Request("gpt-5.6-terra", "{}", 2048, InvoiceReconciliationDecision.PROTOCOL)));
        var readOnly = CoordinationRuntime.forInvoice(root, inputs, identity, clock);
        assertThrows(RuntimeException.class, () -> readOnly.register(identity.sha256(), InvoiceTeamComparison.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), InvoiceTeamComparison.policy())); assertEquals(0, scripts.dispatched.get());
        var result = adapter().run(id); assertEquals("COMPLETED", status(result)); assertEquals(5, scripts.dispatched.get()); assertFalse(scripts.turns.containsKey("fresh-invoice-webhook"));
        assertEquals(1, result.getAsJsonObject("evidence").getAsJsonArray("freshWorkers").size());
    }
    private Map<String, String> hashes() throws IOException {
        var hashes = new TreeMap<String, String>(); for (String directory : List.of("inputs", "invoice-comparison-trials", "invoice-team-runs", "invoice-team-comparisons")) try (var files = Files.walk(root.resolve(directory))) {
            for (var p : files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) hashes.put(root.relativize(p).toString(), CaseInputs.hash(Files.readAllBytes(p)));
        } return hashes;
    }
    private final class Scripts implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrentFresh = new AtomicInteger(), maximumFresh = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(); final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> originalScript = (c, n) -> finish("original-advice-" + participant(c));
        BiFunction<JsonObject, Integer, String> reviewScript = (c, n) -> review(true), freshScript = (c, n) -> finish("Fresh partial advice."), finalScript = (c, n) -> finish("Joint partial advice.", "worker-1");
        String interruptParticipant, unknownParticipant, interruptRegistrationParticipant, blockWorker; CyclicBarrier freshBarrier; CountDownLatch entered, release;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    String who = participant(JsonParser.parseString(request.input()).getAsJsonObject());
                    if (who.equals(interruptRegistrationParticipant)) { interruptRegistrationParticipant = null; throw new IOException("Interrupted registration"); } return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return record(file); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(record(store.resolve(id + "/request.json")).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String who = participant(input); int turn = turns.computeIfAbsent(who, ignored -> new AtomicInteger()).incrementAndGet(); boolean fresh = who.startsWith("fresh-");
                    if (fresh) maximumFresh.accumulateAndGet(concurrentFresh.incrementAndGet(), Math::max);
                    try {
                        if (fresh && freshBarrier != null && turn == 1) try { freshBarrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        if (who.equals(blockWorker)) { entered.countDown(); try { if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("Blocked worker timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
                        var script = who.equals("invoice-final-reconciler") ? finalScript : who.equals("invoice-coordinator") ? reviewScript : fresh ? freshScript : originalScript;
                        boolean unknown = who.equals(unknownParticipant); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED", "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, turn))); DurableExecution.publish(file, response);
                        if (who.equals(interruptParticipant) && interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { if (fresh) concurrentFresh.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return record(store.resolve(id + "/result.json")); }
            };
        }
    }
}
