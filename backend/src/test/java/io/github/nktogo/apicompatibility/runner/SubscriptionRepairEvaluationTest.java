package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionRepairEvaluationTest {
    @TempDir Path state;
    static class Sandbox extends SubscriptionVerificationTest.Sandbox {
        public JsonObject subscription(String image, String name, String source, SubscriptionPeriodOracle oracle,
                                       Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
            return super.subscription(image, name, source.strip(), oracle, deadline, stopped);
        }
    }
    String evidence(RepairVerification verifier) throws Exception {
        String id = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "baseline\n", RepairVerificationTest.IMAGE);
        verifier.execute(id); return id;
    }
    @Test void repetitionsExecuteOnceAndReuseChecksChildEvidence() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(state, sandbox); String evidence = evidence(verifier);
        var models = new SubscriptionCandidatesTest.Models(); var candidates = new SubscriptionCandidates(state, models);
        var evaluation = new SubscriptionRepairEvaluation(state, candidates, verifier);
        String id = evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, 3);
        var result = evaluation.run(id);
        assertEquals(3, result.getAsJsonObject("counts").get("OBSERVED_REGRESSIONS_FIXED").getAsInt());
        assertEquals(3, models.calls); assertEquals(96, sandbox.calls);
        assertEquals(30, result.get("inputTokens").getAsInt());
        assertEquals(result, evaluation.run(id)); assertEquals(3, models.calls); assertEquals(96, sandbox.calls);
        String candidate = result.getAsJsonArray("trials").get(0).getAsJsonObject().get("candidateId").getAsString();
        Files.writeString(state.resolve("subscription-candidates/" + candidate + "/checkout/PeriodConsumer.java"), "tampered");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id));
    }
    @Test void interruptedTrialRecoversWithoutAnotherModelCallOrExecution() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(state, sandbox); String evidence = evidence(verifier);
        var models = new SubscriptionCandidatesTest.Models(); var evaluation = new SubscriptionRepairEvaluation(state, new SubscriptionCandidates(state, models), verifier);
        String id = evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, 1); sandbox.fail = true;
        assertThrows(java.io.IOException.class, () -> evaluation.run(id));
        var result = evaluation.run(id);
        assertEquals(1, result.getAsJsonObject("counts").get("VERIFICATION_INCOMPLETE").getAsInt());
        assertEquals(1, models.calls); assertEquals(25, sandbox.calls); assertEquals(24, sandbox.cleaned);
        assertEquals(result, evaluation.run(id));
    }
    @Test void abstentionAndFailureAreNotCreditedAsRepairs() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(state, sandbox); String evidence = evidence(verifier);
        var models = new SubscriptionCandidatesTest.Models(); models.proposal = "{\"status\":\"ABSTAINED\",\"summary\":\"Insufficient evidence.\",\"edits\":[]}";
        var evaluation = new SubscriptionRepairEvaluation(state, new SubscriptionCandidates(state, models), verifier);
        String id = evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, 2);
        assertEquals(2, evaluation.run(id).getAsJsonObject("counts").get("ABSTAINED").getAsInt()); assertEquals(24, sandbox.calls);
        for (var entry : Map.of("ABSTAINED", "ABSTAINED", "INVALID_PROPOSAL", "INVALID_PROPOSAL", "MODEL_UNCERTAIN", "GENERATION_FAILED", "CANDIDATE_READY", "VERIFICATION_INCOMPLETE").entrySet())
            assertEquals(entry.getValue(), SubscriptionRepairEvaluation.score(json(Map.of("status", entry.getKey())), null).get("outcome").getAsString());
        for (var entry : Map.of("UNRESOLVED", "VERIFICATION_UNRESOLVED", "REGRESSIONS_REMAIN", "REGRESSIONS_REMAIN", "ALL_OBSERVED_REGRESSIONS_FIXED", "OBSERVED_REGRESSIONS_FIXED").entrySet())
            assertEquals(entry.getValue(), SubscriptionRepairEvaluation.score(json(Map.of("status", "CANDIDATE_READY")), json(Map.of("status", "COMPLETED", "evaluation", Map.of("conclusion", entry.getKey())))).get("outcome").getAsString());
    }
    @Test void budgetsAndModifiedSuiteAreRejected() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(state, sandbox); String evidence = evidence(verifier);
        var evaluation = new SubscriptionRepairEvaluation(state, new SubscriptionCandidates(state, new SubscriptionCandidatesTest.Models()), verifier);
        for (int count : List.of(0, 6)) assertThrows(IllegalArgumentException.class, () -> evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, count));
        String id = evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, 1); evaluation.run(id);
        Path suite = state.resolve("repair-evaluations/" + id + "/suite.json"); Files.writeString(suite, Files.readString(suite) + " ");
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id));
    }
    @Test void interruptedSuiteCannotChangeItsPlannedBudget() throws Exception {
        var sandbox = new Sandbox(); var verifier = new RepairVerification(state, sandbox); String evidence = evidence(verifier);
        var models = new SubscriptionCandidatesTest.Models(); var evaluation = new SubscriptionRepairEvaluation(state, new SubscriptionCandidates(state, models), verifier);
        String id = evaluation.create(evidence, "gpt-test", RepairVerificationTest.IMAGE, 1); sandbox.fail = true;
        assertThrows(java.io.IOException.class, () -> evaluation.run(id));
        Path path = state.resolve("repair-evaluations/" + id + "/suite.json"); var suite = CaseInputs.object(Files.readAllBytes(path));
        suite.addProperty("repetitions", 5); CaseInputs.write(path, suite);
        assertThrows(IllegalArgumentException.class, () -> evaluation.run(id)); assertEquals(1, models.calls);
    }
    static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
