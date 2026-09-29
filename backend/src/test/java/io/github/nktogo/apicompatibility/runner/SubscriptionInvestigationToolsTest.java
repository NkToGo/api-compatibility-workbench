package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionInvestigationToolsTest {
    @TempDir Path state;
    String evidence() throws Exception {
        var verifier = new RepairVerification(state, new SubscriptionRepairEvaluationTest.Sandbox());
        String id = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "bound\n", RepairVerificationTest.IMAGE);
        verifier.execute(id); return id;
    }
    @Test void readsAreBoundedAndNeverExposeCandidateEvidence() throws Exception {
        String evidence = evidence(); var tools = new SubscriptionInvestigationTools(state); String context = tools.register(evidence);
        var description = tools.invoke(context, "describe_case", json(Map.of())).getAsJsonObject("value");
        assertEquals(0, description.get("maxTestRuns").getAsInt());
        assertEquals(SubscriptionInvestigationTools.INPUTS.keySet(), description.getAsJsonObject("inputs").keySet());
        var page = tools.invoke(context, "read_input", page("baseline-source", 0, 4)).getAsJsonObject("value");
        assertEquals("base", page.get("content").getAsString()); assertEquals(4, page.get("nextOffset").getAsInt());
        for (var args : List.of(page("reference-source", 0, 4), page("../candidate.txt", 0, 4), page("baseline-source", 0, 4001), page("baseline-source", 99, 4),
                json(Map.of("inputId", "baseline-source", "offset", 0.5, "maxCharacters", 4))))
            assertEquals("INVALID_REQUEST", tools.invoke(context, "read_input", args).get("error").getAsString());
        var run = tools.invoke(context, "run_case", json(Map.of("requestId", "trial")));
        assertEquals(run, tools.invoke(context, "read_run", json(Map.of("requestId", "other"))));
        assertEquals(Set.of("baseline", "target"), run.getAsJsonObject("value").getAsJsonObject("observations").keySet());
        assertFalse(run.toString().contains("candidate"));
        assertEquals("INVALID_REQUEST", tools.invoke(context, "run_case", json(Map.of("requestId", "trial", "verificationId", evidence))).get("error").getAsString());
    }
    @Test void changedEvidenceCannotBeReadOrRegistered() throws Exception {
        String evidence = evidence(); var tools = new SubscriptionInvestigationTools(state); String context = tools.register(evidence);
        Files.writeString(state.resolve("verifications/" + evidence + "/inputs/baseline.txt"), "changed");
        assertEquals("INPUT_IDENTITY_MISMATCH", tools.invoke(context, "read_run", json(Map.of("requestId", "trial"))).get("error").getAsString());
        assertThrows(IllegalArgumentException.class, () -> tools.register(evidence));
        assertThrows(IllegalArgumentException.class, () -> tools.register("../escape"));
    }
    @Test void unavailableEvidenceSupportsAbstentionButChangedEvidenceBlocksCompletion() throws Exception {
        String evidence = evidence(); String context = new SubscriptionInvestigationTools(state).register(evidence);
        var tools = ConsumerInvestigator.toolBackend(state);
        var models = new Models(List.of(json(Map.of("action", "read_run")),
                json(Map.of("action", "finish", "outcome", "ABSTAINED", "summary", "Execution unavailable.", "findings", List.of(
                        Map.of("kind", "LIMITATION", "statement", "Required execution could not be read.", "citations", List.of("event-1")))))));
        var unavailable = new ConsumerInvestigator(state.resolve("agents"), models, (id, tool, args) -> tool.equals("read_run")
                ? json(Map.of("ok", false, "error", "UNAVAILABLE")) : tools.invoke(id, tool, args), Clock.systemUTC());
        var result = unavailable.run(unavailable.create(context, "gpt-test"));
        assertEquals("ABSTAINED", result.get("status").getAsString());
        var changed = new Models(List.of(json(Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000)),
                json(Map.of("action", "run_case")),
                json(Map.of("action", "finish", "outcome", "INVESTIGATED", "summary", "Review.", "findings", List.of(
                        Map.of("kind", "HYPOTHESIS", "statement", "Selection may be wrong.", "citations", List.of("event-2"))))))) {
            public JsonObject execute(String id) throws IOException {
                if (id.equals("call-2")) Files.writeString(state.resolve("verifications/" + evidence + "/inputs/baseline.txt"), "changed");
                return super.execute(id);
            }
        };
        var investigator = new ConsumerInvestigator(state.resolve("agents"), changed, tools, Clock.systemUTC());
        assertEquals("EVIDENCE_UNAVAILABLE", investigator.run(investigator.create(context, "gpt-test")).get("status").getAsString());
    }
    @Test void sharedInvestigatorResumesMappedCallsAndReturnsCitedAdvisoryFindings() throws Exception {
        String context = new SubscriptionInvestigationTools(state).register(evidence());
        var models = new Models(List.of(
                json(Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000)),
                json(Map.of("action", "read_run")),
                json(Map.of("action", "finish", "outcome", "INVESTIGATED", "summary", "Review period selection.", "findings", List.of(
                        Map.of("kind", "HYPOTHESIS", "statement", "Period selection may cause the failures.", "citations", List.of("event-1", "event-2")))))));
        var investigator = new ConsumerInvestigator(state.resolve("agents"), models, ConsumerInvestigator.toolBackend(state), Clock.systemUTC());
        String agent = investigator.create(context, "gpt-test"); models.interrupt = true;
        assertThrows(IOException.class, () -> investigator.run(agent));
        var resumed = new ConsumerInvestigator(state.resolve("agents"), models, ConsumerInvestigator.toolBackend(state), Clock.systemUTC());
        var result = resumed.run(agent); assertEquals("INVESTIGATED", result.get("status").getAsString());
        assertEquals("model-findings-not-execution-evidence", result.get("authority").getAsString());
        assertEquals(result, resumed.run(agent)); assertEquals(3, models.calls.size());
    }
    static class Models implements ConsumerInvestigator.Models {
        final Queue<JsonObject> outputs; final Map<String, JsonObject> calls = new LinkedHashMap<>(); boolean interrupt;
        Models(List<JsonObject> decisions) { outputs = new ArrayDeque<>(decisions); }
        public String register(ModelCallBroker.Request request) {
            String id = "call-" + calls.size(); calls.put(id, json(Map.of("status", "COMPLETED", "inputTokens", 10, "outputTokens", 10,
                    "outputText", json(Map.of("decision", outputs.remove())).toString()))); return id;
        }
        public JsonObject execute(String id) throws IOException { if (interrupt) { interrupt = false; throw new IOException("Interrupted"); } return calls.get(id); }
    }
    static JsonObject page(String id, int offset, int maximum) { return json(Map.of("inputId", id, "offset", offset, "maxCharacters", maximum)); }
    static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
