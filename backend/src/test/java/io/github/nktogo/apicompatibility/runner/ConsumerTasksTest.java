package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerTasksTest {
    @TempDir Path root;
    private final Runtime runtime = new Runtime();
    private ConsumerTasks tasks() throws IOException { return new ConsumerTasks(root, Clock.systemUTC(), runtime); }
    private Path task(String id) { return root.resolve("tasks").resolve(id); }
    private String customer(ConsumerTasks tasks) throws IOException {
        Path source = InvestigationToolsTest.caseRoot();
        return tasks.registerCustomer(source, source.resolve("target/dependencies"), RepairVerificationTest.IMAGE, "gpt-test");
    }
    private String evidence() throws Exception {
        Path source = root.resolve("source");
        var verifier = new RepairVerification(source, new SubscriptionRepairEvaluationTest.Sandbox());
        String id = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "bound\n", RepairVerificationTest.IMAGE);
        verifier.execute(id); return id;
    }
    private String subscription(ConsumerTasks tasks, String evidence) throws IOException {
        return tasks.registerSubscription(root.resolve("source"), evidence, "gpt-test");
    }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject page(String input) { return json(Map.of("inputId", input, "offset", 0, "maxCharacters", 100)); }
    private static JsonObject record(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static String context(Path task) throws IOException { return record(task.resolve("task.json")).get("contextId").getAsString(); }

    @Test void customerTasksUseIndependentStoresAndRejectExpandedToolArguments() throws Exception {
        var tasks = tasks(); String first = customer(tasks), second = customer(tasks);
        assertNotEquals(context(task(first)), context(task(second)));
        assertEquals(tasks.invoke(first, "read_input", page("baseline-source")), tasks.invoke(second, "read_input", page("baseline-source")));
        for (String input : List.of("../" + second + "/grant.json", ".env.local", "grant.json"))
            assertEquals("INVALID_REQUEST", tasks.invoke(first, "read_input", page(input)).get("error").getAsString());
        var foreign = page("baseline-source"); foreign.addProperty("contextId", context(task(second)));
        assertEquals("INVALID_REQUEST", tasks.invoke(first, "read_input", foreign).get("error").getAsString());
        assertEquals("INVALID_REQUEST", tasks.invoke(first, "register", new JsonObject()).get("error").getAsString());
        assertThrows(IllegalArgumentException.class, () -> tasks.run("../" + second));
        assertEquals(0, runtime.dispatches);
    }

    @Test void subscriptionImportsFrozenEvidenceWithoutAuthorizationOrCandidateToolAccess() throws Exception {
        String evidence = evidence(); var tasks = tasks(); String id = subscription(tasks, evidence);
        assertEquals("retained-only", tasks.invoke(id, "describe_case", new JsonObject()).getAsJsonObject("value").get("executionMode").getAsString());
        Path imported = task(id).resolve("state/verifications").resolve(evidence);
        assertFalse(Files.exists(imported.resolve("authorization.json")));
        assertFalse(Files.exists(imported.resolve("started.json")));
        Files.writeString(root.resolve("source/verifications/" + evidence + "/inputs/baseline.txt"), "changed source store");
        assertEquals("baseline\n", tasks.invoke(id, "read_input", page("baseline-source")).getAsJsonObject("value").get("content").getAsString());
        assertEquals("INVALID_REQUEST", tasks.invoke(id, "read_input", page("candidate.txt")).get("error").getAsString());
        var observed = tasks.invoke(id, "run_case", json(Map.of("requestId", "read")));
        assertEquals(Set.of("baseline", "target"), observed.getAsJsonObject("value").getAsJsonObject("observations").keySet());
        try (var attempts = Files.list(task(id).resolve("state/executions"))) { assertEquals(0, attempts.count()); }
        assertEquals(0, runtime.runs.created);
    }

    @Test void heterogeneousInvestigationsKeepModelHistoriesAndResultsSeparate() throws Exception {
        String evidence = evidence(); var tasks = tasks(); String customer = customer(tasks), subscription = subscription(tasks, evidence);
        var first = tasks.run(customer); var second = tasks.run(subscription);
        assertEquals("ABSTAINED", first.get("status").getAsString()); assertEquals("INVESTIGATED", second.get("status").getAsString());
        assertNotEquals(first.get("agentId"), second.get("agentId")); assertEquals(5, runtime.dispatches);
        for (var entry : runtime.requests.entrySet()) for (var request : entry.getValue()) {
            var payload = CaseInputs.object(request.input().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expected = entry.getKey().startsWith(task(customer)) ? CaseInputs.CASE_ID : SubscriptionPeriodCase.CASE_ID;
            assertEquals(expected, payload.getAsJsonObject("case").getAsJsonObject("value").get("caseId").getAsString());
            assertFalse(request.input().contains(expected.equals(CaseInputs.CASE_ID) ? SubscriptionPeriodCase.CASE_ID : CaseInputs.CASE_ID));
        }
        tasks.revoke(subscription); assertEquals(second, tasks().run(subscription)); assertEquals(5, runtime.dispatches);
    }

    @Test void grantFromAnotherTaskCannotAuthorizeCallsAndDoesNotAffectItsOwner() throws Exception {
        var tasks = tasks(); String first = customer(tasks), second = customer(tasks);
        Files.copy(task(second).resolve("grant.json"), task(first).resolve("grant.json"), StandardCopyOption.REPLACE_EXISTING);
        assertEquals("TASK_AUTHORIZATION_DENIED", tasks.run(first).get("status").getAsString()); assertEquals(0, runtime.dispatches);
        assertEquals("ABSTAINED", tasks.run(second).get("status").getAsString());
    }

    @Test void expiryAndPermanentRevocationStopOnlyTheirTaskBeforeDispatch() throws Exception {
        var tasks = tasks(); String expired = customer(tasks), revoked = customer(tasks), permitted = customer(tasks);
        Instant expires = Instant.parse(record(task(expired).resolve("task.json")).get("expiresAt").getAsString());
        var later = new ConsumerTasks(root, Clock.fixed(expires, ZoneOffset.UTC), runtime);
        assertEquals("TASK_EXPIRED", later.run(expired).get("status").getAsString());
        tasks.revoke(revoked); tasks.revoke(revoked);
        assertEquals("TASK_REVOKED", tasks.run(revoked).get("status").getAsString()); assertEquals(0, runtime.dispatches);
        assertEquals("ABSTAINED", tasks.run(permitted).get("status").getAsString());
    }

    @Test void revocationDuringModelResponseStopsFurtherActionsAndPreservesCallRecord() throws Exception {
        var tasks = tasks(); String first = customer(tasks), second = customer(tasks);
        runtime.afterResponse = () -> { try { tasks.revoke(first); } catch (IOException e) { throw new IllegalStateException(e); } };
        assertEquals("TASK_REVOKED", tasks.run(first).get("status").getAsString()); assertEquals(1, runtime.dispatches);
        assertTrue(Files.exists(task(first).resolve("agent.json")));
        try (var calls = Files.list(task(first).resolve("state/model-calls"))) { assertEquals(1, calls.count()); }
        assertEquals("ABSTAINED", tasks.run(second).get("status").getAsString()); assertEquals(3, runtime.dispatches);
    }

    @Test void changedLocalInputsStopWorkButOriginalStoreIsUnmodified() throws Exception {
        String evidence = evidence(); Path original = root.resolve("source/verifications/" + evidence + "/inputs/baseline.txt");
        byte[] originalBytes = Files.readAllBytes(original); var tasks = tasks(); String id = subscription(tasks, evidence);
        Files.writeString(task(id).resolve("state/verifications/" + evidence + "/inputs/baseline.txt"), "changed task copy");
        assertEquals("TASK_INPUT_IDENTITY_MISMATCH", tasks.invoke(id, "read_run", json(Map.of("requestId", "test"))).get("error").getAsString());
        assertEquals("TASK_INPUT_IDENTITY_MISMATCH", tasks.run(id).get("status").getAsString());
        assertArrayEquals(originalBytes, Files.readAllBytes(original)); assertEquals(0, runtime.dispatches);
    }

    @Test void restartReusesAgentAndMappedModelCallWithoutCreatingAnotherRequest() throws Exception {
        var tasks = tasks(); String id = customer(tasks); runtime.interrupt = true;
        assertThrows(IOException.class, () -> tasks.run(id));
        byte[] mapping = Files.readAllBytes(task(id).resolve("agent.json"));
        var result = tasks().run(id); assertEquals("ABSTAINED", result.get("status").getAsString());
        assertArrayEquals(mapping, Files.readAllBytes(task(id).resolve("agent.json")));
        assertEquals(2, runtime.registered); assertEquals(2, runtime.dispatches);
        assertEquals(result, tasks().run(id)); assertEquals(2, runtime.registered);
    }

    @Test void foreignAgentAndModelMappingsCannotBeResumed() throws Exception {
        var tasks = tasks(); String first = customer(tasks), second = customer(tasks);
        runtime.interrupt = true; assertThrows(IOException.class, () -> tasks.run(first));
        runtime.interrupt = true; assertThrows(IOException.class, () -> tasks.run(second));
        Path mapping = task(first).resolve("agent.json"); byte[] original = Files.readAllBytes(mapping);
        Files.copy(task(second).resolve("agent.json"), mapping, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalArgumentException.class, () -> tasks.run(first));
        Files.write(mapping, original);
        String firstAgent = record(mapping).get("agentId").getAsString();
        String secondAgent = record(task(second).resolve("agent.json")).get("agentId").getAsString();
        Path firstCall = task(first).resolve("state/agents/" + firstAgent + "/call-1.json");
        Files.copy(task(second).resolve("state/agents/" + secondAgent + "/call-1.json"), firstCall, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalArgumentException.class, () -> tasks.run(first)); assertEquals(0, runtime.dispatches);
    }

    @Test void testBudgetsAndRetryMappingsAreIndependentPerTask() throws Exception {
        var tasks = tasks(); String first = customer(tasks), second = customer(tasks);
        for (String id : List.of(first, second)) {
            var request = json(Map.of("requestId", "same-request"));
            var result = tasks.invoke(id, "run_case", request);
            assertEquals(result, tasks().invoke(id, "run_case", request));
            assertEquals("INVALID_REQUEST", tasks.invoke(id.equals(first) ? second : first, "read_run", json(Map.of("requestId", "not-mapped"))).get("error").getAsString());
            assertTrue(tasks.invoke(id, "run_case", json(Map.of("requestId", "second"))).get("ok").getAsBoolean());
            assertEquals("TEST_BUDGET_EXHAUSTED", tasks.invoke(id, "run_case", json(Map.of("requestId", "third"))).get("error").getAsString());
        }
        assertEquals(4, runtime.runs.created); assertEquals(4, runtime.runs.executed);
    }

    @Test void activeTaskLeaseDoesNotRegisterAnAgentOrDispatch() throws Exception {
        var tasks = tasks(); String id = customer(tasks);
        try (var lease = LocalFileLease.acquire(task(id).resolve("owner.lock"))) {
            assertNotNull(lease); assertEquals("ACTIVE", tasks.run(id).get("status").getAsString());
            assertFalse(Files.exists(task(id).resolve("agent.json"))); assertEquals(0, runtime.registered);
        }
    }

    @Test void modifiedCompletedRecordsAndUntrustedImportPathsAreRejected() throws Exception {
        var tasks = tasks(); String id = customer(tasks); tasks.run(id);
        String agent = record(task(id).resolve("agent.json")).get("agentId").getAsString();
        Files.writeString(task(id).resolve("state/agents/" + agent + "/result.json"), "changed");
        assertThrows(IllegalArgumentException.class, () -> tasks.run(id));
        String evidence = evidence(); Path path = root.resolve("source/verifications/" + evidence + "/result.json");
        var result = record(path); result.getAsJsonObject("jobArtifacts").addProperty("../../.env.local", "a".repeat(64)); CaseInputs.write(path, result);
        assertThrows(IllegalArgumentException.class, () -> subscription(tasks, evidence));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void linkedInputsAndTaskDirectoriesCannotRedirectAccess() throws Exception {
        var tasks = tasks(); String first = customer(tasks), second = customer(tasks);
        Path input = task(first).resolve("state/investigations/" + context(task(first)) + "/inputs/baseline-source");
        Files.delete(input); Files.createSymbolicLink(input, task(second).resolve("grant.json"));
        assertEquals("TASK_INPUT_IDENTITY_MISMATCH", tasks.run(first).get("status").getAsString());
        Path directory = task(second).resolve("state/investigations/" + context(task(second)) + "/inputs");
        Files.move(directory, directory.resolveSibling("original-inputs")); Files.createSymbolicLink(directory, directory.resolveSibling("original-inputs"));
        assertEquals("TASK_INPUT_IDENTITY_MISMATCH", tasks.run(second).get("status").getAsString());
        assertEquals(0, runtime.dispatches);
    }

    private static final class Runtime implements ConsumerTasks.Backends {
        final Map<Path, List<ModelCallBroker.Request>> requests = new HashMap<>();
        final Map<String, JsonObject> responses = new HashMap<>();
        final Runs runs = new Runs(); int registered, dispatches; boolean interrupt; Runnable afterResponse;
        public ConsumerInvestigator.Models models(Path state) throws IOException {
            var broker = new ModelCallBroker(state.resolve("model-calls"));
            return new ConsumerInvestigator.Models() {
                public String register(ModelCallBroker.Request request) throws IOException {
                    var history = requests.computeIfAbsent(state, ignored -> new ArrayList<>()); int turn = history.size(); history.add(request);
                    boolean subscription = request.input().contains(SubscriptionPeriodCase.CASE_ID);
                    var decision = turn == 0 ? json(Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000))
                            : subscription && turn == 1 ? json(Map.of("action", "read_run"))
                            : json(Map.of("action", "finish", "outcome", subscription ? "INVESTIGATED" : "ABSTAINED", "summary", "Scoped review.",
                                    "findings", List.of(Map.of("kind", subscription ? "HYPOTHESIS" : "LIMITATION", "statement", "Bounded finding.", "citations", List.of("event-1")))));
                    String id = broker.register(request, Duration.ofMinutes(2)); registered++;
                    responses.put(id, json(Map.of("status", "COMPLETED", "inputTokens", 10, "outputTokens", 10, "outputText", json(Map.of("decision", decision)).toString()))); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    if (interrupt) { interrupt = false; throw new IOException("Interrupted before dispatch"); }
                    dispatches++; if (afterResponse != null) { Runnable callback = afterResponse; afterResponse = null; callback.run(); }
                    return responses.get(id);
                }
            };
        }
        public ConsumerInvestigator.Tools tools(Path state) throws IOException {
            var customer = new InvestigationTools(state.resolve("investigations"), runs);
            var subscription = new SubscriptionInvestigationTools(state);
            return (context, tool, arguments) -> context.startsWith("subscription-investigation-")
                    ? subscription.invoke(context, tool, arguments) : customer.invoke(context, tool, arguments);
        }
    }
    private static final class Runs implements InvestigationTools.Runs {
        final Map<String, String> statuses = new HashMap<>(); int created, executed;
        public String create(String image) { created++; String id = "acw-" + UUID.randomUUID(); statuses.put(id, "QUEUED"); return id; }
        public String execute(String id) { executed++; statuses.put(id, "FAILED"); return "FAILED"; }
        public String status(String id) { return statuses.get(id); }
        public Map<String, byte[]> evidence(String id) {
            return Map.of("execution.json", json(Map.of("containerName", id, "status", "FAILED")).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
