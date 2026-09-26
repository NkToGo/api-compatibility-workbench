package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvestigationEvaluationTest {
    @TempDir Path state;
    private final AdaptiveModel model = new AdaptiveModel();
    private int executions;
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private JsonObject tool(String context, String tool, JsonObject arguments) {
        if (tool.equals("describe_case")) return json(Map.of("ok", true, "value", Map.of("caseId", "synthetic")));
        if (tool.equals("read_input")) return json(Map.of("ok", true, "value", Map.of("content", "consumer source")));
        if (tool.equals("run_case")) executions++;
        return json(Map.of("ok", true, "value", Map.of("status", "COMPLETED", "authority", "recorded-execution")));
    }
    private InvestigationEvaluation evaluation() throws IOException { return new InvestigationEvaluation(state, model, this::tool); }
    private String register(InvestigationEvaluation evaluation) throws IOException {
        var contexts = new EnumMap<InvestigationEvaluation.Trial, String>(InvestigationEvaluation.Trial.class);
        for (var trial : InvestigationEvaluation.Trial.values()) contexts.put(trial, "registered-context");
        return evaluation.register(contexts, "gpt-test");
    }
    @Test void trialsExerciseActualInvestigatorAndCompletedSuiteNeverRepeatsCalls() throws Exception {
        var evaluation = evaluation(); String id = register(evaluation);
        var result = evaluation.run(id);
        assertEquals(3, result.getAsJsonArray("trials").size());
        assertEquals(3, result.getAsJsonObject("counts").get("EXPECTED_DECISION").getAsInt());
        assertEquals(8, model.calls.size()); assertEquals(1, executions);
        assertEquals(80, result.get("inputTokens").getAsInt());
        assertEquals(result, evaluation().run(id)); assertEquals(8, model.calls.size()); assertEquals(1, executions);
        assertTrue(model.inputs.stream().noneMatch(input -> input.contains("expectedDecision") || input.contains("SOURCE_UNAVAILABLE")
                || input.contains("EXECUTION_UNAVAILABLE")));
    }
    @Test void interruptedDispatchResumesMappedCallWithoutRegisteringAnother() throws Exception {
        var evaluation = evaluation(); String id = register(evaluation); model.interrupt = true;
        assertThrows(IOException.class, () -> evaluation.run(id));
        assertEquals(1, model.calls.size());
        assertEquals(3, evaluation().run(id).getAsJsonObject("counts").get("EXPECTED_DECISION").getAsInt());
        assertEquals(8, model.calls.size());
    }
    @Test void unknownRemoteOutcomesRemainFailuresNotAbstentions() throws Exception {
        model.failure = true; var evaluation = evaluation(); String id = register(evaluation);
        var result = evaluation.run(id);
        assertEquals(3, result.getAsJsonObject("counts").get("RUN_DID_NOT_COMPLETE").getAsInt());
        assertEquals(3, model.calls.size()); assertEquals(0, executions);
        evaluation.run(id); assertEquals(3, model.calls.size());
    }
    @Test void changedRetainedEvidenceIsRejected() throws Exception {
        var evaluation = evaluation(); String id = register(evaluation); var result = evaluation.run(id);
        String artifact = result.getAsJsonObject("artifacts").keySet().stream().filter(path -> path.endsWith("/result.json")).findFirst().orElseThrow();
        Files.writeString(state.resolve("evaluations").resolve(id).resolve(artifact), "{}");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id));
        assertEquals(8, model.calls.size());
    }
    @Test void failureInjectionCannotBeBypassedByPagingOrReadingRunInsteadOfStartingIt() {
        ConsumerInvestigator.Tools forbidden = (context, tool, arguments) -> { fail("Blocked operation reached delegate"); return null; };
        var source = InvestigationEvaluation.restricted(InvestigationEvaluation.Trial.SOURCE_UNAVAILABLE, forbidden);
        for (int offset : List.of(0, 2000)) assertFalse(source.invoke("context", "read_input",
                json(Map.of("inputId", "baseline-source", "offset", offset))).get("ok").getAsBoolean());
        var execution = InvestigationEvaluation.restricted(InvestigationEvaluation.Trial.EXECUTION_UNAVAILABLE, forbidden);
        for (String tool : List.of("run_case", "read_run")) assertFalse(execution.invoke("context", tool, new JsonObject()).get("ok").getAsBoolean());
    }
    @Test void abstentionWithoutEncounteringTheLimitationDoesNotEarnCredit() {
        var outcome = json(Map.of("status", "ABSTAINED", "events", List.of()));
        assertEquals("UNDEMONSTRATED_ABSTENTION", InvestigationEvaluation.score(InvestigationEvaluation.Trial.SOURCE_UNAVAILABLE, outcome).get("judgment").getAsString());
        assertEquals("UNEXPECTED_ABSTENTION", InvestigationEvaluation.score(InvestigationEvaluation.Trial.EVIDENCE_AVAILABLE, outcome).get("judgment").getAsString());
    }
    @Test void unrelatedToolFailureAndIncompleteExecutionAreNotScoredAsModelMistakes() {
        for (var result : List.of(Map.of("ok", false, "error", "UNAVAILABLE"),
                Map.of("ok", true, "value", Map.of("status", "TIMED_OUT")))) {
            var outcome = json(Map.of("status", "ABSTAINED", "events", List.of(Map.of("tool", "run_case", "arguments", Map.of(), "result", result))));
            assertEquals("EVALUATION_UNRESOLVED", InvestigationEvaluation.score(InvestigationEvaluation.Trial.EVIDENCE_AVAILABLE, outcome).get("judgment").getAsString());
        }
    }
    private static final class AdaptiveModel implements ConsumerInvestigator.Models {
        final Map<String, JsonObject> calls = new LinkedHashMap<>(); final List<String> inputs = new ArrayList<>();
        boolean interrupt, failure;
        public String register(ModelCallBroker.Request request) {
            inputs.add(request.input()); var events = JsonParser.parseString(request.input()).getAsJsonObject().getAsJsonArray("events");
            Object decision;
            if (events.isEmpty()) decision = Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 2000);
            else if (!events.get(events.size() - 1).getAsJsonObject().getAsJsonObject("result").get("ok").getAsBoolean())
                decision = Map.of("action", "finish", "outcome", "ABSTAINED", "summary", "Required evidence is unavailable.", "findings", List.of());
            else if (events.size() == 1) decision = Map.of("action", "run_case");
            else decision = Map.of("action", "finish", "outcome", "INVESTIGATED", "summary", "Recorded evidence is available.",
                    "findings", List.of(Map.of("kind", "HYPOTHESIS", "statement", "Collection handling may explain the failure.", "citations", List.of("event-2"))));
            String id = "call-" + calls.size();
            calls.put(id, failure ? json(Map.of("status", "UNKNOWN_REMOTE_OUTCOME")) : json(Map.of("status", "COMPLETED",
                    "inputTokens", 10, "outputTokens", 20, "outputText", CaseInputs.JSON.toJson(Map.of("decision", decision)))));
            return id;
        }
        public JsonObject execute(String id) throws IOException {
            if (interrupt) { interrupt = false; throw new IOException("Interrupted transport"); }
            return calls.get(id);
        }
    }
}
