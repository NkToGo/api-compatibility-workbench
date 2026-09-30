package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MissingEvidenceEvaluationTest {
    @TempDir Path state;
    private final AdaptiveModel model = new AdaptiveModel();
    private final Map<String, MissingEvidenceEvaluation.Case> cases = new HashMap<>();
    private Path retained;
    private int executions;
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private MissingEvidenceEvaluation evaluation() throws IOException { return new MissingEvidenceEvaluation(state, model, this::tool); }
    private List<MissingEvidenceEvaluation.Trial> trials(int repetitions) throws IOException {
        String verification = "verification-" + UUID.randomUUID(); retained = Files.createDirectories(state.resolve("verifications").resolve(verification));
        Files.writeString(retained.resolve("observations.json"), "EXECUTION_MARKER");
        var trials = new ArrayList<MissingEvidenceEvaluation.Trial>();
        for (var kind : MissingEvidenceEvaluation.Case.values()) for (var condition : MissingEvidenceEvaluation.Condition.values()) for (int i = 1; i <= repetitions; i++) {
            boolean customer = kind == MissingEvidenceEvaluation.Case.CUSTOMER_SOURCES;
            String context = (customer ? "investigation-" : "subscription-investigation-") + UUID.randomUUID(); cases.put(context, kind);
            Path directory = Files.createDirectories(state.resolve(customer ? "investigations" : "subscription-investigations").resolve(context));
            CaseInputs.write(directory.resolve("registration.json"), Map.of("contextId", context, "verificationId", verification));
            Files.createDirectory(directory.resolve("inputs")); Files.writeString(directory.resolve("inputs/baseline-source"), "SOURCE_MARKER");
            trials.add(new MissingEvidenceEvaluation.Trial(kind, condition, i, context));
        }
        return trials;
    }
    private JsonObject tool(String context, String tool, JsonObject arguments) {
        if (tool.equals("describe_case")) return json(Map.of("ok", true, "value", Map.of("caseId",
                cases.get(context) == MissingEvidenceEvaluation.Case.CUSTOMER_SOURCES ? CaseInputs.CASE_ID : SubscriptionPeriodCase.CASE_ID)));
        if (tool.equals("read_input")) return json(Map.of("ok", true, "value", Map.of("content", "SOURCE_MARKER")));
        executions++; return json(Map.of("ok", true, "value", Map.of("status", "COMPLETED", "authority", "recorded-execution", "observations", "EXECUTION_MARKER")));
    }
    private Path directory(String id) { return state.resolve("missing-evidence-evaluations").resolve(id); }
    @Test void twelveFreshTrialsKeepConditionsAndWithheldEvidenceOutsideModelInputs() throws Exception {
        var evaluation = evaluation(); String id = evaluation.register(trials(3), "gpt-test", 3); var result = evaluation.run(id);
        assertEquals(12, result.getAsJsonArray("trials").size());
        assertEquals(12, result.getAsJsonObject("counts").get("ABSTAINED_AFTER_WITHHELD_EVIDENCE").getAsInt());
        assertEquals(42, model.calls.size()); assertEquals(0, executions);
        assertEquals(420, result.get("inputTokens").getAsInt()); assertEquals(840, result.get("outputTokens").getAsInt());
        var agents = new HashSet<String>();
        for (var item : result.getAsJsonArray("trials")) {
            var row = item.getAsJsonObject(); assertTrue(agents.add(row.get("agentId").getAsString()));
            assertEquals("PENDING", row.get("semanticReview").getAsString());
            assertTrue(row.get("encounteredInjectedFailure").getAsBoolean()); assertFalse(row.get("unexpectedToolFailure").getAsBoolean());
        }
        assertTrue(model.inputs.stream().noneMatch(s -> s.contains("SOURCE_UNAVAILABLE") || s.contains("EXECUTION_UNAVAILABLE")
                || s.contains("expectedDecision") || s.contains("EXECUTION_MARKER")));
        var suite = CaseInputs.object(Files.readAllBytes(directory(id).resolve("suite.json")));
        assertEquals(144, suite.get("maxModelCalls").getAsInt());
        assertEquals(result, evaluation().run(id)); assertEquals(42, model.calls.size());
    }
    @Test void interruptionAndActiveResponsesResumeExistingAgentAndCallMappings() throws Exception {
        for (boolean active : List.of(false, true)) {
            var evaluation = evaluation(); String id = evaluation.register(trials(1), "gpt-test", 1);
            int initial = model.calls.size(); model.active = active; model.interrupt = !active;
            if (active) assertEquals("ACTIVE", evaluation.run(id).get("status").getAsString());
            else assertThrows(IOException.class, () -> evaluation.run(id));
            assertEquals(initial + 1, model.calls.size());
            String mapping = Files.readString(directory(id).resolve("trial-1/agent.json"));
            assertEquals(4, evaluation().run(id).getAsJsonObject("counts").get("ABSTAINED_AFTER_WITHHELD_EVIDENCE").getAsInt());
            assertEquals(initial + 14, model.calls.size()); assertEquals(mapping, Files.readString(directory(id).resolve("trial-1/agent.json")));
        }
    }
    @Test void unknownModelOutcomesRemainTechnicalFailuresWithNoAbstentionCredit() throws Exception {
        model.failure = true; var evaluation = evaluation(); String id = evaluation.register(trials(3), "gpt-test", 3);
        var result = evaluation.run(id);
        assertEquals(12, result.getAsJsonObject("counts").get("INVESTIGATION_FAILED").getAsInt());
        assertEquals(12, model.calls.size()); assertEquals(0, executions);
        assertEquals(0, result.get("inputTokens").getAsInt()); assertTrue(result.get("usageScope").getAsString().contains("unknown"));
        evaluation.run(id); assertEquals(12, model.calls.size());
    }
    @Test void sourcePagingReferenceAliasesAndBothExecutionAliasesCannotReachTheDelegate() {
        ConsumerInvestigator.Tools forbidden = (context, tool, arguments) -> { fail("Withheld operation reached delegate"); return null; };
        var source = MissingEvidenceEvaluation.restricted(MissingEvidenceEvaluation.Condition.SOURCE_UNAVAILABLE, forbidden);
        for (String input : List.of("baseline-source", "reference-source")) for (int offset : List.of(0, 2000))
            assertFalse(source.invoke("context", "read_input", json(Map.of("inputId", input, "offset", offset))).get("ok").getAsBoolean());
        var execution = MissingEvidenceEvaluation.restricted(MissingEvidenceEvaluation.Condition.EXECUTION_UNAVAILABLE, forbidden);
        for (String tool : List.of("run_case", "read_run")) assertFalse(execution.invoke("context", tool, new JsonObject()).get("ok").getAsBoolean());
    }
    @Test void matrixBoundsDuplicatesAndCaseBindingsAreRejectedBeforeDispatch() throws Exception {
        var evaluation = evaluation(); var trials = trials(1);
        for (int repetitions : List.of(0, 6)) assertThrows(IllegalArgumentException.class, () -> evaluation.register(trials, "gpt-test", repetitions));
        var duplicate = new ArrayList<>(trials); duplicate.set(3, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> evaluation.register(duplicate, "gpt-test", 1));
        var mismatched = new ArrayList<>(trials); var last = mismatched.get(3);
        mismatched.set(3, new MissingEvidenceEvaluation.Trial(MissingEvidenceEvaluation.Case.CUSTOMER_SOURCES, last.condition(), 1, last.contextId()));
        assertThrows(IllegalArgumentException.class, () -> evaluation.register(mismatched, "gpt-test", 1));
        assertEquals(0, model.calls.size());
    }
    @Test void changedSuiteInputsOrRetainedExecutionStopResumeBeforeFurtherModelRegistration() throws Exception {
        for (String changed : List.of("suite", "input", "execution", "agent")) {
            var evaluation = evaluation(); var trials = trials(1); String id = evaluation.register(trials, "gpt-test", 1);
            int initial = model.calls.size(); model.interrupt = true;
            assertThrows(IOException.class, () -> evaluation.run(id)); assertEquals(initial + 1, model.calls.size());
            if (changed.equals("suite")) {
                Path path = directory(id).resolve("suite.json"); var suite = CaseInputs.object(Files.readAllBytes(path)); suite.addProperty("model", "gpt-other"); Files.writeString(path, CaseInputs.JSON.toJson(suite));
            } else if (changed.equals("input")) Files.writeString(state.resolve("subscription-investigations").resolve(trials.get(3).contextId()).resolve("inputs/baseline-source"), "changed");
            else if (changed.equals("execution")) Files.writeString(retained.resolve("observations.json"), "changed");
            else {
                String agent = CaseInputs.object(Files.readAllBytes(directory(id).resolve("trial-1/agent.json"))).get("agentId").getAsString();
                Path path = directory(id).resolve("trial-1/agents").resolve(agent).resolve("session.json");
                var session = CaseInputs.object(Files.readAllBytes(path)); session.addProperty("contextId", trials.get(1).contextId()); Files.writeString(path, CaseInputs.JSON.toJson(session));
            }
            assertThrows(IllegalArgumentException.class, () -> evaluation().run(id)); assertEquals(initial + 1, model.calls.size());
        }
    }
    @Test void finalizedEventsContextAndExecutionArtifactsCannotBeSilentlyReplaced() throws Exception {
        for (String changed : List.of("event", "context", "execution")) {
            var evaluation = evaluation(); var trials = trials(1); String id = evaluation.register(trials, "gpt-test", 1); var result = evaluation.run(id);
            int calls = model.calls.size();
            if (changed.equals("event")) {
                String artifact = result.getAsJsonObject("artifacts").keySet().stream().filter(s -> s.endsWith("event-1.json")).findFirst().orElseThrow();
                Files.writeString(directory(id).resolve(artifact), "{}");
            } else if (changed.equals("context")) Files.writeString(state.resolve("investigations").resolve(trials.get(0).contextId()).resolve("registration.json"), "{}");
            else Files.writeString(retained.resolve("observations.json"), "changed");
            assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(calls, model.calls.size());
        }
    }
    @Test void unrelatedFailuresPrematureAbstentionsAndUnexpectedCompletionsAreNotCredited() {
        var condition = MissingEvidenceEvaluation.Condition.SOURCE_UNAVAILABLE;
        var empty = json(Map.of("status", "ABSTAINED", "events", List.of()));
        assertEquals("UNDEMONSTRATED_ABSTENTION", MissingEvidenceEvaluation.score(condition, empty).get("decisionCheck").getAsString());
        for (var response : List.of(Map.of("ok", false, "error", "UNAVAILABLE"), Map.of("ok", true, "value", Map.of("status", "TIMED_OUT")))) {
            var outcome = json(Map.of("status", "ABSTAINED", "events", List.of(Map.of("tool", "run_case", "arguments", Map.of(), "result", response))));
            assertEquals("TECHNICAL_UNRESOLVED", MissingEvidenceEvaluation.score(condition, outcome).get("decisionCheck").getAsString());
        }
        empty.addProperty("status", "INVESTIGATED");
        assertEquals("UNEXPECTED_COMPLETION", MissingEvidenceEvaluation.score(condition, empty).get("decisionCheck").getAsString());
    }
    private static final class AdaptiveModel implements ConsumerInvestigator.Models {
        final Map<String, JsonObject> calls = new LinkedHashMap<>(); final List<String> inputs = new ArrayList<>();
        boolean interrupt, active, failure;
        public String register(ModelCallBroker.Request request) {
            assertEquals("investigation-v2", request.protocol()); inputs.add(request.input());
            var events = JsonParser.parseString(request.input()).getAsJsonObject().getAsJsonArray("events"); Object decision;
            if (events.isEmpty()) decision = Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 2000);
            else {
                var last = events.get(events.size() - 1).getAsJsonObject(); boolean source = last.get("tool").getAsString().equals("read_input");
                if (last.getAsJsonObject("result").get("ok").getAsBoolean()) decision = Map.of("action", "run_case");
                else if (events.size() == 1 || events.get(events.size() - 2).getAsJsonObject().getAsJsonObject("result").get("ok").getAsBoolean())
                    decision = source ? Map.of("action", "read_input", "inputId", "baseline-source", "offset", 2000, "maxCharacters", 2000) : Map.of("action", "read_run");
                else decision = Map.of("action", "finish", "outcome", "ABSTAINED", "summary", "Required evidence is unavailable.",
                        "findings", List.of(Map.of("kind", "LIMITATION", "statement", "The required read failed twice.", "citations", List.of(last.get("eventId").getAsString()))));
            }
            String id = "call-" + calls.size(); calls.put(id, failure ? json(Map.of("status", "UNKNOWN_REMOTE_OUTCOME"))
                    : json(Map.of("status", "COMPLETED", "inputTokens", 10, "outputTokens", 20, "outputText", CaseInputs.JSON.toJson(Map.of("decision", decision))))); return id;
        }
        public JsonObject execute(String id) throws IOException {
            if (interrupt) { interrupt = false; throw new IOException("Interrupted transport"); }
            if (active) { active = false; return json(Map.of("status", "ACTIVE")); }
            return calls.get(id);
        }
    }
}
