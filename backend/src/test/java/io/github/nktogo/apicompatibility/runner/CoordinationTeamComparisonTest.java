package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
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
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationTeamComparisonTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return new CoordinationRuntime(root, clock, scripts); }
    private CoordinationTeamComparison adapter() throws IOException { return new CoordinationTeamComparison(root, runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int bytes) throws Exception {
        String bundle = CoordinationInputsTest.fixture(root); var base = CoordinationTeamComparison.checkDefinition(root, bundle);
        return adapter().register(bundle, new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, bytes, 2048)), new Slot("two-case", 1, Arm.COORDINATED_TEAM));
    }
    private Path run(String id) { return root.resolve("coordination-team-comparisons/" + id); }
    private static JsonObject trial(JsonObject result) { return result.getAsJsonObject("trial"); }
    private static String response(Object d) { return CaseInputs.JSON.toJson(Map.of("decision", d)); }
    private static String caseId(JsonObject input) { return input.getAsJsonArray("cases").get(0).getAsJsonObject().get("caseId").getAsString(); }
    private static String read(String id, String alias, int offset) { return response(Map.of("action", "read_evidence", "caseId", id, "inputId", alias, "offset", offset, "maxCharacters", 4000)); }
    private static Map<String, Object> dossier(String id, boolean answer, List<String> refs) { return Map.of("caseId", id, "response", answer ? "ANSWERED" : "ABSTAINED", "summary", "Retained evidence is scoped.", "summaryCitations", refs,
            "findings", refs.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "No general certification follows.", "citations", refs))); }
    private static String abstain(JsonObject input) { return response(Map.of("action", "finish", "cases", List.of(dossier(caseId(input), false, List.of())))); }
    private static String investigate(JsonObject input, int turn) {
        for (String alias : List.of("baseline-source", "execution")) {
            int next = 0, total = -1;
            for (var v : input.getAsJsonArray("readMetadata")) { var p = v.getAsJsonObject(); if (p.get("inputId").getAsString().equals(alias)) { next = p.get("nextOffset").getAsInt(); total = p.get("totalCharacters").getAsInt(); } }
            if (total < 0 || next < total) return read(caseId(input), alias, next);
        }
        var refs = new ArrayList<String>(); input.getAsJsonArray("readMetadata").forEach(p -> refs.add(p.getAsJsonObject().get("pageId").getAsString()));
        return response(Map.of("action", "finish", "cases", List.of(dossier(caseId(input), true, refs))));
    }
    private static String review(JsonObject input, boolean questions, boolean conflict) {
        var proposals = new ArrayList<Map<String, Object>>(); var refs = new ArrayList<String>();
        for (var v : input.getAsJsonArray("workers")) { var w = v.getAsJsonObject(); String ref = w.get("referenceId").getAsString(); refs.add(ref);
            if (questions) proposals.add(Map.of("caseId", w.get("caseId").getAsString(), "question", "What evidence addresses the recorded failure?", "citations", List.of(ref))); }
        return response(Map.of("action", "finish", "summary", "Case questions remain scoped.", "citations", refs, "followUps", proposals,
                "conflicts", conflict ? List.of(Map.of("claimIds", List.of("claim-1", "claim-3"), "statement", "Different cases may explain different claims.")) : List.of()));
    }
    private static String reconcile(JsonObject input, boolean answer) {
        var dossiers = new ArrayList<Map<String, Object>>();
        for (var v : input.getAsJsonArray("workers")) { var w = v.getAsJsonObject(); String id = w.get("caseId").getAsString(), ref = w.get("referenceId").getAsString();
            if (answer) for (var c : input.getAsJsonArray("freshClaims")) { var claim = c.getAsJsonObject(); if (claim.get("caseId").getAsString().equals(id)) { ref = claim.get("referenceId").getAsString(); break; } }
            dossiers.add(dossier(id, answer, List.of(ref)));
        }
        return response(Map.of("action", "finish", "cases", dossiers));
    }
    @Test void fullTrialRunsFreshWorkersConcurrentlyWithIsolatedQuestionContextsAndInclusiveUsage() throws Exception {
        scripts.freshBarrier = new CyclicBarrier(2); String id = fixture(6); var result = adapter().run(id); var t = trial(result);
        assertEquals("COMPLETED", t.get("status").getAsString()); assertEquals(6, scripts.dispatched.get()); assertEquals(2, scripts.maxFresh.get());
        assertEquals(6, t.getAsJsonObject("usage").get("reservedCalls").getAsInt()); assertEquals(0, t.getAsJsonObject("usage").get("caseExecutions").getAsInt());
        assertEquals(2, result.getAsJsonObject("evidence").getAsJsonArray("freshWorkers").size());
        for (var ctx : scripts.freshContexts) {
            String own = caseId(ctx), sibling = own.equals(CaseInputs.CASE_ID) ? SubscriptionPeriodCase.CASE_ID : CaseInputs.CASE_ID;
            assertFalse(ctx.toString().contains(sibling)); assertEquals(1, ctx.get("delegationDepth").getAsInt()); assertTrue(ctx.getAsJsonArray("readMetadata").isEmpty());
        }
        for (var c : t.getAsJsonArray("cases")) { assertEquals("ABSTAINED", c.getAsJsonObject().get("response").getAsString()); assertTrue(c.getAsJsonObject().get("review").isJsonNull()); }
        assertEquals("PENDING", new CoordinationTeamRunner(root, runtime()).retained(id).get("wholeTrialStatus").getAsString());
        assertEquals(result, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void noSelectedQuestionsMeansNoFreshAgentsAndUnusedBudgetCarriesIntoReconciliation() throws Exception {
        scripts.review = (in, n) -> review(in, false, false); scripts.reconcile = (in, n) -> n <= 6 ? read(CaseInputs.CASE_ID, "description", 0) : reconcile(in, false);
        var result = adapter().run(fixture(10)); assertEquals(10, scripts.dispatched.get()); assertTrue(scripts.freshContexts.isEmpty());
        assertEquals(7, result.getAsJsonObject("stageReservations").get("reconciliation").getAsInt()); assertEquals("COMPLETED", trial(result).get("status").getAsString());
    }
    @Test void originalAndFreshClaimLineageAndConflictsSurviveFinalAnswerWithoutQualityPromotion() throws Exception {
        scripts.original = CoordinationTeamComparisonTest::investigate; scripts.fresh = CoordinationTeamComparisonTest::investigate;
        scripts.review = (in, n) -> review(in, true, true); scripts.reconcile = (in, n) -> reconcile(in, true);
        var result = adapter().run(fixture(60)); var evidence = result.getAsJsonObject("evidence");
        assertEquals("COMPLETED", trial(result).get("status").getAsString()); assertEquals(4, evidence.getAsJsonArray("originalClaims").size()); assertEquals(4, evidence.getAsJsonArray("freshClaims").size());
        assertEquals("UNRESOLVED", evidence.getAsJsonArray("possibleConflicts").get(0).getAsJsonObject().get("state").getAsString());
        for (var c : trial(result).getAsJsonArray("cases")) { assertEquals("ANSWERED", c.getAsJsonObject().get("response").getAsString()); assertTrue(c.getAsJsonObject().get("review").isJsonNull()); }
        assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString());
    }
    @Test void followUpCumulativeCeilingAllowsMoreThanTwelveDecisionsAndPreservesFinalCalls() throws Exception {
        scripts.review = (in, n) -> { var d = JsonParser.parseString(review(in, true, false)).getAsJsonObject(); d.getAsJsonObject("decision").getAsJsonArray("followUps").remove(1); return d.toString(); };
        scripts.fresh = (in, n) -> read(caseId(in), "description", 0); scripts.reconcile = (in, n) -> read(CaseInputs.CASE_ID, "description", 0);
        var result = adapter().run(fixture(30)); var counts = result.getAsJsonObject("stageReservations");
        assertEquals(24, counts.get("followUp").getAsInt()); assertEquals(3, counts.get("reconciliation").getAsInt()); assertEquals(30, scripts.dispatched.get());
        assertEquals("FAILED", trial(result).get("status").getAsString()); for (var c : trial(result).getAsJsonArray("cases")) assertEquals("NOT_PROVIDED", c.getAsJsonObject().get("response").getAsString());
    }
    @Test void rejectedContextConsumesNoReservationAndIsNotManufacturedIntoAbstention() throws Exception {
        var result = adapter().run(fixture(6, 1)); assertEquals(0, scripts.dispatched.get()); assertEquals(0, trial(result).getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertEquals("FAILED", trial(result).get("status").getAsString()); assertTrue(record(run(result.get("runId").getAsString()), "reconciliation.json").toString().contains("CONTEXT_BUDGET_EXHAUSTED"));
    }
    @Test void unknownFreshAndRootCallsAreSpentAndNeverReplayedOrHiddenByACompletedDossier() throws Exception {
        scripts.unknownPhase = "fresh"; String id = fixture(6); var result = adapter().run(id);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", trial(result).get("status").getAsString()); assertEquals(2, trial(result).getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt()); assertEquals(result, adapter().run(id));
        scripts.unknownPhase = "reconcile"; var rootResult = adapter().run(fixture(6)); assertEquals("UNKNOWN_REMOTE_OUTCOME", trial(rootResult).get("status").getAsString());
        for (var c : trial(rootResult).getAsJsonArray("cases")) assertEquals("NOT_PROVIDED", c.getAsJsonObject().get("response").getAsString());
        assertEquals(12, scripts.dispatched.get());
    }
    @Test void unmappedFreshRegistrationConsumesItsSlotWithoutGuessingARetry() throws Exception {
        scripts.interruptRegistration.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id)); int sent = scripts.dispatched.get();
        var result = adapter().run(id); assertEquals("UNKNOWN_REMOTE_OUTCOME", trial(result).get("status").getAsString());
        assertEquals(6, trial(result).getAsJsonObject("usage").get("reservedCalls").getAsInt()); assertEquals(1, trial(result).getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        assertEquals(5, scripts.dispatched.get()); assertTrue(sent >= 3); assertEquals(result, adapter().run(id));
    }
    @Test void responsesPersistedBeforeFreshOrRootControllerInterruptionAreReused() throws Exception {
        for (String phase : List.of("fresh", "reconcile")) {
            scripts.interruptPhase = phase; scripts.interruptResponse.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
            var result = adapter().run(id); assertEquals("COMPLETED", trial(result).get("status").getAsString()); assertEquals(6, trial(result).getAsJsonObject("usage").get("reservedCalls").getAsInt());
        }
        assertEquals(12, scripts.dispatched.get()); assertEquals(12, scripts.registered.get());
    }
    @Test void freshWorkersCannotReadSiblingCasesOrDelegateRecursively() throws Exception {
        scripts.fresh = (in, n) -> read(caseId(in).equals(CaseInputs.CASE_ID) ? SubscriptionPeriodCase.CASE_ID : CaseInputs.CASE_ID, "description", 0);
        var result = adapter().run(fixture(6)); assertEquals("FAILED", trial(result).get("status").getAsString()); assertTrue(result.getAsJsonObject("evidence").get("freshWorkers").toString().contains("INVALID_MODEL_DECISION"));
        scripts.fresh = (in, n) -> response(Map.of("action", "delegate", "question", "Escape")); assertEquals("FAILED", trial(adapter().run(fixture(6))).get("status").getAsString());
    }
    @Test void rootRejectsUnseenForeignAndAbstainedClaimAnswerPromotion() throws Exception {
        for (String bad : List.of("claim-99", "worker-2", "claim-1")) {
            scripts.reconcile = (in, n) -> response(Map.of("action", "finish", "cases", List.of(dossier(CaseInputs.CASE_ID, true, List.of(bad)), dossier(SubscriptionPeriodCase.CASE_ID, false, List.of("worker-2")))));
            var result = adapter().run(fixture(6)); assertEquals("FAILED", trial(result).get("status").getAsString());
            for (var c : trial(result).getAsJsonArray("cases")) assertEquals("NOT_PROVIDED", c.getAsJsonObject().get("response").getAsString());
        }
    }
    @Test void rootCanReadCapturedPagesAndItsOwnPageOriginRemainsBoundOnResume() throws Exception {
        scripts.reconcile = (in, n) -> n == 1 ? read(CaseInputs.CASE_ID, "description", 0) : reconcile(in, false);
        String id = fixture(8); var result = adapter().run(id); assertEquals(7, scripts.dispatched.get()); assertEquals(1, trial(result).getAsJsonObject("usage").get("toolCalls").getAsInt());
        assertEquals(result, adapter().run(id)); var pages = record(run(id), "reconciliation.json").getAsJsonArray("pages"); assertEquals("read-1", pages.get(0).getAsJsonObject().get("pageId").getAsString());
    }
    @Test void expiryOrRevocationBeforeFreshWorkCannotRenewTheWholeTrial() throws Exception {
        scripts.review = (in, n) -> { var d = JsonParser.parseString(review(in, true, false)).getAsJsonObject(); d.getAsJsonObject("decision").getAsJsonArray("followUps").remove(1); return d.toString(); };
        scripts.beforeFresh = () -> clock.now = clock.now.plusSeconds(1201); var expired = adapter().run(fixture(6)); assertEquals("FAILED", trial(expired).get("status").getAsString());
        assertEquals(1, scripts.freshContexts.size()); assertEquals(4, scripts.dispatched.get());
        scripts.beforeFresh = null; String id = fixture(6); scripts.beforeFresh = () -> { try { runtime().revoke(id); } catch (IOException e) { throw new RuntimeException(e); } };
        var revoked = adapter().run(id); assertEquals("CANCELLED", trial(revoked).get("status").getAsString()); assertEquals(8, scripts.dispatched.get());
    }
    @Test void tamperedRequestsStageBarriersAndPendingPublicationFailClosed() throws Exception {
        scripts.interruptPhase = "reconcile"; scripts.interruptResponse.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id)); int calls = scripts.dispatched.get();
        Files.writeString(run(id).resolve("request-1-reconciler.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(calls, scripts.dispatched.get());
        String done = fixture(6); adapter().run(done); Files.writeString(run(done).resolve("follow-ups.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(done));
        String pending = fixture(6); Files.writeString(run(pending).resolve("fresh-round-1.json.pending"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(pending));
    }
    @Test void finalReceiptAndPostTerminalExportRecoveryDoNotDispatchAgain() throws Exception {
        String id = fixture(6); var result = adapter().run(id); Files.delete(run(id).resolve("receipt.json")); assertThrows(RuntimeException.class, () -> adapter().retained(id)); assertEquals(result, adapter().run(id));
        Files.delete(run(id).resolve("result.json")); Files.delete(run(id).resolve("receipt.json")); assertEquals(result, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void sealedFullTrialAndHistoricalPrefixAreCredentialFreeAndUnchangedInAFreshProcess() throws Exception {
        String id = fixture(6); var result = adapter().run(id); var before = inventory();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Controller.class.getName(), root.toString(), id).redirectErrorStream(true); builder.environment().remove("OPENAI_API_KEY");
        var process = builder.start(); assertTrue(process.waitFor(40, TimeUnit.SECONDS)); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip(); assertEquals(0, process.exitValue(), output); assertEquals("COMPLETED", output); assertEquals(before, inventory()); assertEquals(result, adapter().run(id));
    }
    @Test void policySurvivesFreshJvmOrderingAndPreRegistersOnlyBoundedCaseScopes() throws Exception {
        var policy = CoordinationTeamComparison.policy(); assertEquals(6, policy.participants().size());
        String id = fixture(6); assertThrows(RuntimeException.class, () -> runtime().read(id, "fresh-customer", "wrong", SubscriptionPeriodCase.CASE_ID, "description"));
        assertThrows(RuntimeException.class, () -> runtime().call(id, "reconciler", "wrong", new ModelCallBroker.Request("gpt-5.6-terra", "input", 2048, CapturedTeamDecision.PROTOCOL)));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Controller.class.getName()).redirectErrorStream(true).start(); assertTrue(process.waitFor(40, TimeUnit.SECONDS));
        assertEquals(CoordinationRuntime.implementationIdentity(policy).sha256(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip()); assertEquals(0, process.exitValue()); assertEquals(0, scripts.dispatched.get());
    }
    private Map<String, String> inventory() throws IOException { var values = new TreeMap<String, String>(); try (var files = Files.walk(root)) { for (var f : files.filter(Files::isRegularFile).toList()) if (!f.getFileName().toString().endsWith(".lock")) values.put(root.relativize(f).toString(), CaseInputs.hash(Files.readAllBytes(f))); } return values; }
    public static final class Controller {
        public static void main(String[] args) throws Exception {
            if (args.length == 0) System.out.println(CoordinationRuntime.implementationIdentity(CoordinationTeamComparison.policy()).sha256());
            else { var adapter = new CoordinationTeamComparison(Path.of(args[0])); var result = adapter.retained(args[1]); if (!result.equals(adapter.run(args[1]))) throw new IllegalStateException("Changed full result"); new CoordinationTeamRunner(Path.of(args[0])).retained(args[1]); System.out.println(trial(result).get("status").getAsString()); }
        }
    }
    private final class Scripts implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), freshConcurrent = new AtomicInteger(), maxFresh = new AtomicInteger();
        final AtomicBoolean interruptResponse = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> freshContexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> original = (in, n) -> abstain(in), fresh = (in, n) -> abstain(in), review = (in, n) -> review(in, true, false), reconcile = (in, n) -> reconcile(in, false);
        String unknownPhase, interruptPhase; CyclicBarrier freshBarrier; Runnable beforeFresh;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (request.input().contains("delegatedQuestion") && interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted fresh registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    var file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file)); assertTrue(permitted.getAsBoolean());
                    var request = CaseInputs.object(Files.readAllBytes(store.resolve(id + "/request.json"))); var input = JsonParser.parseString(request.get("input").getAsString()).getAsJsonObject(); String protocol = request.get("protocol").getAsString();
                    String phase = protocol.equals(CapturedTeamDecision.PROTOCOL) ? "review" : protocol.equals(CapturedReconciliationDecision.PROTOCOL) ? "reconcile" : input.has("delegatedQuestion") ? "fresh" : "original";
                    String key = phase + (Set.of("original", "fresh").contains(phase) ? caseId(input) : ""); int turn = turns.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet(); dispatched.incrementAndGet();
                    if (phase.equals("fresh")) { freshContexts.add(input); maxFresh.accumulateAndGet(freshConcurrent.incrementAndGet(), Math::max); }
                    try {
                        if (phase.equals("fresh") && freshBarrier != null && turn == 1) try { freshBarrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        if (phase.equals("fresh") && beforeFresh != null) beforeFresh.run(); boolean unknown = phase.equals(unknownPhase);
                        var script = switch (phase) { case "original" -> original; case "fresh" -> fresh; case "review" -> review; default -> reconcile; };
                        var result = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED", "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, turn))); DurableExecution.publish(file, result);
                        if (phase.equals(interruptPhase) && interruptResponse.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return result;
                    } finally { if (phase.equals("fresh")) freshConcurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
