package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
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

class CoordinationTeamRunnerTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final ScriptedBrokers brokers = new ScriptedBrokers();
    private CoordinationRuntime runtime() throws IOException { return new CoordinationRuntime(root, clock, brokers); }
    private CoordinationTeamRunner adapter() throws IOException { return new CoordinationTeamRunner(root, runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int bytes) throws Exception {
        return register(CoordinationInputsTest.fixture(root), calls, bytes);
    }
    private String register(String bundle, int calls, int bytes) throws Exception {
        var base = CoordinationTeamRunner.checkDefinition(root, bundle);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, bytes, 2048));
        return adapter().register(bundle, definition, new Slot("two-case", 1, Arm.COORDINATED_TEAM));
    }
    private Path run(String id) { return root.resolve("coordination-team-runs/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static boolean review(JsonObject input) { return input.has("workers"); }
    private static String participant(JsonObject input) { return review(input) ? "coordinator" : input.getAsJsonArray("cases").get(0).getAsJsonObject().get("caseId").getAsString(); }
    private static String read(String caseId, String alias, int offset) { return decision(Map.of("action", "read_evidence", "caseId", caseId, "inputId", alias, "offset", offset, "maxCharacters", 4000)); }
    private static String original(JsonObject input, boolean answer, List<String> citations) {
        return decision(Map.of("action", "finish", "cases", List.of(Map.of("caseId", participant(input), "response", answer ? "ANSWERED" : "ABSTAINED",
                "summary", "Captured case evidence is scoped.", "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "No general certification follows.", "citations", citations))))));
    }
    private static String investigate(JsonObject input, int turn) {
        for (String alias : List.of("baseline-source", "execution")) {
            int next = 0, total = -1;
            for (var value : input.getAsJsonArray("readMetadata")) {
                var page = value.getAsJsonObject(); if (alias.equals(page.get("inputId").getAsString())) { next = page.get("nextOffset").getAsInt(); total = page.get("totalCharacters").getAsInt(); }
            }
            if (total < 0 || next < total) return read(participant(input), alias, next);
        }
        var citations = new ArrayList<String>(); input.getAsJsonArray("readMetadata").forEach(p -> citations.add(p.getAsJsonObject().get("pageId").getAsString()));
        return original(input, true, citations);
    }
    private static String finishReview(JsonObject input, boolean questions) {
        var proposals = new ArrayList<Object>();
        if (questions) for (var worker : input.getAsJsonArray("workers")) {
            var w = worker.getAsJsonObject(); proposals.add(Map.of("caseId", w.get("caseId").getAsString(), "question", "Which recorded limits remain unresolved?", "citations", List.of(w.get("referenceId").getAsString())));
        }
        return decision(Map.of("action", "finish", "summary", "The original outcomes have been retained; further checks are advisory.", "citations", List.of("worker-1", "worker-2"), "followUps", proposals, "conflicts", List.of()));
    }
    @Test void concurrentIsolatedOriginalsReachASealedReviewWithoutExportingATeamTrial() throws Exception {
        brokers.originalScript = CoordinationTeamRunnerTest::investigate; brokers.barrier = new CyclicBarrier(2);
        String id = fixture(60); var result = adapter().run(id);
        assertEquals("REVIEW_RECORDED", result.get("status").getAsString()); assertEquals("PENDING", result.get("wholeTrialStatus").getAsString()); assertFalse(result.has("trial"));
        assertEquals(2, brokers.maxConcurrent.get()); assertEquals(9, brokers.dispatched.get());
        assertEquals(8, result.getAsJsonObject("stageReservations").get("investigation").getAsInt()); assertEquals(1, result.getAsJsonObject("stageReservations").get("review").getAsInt());
        assertEquals(6, result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("toolCalls").getAsInt());
        assertEquals(0, result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("caseExecutions").getAsInt());
        assertFalse(Files.exists(root.resolve("coordination-trials/" + id + "/result.json")));
        var selection = result.getAsJsonObject("coordinatorReview").getAsJsonObject("selection"); assertEquals(2, selection.getAsJsonArray("selectedFollowUps").size());
        assertEquals(4, selection.getAsJsonArray("originalClaims").size()); assertEquals("NOT_PERFORMED", selection.get("qualityReview").getAsString());
        for (var input : brokers.contexts) if (!review(input)) {
            assertEquals(1, input.getAsJsonArray("cases").size()); assertFalse(input.has("workers")); assertFalse(input.has("criteria"));
            input.getAsJsonArray("readMetadata").forEach(p -> assertEquals(participant(input), p.getAsJsonObject().get("caseId").getAsString()));
        } else { assertTrue(Files.exists(run(id).resolve("originals-receipt.json"))); assertEquals(22, input.get("decisionsRemaining").getAsInt()); }
        assertEquals(result, adapter().retained(id)); assertEquals(result, adapter().run(id)); assertEquals(9, brokers.dispatched.get());
    }
    @Test void cumulativeLimitsCountReservationsAndCarryUnusedWorkerOpportunitiesForward() throws Exception {
        brokers.originalScript = (input, turn) -> participant(input).equals(SubscriptionPeriodCase.CASE_ID) ? original(input, false, List.of()) : read(participant(input), "description", 0);
        brokers.reviewScript = (input, turn) -> read(CaseInputs.CASE_ID, "description", 0);
        var result = adapter().run(fixture(60));
        assertEquals(24, result.getAsJsonObject("stageReservations").get("investigation").getAsInt()); assertEquals(6, result.getAsJsonObject("stageReservations").get("review").getAsInt());
        assertEquals(30, brokers.dispatched.get()); assertEquals(23, brokers.turns.get(CaseInputs.CASE_ID).get()); assertEquals(1, brokers.turns.get(SubscriptionPeriodCase.CASE_ID).get());
        assertEquals("REVIEW_STOPPED", result.get("status").getAsString()); assertEquals("STAGE_CALL_BUDGET_EXHAUSTED", result.getAsJsonObject("coordinatorReview").get("stopReason").getAsString());
        assertTrue(result.getAsJsonObject("coordinatorReview").get("selection").isJsonNull());
        brokers.originalScript = (input, turn) -> original(input, false, List.of());
        var carry = adapter().run(fixture(60)); assertEquals(28, carry.getAsJsonObject("stageReservations").get("review").getAsInt());
    }
    @Test void stoppedOutcomesCanAnchorReviewButUnknownRegistrationsNeverReplay() throws Exception {
        brokers.interruptRegistration.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
        var result = adapter().run(id); assertEquals(3, brokers.registered.get()); assertEquals(2, brokers.dispatched.get());
        assertEquals(1, result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        assertTrue(result.get("workers").toString().contains("UNKNOWN_REMOTE_OUTCOME")); assertTrue(result.get("workers").toString().contains("CALL_REGISTRATION_UNCERTAIN"));
        assertEquals(2, result.getAsJsonObject("coordinatorReview").getAsJsonObject("selection").getAsJsonArray("selectedFollowUps").size());
        assertEquals(result, adapter().run(id)); assertEquals(3, brokers.registered.get());
    }
    @Test void unknownRemoteOutcomesRemainVisibleWhenReviewSuccessfullyRecordsAdvice() throws Exception {
        brokers.unknownParticipant = CaseInputs.CASE_ID; var result = adapter().run(fixture(6));
        assertEquals("REVIEW_RECORDED", result.get("status").getAsString()); assertTrue(result.get("workers").toString().contains("MODEL_UNKNOWN_REMOTE_OUTCOME"));
        assertEquals(1, result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt()); assertEquals(3, brokers.dispatched.get());
    }
    @Test void anUncertainCoordinatorHasNoSelectedPlanAndIsNotRedispatched() throws Exception {
        brokers.unknownParticipant = "coordinator"; String id = fixture(6); var result = adapter().run(id);
        assertEquals("REVIEW_STOPPED", result.get("status").getAsString());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("coordinatorReview").get("status").getAsString());
        assertTrue(result.getAsJsonObject("coordinatorReview").get("selection").isJsonNull());
        assertEquals(result, adapter().run(id)); assertEquals(3, brokers.dispatched.get());
    }
    @Test void rejectedOriginalContextsLeaveEveryUnreservedOpportunityAvailable() throws Exception {
        var digest = new io.github.nktogo.apicompatibility.compatibility.ContentDigest("a".repeat(64));
        var subscription = new CaseTask(SubscriptionPeriodCase.CASE_ID, digest, ExpectedResponse.ANSWER, List.of("observed-regressions"));
        int bytes = CoordinationSingleAgent.context(new Workload("isolated-case", List.of(subscription)), List.of(), 2, 64000).getBytes(StandardCharsets.UTF_8).length;
        var result = adapter().run(fixture(6, bytes));
        assertTrue(result.get("workers").toString().contains("CONTEXT_BUDGET_EXHAUSTED"));
        assertEquals(1, result.getAsJsonObject("stageReservations").get("investigation").getAsInt());
        assertEquals(1, result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertEquals("CONTEXT_BUDGET_EXHAUSTED", result.getAsJsonObject("coordinatorReview").get("stopReason").getAsString());
    }
    @Test void interruptionAfterOriginalOrCoordinatorResponseReusesTheSameCallAndBarrier() throws Exception {
        brokers.interruptParticipant = CaseInputs.CASE_ID; brokers.interruptAfterPersist.set(true); String id = fixture(6);
        assertThrows(IOException.class, () -> adapter().run(id)); assertEquals(2, brokers.dispatched.get());
        assertEquals("REVIEW_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(3, brokers.dispatched.get());
        brokers.interruptParticipant = "coordinator"; brokers.interruptAfterPersist.set(true); String second = fixture(6);
        assertThrows(IOException.class, () -> adapter().run(second)); assertTrue(Files.exists(run(second).resolve("originals-receipt.json"))); assertEquals(6, brokers.dispatched.get());
        assertEquals("REVIEW_RECORDED", adapter().run(second).get("status").getAsString()); assertEquals(6, brokers.dispatched.get()); assertEquals(6, brokers.registered.get());
    }
    @Test void invalidReviewReferencesAndCommandsNeverBecomeFollowUpPermissions() throws Exception {
        brokers.reviewScript = (input, turn) -> decision(Map.of("action", "finish", "summary", "Unsupported", "citations", List.of("claim-99"), "followUps", List.of(), "conflicts", List.of()));
        var result = adapter().run(fixture(6)); assertEquals("UNSUPPORTED_MODEL_DECISION", result.getAsJsonObject("coordinatorReview").get("stopReason").getAsString());
        assertTrue(result.getAsJsonObject("coordinatorReview").get("selection").isJsonNull()); assertEquals(3, brokers.dispatched.get());
        brokers.reviewScript = (input, turn) -> decision(Map.of("action", "execute", "command", "something"));
        assertEquals("INVALID_MODEL_DECISION", adapter().run(fixture(6)).getAsJsonObject("coordinatorReview").get("stopReason").getAsString());
    }
    @Test void coordinatorPagesHaveTheirOwnReferencesAndVerifiedCapturedRuntimeOrigin() throws Exception {
        brokers.reviewScript = (input, turn) -> turn == 1 ? read(CaseInputs.CASE_ID, "description", 0) : decision(Map.of("action", "finish", "summary", "Scoped review", "citations", List.of("read-1", "worker-1"),
                "followUps", List.of(Map.of("caseId", CaseInputs.CASE_ID, "question", "What remains unknown?", "citations", List.of("read-1", "worker-1"))), "conflicts", List.of()));
        String id = fixture(8); var result = adapter().run(id); assertEquals(4, brokers.dispatched.get());
        assertEquals(1, result.getAsJsonObject("coordinatorReview").getAsJsonArray("pages").size()); assertEquals(result, adapter().run(id));
        assertEquals(1, result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("toolCalls").getAsInt());
    }
    @Test void aSupplementaryUnicodeCharacterAtThePageBoundarySurvivesDurableReplay() throws Exception {
        var customer = new CoordinationInputsTest.Tools(false); customer.artifacts.put("provenance", ("a".repeat(3999) + "😀" + "z").getBytes(StandardCharsets.UTF_8));
        String bundle = new CoordinationInputs(root).capture(new CoordinationInputs.Source(root, "investigation-" + UUID.randomUUID(), "recorded"), customer,
                new CoordinationInputs.Source(root, "subscription-investigation-" + UUID.randomUUID(), "recorded"), new CoordinationInputsTest.Tools(true));
        brokers.reviewScript = (input, turn) -> turn == 1 ? read(CaseInputs.CASE_ID, "provenance", 0) : turn == 2 ? read(CaseInputs.CASE_ID, "provenance", 3999) : finishReview(input, false);
        String id = register(bundle, 10, 64000); var result = adapter().run(id); var pages = result.getAsJsonObject("coordinatorReview").getAsJsonArray("pages");
        assertEquals("a".repeat(3999), pages.get(0).getAsJsonObject().get("content").getAsString()); assertEquals("😀z", pages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals(result, adapter().run(id)); assertEquals(5, brokers.dispatched.get());
    }
    @Test void expiryOrRevocationBetweenStagesCannotRenewTheParentDeadline() throws Exception {
        brokers.beforeReview = () -> clock.now = clock.now.plusSeconds(1201);
        var expired = adapter().run(fixture(6)); assertEquals("TRIAL_EXPIRED", expired.getAsJsonObject("coordinatorReview").get("stopReason").getAsString()); assertEquals(3, brokers.dispatched.get());
        assertEquals(3, expired.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("reservedCalls").getAsInt());
        brokers.beforeReview = null; String revoked = fixture(6); runtime().revoke(revoked);
        var cancelled = adapter().run(revoked); assertEquals("CANCELLED", cancelled.getAsJsonObject("coordinatorReview").get("status").getAsString()); assertEquals(3, brokers.dispatched.get());
        assertTrue(cancelled.get("workers").toString().contains("CANCELLED"));
    }
    @Test void participantCaseAndProtocolScopesRejectSiblingOrFutureStageAccess() throws Exception {
        String id = fixture(6);
        assertThrows(RuntimeException.class, () -> runtime().read(id, CaseInputs.CASE_ID, "wrong", SubscriptionPeriodCase.CASE_ID, "description"));
        assertThrows(RuntimeException.class, () -> runtime().call(id, "coordinator", "wrong", new ModelCallBroker.Request("gpt-5.6-terra", "input", 2048, CaseInvestigationDecision.protocol(CaseInputs.CASE_ID))));
        assertThrows(RuntimeException.class, () -> runtime().call(id, "follow-up-1", "wrong", new ModelCallBroker.Request("gpt-5.6-terra", "input", 2048, CaseInvestigationDecision.protocol(CaseInputs.CASE_ID))));
        assertEquals(0, brokers.registered.get());
    }
    @Test void changedRequestsBarriersEventsAndParentTraceAreRejected() throws Exception {
        brokers.interruptParticipant = "coordinator"; brokers.interruptAfterPersist.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
        Files.writeString(run(id).resolve("originals.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(3, brokers.dispatched.get());
        String complete = fixture(6); adapter().run(complete); Files.writeString(run(complete).resolve("event-1-coordinator.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(complete));
        String parent = fixture(6); adapter().run(parent); Files.writeString(root.resolve("coordination-trials/" + parent + "/started.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(parent));
        brokers.interruptAfterPersist.set(true); String changed = fixture(6); assertThrows(IOException.class, () -> adapter().run(changed));
        Files.writeString(run(changed).resolve("request-1-coordinator.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(changed));
    }
    @Test void incompletePublicationFailsClosedAndAResultReceiptCanBeRecoveredWithoutNewCalls() throws Exception {
        String id = fixture(6); var result = adapter().run(id); Files.delete(run(id).resolve("receipt.json")); assertThrows(RuntimeException.class, () -> adapter().retained(id));
        assertEquals(result, adapter().run(id)); assertEquals(3, brokers.dispatched.get());
        String pending = fixture(6); Files.writeString(run(pending).resolve("round-1.json.pending"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(pending)); assertEquals(3, brokers.dispatched.get());
        assertThrows(RuntimeException.class, () -> fixture(5));
    }
    @Test void historicalPrefixSurvivesLaterRevocationAndIsReadableWithoutCredentialsInAFreshProcess() throws Exception {
        String id = fixture(6); var result = adapter().run(id); clock.now = clock.now.plusSeconds(1800); runtime().revoke(id);
        assertEquals(result, adapter().retained(id)); assertEquals(result, adapter().run(id)); var before = inventory();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Controller.class.getName(), root.toString(), id).redirectErrorStream(true);
        builder.environment().remove("OPENAI_API_KEY"); var process = builder.start(); assertTrue(process.waitFor(40, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip(); assertEquals(0, process.exitValue(), output); assertEquals("REVIEW_RECORDED", output); assertEquals(before, inventory());
    }
    @Test void currentPolicyIsStableAcrossFreshJvmObjectOrdering() throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Controller.class.getName()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(40, TimeUnit.SECONDS)); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue()); assertEquals(CoordinationRuntime.implementationIdentity(CoordinationTeamRunner.policy()).sha256(), output);
    }
    private Map<String, String> inventory() throws IOException {
        var values = new TreeMap<String, String>(); try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) if (!path.getFileName().toString().endsWith(".lock")) values.put(root.relativize(path).toString(), CaseInputs.hash(Files.readAllBytes(path)));
        } return values;
    }
    public static final class Controller {
        public static void main(String[] args) throws Exception {
            if (args.length == 0) System.out.println(CoordinationRuntime.implementationIdentity(CoordinationTeamRunner.policy()).sha256());
            else { var adapter = new CoordinationTeamRunner(Path.of(args[0])); var result = adapter.retained(args[1]); if (!result.equals(adapter.run(args[1]))) throw new IllegalStateException("Changed retained prefix"); System.out.println(result.get("status").getAsString()); }
        }
    }
    private final class ScriptedBrokers implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrent = new AtomicInteger(), maxConcurrent = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> originalScript = (input, turn) -> original(input, false, List.of());
        BiFunction<JsonObject, Integer, String> reviewScript = (input, turn) -> finishReview(input, true);
        String unknownParticipant, interruptParticipant; CyclicBarrier barrier; Runnable beforeReview;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file)); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(CaseInputs.object(Files.readAllBytes(store.resolve(id + "/request.json"))).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String scoped = participant(input); int turn = turns.computeIfAbsent(scoped, k -> new AtomicInteger()).incrementAndGet(); int count = concurrent.incrementAndGet(); maxConcurrent.accumulateAndGet(count, Math::max);
                    try {
                        if (!review(input) && barrier != null && turn == 1) try { barrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        if (review(input) && beforeReview != null) beforeReview.run();
                        boolean unknown = scoped.equals(unknownParticipant); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                                "inputTokens", 5, "outputTokens", 2, "outputText", (review(input) ? reviewScript : originalScript).apply(input, turn))); DurableExecution.publish(file, response);
                        if (scoped.equals(interruptParticipant) && interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { concurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
