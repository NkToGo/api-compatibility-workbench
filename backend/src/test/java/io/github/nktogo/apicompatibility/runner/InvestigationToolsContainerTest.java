package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "workbench.container.image", matches = "sha256:[0-9a-f]{64}")
class InvestigationToolsContainerTest {
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
