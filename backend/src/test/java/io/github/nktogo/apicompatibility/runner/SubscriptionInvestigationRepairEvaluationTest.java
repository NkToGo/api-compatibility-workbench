package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionInvestigationRepairEvaluationTest {
    @TempDir Path state;
    String evidence() throws Exception {
        var verifier = new RepairVerification(state, new SubscriptionRepairEvaluationTest.Sandbox());
        String id = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "baseline\n", RepairVerificationTest.IMAGE);
        verifier.execute(id); return id;
    }
    String create(SubscriptionInvestigationRepairEvaluation evaluation, int count) throws Exception {
        return evaluation.create(evidence(), "gpt-test", RepairVerificationTest.IMAGE, count);
    }
    class Flow implements SubscriptionInvestigationRepairEvaluation.Flow {
        int agents, investigations, generations, executions;
        String investigationStatus = "INVESTIGATED", generationStatus = "CANDIDATE_READY", conclusion = "ALL_OBSERVED_REGRESSIONS_FIXED";
        String pause = "", badBinding = ""; boolean interruptVerification, interruptInvestigation;
        private String id(String kind) { return kind + "-" + UUID.randomUUID(); }
        private Path path(String kind, String id) { return state.resolve(Map.of("agent", "agents", "candidate", "subscription-candidates", "verification", "verifications").get(kind)).resolve(id); }
        public String agent(String context, String model) throws IOException {
            agents++; String id = id("agent"); Path path = Files.createDirectories(path("agent", id));
            DurableExecution.publish(path.resolve("session.json"), Map.of("agentId", id, "contextId", context, "model", model,
                    "case", new SubscriptionInvestigationTools(state).invoke(context, "describe_case", new JsonObject()), "protocol", "investigation-v2", "maxDecisions", 12));
            if (badBinding.equals("agent")) change(path.resolve("session.json"), "model", "other-model"); return id;
        }
        private String model(String model, String protocol, int output) throws IOException {
            return new ModelCallBroker(state.resolve("model-calls")).register(new ModelCallBroker.Request(model, "fixture", output, protocol), Duration.ofMinutes(2));
        }
        public JsonObject investigate(String agent) throws IOException {
            Path directory = path("agent", agent);
            if (Files.exists(directory.resolve("result.json"))) return read(directory.resolve("result.json"));
            if (pause.equals("agent")) return json(Map.of("status", "ACTIVE"));
            if (!Files.exists(directory.resolve("call-1.json"))) {
                investigations++; String call = model("gpt-test", "investigation-v2", 2048);
                DurableExecution.publish(directory.resolve("call-1.json"), Map.of("callId", call));
                if (interruptInvestigation) { interruptInvestigation = false; throw new IOException("Interrupted request"); }
            } else return result("agent", agent, json(Map.of("status", "MODEL_UNKNOWN_REMOTE_OUTCOME", "inputTokens", 0, "outputTokens", 0)));
            return result("agent", agent, json(Map.of("status", investigationStatus, "inputTokens", 10, "outputTokens", 2)));
        }
        public String candidate(String agent, String model) throws IOException {
            var session = read(path("agent", agent).resolve("session.json")); String context = session.get("contextId").getAsString();
            String evidence = read(state.resolve("subscription-investigations/" + context + "/registration.json")).get("verificationId").getAsString();
            String id = id("candidate"); Path directory = Files.createDirectories(path("candidate", id));
            String call = model(model, "subscription-repair-v1", 4096);
            DurableExecution.publish(directory.resolve("registration.json"), Map.of("verificationId", evidence, "modelCallId", call,
                    "investigation", Map.of("agentId", badBinding.equals("candidate") ? id("agent") : agent, "contextId", context)));
            if (badBinding.equals("model")) change(state.resolve("model-calls/" + call + "/request.json"), "model", "other-model"); return id;
        }
        public JsonObject generate(String candidate) throws IOException {
            if (pause.equals("candidate")) return json(Map.of("status", "ACTIVE"));
            if (!Files.exists(path("candidate", candidate).resolve("result.json"))) generations++;
            return result("candidate", candidate, json(Map.of("status", generationStatus, "usage", Map.of("inputTokens", 20, "outputTokens", 3))));
        }
        public String verification(String candidate, String image) throws IOException {
            String id = id("verification"); Path directory = Files.createDirectories(path("verification", id));
            DurableExecution.publish(directory.resolve("spec.json"), Map.of("verificationId", id, "candidateId", candidate,
                    "imageId", badBinding.equals("verification") ? "sha256:" + "0".repeat(64) : image, "protocol", "subscription-period-v1",
                    "candidateManifestSha256", CaseInputs.hash(Files.readAllBytes(path("candidate", candidate).resolve("result.json"))))); return id;
        }
        public JsonObject verify(String verification) throws IOException {
            Path directory = path("verification", verification);
            if (Files.exists(directory.resolve("result.json"))) return read(directory.resolve("result.json"));
            if (pause.equals("verification")) return json(Map.of("status", "ACTIVE"));
            if (Files.exists(directory.resolve("started.json"))) return result("verification", verification, json(Map.of("status", "INTERRUPTED")));
            executions++; DurableExecution.publish(directory.resolve("started.json"), Map.of("dispatched", true));
            if (interruptVerification) { interruptVerification = false; throw new IOException("Interrupted verification"); }
            return result("verification", verification, json(Map.of("status", "COMPLETED", "evaluation", Map.of("conclusion", conclusion))));
        }
        private JsonObject result(String kind, String id, JsonObject value) throws IOException {
            Path path = path(kind, id).resolve("result.json"); if (!Files.exists(path)) DurableExecution.publish(path, value); return read(path);
        }
    }
    @Test void threeFreshTrialsRetainStagesUsageAndModelRecordsWithoutReplay() throws Exception {
        var flow = new Flow(); var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 3);
        var result = evaluation.run(id); assertEquals(3, flow.agents); assertEquals(3, flow.investigations); assertEquals(3, flow.generations); assertEquals(3, flow.executions);
        assertEquals(3, result.getAsJsonObject("counts").get("OBSERVED_REGRESSIONS_FIXED").getAsInt());
        assertEquals(90, result.get("inputTokens").getAsInt()); assertEquals(15, result.get("outputTokens").getAsInt()); assertEquals(6, result.get("registeredModelCalls").getAsInt());
        assertEquals("PENDING", result.get("semanticReview").getAsString());
        var contexts = new HashSet<String>(); for (var row : result.getAsJsonArray("trials")) contexts.add(row.getAsJsonObject().get("contextId").getAsString());
        assertEquals(3, contexts.size()); assertEquals(result, new SubscriptionInvestigationRepairEvaluation(state, flow).run(id));
        assertEquals(3, flow.investigations); assertEquals(3, flow.generations); assertEquals(3, flow.executions);
    }
    @Test void unknownInvestigationIsNotReplayedAndRemainingTrialsProceed() throws Exception {
        var flow = new Flow(); flow.interruptInvestigation = true; var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 2);
        assertThrows(IOException.class, () -> evaluation.run(id)); String agent = mapping(id, "agent");
        var result = new SubscriptionInvestigationRepairEvaluation(state, flow).run(id);
        assertEquals(agent, result.getAsJsonArray("trials").get(0).getAsJsonObject().get("agentId").getAsString());
        assertEquals(1, result.getAsJsonObject("counts").get("INVESTIGATION_FAILED").getAsInt());
        assertEquals(1, result.getAsJsonObject("counts").get("OBSERVED_REGRESSIONS_FIXED").getAsInt());
        assertEquals(2, flow.investigations); assertEquals(1, flow.generations); assertEquals(1, flow.executions);
    }
    @Test void interruptedVerificationKeepsMappingsAndDoesNotRepeatModelOrExecution() throws Exception {
        var flow = new Flow(); flow.interruptVerification = true; var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 2);
        assertThrows(IOException.class, () -> evaluation.run(id)); String verification = mapping(id, "verification");
        var result = evaluation.run(id); assertEquals(verification, result.getAsJsonArray("trials").get(0).getAsJsonObject().get("verificationId").getAsString());
        assertEquals(1, result.getAsJsonObject("counts").get("VERIFICATION_INCOMPLETE").getAsInt()); assertEquals(2, flow.generations); assertEquals(2, flow.executions);
        assertEquals(result, evaluation.run(id)); assertEquals(2, flow.executions);
    }
    @Test void abstentionsOperationalFailuresAndRemainingRegressionsStayDistinct() throws Exception {
        var modes = Map.of("investigation-abstained", "INVESTIGATION_ABSTAINED", "investigation-failed", "INVESTIGATION_FAILED",
                "generation-abstained", "ABSTAINED", "generation-failed", "GENERATION_FAILED", "invalid", "INVALID_PROPOSAL", "remaining", "REGRESSIONS_REMAIN");
        for (var mode : modes.entrySet()) {
            var flow = new Flow();
            switch (mode.getKey()) {
                case "investigation-abstained" -> flow.investigationStatus = "ABSTAINED";
                case "investigation-failed" -> flow.investigationStatus = "MODEL_UNKNOWN_REMOTE_OUTCOME";
                case "generation-abstained" -> flow.generationStatus = "ABSTAINED";
                case "generation-failed" -> flow.generationStatus = "MODEL_UNKNOWN_REMOTE_OUTCOME";
                case "invalid" -> flow.generationStatus = "INVALID_PROPOSAL";
                case "remaining" -> flow.conclusion = "REGRESSIONS_REMAIN";
            }
            var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); var result = evaluation.run(create(evaluation, 1));
            assertEquals(1, result.getAsJsonObject("counts").get(mode.getValue()).getAsInt());
            assertEquals(mode.getKey().startsWith("investigation") ? 0 : 1, flow.generations); assertEquals(mode.getKey().equals("remaining") ? 1 : 0, flow.executions);
        }
    }
    @Test void wrongStageBindingsAreRejectedBeforeTheirDispatch() throws Exception {
        for (String kind : List.of("agent", "candidate", "model", "verification")) {
            var flow = new Flow(); flow.badBinding = kind; var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 1);
            assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(0, flow.executions);
            assertEquals(kind.equals("verification") ? 1 : 0, flow.generations); assertEquals(kind.equals("agent") ? 0 : 1, flow.investigations);
        }
    }
    @Test void activeStagesResumeWithSameIdsAndChangedRegistrationsStopDispatch() throws Exception {
        for (String kind : List.of("agent", "candidate", "verification")) {
            var flow = new Flow(); flow.pause = kind; var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 1);
            assertEquals("ACTIVE", evaluation.run(id).get("status").getAsString()); String child = mapping(id, kind);
            flow.pause = ""; assertEquals("COMPLETED", evaluation.run(id).get("status").getAsString()); assertEquals(child, mapping(id, kind));
            var paused = new Flow(); paused.pause = kind; var other = new SubscriptionInvestigationRepairEvaluation(state, paused); String pending = create(other, 1); other.run(pending);
            String stage = mapping(pending, kind); Path registration = state.resolve(Map.of("agent", "agents", "candidate", "subscription-candidates", "verification", "verifications").get(kind)).resolve(stage)
                    .resolve(Map.of("agent", "session.json", "candidate", "registration.json", "verification", "spec.json").get(kind));
            Files.writeString(registration, Files.readString(registration) + " "); paused.pause = "";
            assertThrows(IllegalArgumentException.class, () -> other.run(pending)); assertEquals(0, paused.executions);
        }
    }
    @Test void boundsSealsAndDuplicateContextsAreRejected() throws Exception {
        var flow = new Flow(); var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String evidence = evidence();
        for (int count : List.of(0, 6)) assertThrows(IllegalArgumentException.class, () -> evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, count));
        assertThrows(IllegalArgumentException.class, () -> evaluation.create(evidence, "gpt-test", "latest", 1));
        String id = create(evaluation, 1); flow.pause = "agent"; evaluation.run(id);
        Path suite = directory(id).resolve("suite.json"); Files.writeString(suite, Files.readString(suite) + " ");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(0, flow.investigations);
        String duplicate = create(evaluation, 2); Path path = directory(duplicate).resolve("suite.json"); var value = read(path);
        value.getAsJsonArray("contexts").set(1, value.getAsJsonArray("contexts").get(0).deepCopy()); Files.writeString(path, value.toString());
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(duplicate)); assertEquals(1, flow.agents);
    }
    @Test void originalInputsAndContextMutationStopDispatchAndCompletedReuse() throws Exception {
        for (String kind : List.of("evidence", "context")) for (boolean completed : List.of(false, true)) {
            var flow = new Flow(); if (!completed) flow.pause = "agent";
            var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 1); evaluation.run(id); var suite = read(directory(id).resolve("suite.json"));
            Path file = kind.equals("evidence") ? state.resolve("verifications/" + suite.get("verificationId").getAsString() + "/inputs/baseline.txt")
                    : state.resolve("subscription-investigations/" + suite.getAsJsonArray("contexts").get(0).getAsJsonObject().get("contextId").getAsString() + "/registration.json");
            Files.writeString(file, Files.readString(file) + " "); flow.pause = "";
            assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(completed ? 1 : 0, flow.investigations);
        }
    }
    @Test void finalizedChildMappingsAndBrokerRecordsCannotBeChanged() throws Exception {
        for (String kind : List.of("agent", "candidate", "verification", "mapping", "model")) {
            var flow = new Flow(); var evaluation = new SubscriptionInvestigationRepairEvaluation(state, flow); String id = create(evaluation, 1); var result = evaluation.run(id);
            var row = result.getAsJsonArray("trials").get(0).getAsJsonObject();
            Path path;
            if (kind.equals("mapping")) path = directory(id).resolve("candidate-1.json");
            else if (kind.equals("model")) path = state.resolve("model-calls/" + row.getAsJsonObject("modelCallArtifacts").keySet().iterator().next() + "/request.json");
            else path = state.resolve(Map.of("agent", "agents", "candidate", "subscription-candidates", "verification", "verifications").get(kind)).resolve(row.get(kind + "Id").getAsString()).resolve("result.json");
            Files.writeString(path, Files.readString(path) + " "); assertThrows(IllegalArgumentException.class, () -> evaluation.run(id));
            assertEquals(1, flow.investigations); assertEquals(1, flow.generations); assertEquals(1, flow.executions);
        }
    }
    private Path directory(String id) { return state.resolve("subscription-investigation-repair-evaluations").resolve(id); }
    private String mapping(String id, String kind) throws IOException { return read(directory(id).resolve(kind + "-1.json")).get(kind + "Id").getAsString(); }
    private static void change(Path path, String key, String value) throws IOException { var data = read(path); data.addProperty(key, value); Files.writeString(path, data.toString()); }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
