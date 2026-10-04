package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationIndependentWorkersTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final ScriptedBrokers brokers = new ScriptedBrokers();
    private CoordinationRuntime runtime() throws IOException { return new CoordinationRuntime(root, clock, brokers); }
    private CoordinationIndependentWorkers adapter() throws IOException { return new CoordinationIndependentWorkers(root, runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int inputBytes) throws Exception {
        String bundle = CoordinationInputsTest.fixture(root); var base = CoordinationIndependentWorkers.checkDefinition(root, bundle);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, inputBytes, 2048));
        return adapter().register(bundle, definition, new Slot("two-case", 1, Arm.INDEPENDENT_WORKERS));
    }
    private Path run(String id) { return root.resolve("coordination-independent-workers/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static String caseId(JsonObject input) { assertEquals(1, input.getAsJsonArray("cases").size()); return input.getAsJsonArray("cases").get(0).getAsJsonObject().get("caseId").getAsString(); }
    private static String read(JsonObject input, String alias, int offset) { return decision(Map.of("action", "read_evidence", "caseId", caseId(input), "inputId", alias, "offset", offset, "maxCharacters", 4000)); }
    private static String finish(JsonObject input, boolean answered, List<String> citations) {
        return decision(Map.of("action", "finish", "cases", List.of(Map.of("caseId", caseId(input), "response", answered ? "ANSWERED" : "ABSTAINED",
                "summary", "The captured evidence is scoped to this case.", "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "No general certification follows.", "citations", citations))))));
    }
    private static String investigate(JsonObject input, int ignored) {
        for (String alias : List.of("baseline-source", "execution")) {
            int next = 0, total = -1;
            for (var value : input.getAsJsonArray("readMetadata")) {
                var page = value.getAsJsonObject(); if (alias.equals(page.get("inputId").getAsString())) { next = page.get("nextOffset").getAsInt(); total = page.get("totalCharacters").getAsInt(); }
            }
            if (total < 0 || next < total) return read(input, alias, next);
        }
        var citations = new ArrayList<String>(); input.getAsJsonArray("readMetadata").forEach(p -> citations.add(p.getAsJsonObject().get("pageId").getAsString()));
        return finish(input, true, citations);
    }
    @Test void realOverlapAndIsolatedContextsProduceUnreviewedOutputsWithOneUsageLedger() throws Exception {
        brokers.script = CoordinationIndependentWorkersTest::investigate; brokers.firstRoundBarrier = new CyclicBarrier(2);
        String id = fixture(60); var result = adapter().run(id); var trial = result.getAsJsonObject("trial");
        assertEquals("COMPLETED", trial.get("status").getAsString()); assertEquals(2, brokers.maxConcurrent.get()); assertEquals(8, brokers.dispatched.get());
        assertEquals(8, trial.getAsJsonObject("usage").get("reservedCalls").getAsInt()); assertEquals(6, trial.getAsJsonObject("usage").get("toolCalls").getAsInt());
        assertEquals(0, trial.getAsJsonObject("usage").get("caseExecutions").getAsInt());
        for (var input : brokers.contexts) {
            String scoped = caseId(input); for (var page : input.getAsJsonArray("readMetadata")) assertEquals(scoped, page.getAsJsonObject().get("caseId").getAsString());
            for (var page : input.getAsJsonArray("recentPages")) assertEquals(scoped, page.getAsJsonObject().get("caseId").getAsString());
            assertFalse(input.has("workers")); assertFalse(input.has("criteria"));
        }
        for (var value : trial.getAsJsonArray("cases")) { assertEquals("ANSWERED", value.getAsJsonObject().get("response").getAsString()); assertTrue(value.getAsJsonObject().get("review").isJsonNull()); }
        assertEquals(Arm.INDEPENDENT_WORKERS, CaseInputs.JSON.fromJson(trial, CoordinationTrial.class).slot().arm());
        assertEquals(result, adapter().retained(id)); assertEquals(result, adapter().run(id)); assertEquals(8, brokers.dispatched.get());
    }
    @Test void unusedOpportunitiesRemainAvailableToTheOtherWorkerThroughAllSixtyCalls() throws Exception {
        brokers.script = (input, turn) -> caseId(input).equals(SubscriptionPeriodCase.CASE_ID) || turn == 59 ? finish(input, false, List.of()) : read(input, "description", 0);
        var result = adapter().run(fixture(60)); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(60, brokers.dispatched.get()); assertEquals(59, brokers.turns.get(CaseInputs.CASE_ID).get()); assertEquals(1, brokers.turns.get(SubscriptionPeriodCase.CASE_ID).get());
        assertTrue(brokers.contexts.stream().anyMatch(c -> c.get("decisionsRemaining").getAsInt() == 1));
    }
    @Test void oddBudgetHasDeterministicFairRoundsAndNoSyntheticAbstention() throws Exception {
        brokers.script = (input, turn) -> read(input, "description", 0); String id = fixture(3); var result = adapter().run(id);
        assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(3, brokers.dispatched.get());
        assertEquals(1, brokers.turns.get(CaseInputs.CASE_ID).get()); assertEquals(2, brokers.turns.get(SubscriptionPeriodCase.CASE_ID).get());
        for (var value : result.getAsJsonObject("trial").getAsJsonArray("cases")) assertEquals("NOT_PROVIDED", value.getAsJsonObject().get("response").getAsString());
        assertEquals(1, CaseInputs.object(Files.readAllBytes(run(id).resolve("round-2.json"))).get("decisionsRemaining").getAsInt());
    }
    @Test void aRejectedContextDoesNotConsumeAnUnreservedCallOpportunity() throws Exception {
        var digest = new io.github.nktogo.apicompatibility.compatibility.ContentDigest("a".repeat(64));
        var subscription = new CaseTask(SubscriptionPeriodCase.CASE_ID, digest, ExpectedResponse.ANSWER, List.of("observed-regressions"));
        int bytes = CoordinationSingleAgent.context(new Workload("isolated-case", List.of(subscription)), List.of(), 1, 64000).getBytes(StandardCharsets.UTF_8).length;
        var customer = new CaseTask(CaseInputs.CASE_ID, digest, ExpectedResponse.ANSWER, List.of("observed-regressions"));
        assertThrows(CoordinationRuntime.Stop.class, () -> CoordinationSingleAgent.context(new Workload("isolated-case", List.of(customer)), List.of(), 1, bytes));
        var result = adapter().run(fixture(1, bytes)); assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(1, brokers.dispatched.get()); assertEquals(1, result.getAsJsonObject("trial").getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertTrue(result.get("workers").toString().contains("CONTEXT_BUDGET_EXHAUSTED"));
        assertEquals("ABSTAINED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(1).getAsJsonObject().get("response").getAsString());
    }
    @Test void wrongCaseActionsAndUnsupportedAnswersStopOnlyTheirOwnWorker() throws Exception {
        brokers.script = (input, turn) -> caseId(input).equals(CaseInputs.CASE_ID)
                ? decision(Map.of("action", "read_evidence", "caseId", SubscriptionPeriodCase.CASE_ID, "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000))
                : finish(input, false, List.of());
        var result = adapter().run(fixture(4)); assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString());
        assertTrue(result.get("workers").toString().contains("INVALID_MODEL_DECISION")); assertEquals(2, brokers.dispatched.get());
        assertEquals(1, result.getAsJsonObject("trial").getAsJsonArray("cases").asList().stream().filter(v -> v.getAsJsonObject().get("response").getAsString().equals("ABSTAINED")).count());
        brokers.script = (input, turn) -> finish(input, true, List.of("read-99"));
        assertTrue(adapter().run(fixture(4)).get("workers").toString().contains("UNSUPPORTED_DOSSIER"));
    }
    @Test void completedResponseAndSiblingEventSurviveControllerInterruptionWithoutRedispatch() throws Exception {
        brokers.script = CoordinationIndependentWorkersTest::investigate; brokers.interruptAfterPersist.set(true); String id = fixture(60);
        assertThrows(IOException.class, () -> adapter().run(id)); assertEquals(2, brokers.dispatched.get());
        var result = adapter().run(id); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(8, brokers.dispatched.get());
        assertEquals(8, brokers.registered.get());
    }
    @Test void uncertainRegistrationIsNotReplayedWhileTheOtherWorkerCanStillFinish() throws Exception {
        brokers.interruptRegistration.set(true); String id = fixture(4); assertThrows(IOException.class, () -> adapter().run(id));
        assertEquals(2, brokers.registered.get()); var result = adapter().run(id); assertEquals(2, brokers.registered.get());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertTrue(result.get("workers").toString().contains("CALL_REGISTRATION_UNCERTAIN"));
        assertEquals(1, result.getAsJsonObject("trial").getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
    }
    @Test void unknownRemoteOutcomeRetainsPartialOutputsAndCountsTheUncertainCall() throws Exception {
        brokers.unknownCase = CaseInputs.CASE_ID; var result = adapter().run(fixture(4));
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(2, brokers.dispatched.get());
        assertEquals(1, result.getAsJsonObject("trial").getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        assertEquals(1, result.getAsJsonObject("trial").getAsJsonArray("cases").asList().stream().filter(v -> v.getAsJsonObject().get("response").getAsString().equals("ABSTAINED")).count());
    }
    @Test void expiredAndRevokedTrialsNeverDispatchAndCannotRenewTheirClocks() throws Exception {
        String expired = fixture(4); runtime().start(expired); clock.now = clock.now.plusSeconds(1201);
        assertTrue(adapter().run(expired).get("workers").toString().contains("TRIAL_EXPIRED")); assertEquals(0, brokers.dispatched.get());
        String revoked = fixture(4); runtime().revoke(revoked); assertEquals("CANCELLED", adapter().run(revoked).getAsJsonObject("trial").get("status").getAsString()); assertEquals(0, brokers.dispatched.get());
    }
    @Test void expiryAfterOneWorkerFinishesPreservesItsDossierAndStopsTheSibling() throws Exception {
        brokers.script = (input, turn) -> {
            if (caseId(input).equals(CaseInputs.CASE_ID)) return finish(input, false, List.of());
            if (turn == 2) clock.now = clock.now.plusSeconds(1201);
            return read(input, "description", 0);
        };
        var result = adapter().run(fixture(6)); assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(3, brokers.dispatched.get()); assertTrue(result.get("workers").toString().contains("TRIAL_EXPIRED"));
        var outputs = result.getAsJsonObject("trial").getAsJsonArray("cases");
        assertEquals("ABSTAINED", outputs.get(0).getAsJsonObject().get("response").getAsString()); assertEquals("NOT_PROVIDED", outputs.get(1).getAsJsonObject().get("response").getAsString());
    }
    @Test void runtimeCaseAndProtocolPermissionsRejectSiblingAccessBeforeBrokerRegistration() throws Exception {
        String id = fixture(4);
        assertThrows(IllegalArgumentException.class, () -> runtime().read(id, CaseInputs.CASE_ID, "wrong-read", SubscriptionPeriodCase.CASE_ID, "description"));
        var wrong = new ModelCallBroker.Request("gpt-5.6-terra", "input", 2048, CaseInvestigationDecision.protocol(SubscriptionPeriodCase.CASE_ID));
        assertThrows(IllegalArgumentException.class, () -> runtime().call(id, CaseInputs.CASE_ID, "wrong-call", wrong)); assertEquals(0, brokers.registered.get());
        assertEquals(0, runtime().snapshot(id).getAsJsonObject("usage").get("toolCalls").getAsInt());
    }
    @Test void sealedEventsCannotBeChangedDuringResume() throws Exception {
        brokers.interruptAfterPersist.set(true); String id = fixture(4); assertThrows(IOException.class, () -> adapter().run(id));
        try (var files = Files.list(run(id))) {
            Path seal = files.filter(p -> p.getFileName().toString().matches("event-.*-receipt.json")).findFirst().orElseThrow();
            Path event = seal.resolveSibling(seal.getFileName().toString().replace("-receipt.json", ".json")); Files.writeString(event, "{}");
        }
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(2, brokers.dispatched.get());
    }
    @Test void changedRequestsAreRefusedBeforeDispatchAndResultReceiptsRemainRequired() throws Exception {
        brokers.interruptAfterPersist.set(true); String id = fixture(4); assertThrows(IOException.class, () -> adapter().run(id));
        try (var files = Files.list(run(id))) { Files.writeString(files.filter(p -> p.getFileName().toString().startsWith("request-")).findFirst().orElseThrow(), "{}"); }
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(2, brokers.dispatched.get());
        String completed = fixture(4); var result = adapter().run(completed); Files.delete(run(completed).resolve("receipt.json"));
        assertThrows(RuntimeException.class, () -> adapter().retained(completed)); assertEquals(result, adapter().run(completed));
        Files.writeString(run(completed).resolve(CaseInputs.CASE_ID + ".json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(completed));
    }
    @Test void caseProtocolsHaveStrictSingleCaseSchemasAndRejectExtraDossiersOrDelegation() {
        for (String id : List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)) {
            String protocol = CaseInvestigationDecision.protocol(id); var body = CaseInputs.object(new ModelCallBroker.Request("gpt-test", "input", 2048, protocol).body());
            assertEquals(CaseInvestigationDecision.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString()); assertFalse(body.get("parallel_tool_calls").getAsBoolean());
            assertEquals(CaseInvestigationDecision.schema(protocol), body.getAsJsonArray("tools").get(0).getAsJsonObject().get("parameters"));
            assertFalse(body.getAsJsonArray("tools").toString().contains(id.equals(CaseInputs.CASE_ID) ? SubscriptionPeriodCase.CASE_ID : CaseInputs.CASE_ID));
            assertThrows(RuntimeException.class, () -> CaseInvestigationDecision.parse(decision(Map.of("action", "delegate")), id));
            assertThrows(RuntimeException.class, () -> CaseInvestigationDecision.parse("{\"decision\":{\"action\":\"finish\",\"action\":\"finish\",\"cases\":[]}}", id));
        }
    }
    @Test void policyIsStableAcrossFreshJvmObjectOrdering() throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Controller.class.getName()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(40, TimeUnit.SECONDS)); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue()); assertEquals(CoordinationRuntime.implementationIdentity(CoordinationIndependentWorkers.policy()).sha256(), output);
    }
    @Test void aFreshProcessCanInspectAndResumeCompletedTrialsWithoutCredentialsOrChanges() throws Exception {
        String id = fixture(4); adapter().run(id); var before = inventory();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Controller.class.getName(), root.toString(), id).redirectErrorStream(true);
        builder.environment().remove("OPENAI_API_KEY"); var process = builder.start(); assertTrue(process.waitFor(40, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue()); assertEquals("COMPLETED", output); assertEquals(before, inventory()); assertEquals(2, brokers.dispatched.get());
    }
    private Map<String, String> inventory() throws IOException {
        var files = new TreeMap<String, String>(); try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) if (!path.getFileName().toString().endsWith(".lock")) files.put(root.relativize(path).toString(), CaseInputs.hash(Files.readAllBytes(path)));
        }
        return files;
    }
    public static final class Controller {
        public static void main(String[] args) throws Exception {
            if (args.length == 0) System.out.println(CoordinationRuntime.implementationIdentity(CoordinationIndependentWorkers.policy()).sha256());
            else {
                var adapter = new CoordinationIndependentWorkers(Path.of(args[0])); var result = adapter.retained(args[1]);
                if (!result.equals(adapter.run(args[1]))) throw new IllegalStateException("Changed completed result");
                System.out.println(result.getAsJsonObject("trial").get("status").getAsString());
            }
        }
    }
    private final class ScriptedBrokers implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrent = new AtomicInteger(), maxConcurrent = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> script = (input, turn) -> finish(input, false, List.of()); String unknownCase; CyclicBarrier firstRoundBarrier;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file)); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(CaseInputs.object(Files.readAllBytes(store.resolve(id + "/request.json"))).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String scoped = caseId(input); int turn = turns.computeIfAbsent(scoped, k -> new AtomicInteger()).incrementAndGet();
                    int count = concurrent.incrementAndGet(); maxConcurrent.accumulateAndGet(count, Math::max);
                    try {
                        if (firstRoundBarrier != null && turn == 1) try { firstRoundBarrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        boolean unknown = scoped.equals(unknownCase); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                                "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, turn))); DurableExecution.publish(file, response);
                        if (interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { concurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
