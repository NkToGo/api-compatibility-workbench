package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "workbench.container.image", matches = "sha256:[0-9a-f]{64}")
class InvestigationToolsContainerTest {
    @Test void parallelBatchRunsTwoIndependentContainersWithSyntheticModelDecisions() throws Exception {
        Path state = Path.of(System.getProperty("workbench.repository"), "backend/target/investigation-test-runs", UUID.randomUUID().toString());
        var runtime = new ConsumerTasks.Backends() {
            final CountDownLatch ready = new CountDownLatch(2);
            public ConsumerInvestigator.Tools tools(Path taskState) throws IOException {
                var delegate = ConsumerInvestigator.toolBackend(taskState);
                return (context, tool, arguments) -> {
                    if (tool.equals("run_case")) {
                        ready.countDown();
                        try { CaseInputs.require(ready.await(30, TimeUnit.SECONDS), "Parallel worker not admitted"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Container test interrupted", e); }
                    }
                    return delegate.invoke(context, tool, arguments);
                };
            }
            public ConsumerInvestigator.Models models(Path taskState) throws IOException {
                var broker = new ModelCallBroker(taskState.resolve("model-calls"));
                var responses = new HashMap<String, JsonObject>();
                return new ConsumerInvestigator.Models() {
                    public String register(ModelCallBroker.Request request) throws IOException {
                        int turn = 13 - CaseInputs.object(request.input().getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("decisionsRemaining").getAsInt();
                        Object decision = turn == 1 ? Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000)
                                : turn == 2 ? Map.of("action", "run_case")
                                : Map.of("action", "finish", "outcome", "INVESTIGATED", "summary", "Recorded synthetic case results.",
                                "findings", List.of(Map.of("kind", "HYPOTHESIS", "statement", "Review the observed target failures.", "citations", List.of("event-1", "event-2"))));
                        String id = broker.register(request, Duration.ofMinutes(2));
                        responses.put(id, CaseInputs.JSON.toJsonTree(Map.of("status", "COMPLETED", "inputTokens", 10,
                                "outputTokens", 10, "outputText", CaseInputs.JSON.toJson(Map.of("decision", decision)))).getAsJsonObject());
                        return id;
                    }
                    public JsonObject execute(String id) throws IOException {
                        Path result = taskState.resolve("model-calls/" + id + "/result.json");
                        if (!Files.exists(result)) DurableExecution.publish(result, responses.get(id));
                        return CaseInputs.object(Files.readAllBytes(result));
                    }
                };
            }
        };
        var tasks = new ConsumerTasks(state, Clock.systemUTC(), runtime); Path source = InvestigationToolsTest.caseRoot();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 2; i++) ids.add(tasks.registerCustomer(source, source.resolve("target/dependencies"), System.getProperty("workbench.container.image"), "gpt-test"));
        var scheduler = new ParallelInvestigations(state, Clock.systemUTC(), runtime);
        String batch = scheduler.register(ids, 2, 6); var result = scheduler.run(batch);
        assertEquals("COMPLETED", result.get("status").getAsString()); assertEquals(6, result.getAsJsonObject("usage").get("completedResponses").getAsInt());
        var runs = new HashSet<String>();
        for (String id : ids) {
            assertEquals("INVESTIGATED", result.getAsJsonObject("children").getAsJsonObject(id).get("status").getAsString());
            var evidence = new EvidenceStore(state.resolve("tasks/" + id + "/state/evidence"));
            var retained = evidence.list(); assertEquals(1, retained.size()); runs.add(retained.getFirst());
        }
        assertEquals(2, runs.size()); assertEquals(result, new ParallelInvestigations(state).run(batch));
    }

    @Test void consumerTasksRetainIndependentContainerAttemptsAndCannotReadSiblingMappings() throws Exception {
        Path state = Path.of(System.getProperty("workbench.repository"), "backend/target/investigation-test-runs", UUID.randomUUID().toString());
        var tasks = new ConsumerTasks(state); Path source = InvestigationToolsTest.caseRoot();
        String first = tasks.registerCustomer(source, source.resolve("target/dependencies"), System.getProperty("workbench.container.image"), "gpt-test");
        String second = tasks.registerCustomer(source, source.resolve("target/dependencies"), System.getProperty("workbench.container.image"), "gpt-test");
        JsonObject arguments = CaseInputs.JSON.toJsonTree(Map.of("requestId", "same-request")).getAsJsonObject();
        var initial = tasks.invoke(first, "run_case", arguments);
        assertTrue(initial.get("ok").getAsBoolean(), initial::toString);
        assertEquals("COMPLETED", initial.getAsJsonObject("value").get("status").getAsString());
        assertEquals("INVALID_REQUEST", tasks.invoke(second, "read_run", arguments).get("error").getAsString());
        var other = tasks.invoke(second, "run_case", arguments);
        assertTrue(other.get("ok").getAsBoolean(), other::toString);
        assertEquals("COMPLETED", other.getAsJsonObject("value").get("status").getAsString());
        assertNotEquals(initial.getAsJsonObject("value").get("runId"), other.getAsJsonObject("value").get("runId"));
        assertEquals(initial, new ConsumerTasks(state).invoke(first, "run_case", arguments));
        for (String id : List.of(first, second)) {
            assertEquals(1, new EvidenceStore(state.resolve("tasks/" + id + "/state/evidence")).list().size());
            assertFalse(Files.exists(state.resolve("tasks/" + id + "/state/model-calls")));
        }
        tasks.revoke(first);
        assertEquals("TASK_REVOKED", tasks.invoke(first, "read_run", arguments).get("error").getAsString());
        assertEquals(other, tasks.invoke(second, "read_run", arguments));
    }

    @Test void registeredToolsExecuteTheCaseAndReuseVerifiedEvidence() throws Exception {
        Path state = Path.of(System.getProperty("workbench.repository"), "backend/target/investigation-test-runs", UUID.randomUUID().toString());
        var tools = new InvestigationTools(state);
        String context = tools.register(InvestigationToolsTest.caseRoot(), InvestigationToolsTest.caseRoot().resolve("target/dependencies"),
                System.getProperty("workbench.container.image"));
        JsonObject arguments = CaseInputs.JSON.toJsonTree(Map.of("requestId", "initial-check")).getAsJsonObject();
        var response = tools.invoke(context, "run_case", arguments);
        assertTrue(response.get("ok").getAsBoolean(), response::toString);
        var result = response.getAsJsonObject("value");
        assertEquals("COMPLETED", result.get("status").getAsString());
        assertEquals(4, result.getAsJsonObject("assessments").getAsJsonObject("target").getAsJsonArray("regressedScenarioIds").size());
        assertEquals(2, result.getAsJsonObject("assessments").getAsJsonObject("upstream-reference").getAsJsonArray("regressedScenarioIds").size());
        assertEquals(5, result.getAsJsonObject("observations").getAsJsonArray("baseline").size());
        assertFalse(result.toString().contains("\"definition\""));
        assertTrue(result.toString().length() < 10000);
        var evidence = new EvidenceStore(state.resolve("evidence"));
        assertEquals(1, evidence.list().size());
        var stored = evidence.read(result.get("runId").getAsString());
        assertEquals(CaseInputs.hash(stored.get("worker-report.json")), result.get("reportSha256").getAsString());
        assertEquals(response, new InvestigationTools(state).invoke(context, "run_case", arguments));
        assertEquals(response, tools.invoke(context, "read_run", arguments));
        assertEquals(1, evidence.list().size());
        CaseInputs.write(state.resolve("tool-result.json"), response);
    }
}
