package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CustomerRepairEvaluationTest {
    @TempDir Path state;
    Path caseRoot() { return Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID); }
    String create(CustomerRepairEvaluation evaluation, int repetitions) throws Exception {
        return evaluation.create(caseRoot(), caseRoot().resolve("target/dependencies"), RepairVerificationTest.IMAGE, RepairVerificationTest.IMAGE, "gpt-test", repetitions);
    }
    class Flow implements CustomerRepairEvaluation.Flow {
        int agents, generations, executions; boolean interrupt;
        String investigationStatus = "INVESTIGATED", generationStatus = "CANDIDATE_READY", verificationStatus = "COMPLETED";
        String conclusion = "ALL_OBSERVED_REGRESSIONS_FIXED";
        private String register(String kind, Map<String, ?> data) throws IOException {
            String id = kind + "-" + UUID.randomUUID(); Path path = path(kind, id); Files.createDirectories(path);
            DurableExecution.publish(path.resolve("registration.json"), data); return id;
        }
        private Path path(String kind, String id) { return state.resolve(Map.of("agent", "agents", "candidate", "candidates", "verification", "verifications").get(kind)).resolve(id); }
        public String agent(String context, String model) throws IOException {
            agents++; String id = register("agent", Map.of("contextId", context, "model", model));
            DurableExecution.publish(path("agent", id).resolve("session.json"), Map.of("contextId", context, "model", model)); return id;
        }
        public JsonObject investigate(String agent) throws IOException { return result("agent", agent, json(Map.of("status", investigationStatus, "inputTokens", 10, "outputTokens", 2))); }
        public String candidate(String agent, String model, String sdk) throws IOException {
            assertTrue(sdk.contains("PaymentSourceCollection")); return register("candidate", Map.of("agentId", agent,
                    "contextId", read(path("agent", agent).resolve("session.json")).get("contextId")));
        }
        public JsonObject generate(String candidate) throws IOException {
            Path result = path("candidate", candidate).resolve("result.json");
            if (!Files.exists(result)) generations++;
            return result("candidate", candidate, json(Map.of("status", generationStatus, "usage", Map.of("inputTokens", 20, "outputTokens", 3))));
        }
        public String verification(String candidate, Path root, Path dependencies, String image) throws IOException {
            String id = register("verification", Map.of("candidateId", candidate));
            DurableExecution.publish(path("verification", id).resolve("spec.json"), Map.of("candidateId", candidate, "imageId", image)); return id;
        }
        public JsonObject verify(String verification) throws IOException {
            Path directory = path("verification", verification);
            if (Files.exists(directory.resolve("result.json"))) return read(directory.resolve("result.json"));
            if (Files.exists(directory.resolve("started.json"))) return result("verification", verification, json(Map.of("status", "INTERRUPTED")));
            executions++; DurableExecution.publish(directory.resolve("started.json"), Map.of("dispatched", true));
            if (interrupt) { interrupt = false; throw new IOException("Controller interruption"); }
            return result("verification", verification, json(Map.of("status", verificationStatus, "evaluation", Map.of("conclusion", conclusion))));
        }
        private JsonObject result(String kind, String id, JsonObject value) throws IOException {
            Path path = path(kind, id).resolve("result.json"); if (!Files.exists(path)) DurableExecution.publish(path, value); return read(path);
        }
    }
    @Test void freshTrialsRetainAllStagesAndReuseDoesNotDispatch() throws Exception {
        var flow = new Flow(); var evaluation = new CustomerRepairEvaluation(state, flow); String id = create(evaluation, 3); var result = evaluation.run(id);
        assertEquals(3, flow.agents); assertEquals(3, flow.generations); assertEquals(3, flow.executions);
        assertEquals(3, result.getAsJsonObject("counts").get("OBSERVED_REGRESSIONS_FIXED").getAsInt());
        assertEquals(90, result.get("inputTokens").getAsInt()); assertEquals(15, result.get("outputTokens").getAsInt());
        assertEquals(result, new CustomerRepairEvaluation(state, flow).run(id)); assertEquals(3, flow.executions);
        var row = result.getAsJsonArray("trials").get(0).getAsJsonObject();
        Files.writeString(state.resolve("agents/" + row.get("agentId").getAsString() + "/result.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id));
    }
    @Test void interruptedVerificationKeepsChildIdsAndContinuesRemainingTrial() throws Exception {
        var flow = new Flow(); flow.interrupt = true; var evaluation = new CustomerRepairEvaluation(state, flow); String id = create(evaluation, 2);
        assertThrows(IOException.class, () -> evaluation.run(id));
        Path directory = state.resolve("customer-repair-evaluations").resolve(id);
        String verification = read(directory.resolve("verification-1.json")).get("verificationId").getAsString();
        var result = new CustomerRepairEvaluation(state, flow).run(id);
        assertEquals(verification, result.getAsJsonArray("trials").get(0).getAsJsonObject().get("verificationId").getAsString());
        assertEquals(1, result.getAsJsonObject("counts").get("VERIFICATION_INCOMPLETE").getAsInt());
        assertEquals(1, result.getAsJsonObject("counts").get("OBSERVED_REGRESSIONS_FIXED").getAsInt());
        assertEquals(2, flow.agents); assertEquals(2, flow.generations); assertEquals(2, flow.executions);
        assertEquals(result, evaluation.run(id));
    }
    @Test void abstentionAndOperationalFailuresRemainDifferentOutcomes() throws Exception {
        for (String mode : List.of("investigation-abstained", "investigation-failed", "generation-abstained", "generation-failed", "remaining")) {
            var flow = new Flow();
            switch (mode) {
                case "investigation-abstained" -> flow.investigationStatus = "ABSTAINED";
                case "investigation-failed" -> flow.investigationStatus = "MODEL_UNKNOWN_REMOTE_OUTCOME";
                case "generation-abstained" -> flow.generationStatus = "ABSTAINED";
                case "generation-failed" -> flow.generationStatus = "MODEL_UNKNOWN_REMOTE_OUTCOME";
                case "remaining" -> flow.conclusion = "REGRESSIONS_REMAIN";
            }
            var evaluation = new CustomerRepairEvaluation(state, flow); String id = create(evaluation, 1); var result = evaluation.run(id);
            String expected = Map.of("investigation-abstained", "INVESTIGATION_ABSTAINED", "investigation-failed", "INVESTIGATION_FAILED",
                    "generation-abstained", "ABSTAINED", "generation-failed", "GENERATION_FAILED", "remaining", "REGRESSIONS_REMAIN").get(mode);
            assertEquals(1, result.getAsJsonObject("counts").get(expected).getAsInt());
            assertEquals(mode.equals("remaining") ? 1 : 0, flow.executions);
            assertEquals(mode.startsWith("investigation") ? 0 : 1, flow.generations);
        }
    }
    @Test void boundsStartedBudgetAndFinalizedMappingsCannotChange() throws Exception {
        var flow = new Flow(); var evaluation = new CustomerRepairEvaluation(state, flow);
        for (int count : List.of(0, 6)) assertThrows(IllegalArgumentException.class, () -> create(evaluation, count));
        String id = create(evaluation, 1); flow.interrupt = true; assertThrows(IOException.class, () -> evaluation.run(id));
        Path suite = state.resolve("customer-repair-evaluations/" + id + "/suite.json"); Files.writeString(suite, Files.readString(suite) + " ");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(1, flow.generations);
        String complete = create(evaluation, 1); evaluation.run(complete);
        Path mapping = state.resolve("customer-repair-evaluations/" + complete + "/candidate-1.json"); Files.writeString(mapping, Files.readString(mapping) + " ");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(complete));
    }
    @Test void changedStageBindingsStopResumptionBeforeMoreDispatch() throws Exception {
        var flow = new Flow(); flow.interrupt = true; var evaluation = new CustomerRepairEvaluation(state, flow); String id = create(evaluation, 1);
        assertThrows(IOException.class, () -> evaluation.run(id));
        String candidate = read(state.resolve("customer-repair-evaluations/" + id + "/candidate-1.json")).get("candidateId").getAsString();
        Path registration = state.resolve("candidates/" + candidate + "/registration.json");
        var changed = read(registration); changed.addProperty("agentId", "agent-00000000-0000-0000-0000-000000000000"); Files.writeString(registration, changed.toString());
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(1, flow.generations); assertEquals(1, flow.executions);
    }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
