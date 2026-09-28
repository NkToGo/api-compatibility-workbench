package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionCandidatesTest {
    @TempDir Path state;
    static class Models implements ConsumerInvestigator.Models {
        int calls; ModelCallBroker.Request request;
        String status = "COMPLETED";
        String proposal = "{\"status\":\"PROPOSED\",\"summary\":\"Change source.\",\"edits\":[{\"before\":\"baseline\",\"after\":\"candidate\"}]}";
        public String register(ModelCallBroker.Request request) { this.request = request; return "model-test"; }
        public JsonObject execute(String id) {
            calls++; return CaseInputs.JSON.toJsonTree(Map.of("status", status, "outputText", proposal, "inputTokens", 10, "outputTokens", 5)).getAsJsonObject();
        }
    }
    String evidence() throws Exception {
        var verifier = new RepairVerification(state, new SubscriptionVerificationTest.Sandbox());
        String id = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "candidate\n", RepairVerificationTest.IMAGE);
        verifier.execute(id); return id;
    }
    @Test void proposalRemainsInertAndVerificationBindsItsManifest() throws Exception {
        var models = new Models(); var candidates = new SubscriptionCandidates(state, models); String id = candidates.create(evidence(), "gpt-test");
        assertEquals("subscription-repair-v1", models.request.protocol());
        var result = candidates.generate(id); assertEquals("CANDIDATE_READY", result.get("status").getAsString());
        assertFalse(result.get("executed").getAsBoolean()); assertEquals(result, candidates.generate(id)); assertEquals(1, models.calls);
        String verification = candidates.verification(id, RepairVerificationTest.IMAGE);
        var spec = CaseInputs.object(Files.readAllBytes(state.resolve("verifications/" + verification + "/spec.json")));
        assertEquals(id, spec.get("candidateId").getAsString());
        assertEquals(CaseInputs.hash(Files.readAllBytes(state.resolve("subscription-candidates/" + id + "/result.json"))), spec.get("candidateManifestSha256").getAsString());
        assertFalse(Files.exists(state.resolve("verifications/" + verification + "/started.json")));
    }
    @Test void abstentionMalformedAndUncertainOutcomesNeverCreateCheckout() throws Exception {
        String evidence = evidence();
        for (String mode : List.of("abstain", "invalid", "uncertain")) {
            var models = new Models();
            if (mode.equals("abstain")) models.proposal = "{\"status\":\"ABSTAINED\",\"summary\":\"Insufficient evidence.\",\"edits\":[]}";
            if (mode.equals("invalid")) models.proposal = models.proposal.replace("baseline", "not-in-source");
            if (mode.equals("uncertain")) models.status = "UNCERTAIN";
            var candidates = new SubscriptionCandidates(state, models); String id = candidates.create(evidence, "gpt-test");
            assertEquals(Map.of("abstain", "ABSTAINED", "invalid", "INVALID_PROPOSAL", "uncertain", "MODEL_UNCERTAIN").get(mode), candidates.generate(id).get("status").getAsString());
            assertFalse(Files.exists(state.resolve("subscription-candidates/" + id + "/checkout")));
            candidates.generate(id); assertEquals(1, models.calls);
            assertThrows(IllegalArgumentException.class, () -> candidates.verification(id, RepairVerificationTest.IMAGE));
        }
    }
    @Test void changedEvidenceStopsDispatchAndChangedArtifactsStopReuse() throws Exception {
        var models = new Models(); var candidates = new SubscriptionCandidates(state, models); String evidence = evidence(); String id = candidates.create(evidence, "gpt-test");
        Files.writeString(state.resolve("verifications/" + evidence + "/inputs/baseline.txt"), "changed");
        assertEquals("INPUT_IDENTITY_MISMATCH", candidates.generate(id).get("status").getAsString()); assertEquals(0, models.calls);
        String other = candidates.create(evidence(), "gpt-test"); candidates.generate(other);
        Files.writeString(state.resolve("subscription-candidates/" + other + "/checkout/PeriodConsumer.java"), "changed");
        assertThrows(IllegalArgumentException.class, () -> candidates.generate(other));
    }
    @Test void partialArtifactsCannotBeOverwrittenAndVerificationCannotStartGeneration() throws Exception {
        var models = new Models(); var candidates = new SubscriptionCandidates(state, models); String id = candidates.create(evidence(), "gpt-test");
        assertThrows(IllegalArgumentException.class, () -> candidates.verification(id, RepairVerificationTest.IMAGE)); assertEquals(0, models.calls);
        Path checkout = Files.createDirectory(state.resolve("subscription-candidates/" + id + "/checkout"));
        Files.writeString(checkout.resolve("PeriodConsumer.java"), "partial");
        assertThrows(IllegalArgumentException.class, () -> candidates.generate(id));
        assertEquals("partial", Files.readString(checkout.resolve("PeriodConsumer.java")));
    }
    @Test void duplicateOverlappingAndNoOpEditsAreRejected() {
        for (var edits : List.of(List.of(new RepairProposal.Edit("abc", "abc")),
                List.of(new RepairProposal.Edit("abc", "x"), new RepairProposal.Edit("bc", "y")),
                List.of(new RepairProposal.Edit("abc", "x"), new RepairProposal.Edit("abc", "y"))))
            assertThrows(IllegalArgumentException.class, () -> RepairProposal.applySubscription("abc\n", new RepairProposal.Proposal("PROPOSED", "change", edits)));
        assertThrows(IllegalArgumentException.class, () -> RepairProposal.applySubscription("abc abc\n",
                new RepairProposal.Proposal("PROPOSED", "change", List.of(new RepairProposal.Edit("abc", "x")))));
    }
}
