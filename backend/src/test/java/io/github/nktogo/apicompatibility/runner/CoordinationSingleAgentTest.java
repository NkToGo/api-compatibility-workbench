package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationSingleAgentTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final ScriptedBrokers brokers = new ScriptedBrokers();
    private CoordinationRuntime runtime() throws IOException { return new CoordinationRuntime(root, clock, brokers); }
    private CoordinationSingleAgent adapter() throws IOException { return new CoordinationSingleAgent(root, runtime()); }
    private record Fixture(String id, Definition definition, String bundle) {}
    private Fixture fixture(int calls) throws Exception {
        String bundle = CoordinationInputsTest.fixture(root); var base = CoordinationSingleAgent.checkDefinition(root, bundle);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, 64000, 2048));
        return new Fixture(adapter().register(bundle, definition, new Slot("two-case", 1, Arm.SINGLE_AGENT)), definition, bundle);
    }
    private Path run(String id) { return root.resolve("coordination-single-agent/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static String read(String caseId, String alias, int offset) { return decision(Map.of("action", "read_evidence", "caseId", caseId, "inputId", alias, "offset", offset, "maxCharacters", 4000)); }
    private static JsonObject dossier(String caseId, String response, List<String> citations) {
        return json(Map.of("caseId", caseId, "response", response, "summary", "The captured evidence is scoped to this case.", "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "No general provider certification follows.", "citations", citations))));
    }
    private static String finish(JsonObject input, boolean answered) {
        var cases = new ArrayList<JsonObject>();
        for (var task : input.getAsJsonArray("cases")) {
            String caseId = task.getAsJsonObject().get("caseId").getAsString(); var citations = new ArrayList<String>();
            for (var page : input.getAsJsonArray("readMetadata")) if (caseId.equals(page.getAsJsonObject().get("caseId").getAsString())) citations.add(page.getAsJsonObject().get("pageId").getAsString());
            cases.add(dossier(caseId, answered ? "ANSWERED" : "ABSTAINED", citations.stream().limit(12).toList()));
        }
        return decision(Map.of("action", "finish", "cases", cases));
    }
    private static String investigate(JsonObject input, int ignored) {
        for (var task : input.getAsJsonArray("cases")) {
            String caseId = task.getAsJsonObject().get("caseId").getAsString();
            for (String alias : List.of("baseline-source", "execution")) {
                int next = 0, total = -1;
                for (var value : input.getAsJsonArray("readMetadata")) {
                    var page = value.getAsJsonObject(); if (caseId.equals(page.get("caseId").getAsString()) && alias.equals(page.get("inputId").getAsString())) { next = page.get("nextOffset").getAsInt(); total = page.get("totalCharacters").getAsInt(); }
                }
                if (total < 0 || next < total) return read(caseId, alias, next);
            }
        }
        return finish(input, true);
    }
    @Test void oneContextSeesBothCasesAndExportsUnreviewedBoundOutputsWithoutExecution() throws Exception {
        brokers.script = CoordinationSingleAgentTest::investigate; var fixture = fixture(60); var result = adapter().run(fixture.id());
        var trial = result.getAsJsonObject("trial"); assertEquals("COMPLETED", trial.get("status").getAsString()); assertEquals(2, trial.getAsJsonArray("cases").size());
        for (var output : trial.getAsJsonArray("cases")) { assertEquals("ANSWERED", output.getAsJsonObject().get("response").getAsString()); assertTrue(output.getAsJsonObject().get("review").isJsonNull()); }
        assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString()); assertEquals(0, trial.getAsJsonObject("usage").get("caseExecutions").getAsInt());
        assertTrue(brokers.contexts.stream().allMatch(c -> c.getAsJsonArray("cases").size() == 2));
        assertTrue(brokers.contexts.getLast().getAsJsonArray("readMetadata").toString().contains(SubscriptionPeriodCase.CASE_ID));
        assertEquals(result, adapter().retained(fixture.id())); int sent = brokers.dispatched; assertEquals(result, adapter().run(fixture.id())); assertEquals(sent, brokers.dispatched);
        var record = CaseInputs.JSON.fromJson(trial, CoordinationTrial.class); assertEquals(fixture.definition().implementations().get(Arm.SINGLE_AGENT), record.implementationIdentity());
    }
    @Test void canUseAllSixtyDecisionsRatherThanAnInheritedTwelveTurnLoop() throws Exception {
        brokers.script = (input, turn) -> turn == 60 ? finish(input, false) : read(CaseInputs.CASE_ID, "description", 0);
        var fixture = fixture(60); var result = adapter().run(fixture.id()); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(60, brokers.dispatched); assertEquals(60, result.getAsJsonObject("trial").getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertTrue(brokers.contexts.getLast().get("decisionsRemaining").getAsInt() == 1); assertEquals(59, brokers.contexts.getLast().getAsJsonArray("readMetadata").size());
    }
    @Test void exhaustedBudgetRetainsActionsAndDoesNotFabricateAnAbstention() throws Exception {
        brokers.script = (input, turn) -> read(CaseInputs.CASE_ID, "description", 0); var fixture = fixture(2); var result = adapter().run(fixture.id());
        assertEquals("CALL_BUDGET_EXHAUSTED", result.get("stopReason").getAsString()); assertEquals(2, brokers.dispatched);
        assertTrue(Files.exists(run(fixture.id()).resolve("event-2.json")));
        for (var value : result.getAsJsonObject("trial").getAsJsonArray("cases")) assertEquals("NOT_PROVIDED", value.getAsJsonObject().get("response").getAsString());
    }
    @Test void schemaRejectsInstructionsUnsupportedActionsDuplicatesAndIncompleteDossiers() {
        for (String invalid : List.of("{\"decision\":{\"action\":\"run_case\"}}", "{\"decision\":{\"action\":\"finish\",\"cases\":[]}}",
                "{\"decision\":{\"action\":\"finish\",\"action\":\"finish\",\"cases\":[]}}", "{\"decision\":null}")) assertThrows(RuntimeException.class, () -> BundleInvestigationDecision.parse(invalid));
        assertThrows(RuntimeException.class, () -> BundleInvestigationDecision.parse(read(SubscriptionPeriodCase.CASE_ID, "reference-source", 0)));
        String duplicate = decision(Map.of("action", "finish", "cases", List.of(dossier(CaseInputs.CASE_ID, "ABSTAINED", List.of()), dossier(CaseInputs.CASE_ID, "ABSTAINED", List.of()))));
        assertThrows(RuntimeException.class, () -> BundleInvestigationDecision.parse(duplicate));
    }
    @Test void inventedCrossCaseCitationsAndAnswersWithoutCompleteRequiredReadsFail() throws Exception {
        brokers.script = (input, turn) -> turn == 1 ? read(CaseInputs.CASE_ID, "description", 0) : decision(Map.of("action", "finish", "cases", List.of(
                dossier(CaseInputs.CASE_ID, "ABSTAINED", List.of("read-99")), dossier(SubscriptionPeriodCase.CASE_ID, "ABSTAINED", List.of("read-1")))));
        assertEquals("UNSUPPORTED_DOSSIER", adapter().run(fixture(4).id()).get("stopReason").getAsString());
        brokers.script = (input, turn) -> finish(input, true); assertEquals("UNSUPPORTED_DOSSIER", adapter().run(fixture(4).id()).get("stopReason").getAsString());
    }
    @Test void modelFailureAndUnknownRemoteOutcomeAreNotSuccessfulAbstentions() throws Exception {
        var fixture = fixture(3); brokers.remote = "UNKNOWN";
        var result = adapter().run(fixture.id()); assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(1, result.getAsJsonObject("trial").getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt()); assertEquals(1, brokers.dispatched);
    }
    @Test void completedCallWithoutAdapterEventResumesWithoutRedispatchOrLostContext() throws Exception {
        brokers.script = CoordinationSingleAgentTest::investigate; var fixture = fixture(60); brokers.interruptAfterPersist = true;
        assertThrows(IOException.class, () -> adapter().run(fixture.id())); assertEquals(1, brokers.dispatched); assertFalse(Files.exists(run(fixture.id()).resolve("event-1.json")));
        var result = adapter().run(fixture.id()); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(brokers.dispatched, brokers.registered); assertTrue(brokers.contexts.get(1).getAsJsonArray("readMetadata").size() == 1);
    }
    @Test void changedResumedRequestIsRefusedBeforeAnotherRemoteCall() throws Exception {
        brokers.interruptAfterPersist = true; var fixture = fixture(4); assertThrows(IOException.class, () -> adapter().run(fixture.id()));
        Files.writeString(run(fixture.id()).resolve("request-1.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(fixture.id())); assertEquals(1, brokers.dispatched);
    }
    @Test void interruptedRegistrationRetainsUncertainReservationAndNeverRegistersAgain() throws Exception {
        var fixture = fixture(4); brokers.interruptRegistration = true;
        assertThrows(IOException.class, () -> adapter().run(fixture.id())); assertEquals(1, brokers.registered); assertEquals(0, brokers.dispatched);
        var result = adapter().run(fixture.id()); assertEquals("CALL_REGISTRATION_UNCERTAIN", result.get("stopReason").getAsString());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(1, result.getAsJsonObject("trial").getAsJsonObject("usage").get("reservedCalls").getAsInt()); assertEquals(1, brokers.registered); assertEquals(0, brokers.dispatched);
    }
    @Test void aGapInRequiredSourceCoverageCannotBePromotedToAnAnsweredDossier() {
        var pages = new ArrayList<JsonObject>(); var cases = new ArrayList<JsonObject>(); int id = 1;
        for (String caseId : List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)) {
            var citations = new ArrayList<String>();
            for (String alias : List.of("baseline-source", "execution")) {
                String reference = "read-" + id++; citations.add(reference);
                pages.add(json(Map.of("pageId", reference, "caseId", caseId, "inputId", alias, "offset", 0, "nextOffset", 3,
                        "totalCharacters", caseId.equals(CaseInputs.CASE_ID) && alias.equals("baseline-source") ? 8 : 3)));
            }
            cases.add(dossier(caseId, "ANSWERED", citations));
        }
        var finish = BundleInvestigationDecision.parse(decision(Map.of("action", "finish", "cases", cases)));
        assertFalse(CoordinationSingleAgent.supported(finish, pages));
        pages.add(json(Map.of("pageId", "read-5", "caseId", CaseInputs.CASE_ID, "inputId", "baseline-source", "offset", 5, "nextOffset", 8, "totalCharacters", 8)));
        assertFalse(CoordinationSingleAgent.supported(finish, pages));
        pages.add(json(Map.of("pageId", "read-6", "caseId", CaseInputs.CASE_ID, "inputId", "baseline-source", "offset", 3, "nextOffset", 5, "totalCharacters", 8)));
        assertTrue(CoordinationSingleAgent.supported(finish, pages));
    }
    @Test void expiryAndRevocationSurviveControllerRestartAndIssueNoNewCalls() throws Exception {
        var expired = fixture(4); runtime().start(expired.id()); clock.now = clock.now.plusSeconds(1201);
        assertEquals("TRIAL_EXPIRED", adapter().run(expired.id()).get("stopReason").getAsString()); assertEquals(0, brokers.dispatched);
        var revoked = fixture(4); runtime().revoke(revoked.id()); assertEquals("CANCELLED", adapter().run(revoked.id()).getAsJsonObject("trial").get("status").getAsString()); assertEquals(0, brokers.dispatched);
    }
    @Test void changedFinalDossierOrReadLedgerAndMissingReceiptAreRefused() throws Exception {
        var fixture = fixture(4); var result = adapter().run(fixture.id()); Path receipt = run(fixture.id()).resolve("receipt.json"); Files.delete(receipt);
        assertThrows(IllegalArgumentException.class, () -> adapter().retained(fixture.id())); assertEquals(result, adapter().run(fixture.id()));
        Files.writeString(run(fixture.id()).resolve(CaseInputs.CASE_ID + ".json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(fixture.id()));
    }
    @Test void contextTrimsOldContentButKeepsBothCasesAndEveryReadCitationMetadata() throws Exception {
        var fixture = fixture(60); var workload = fixture.definition().workloads().getFirst(); var pages = new ArrayList<JsonObject>();
        for (int i = 1; i <= 59; i++) pages.add(json(Map.of("pageId", "read-" + i, "caseId", i % 2 == 0 ? CaseInputs.CASE_ID : SubscriptionPeriodCase.CASE_ID,
                "inputId", "fixtures", "offset", 0, "nextOffset", 4000, "totalCharacters", 4000, "content", "😀".repeat(2000))));
        String context = CoordinationSingleAgent.context(workload, pages, 1, 19000); assertTrue(context.getBytes(StandardCharsets.UTF_8).length <= 19000);
        var value = JsonParser.parseString(context).getAsJsonObject(); assertEquals(59, value.getAsJsonArray("readMetadata").size()); assertTrue(value.getAsJsonArray("recentPages").size() < 6);
        assertThrows(CoordinationRuntime.Stop.class, () -> CoordinationSingleAgent.context(workload, pages, 1, 100));
    }
    @Test void returnedSchemaUsesStrictForcedFunctionAndNoParallelDelegation() {
        var body = CaseInputs.object(new ModelCallBroker.Request("gpt-test", "input", 2048, BundleInvestigationDecision.PROTOCOL).body());
        assertEquals(BundleInvestigationDecision.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString());
        assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().get("strict").getAsBoolean()); assertFalse(body.get("parallel_tool_calls").getAsBoolean());
        assertEquals(BundleInvestigationDecision.schema(), body.getAsJsonArray("tools").get(0).getAsJsonObject().get("parameters"));
    }
    @Test void policyAndRequestContextAreStableAcrossFreshJvmObjectOrdering() throws Exception {
        var fixture = fixture(4); var workload = fixture.definition().workloads().getFirst(); Path captured = root.resolve("context-fixture.json");
        Files.writeString(captured, CaseInputs.JSON.toJson(Map.of("workload", workload, "pages", List.of(), "remaining", 4)));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                Controller.class.getName(), captured.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue()); String[] lines = output.strip().split("\\R");
        assertEquals(CoordinationRuntime.implementationIdentity(CoordinationSingleAgent.policy()).sha256(), lines[0]);
        assertEquals(CaseInputs.hash(CoordinationSingleAgent.context(workload, List.of(), 4, 64000).getBytes(StandardCharsets.UTF_8)), lines[1]);
    }
    public static final class Controller {
        public static void main(String[] args) throws Exception {
            var input = CaseInputs.object(Files.readAllBytes(Path.of(args[0])));
            var workload = CaseInputs.JSON.fromJson(input.get("workload"), Workload.class);
            var pages = new ArrayList<JsonObject>(); input.getAsJsonArray("pages").forEach(p -> pages.add(p.getAsJsonObject()));
            System.out.println(CoordinationRuntime.implementationIdentity(CoordinationSingleAgent.policy()).sha256());
            System.out.println(CaseInputs.hash(CoordinationSingleAgent.context(workload, pages, input.get("remaining").getAsInt(), 64000).getBytes(StandardCharsets.UTF_8)));
        }
    }
    private final class ScriptedBrokers implements CoordinationRuntime.Brokers {
        int registered, dispatched; boolean interruptAfterPersist, interruptRegistration; String remote = "RESPONSE_RECEIVED";
        BiFunction<JsonObject, Integer, String> script = (input, turn) -> finish(input, false); final List<JsonObject> contexts = new ArrayList<>();
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered++;
                    if (interruptRegistration) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file)); assertTrue(permitted.getAsBoolean()); dispatched++;
                    var input = JsonParser.parseString(CaseInputs.object(Files.readAllBytes(store.resolve(id + "/request.json"))).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    var response = json(Map.of("callId", id, "status", remote.equals("RESPONSE_RECEIVED") ? "COMPLETED" : "UNKNOWN_REMOTE_OUTCOME", "remoteOutcome", remote,
                            "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, dispatched))); DurableExecution.publish(file, response);
                    if (interruptAfterPersist) { interruptAfterPersist = false; throw new IOException("Interrupted controller"); } return response;
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
