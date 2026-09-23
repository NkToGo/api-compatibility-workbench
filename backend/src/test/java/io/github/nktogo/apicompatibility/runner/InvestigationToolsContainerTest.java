package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "workbench.container.image", matches = "sha256:[0-9a-f]{64}")
class InvestigationToolsContainerTest {
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
