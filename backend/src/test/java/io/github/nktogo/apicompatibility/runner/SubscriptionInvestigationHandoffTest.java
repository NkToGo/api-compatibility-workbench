package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionInvestigationHandoffTest {
    @TempDir Path state;
    String evidence() throws Exception {
        var verifier = new RepairVerification(state, new SubscriptionVerificationTest.Sandbox());
        String id = verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(), "baseline\n", "candidate\n", RepairVerificationTest.IMAGE);
        verifier.execute(id); return id;
    }
    String investigate(String evidence, String outcome) throws Exception {
        String context = new SubscriptionInvestigationTools(state).register(evidence);
        var models = new SubscriptionInvestigationToolsTest.Models(List.of(
                json(Map.of("action", "read_input", "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000)),
                json(Map.of("action", "read_run")),
                json(Map.of("action", "finish", "outcome", outcome, "summary", "Review renewal selection.", "findings", List.of(
                        Map.of("kind", "HYPOTHESIS", "statement", "The field selection may cause the failures.", "citations", List.of("event-1", "event-2")))))));
        var investigator = new ConsumerInvestigator(state.resolve("agents"), models, ConsumerInvestigator.toolBackend(state), Clock.systemUTC());
        String agent = investigator.create(context, "gpt-test"); assertEquals(outcome, investigator.run(agent).get("status").getAsString()); return agent;
    }
    static class Models extends SubscriptionCandidatesTest.Models {
        int registered;
        public String register(ModelCallBroker.Request request) { registered++; return super.register(request); }
    }
    @Test void handoffCapturesFindingsAndKeepsVerificationSeparate() throws Exception {
        String evidence = evidence(), agent = investigate(evidence, "INVESTIGATED"); var models = new Models();
        var candidates = new SubscriptionCandidates(state, models); String candidate = candidates.createFromInvestigation(agent, "gpt-test");
        var payload = JsonParser.parseString(models.request.input()).getAsJsonObject();
        assertEquals("The field selection may cause the failures.", payload.getAsJsonObject("advisoryFindings").getAsJsonArray("findings").get(0).getAsJsonObject().get("statement").getAsString());
        assertEquals(2, payload.getAsJsonArray("investigationEvents").size());
        assertEquals(Set.of("baseline", "target"), payload.getAsJsonArray("investigationEvents").get(1).getAsJsonObject().getAsJsonObject("result").getAsJsonObject("value").getAsJsonObject("observations").keySet());
        assertFalse(payload.has("candidateObservations")); assertEquals(1, models.registered);
        Path directory = candidate(candidate); var registration = read(directory.resolve("registration.json"));
        assertEquals(evidence, registration.get("verificationId").getAsString());
        assertEquals(agent, registration.getAsJsonObject("investigation").get("agentId").getAsString());
        assertEquals(CaseInputs.JSON.toJsonTree(CaseInputs.inventory(directory.resolve("investigation"))), registration.getAsJsonObject("investigation").get("records"));
        var result = candidates.generate(candidate); assertEquals("CANDIDATE_READY", result.get("status").getAsString()); assertFalse(result.get("executed").getAsBoolean());
        assertEquals(result, candidates.generate(candidate)); assertEquals(1, models.calls);
        String verification = candidates.verification(candidate, RepairVerificationTest.IMAGE);
        assertFalse(Files.exists(state.resolve("verifications/" + verification + "/started.json")));
        assertEquals(candidate, read(state.resolve("verifications/" + verification + "/spec.json")).get("candidateId").getAsString());
    }
    @Test void abstainedFailedAndWrongContextInvestigationsDoNotRegisterModelWork() throws Exception {
        String evidence = evidence(); var models = new Models(); var candidates = new SubscriptionCandidates(state, models);
        String abstained = investigate(evidence, "ABSTAINED");
        assertThrows(IllegalArgumentException.class, () -> candidates.createFromInvestigation(abstained, "gpt-test"));
        for (String changed : List.of("failed", "context", "citation")) {
            String agent = investigate(evidence, "INVESTIGATED"); Path directory = state.resolve("agents").resolve(agent);
            if (changed.equals("context")) {
                var session = read(directory.resolve("session.json")); session.addProperty("contextId", "investigation-00000000-0000-0000-0000-000000000000");
                Files.writeString(directory.resolve("session.json"), session.toString());
            } else {
                var result = read(directory.resolve("result.json"));
                if (changed.equals("failed")) result.addProperty("status", "MODEL_UNKNOWN_REMOTE_OUTCOME");
                else result.getAsJsonObject("findings").getAsJsonArray("findings").get(0).getAsJsonObject().getAsJsonArray("citations").add("event-99");
                Files.writeString(directory.resolve("result.json"), result.toString());
            }
            assertThrows(IllegalArgumentException.class, () -> candidates.createFromInvestigation(agent, "gpt-test"));
        }
        assertEquals(0, models.registered); assertEquals(0, models.calls);
    }
    @Test void changedOriginalRecordsStopDispatchWithoutReplacingHistory() throws Exception {
        String evidence = evidence();
        for (String file : List.of("session.json", "result.json", "event-1.json")) {
            String agent = investigate(evidence, "INVESTIGATED"); var models = new Models(); var candidates = new SubscriptionCandidates(state, models);
            String candidate = candidates.createFromInvestigation(agent, "gpt-test"); Path original = state.resolve("agents/" + agent + "/" + file);
            Files.writeString(original, Files.readString(original) + " ");
            assertEquals("INPUT_IDENTITY_MISMATCH", candidates.generate(candidate).get("status").getAsString());
            assertEquals(0, models.calls); assertFalse(Files.exists(candidate(candidate).resolve("checkout")));
            assertEquals("INVESTIGATED", read(state.resolve("agents/" + agent + "/result.json")).get("status").getAsString());
        }
    }
    @Test void changedCopiedRecordsAndMismatchedObservedEventsAreRejected() throws Exception {
        String evidence = evidence(), agent = investigate(evidence, "INVESTIGATED"); var models = new Models(); var candidates = new SubscriptionCandidates(state, models);
        String candidate = candidates.createFromInvestigation(agent, "gpt-test");
        Files.writeString(candidate(candidate).resolve("investigation/result.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> candidates.generate(candidate)); assertEquals(0, models.calls);
        Path directory = state.resolve("agents").resolve(agent); var result = read(directory.resolve("result.json"));
        var event = result.getAsJsonArray("events").get(0).getAsJsonObject(); event.getAsJsonObject("result").getAsJsonObject("value").addProperty("content", "Different source");
        Files.writeString(directory.resolve("event-1.json"), event.toString()); Files.writeString(directory.resolve("result.json"), result.toString());
        assertThrows(IllegalArgumentException.class, () -> candidates.createFromInvestigation(agent, "gpt-test")); assertEquals(1, models.registered);
    }
    @Test void changesDuringDispatchCannotPublishARepairAndUnknownOutcomesAreNotReplayed() throws Exception {
        String evidence = evidence(), agent = investigate(evidence, "INVESTIGATED");
        var models = new Models() {
            public JsonObject execute(String id) {
                var result = super.execute(id);
                try { Path path = state.resolve("agents/" + agent + "/result.json"); Files.writeString(path, Files.readString(path) + " "); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                return result;
            }
        };
        var candidates = new SubscriptionCandidates(state, models); String candidate = candidates.createFromInvestigation(agent, "gpt-test");
        assertEquals("INPUT_IDENTITY_MISMATCH", candidates.generate(candidate).get("status").getAsString());
        assertEquals(1, models.calls); assertFalse(Files.exists(candidate(candidate).resolve("checkout")));
        String other = investigate(evidence, "INVESTIGATED"); var unknown = new Models(); unknown.status = "UNKNOWN_REMOTE_OUTCOME";
        var uncertain = new SubscriptionCandidates(state, unknown); String attempt = uncertain.createFromInvestigation(other, "gpt-test");
        assertEquals("MODEL_UNKNOWN_REMOTE_OUTCOME", uncertain.generate(attempt).get("status").getAsString());
        uncertain.generate(attempt); assertEquals(1, unknown.calls);
    }
    @Test void historicalCandidateCanBeReadButChangedInvestigationBlocksNewVerification() throws Exception {
        String agent = investigate(evidence(), "INVESTIGATED"); var candidates = new SubscriptionCandidates(state, new Models());
        String candidate = candidates.createFromInvestigation(agent, "gpt-test"); var result = candidates.generate(candidate);
        Path original = state.resolve("agents/" + agent + "/result.json"); Files.writeString(original, Files.readString(original) + " ");
        assertEquals(result, candidates.generate(candidate));
        assertThrows(IllegalArgumentException.class, () -> candidates.verification(candidate, RepairVerificationTest.IMAGE));
        Files.writeString(candidate(candidate).resolve("investigation/session.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> candidates.generate(candidate));
    }
    private Path candidate(String id) { return state.resolve("subscription-candidates").resolve(id); }
    private static JsonObject read(Path path) throws Exception { return CaseInputs.object(Files.readAllBytes(path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
