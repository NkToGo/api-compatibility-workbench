package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestigationToolsTest {
    @TempDir Path root;
    private final FakeRuns runs = new FakeRuns();
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    static Path caseRoot() { return Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID); }
    private InvestigationTools tools() throws Exception { return new InvestigationTools(root.resolve("contexts"), runs); }
    private String register(InvestigationTools tools) throws Exception { return tools.register(caseRoot(), caseRoot().resolve("target/dependencies"), IMAGE); }
    private static JsonObject args(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject request(String id) { return args(Map.of("requestId", id)); }
    private static JsonObject page(String input, Object offset, int maximum) { return args(Map.of("inputId", input, "offset", offset, "maxCharacters", maximum)); }
    private static JsonObject value(JsonObject result) { assertTrue(result.get("ok").getAsBoolean(), result::toString); return result.getAsJsonObject("value"); }
    private static void error(String expected, JsonObject result) { assertFalse(result.get("ok").getAsBoolean()); assertEquals(expected, result.get("error").getAsString()); }

    @Test void readsOnlyBoundedRegisteredInputsAndRejectsAuthorityExpandingArguments() throws Exception {
        var tools = tools(); String context = register(tools);
        var description = value(tools.invoke(context, "describe_case", new JsonObject()));
        assertEquals(InvestigationTools.INPUTS.keySet(), description.getAsJsonObject("inputs").keySet());
        var page = value(tools.invoke(context, "read_input", page("baseline-source", 0, 23)));
        assertEquals(Files.readString(caseRoot().resolve("consumer/baseline.java.txt")).substring(0, 23), page.get("content").getAsString());
        assertEquals(23, page.get("nextOffset").getAsInt());
        assertEquals("untrusted-data", page.get("contentTrust").getAsString());
        for (String input : List.of("../registration.json", ".env.local", "https://example.com"))
            error("INVALID_REQUEST", tools.invoke(context, "read_input", page(input, 0, 20)));
        for (Object offset : List.of(-1, 1.5, "0", 2147483648L))
            error("INVALID_REQUEST", tools.invoke(context, "read_input", page("fixtures", offset, 20)));
        error("INVALID_REQUEST", tools.invoke(context, "read_input", page("fixtures", 0, 4001)));
        error("INVALID_REQUEST", tools.invoke(context, "run_case", args(Map.of("requestId", "first", "command", "anything"))));
        error("INVALID_REQUEST", tools.invoke(context, "run_case", request("../escape")));
        error("INVALID_REQUEST", tools.invoke(context, "shell", new JsonObject()));
        assertEquals(0, runs.created);
    }

    @Test void snapshotSurvivesSourceRemovalButTamperingStopsReadsAndExecution() throws Exception {
        Path copy = root.resolve("case");
        for (String name : InvestigationTools.INPUTS.values()) {
            Path target = copy.resolve(name); Files.createDirectories(target.getParent()); Files.copy(caseRoot().resolve(name), target);
        }
        var tools = tools(); String context = tools.register(copy, caseRoot().resolve("target/dependencies"), IMAGE);
        Files.delete(copy.resolve("fixtures.json"));
        value(tools.invoke(context, "read_input", page("fixtures", 0, 20)));
        Files.writeString(root.resolve("contexts").resolve(context).resolve("inputs/fixtures"), "changed");
        error("INPUT_IDENTITY_MISMATCH", tools.invoke(context, "read_input", page("fixtures", 0, 20)));
        error("INPUT_IDENTITY_MISMATCH", tools.invoke(context, "run_case", request("first")));
        assertEquals(0, runs.created);
    }

    @Test void retriesReuseRecordedRunAcrossToolInstancesAndBudgetStopsNewRuns() throws Exception {
        var tools = tools(); String context = register(tools);
        var first = value(tools.invoke(context, "run_case", request("first")));
        assertEquals("FAILED", first.get("status").getAsString());
        assertEquals("NOT_AVAILABLE", first.get("assessment").getAsString());
        assertEquals(first, value(tools().invoke(context, "run_case", request("first"))));
        assertEquals(first, value(tools().invoke(context, "read_run", request("first"))));
        assertEquals(1, runs.created); assertEquals(1, runs.executed);
        value(tools.invoke(context, "run_case", request("second")));
        error("TEST_BUDGET_EXHAUSTED", tools.invoke(context, "run_case", request("third")));
        assertEquals(2, runs.created); assertEquals(2, runs.executed);
        value(tools.invoke(context, "run_case", request("first")));
    }

    @Test void activeAndInterruptedRunsAreNeverReplayedAndRequestsAreContextScoped() throws Exception {
        var tools = tools(); String context = register(tools);
        String id = value(tools.invoke(context, "run_case", request("first"))).get("runId").getAsString();
        for (String status : List.of("ACTIVE", "RECOVERY_REQUIRED")) {
            runs.states.put(id, status);
            assertEquals(status, value(tools.invoke(context, "run_case", request("first"))).get("status").getAsString());
        }
        assertEquals(1, runs.executed);
        String other = register(tools);
        error("INVALID_REQUEST", tools.invoke(other, "read_run", request("first")));
        assertEquals(1, runs.created);
    }

    @Test void ambiguousDispatchIsRetainedInsteadOfRetried() throws Exception {
        var tools = tools(); String context = register(tools); runs.interrupt = true;
        error("UNAVAILABLE", tools.invoke(context, "run_case", request("first")));
        assertEquals("RECOVERY_REQUIRED", value(tools.invoke(context, "run_case", request("first"))).get("status").getAsString());
        assertEquals(1, runs.created); assertEquals(1, runs.executed);
    }

    @Test void resultsFromDifferentImagesOrInputHashesAreRejected() throws Exception {
        var tools = tools(); String context = register(tools);
        String run = value(tools.invoke(context, "run_case", request("first"))).get("runId").getAsString();
        runs.states.put(run, "COMPLETED"); runs.image = "sha256:" + "b".repeat(64);
        error("INPUT_IDENTITY_MISMATCH", tools.invoke(context, "read_run", request("first")));
        runs.image = IMAGE;
        error("INPUT_IDENTITY_MISMATCH", tools.invoke(context, "read_run", request("first")));
        assertEquals(1, runs.executed);
    }

    @Test void registrationRefusesMutableImageTagsAndEscapingIndexEntries() throws Exception {
        var tools = tools();
        assertThrows(IllegalArgumentException.class, () -> tools.register(caseRoot(), caseRoot().resolve("target/dependencies"), "worker:latest"));
        Path copy = root.resolve("case");
        for (String name : InvestigationTools.INPUTS.values()) {
            Path target = copy.resolve(name); Files.createDirectories(target.getParent()); Files.copy(caseRoot().resolve(name), target);
        }
        var index = CaseInputs.object(Files.readAllBytes(copy.resolve("integrity.json")));
        index.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("path", "../private.txt");
        CaseInputs.write(copy.resolve("integrity.json"), index);
        assertThrows(IllegalArgumentException.class, () -> tools.register(copy, caseRoot().resolve("target/dependencies"), IMAGE));
    }

    private static final class FakeRuns implements InvestigationTools.Runs {
        int created, executed; boolean interrupt; String image = IMAGE;
        final Map<String, String> states = new HashMap<>();
        public String create(String image) { created++; String id = "acw-" + UUID.randomUUID(); states.put(id, "QUEUED"); return id; }
        public String execute(String id) {
            executed++; states.put(id, interrupt ? "RECOVERY_REQUIRED" : "FAILED");
            if (interrupt) throw new IllegalStateException("Uncertain dispatch"); return states.get(id);
        }
        public String status(String id) { return states.get(id); }
        public Map<String, byte[]> evidence(String id) {
            return Map.of("execution.json", CaseInputs.JSON.toJson(Map.of("containerName", id, "status", states.get(id), "imageId", image)).getBytes(StandardCharsets.UTF_8),
                    "worker-report.json", "{\"artifacts\":{}}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
