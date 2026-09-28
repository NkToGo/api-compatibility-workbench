package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionVerificationTest {
    @TempDir Path temporary;
    static Path caseRoot() { return Path.of(System.getProperty("workbench.repository"), "cases", SubscriptionPeriodCase.CASE_ID); }
    static String register(RepairVerification verifier) throws Exception {
        return verifier.registerSubscription(caseRoot(), "baseline", "candidate", RepairVerificationTest.IMAGE);
    }
    static class Sandbox extends RepairVerificationTest.FakeSandbox {
        public JsonObject subscription(String image, String name, String source, SubscriptionPeriodOracle oracle,
                                       Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
            calls++; if (fail) throw new java.io.IOException("interrupted");
            var input = oracle.request(RepairVerificationTest.request(Map.of("op", "read"))).getAsJsonObject();
            var binding = input.get("binding");
            var actual = SubscriptionPeriodCase.consume(input.getAsJsonObject("subscription"), binding.isJsonNull() ? null : binding.getAsString(),
                    source.equals("candidate") ? SubscriptionPeriodCase.Strategy.BOUND_ITEM : SubscriptionPeriodCase.Strategy.ORIGINAL);
            oracle.request(RepairVerificationTest.request(Map.of("op", "return", "result", actual)));
            var result = oracle.result("COMPLETED"); result.addProperty("executionStatus", "COMPLETED"); result.addProperty("containerRemoved", true);
            return result;
        }
    }
    @Test void capturedInputsAndThreeWayEvidenceAreRetainedWithoutReplay() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(temporary, sandbox); String id = register(verifier);
        var result = verifier.execute(id);
        assertEquals("ALL_OBSERVED_REGRESSIONS_FIXED", result.getAsJsonObject("evaluation").get("conclusion").getAsString());
        assertEquals(24, sandbox.calls); assertEquals(result, verifier.execute(id)); assertEquals(24, sandbox.calls);
        var directory = temporary.resolve("verifications/" + id);
        var observations = CaseInputs.object(Files.readAllBytes(directory.resolve("observations.json")));
        for (var variant : observations.entrySet()) {
            assertEquals(8, variant.getValue().getAsJsonArray().size());
            assertEquals(SubscriptionPeriodCase.CASE_ID, variant.getValue().getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("inputs").get("caseId").getAsString());
        }
        Files.writeString(directory.resolve("inputs/candidate.txt"), "changed");
        assertThrows(IllegalArgumentException.class, () -> verifier.execute(id));
    }
    @Test void interruptionCleansAllRegisteredNamesWithoutRepeatingExecution() throws Exception {
        var sandbox = new Sandbox(); sandbox.fail = true; var verifier = new RepairVerification(temporary, sandbox); String id = register(verifier);
        assertThrows(java.io.IOException.class, () -> verifier.execute(id));
        assertEquals("INTERRUPTED", verifier.execute(id).get("status").getAsString());
        assertEquals(24, sandbox.cleaned); assertEquals(1, sandbox.calls);
        verifier.execute(id); assertEquals(24, sandbox.cleaned); assertEquals(1, sandbox.calls);
    }
    @Test void revokeCancelAndChangedHarnessPreventDispatch() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(temporary, sandbox);
        String revoked = register(verifier); verifier.revoke(revoked);
        assertEquals("AUTHORIZATION_DENIED", verifier.execute(revoked).get("status").getAsString());
        assertEquals("CANCELLED", verifier.cancel(register(verifier)).get("status").getAsString());
        String changed = register(verifier); Path spec = temporary.resolve("verifications/" + changed + "/spec.json");
        var value = CaseInputs.object(Files.readAllBytes(spec)); value.addProperty("harnessSha256", "0".repeat(64)); CaseInputs.write(spec, value);
        assertThrows(IllegalArgumentException.class, () -> verifier.execute(changed)); assertEquals(0, sandbox.calls);
    }
    @Test void evidenceMutationIsRejectedOnReuse() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(temporary, sandbox); String id = register(verifier); verifier.execute(id);
        Files.writeString(temporary.resolve("verifications/" + id + "/jobs/candidate-single-item.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> verifier.execute(id));
    }
}
