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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTeamComparisonV2Test {
    @TempDir Path root;
    @TempDir static Path captureRoot;
    private static Path inputs; private static ContentDigest identity;
    private static final String FINAL = "invoice-final-reviewer";
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), captureRoot.resolve("capture"), Duration.ofSeconds(30), () -> true);
        inputs = captureRoot.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
    }
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceReconciliationV2(root, inputs, identity, clock, scripts); }
    private InvoiceTeamComparisonV2 adapter() throws IOException { return new InvoiceTeamComparisonV2(root, InvoiceComparisonInputs.open(inputs, identity), runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int bytes) throws Exception {
        var base = InvoiceTeamComparisonV2.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, bytes, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM));
    }
    private JsonObject execute(int calls) throws Exception { return adapter().run(fixture(calls)); }
    private Path run(String id) { return root.resolve("invoice-team-comparisons-v2/" + id); }
    private Path parent(String id) { return root.resolve("invoice-team-reconciliation-v2-trials/" + id); }
    private Path prefix(String id) { return root.resolve("invoice-team-reconciliation-v2-prefix-runs/" + id); }
    private Path freshPath(String id) { return root.resolve("invoice-team-reconciliation-v2-fresh-runs/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path file) throws IOException { return CaseInputs.object(Files.readAllBytes(file)); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static boolean fresh(JsonObject input) { return input.has("followUp"); }
    private static boolean review(JsonObject input) { return input.has("workers"); }
    private static String participant(JsonObject input) { return review(input) ? "invoice-coordinator" : input.getAsJsonObject("subject").get("participantId").getAsString(); }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String original(JsonObject input, List<String> citations) {
        return decision(Map.of("action", "finish", "response", "ABSTAINED", "summary", "original-claim-" + participant(input), "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "The captured evidence does not establish release approval.", "citations", citations))));
    }
    private static String finishReview(JsonObject input, boolean questions) {
        var proposals = new ArrayList<Object>();
        if (questions) for (var value : input.getAsJsonArray("workers")) { var w = value.getAsJsonObject();
            proposals.add(Map.of("workerId", w.get("workerId").getAsString(), "question", "Which recorded ledger effects remain unexplained?", "citations", List.of(w.get("referenceId").getAsString()))); }
        return decision(Map.of("action", "finish", "summary", "Original outcomes remain advisory.", "citations", List.of("worker-1", "worker-2"), "followUps", proposals, "conflicts", List.of()));
    }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonObject("usage"); }
    private static JsonObject finalOutcome(JsonObject result) { return result.getAsJsonObject("reconciliation"); }
    private static JsonObject output(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject(); }
    @Test void allSixStagesShareOneTrialAndExportOneUnreviewedFinalDossier() throws Exception {
        scripts.originalScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        scripts.reviewScript = (input, turn) -> turn == 1 ? read("task") : finishReview(input, true);
        scripts.freshScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        scripts.finalScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        String id = fixture(60); var result = adapter().run(id);
        assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(12, scripts.dispatched.get());
        assertEquals(12, usage(result).get("reservedCalls").getAsInt()); assertEquals(12, usage(result).get("toolCalls").getAsInt());
        assertEquals(2, result.getAsJsonObject("stageReservations").get("reconciliation").getAsInt()); assertEquals(0, usage(result).get("caseExecutions").getAsInt());
        assertEquals("ABSTAINED", output(result).get("response").getAsString()); assertTrue(output(result).get("review").isJsonNull()); assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString());
        assertEquals(6, InvoiceTeamComparisonV2.policy().participants().size()); assertEquals(3, InvoiceTeamRunnerV2.policy().participants().size()); assertEquals(5, InvoiceFollowUpRunnerV2.policy().participants().size());
        assertTrue(Files.exists(parent(id).resolve("result.json"))); assertFalse(Files.exists(root.resolve("invoice-follow-up-v2-runs/" + id)));
        var context = scripts.contexts.stream().filter(c -> c.has("history")).reduce((a, b) -> b).orElseThrow();
        assertEquals("RECONCILIATION", context.getAsJsonObject("teamStage").get("stage").getAsString()); assertEquals(4, context.getAsJsonObject("history").getAsJsonArray("originalClaims").size());
        assertEquals(FINAL, finalOutcome(result).getAsJsonObject("bootstrap").get("participant").getAsString());
        var before = inventory(); clock.now = clock.now.plusSeconds(2000); assertEquals(result, adapter().run(id)); assertEquals(result, adapter().retained(id)); assertEquals(before, inventory()); assertEquals(12, scripts.dispatched.get());
    }
    @Test void zeroQuestionsStillRunFinalReviewWithoutInventingFreshWork() throws Exception {
        scripts.reviewScript = (input, turn) -> finishReview(input, false); var result = execute(60);
        assertEquals(4, scripts.dispatched.get()); assertTrue(result.getAsJsonObject("evidence").getAsJsonArray("freshWorkers").isEmpty());
        assertTrue(result.getAsJsonObject("evidence").getAsJsonArray("selectedFollowUps").isEmpty()); assertEquals("ABSTAINED", output(result).get("response").getAsString());
    }
    @Test void unknownOriginalOrReviewOutcomesRemainUnknownEvenAfterFinalAbstention() throws Exception {
        scripts.unknownParticipant = "invoice-coordinator"; var result = execute(60);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(4, scripts.dispatched.get());
        assertTrue(result.getAsJsonObject("evidence").get("selectedFollowUps").isJsonNull()); assertEquals("ABSTAINED", output(result).get("response").getAsString());
        assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
    }
    @Test void finalStageUsesForwardCarryButCannotExceedWholeTrialPool() throws Exception {
        scripts.finalScript = (input, turn) -> read("task"); var result = execute(60);
        assertEquals(60, usage(result).get("reservedCalls").getAsInt()); assertEquals(55, result.getAsJsonObject("stageReservations").get("reconciliation").getAsInt());
        assertEquals("STAGE_CALL_BUDGET_EXHAUSTED", finalOutcome(result).get("stopReason").getAsString()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        assertTrue(finalOutcome(result).get("accepted").isJsonNull()); assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString());
    }
    @Test void allEarlierStagesCanLeaveOnlyOneFinalCallWithoutAFreeFinish() throws Exception {
        scripts.freshScript = (input, turn) -> read("task"); scripts.finalScript = (input, turn) -> read("task"); var result = execute(9);
        assertEquals(9, scripts.dispatched.get()); assertEquals(1, scripts.turns.get(FINAL).get()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
    }
    @Test void missingFinalBootstrapAtSharedReadCeilingCostsNoModelCall() throws Exception {
        String id = fixture(60); for (int i = 0; i < 95; i++) runtime().describe(id, "invoice-coordinator", "earlier-" + i, InvoiceRenewalFixtures.CASE_ID);
        var result = adapter().run(id); assertEquals(100, usage(result).get("toolCalls").getAsInt()); assertEquals(5, scripts.dispatched.get());
        assertEquals("READ_BUDGET_EXHAUSTED", finalOutcome(result).get("stopReason").getAsString()); assertTrue(finalOutcome(result).get("bootstrap").isJsonNull());
    }
    @Test void unknownAndInterruptedFinalWorkAreNotRedispatched() throws Exception {
        scripts.unknownParticipant = FINAL; String id = fixture(60); var result = adapter().run(id);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        assertEquals(result, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void interruptedFinalRegistrationRetainsItsChargedUncertainSlot() throws Exception {
        scripts.interruptRegistrationParticipant = FINAL; scripts.interruptRegistration.set(true); String id = fixture(60);
        assertThrows(IOException.class, () -> adapter().run(id)); assertEquals(5, scripts.dispatched.get()); var result = adapter().run(id);
        assertEquals("CALL_REGISTRATION_UNCERTAIN", finalOutcome(result).get("stopReason").getAsString()); assertEquals(6, usage(result).get("reservedCalls").getAsInt());
        assertEquals(5, scripts.dispatched.get()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
    }
    @Test void completedFinalResponseSurvivesInterruptionWithoutAnotherDispatch() throws Exception {
        scripts.interruptParticipant = FINAL; scripts.interruptAfterPersist.set(true); String id = fixture(60); assertThrows(IOException.class, () -> adapter().run(id));
        assertEquals(6, scripts.dispatched.get()); var result = adapter().run(id); assertEquals("ABSTAINED", output(result).get("response").getAsString()); assertEquals(6, scripts.dispatched.get());
    }
    @Test void inventedOrInheritedFinalCitationsRetainRejectedCandidatesWithoutDossiers() throws Exception {
        scripts.finalScript = (input, turn) -> original(input, List.of("read-1")); String id = fixture(60); var result = adapter().run(id);
        assertEquals("UNSUPPORTED_DOSSIER", finalOutcome(result).get("stopReason").getAsString()); assertFalse(Files.exists(run(id).resolve("dossier.json")));
        var event = record(run(id).resolve("event-1-" + FINAL + ".json")); assertEquals("REJECTED_FINISH", event.get("actionStatus").getAsString()); assertTrue(event.has("decision")); assertTrue(event.has("readiness"));
    }
    @Test void malformedFinalActionsNeverExpandTheAllowedToolSet() throws Exception {
        scripts.finalScript = (input, turn) -> decision(Map.of("action", "delegate", "worker", "new-agent")); var result = execute(60);
        assertEquals("INVALID_MODEL_DECISION", finalOutcome(result).get("stopReason").getAsString()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
    }
    @Test void finalContextOverflowStopsBeforeReservingAModelCall() throws Exception {
        String id = fixture(60, 256); var result = adapter().run(id);
        assertEquals(0, scripts.dispatched.get()); assertEquals(0, usage(result).get("reservedCalls").getAsInt());
        assertEquals("CONTEXT_BUDGET_EXHAUSTED", finalOutcome(result).get("stopReason").getAsString());
    }
    @Test void missingPublishedSourceOrFinalSealsRejectBeforeParticipantRecovery() throws Exception {
        String id = fixture(60); adapter().run(id); int calls = scripts.dispatched.get(); var before = inventory();
        for (Path file : List.of(run(id).resolve("source.json"), run(id).resolve("evidence.json"), run(id).resolve("reconciliation-receipt.json"),
                run(id).resolve("event-1-" + FINAL + "-receipt.json"), prefix(id).resolve("originals-receipt.json"), freshPath(id).resolve("follow-ups-receipt.json"))) {
            byte[] bytes = Files.readAllBytes(file); Files.delete(file);
            assertThrows(Exception.class, () -> adapter().retained(id), file.toString()); assertThrows(Exception.class, () -> adapter().run(id), file.toString());
            assertFalse(Files.exists(file)); assertEquals(calls, scripts.dispatched.get()); Files.write(file, bytes);
        }
        assertEquals(before, inventory());
    }
    @Test void changedSourceHistoryDossierAndFinalEventsAreRejected() throws Exception {
        String id = fixture(60); adapter().run(id);
        for (Path file : List.of(run(id).resolve("evidence.json"), run(id).resolve("dossier.json"), run(id).resolve("event-1-" + FINAL + ".json"), run(id).resolve("reconciliation.json"))) {
            byte[] bytes = Files.readAllBytes(file); var changed = record(file); changed.addProperty("forged", "change"); Files.writeString(file, changed.toString());
            assertThrows(Exception.class, () -> adapter().retained(id)); Files.write(file, bytes);
        }
        assertEquals(6, scripts.dispatched.get());
    }
    @Test void terminalAndResultPublicationRecoverWithoutModelOrReadOperations() throws Exception {
        String id = fixture(60); var expected = adapter().run(id); Files.delete(run(id).resolve("receipt.json")); assertEquals(expected, adapter().run(id));
        Files.delete(run(id).resolve("result.json")); Files.delete(run(id).resolve("receipt.json")); clock.now = clock.now.plusSeconds(2000);
        assertEquals(expected, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void ownerLeaseReturnsActiveWithoutStartingWork() throws Exception {
        String id = fixture(60); try (var lease = LocalFileLease.tryAcquire(run(id).resolve("owner.lock"))) { assertNotNull(lease); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); }
        assertEquals(0, scripts.dispatched.get());
    }
    @Test void expiryOrRevocationBeforeFinalStagePreservesEarlierWorkAndStopsDispatch() throws Exception {
        String id = fixture(60); InvoiceFollowUpRunnerV2.forReconciliation(root, InvoiceComparisonInputs.open(inputs, identity), runtime()).run(id);
        assertFalse(scripts.turns.containsKey(FINAL)); assertEquals(5, scripts.dispatched.get()); clock.now = clock.now.plusMillis(1200000);
        var result = adapter().run(id);
        assertEquals("TRIAL_EXPIRED", finalOutcome(result).get("stopReason").getAsString()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        assertEquals(5, scripts.dispatched.get()); assertEquals(result, adapter().retained(id));
    }
    @Test void fixedSixMembershipAndInputReceiptCannotBeReplaced() throws Exception {
        var p = InvoiceFollowUpRunnerV2.policy(); var base = InvoiceTeamComparisonV2.checkDefinition(identity); var implementations = new EnumMap<>(base.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(p));
        var wrong = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), implementations, base.workloads(), 1, base.limits());
        assertThrows(Exception.class, () -> adapter().register(identity.sha256(), wrong, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM))); assertEquals(0, scripts.dispatched.get());
    }
    @Test void missingSourceSealDuringInterruptedFinalRecoveryNeverDispatchesWork() throws Exception {
        scripts.interruptParticipant = FINAL; scripts.interruptAfterPersist.set(true); String id = fixture(60); assertThrows(IOException.class, () -> adapter().run(id));
        int calls = scripts.dispatched.get(); Path file = prefix(id).resolve("originals-receipt.json"); byte[] bytes = Files.readAllBytes(file); Files.delete(file);
        assertThrows(Exception.class, () -> adapter().run(id)); assertFalse(Files.exists(file)); assertEquals(calls, scripts.dispatched.get());
        Files.write(file, bytes); assertEquals("ABSTAINED", output(adapter().run(id)).get("response").getAsString()); assertEquals(calls, scripts.dispatched.get());
    }
    @Test void possibleConflictsRemainUnresolvedAfterACompletedFinalReview() throws Exception {
        scripts.reviewScript = (input, turn) -> {
            var response = JsonParser.parseString(finishReview(input, false)).getAsJsonObject();
            response.getAsJsonObject("decision").add("conflicts", CaseInputs.JSON.toJsonTree(List.of(Map.of("claimIds", List.of("claim-1", "claim-2"), "statement", "Authored possible conflict with scope unestablished")))); return response.toString();
        };
        var result = execute(60); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals("UNRESOLVED", result.getAsJsonObject("evidence").getAsJsonArray("possibleConflicts").get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString());
    }
    @Test void explicitRevocationBeforeFinalDispatchKeepsItsCancelledDisposition() throws Exception {
        String id = fixture(60); InvoiceFollowUpRunnerV2.forReconciliation(root, InvoiceComparisonInputs.open(inputs, identity), runtime()).run(id);
        assertFalse(scripts.turns.containsKey(FINAL)); assertEquals(5, scripts.dispatched.get()); runtime().revoke(id);
        var result = adapter().run(id); assertEquals("TRIAL_REVOKED", finalOutcome(result).get("stopReason").getAsString());
        assertEquals("CANCELLED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        assertEquals(5, scripts.dispatched.get());
    }
    @Test void completedInspectionWorksInAnotherCredentialFreeProcess() throws Exception {
        String id = fixture(60); adapter().run(id); var before = inventory();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), InvoiceTeamComparisonV2.class.getName(), "read", root.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true);
        command.environment().remove("OPENAI_API_KEY"); var process = command.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), text); assertTrue(text.contains("quality review: NOT_PERFORMED")); assertEquals(before, inventory()); assertEquals(6, scripts.dispatched.get());
    }
    @Test void actualFreshAnsweredClaimsCanSupportFinalAdviceWithoutErasingOriginalUnknowns() throws Exception {
        Path ready = root.resolve("authored-ready-inputs");
        try (var paths = Files.walk(inputs)) { for (var path : paths.toList()) { Path target = ready.resolve(inputs.relativize(path));
            if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target); } }
        // Authored structural access fixture, not newly observed consumer execution or model quality.
        var manifest = record(ready.resolve("manifest.json")); manifest.addProperty("sourceExecutionCoverageSatisfied", true);
        byte[] bytes = CaseInputs.JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8); Files.write(ready.resolve("manifest.json"), bytes);
        var readyIdentity = new ContentDigest(CaseInputs.hash(bytes)); Files.writeString(ready.resolve("receipt.json"), json(Map.of("manifestSha256", readyIdentity.sha256())).toString());
        var bundle = InvoiceComparisonInputs.open(ready, readyIdentity); var actions = new TreeMap<String, List<String>>();
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            String role = InvoiceWorkerDecision.role(worker);
            String business = bundle.catalogPrefix("business/", 0, 40).getAsJsonArray("artifacts").asList().stream().map(v -> v.getAsJsonObject().get("alias").getAsString()).filter(a -> a.endsWith("/" + role)).findFirst().orElseThrow();
            String order = bundle.catalogPrefix("order/", 0, 1).getAsJsonArray("artifacts").get(0).getAsJsonObject().get("alias").getAsString(); var list = new ArrayList<String>();
            for (String alias : List.of("task", "build-configuration", InvoiceWorkerDecision.baseline(worker), business, order)) {
                int offset = 0; JsonObject page;
                do { page = bundle.read(alias, offset, 4000); list.add(decision(Map.of("action", "read_text", "alias", alias, "offset", offset, "maxCharacters", 4000))); offset = page.get("nextOffset").getAsInt(); }
                while (offset < page.get("totalCharacters").getAsInt());
            }
            actions.put(InvoiceFollowUpDecisionV2.participant(worker), list);
        }
        scripts.unknownParticipant = "invoice-webhook";
        scripts.freshScript = (input, turn) -> { var list = actions.get(participant(input)); if (turn <= list.size()) return list.get(turn - 1);
            var value = JsonParser.parseString(original(input, List.of("read-1"))).getAsJsonObject(); value.getAsJsonObject("decision").addProperty("response", "ANSWERED"); return value.toString(); };
        scripts.finalScript = (input, turn) -> { var value = JsonParser.parseString(original(input, List.of("freshclaim-1", "freshclaim-3"))).getAsJsonObject(); value.getAsJsonObject("decision").addProperty("response", "ANSWERED"); return value.toString(); };
        var runtime = CoordinationRuntime.forInvoiceReconciliationV2(root, ready, readyIdentity, clock, scripts); var adapter = new InvoiceTeamComparisonV2(root, bundle, runtime);
        var plan = InvoiceTeamComparisonV2.checkDefinition(readyIdentity); String id = adapter.register(readyIdentity.sha256(), plan, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); var result = adapter.run(id);
        assertEquals("ANSWERED", output(result).get("response").getAsString()); assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt()); assertTrue(result.getAsJsonObject("evidence").getAsJsonArray("workers").toString().contains("UNKNOWN_REMOTE_OUTCOME"));
        assertFalse(finalOutcome(result).getAsJsonObject("accepted").getAsJsonObject("readiness").get("structuralAccessReady").getAsBoolean());
        assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString()); assertEquals(result, adapter.retained(id));
    }
    private Map<String, String> inventory() throws IOException {
        var values = new TreeMap<String, String>(); for (String folder : List.of("invoice-team-reconciliation-v2-trials", "invoice-team-reconciliation-v2-prefix-runs", "invoice-team-reconciliation-v2-fresh-runs", "invoice-team-comparisons-v2")) try (var paths = Files.walk(root.resolve(folder))) {
            for (var file : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) values.put(root.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file)));
        } return values;
    }
    private final class Scripts implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrent = new AtomicInteger(), maximum = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> originalScript = (input, turn) -> original(input, List.of());
        BiFunction<JsonObject, Integer, String> reviewScript = (input, turn) -> finishReview(input, true);
        BiFunction<JsonObject, Integer, String> freshScript = (input, turn) -> original(input, List.of());
        BiFunction<JsonObject, Integer, String> finalScript = (input, turn) -> original(input, List.of());
        String interruptRegistrationParticipant;
        String interruptParticipant, unknownParticipant, blockWorker; CyclicBarrier barrier; CountDownLatch entered, release;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (participant(JsonParser.parseString(request.input()).getAsJsonObject()).equals(interruptRegistrationParticipant) && interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return record(file); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(record(store.resolve(id + "/request.json")).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String participant = participant(input); int turn = turns.computeIfAbsent(participant, ignored -> new AtomicInteger()).incrementAndGet(); int active = concurrent.incrementAndGet(); maximum.accumulateAndGet(active, Math::max);
                    try {
                        if (barrier != null && fresh(input) && turn == 1) try { barrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        if (participant.equals(blockWorker)) { entered.countDown(); try { if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("Blocked worker timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
                        boolean unknown = participant.equals(unknownParticipant); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                                "inputTokens", 5, "outputTokens", 2, "outputText", (input.has("history") ? finalScript : review(input) ? reviewScript : fresh(input) ? freshScript : originalScript).apply(input, turn))); DurableExecution.publish(file, response);
                        if (participant.equals(interruptParticipant) && interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { concurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return record(store.resolve(id + "/result.json")); }
            };
        }
    }
}
