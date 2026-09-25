package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RepairVerificationTest {
    @TempDir Path temporary;
    static final String IMAGE = "sha256:" + "a".repeat(64);
    static CaseInputs inputs() throws Exception {
        Path root = Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID);
        return new CaseInputs(root, root.resolve("target/dependencies"));
    }
    static JsonObject request(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    static RepairOracle oracle(String scenario) throws Exception {
        var inputs = inputs(); return new RepairOracle(inputs.scenarios.get(scenario), inputs.expectations.get(scenario), "target", "2020-08-27");
    }
    @Test void forgedAssertionCannotChangeHostStateOrPass() throws Exception {
        var oracle = oracle("legacy-method-not-lost");
        assertThrows(IllegalArgumentException.class, () -> oracle.request(request(Map.of("op", "result", "assertionOutcome", "PASSED"))));
        assertEquals("NOT_EVALUATED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
        assertEquals(List.of("card_curated_legacy"), CaseInputs.strings(oracle.result("COMPLETED"), "finalMethodIds"));
        assertThrows(IllegalArgumentException.class, () -> oracle.request(request(Map.of("op", "return", "ids", List.of("card_curated_legacy")))));
    }
    @Test void returningExpectedIdsCannotUndoAnUnsafeDeletion() throws Exception {
        var oracle = oracle("legacy-method-not-lost");
        oracle.request(request(Map.of("op", "delete", "id", UUID.nameUUIDFromBytes("card_curated_legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())));
        oracle.request(request(Map.of("op", "return", "ids", List.of("card_curated_legacy"))));
        var result = oracle.result("COMPLETED");
        assertEquals("FAILED", result.get("assertionOutcome").getAsString());
        assertEquals(List.of("card_curated_legacy"), CaseInputs.strings(result, "unsafeDeactivationIds"));
    }
    @Test void stateAndExpectationsRemainIndependentOfCallerMutations() throws Exception {
        var inputs = inputs(); var fixture = inputs.scenarios.get("legacy-method-not-lost"); var expected = inputs.expectations.get("legacy-method-not-lost");
        var oracle = new RepairOracle(fixture, expected, "target", "2020-08-27");
        fixture.add("initialLocalMethodIds", new JsonArray()); expected.add("finalMethodIds", new JsonArray());
        oracle.request(request(Map.of("op", "return", "ids", List.of())));
        assertEquals("FAILED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
    }
    @Test void unsupportedEndpointsAndExtraFieldsFailClosed() throws Exception {
        var oracle = oracle("legacy-method-not-lost");
        assertThrows(IllegalArgumentException.class, () -> oracle.request(request(Map.of("op", "provider", "method", "GET",
                "url", "http://localhost/private", "version", "2020-08-27", "params", Map.of()))));
        var other = oracle("legacy-method-not-lost");
        assertThrows(IllegalArgumentException.class, () -> other.request(request(Map.of("op", "list", "sourcesRead", true))));
        assertEquals("NOT_EVALUATED", other.result("COMPLETED").get("assertionOutcome").getAsString());
    }
    @Test void missingReturnAndIncompleteExecutionCannotEarnPassingResult() throws Exception {
        var oracle = oracle("empty-account-survives-omission");
        assertEquals("NOT_EVALUATED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
        oracle.request(request(Map.of("op", "return", "ids", List.of())));
        assertEquals("NOT_EVALUATED", oracle.result("TIMED_OUT").get("assertionOutcome").getAsString());
    }
    @Test void exhaustedProtocolBudgetCannotBeRecoveredBySendingSuccess() throws Exception {
        var oracle = oracle("empty-account-survives-omission");
        for (int i = 0; i < 100; i++) oracle.request(request(Map.of("op", "list")));
        assertThrows(IllegalArgumentException.class, () -> oracle.request(request(Map.of("op", "return", "ids", List.of()))));
        assertEquals("NOT_EVALUATED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
    }
    @Test void completionIsIdempotentAndCapturedInputTamperingIsRejected() throws Exception {
        var sandbox = new FakeSandbox(); var verifier = new RepairVerification(temporary, sandbox);
        String id = verifier.register(inputs(), "candidate", "test-candidate", "b".repeat(64), IMAGE);
        assertEquals("COMPLETED", verifier.execute(id).get("status").getAsString());
        assertEquals(15, sandbox.calls); verifier.execute(id); assertEquals(15, sandbox.calls);
        Files.writeString(temporary.resolve("verifications/" + id + "/inputs/candidate.txt"), "changed");
        assertThrows(IllegalArgumentException.class, () -> verifier.execute(id));
    }
    @Test void interruptedAttemptIsCleanedUpWithoutReplay() throws Exception {
        var sandbox = new FakeSandbox(); sandbox.fail = true; var verifier = new RepairVerification(temporary, sandbox);
        String id = verifier.register(inputs(), "candidate", "test-candidate", "b".repeat(64), IMAGE);
        assertThrows(java.io.IOException.class, () -> verifier.execute(id));
        assertEquals("INTERRUPTED", verifier.execute(id).get("status").getAsString());
        assertEquals(1, sandbox.calls); assertEquals(15, sandbox.cleaned);
        verifier.execute(id); assertEquals(1, sandbox.calls); assertEquals(15, sandbox.cleaned);
    }
    @Test void revokedPermissionAndCancellationPreventDispatch() throws Exception {
        var sandbox = new FakeSandbox(); var verifier = new RepairVerification(temporary, sandbox);
        String id = verifier.register(inputs(), "candidate", "test-candidate", "b".repeat(64), IMAGE);
        verifier.revoke(id); assertEquals("AUTHORIZATION_DENIED", verifier.execute(id).get("status").getAsString());
        String other = verifier.register(inputs(), "candidate", "test-candidate", "b".repeat(64), IMAGE);
        assertEquals("CANCELLED", verifier.cancel(other).get("status").getAsString()); assertEquals(0, sandbox.calls);
    }
    @Test void scenarioTimeoutIsRetainedAsTimeoutRatherThanAnAssertionFailure() throws Exception {
        var sandbox = new FakeSandbox(); sandbox.status = "TIMED_OUT"; var verifier = new RepairVerification(temporary, sandbox);
        String id = verifier.register(inputs(), "candidate", "test-candidate", "b".repeat(64), IMAGE);
        var result = verifier.execute(id);
        assertEquals("UNRESOLVED", result.getAsJsonObject("evaluation").get("conclusion").getAsString());
        var observations = CaseInputs.object(Files.readAllBytes(temporary.resolve("verifications/" + id + "/observations.json")));
        for (var side : observations.entrySet()) for (var entry : side.getValue().getAsJsonArray()) {
            assertEquals("TIMED_OUT", entry.getAsJsonObject().get("disposition").getAsString());
            assertEquals("NOT_EVALUATED", entry.getAsJsonObject().get("assertionOutcome").getAsString());
        }
    }
    static class FakeSandbox implements RepairVerification.Sandbox {
        int calls, cleaned; boolean fail; String status = "WORKER_FAILED";
        public JsonObject run(String image, String name, String source, String sdk, RepairOracle oracle, Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
            calls++; if (fail) throw new java.io.IOException("interrupted");
            var result = oracle.result(status); result.addProperty("executionStatus", status); result.addProperty("containerRemoved", true); return result;
        }
        public void clean(String name, String image) { cleaned++; }
    }
}
