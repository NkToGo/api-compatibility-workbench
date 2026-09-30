package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairCandidatesTest {
    @TempDir Path state;
    private static final String BEFORE = "final Set<String> stripeObjectsTreated = new HashSet<String>();";
    private static final String AFTER = BEFORE + " // candidate note";
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String source() throws Exception { return Files.readString(Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID, "consumer/baseline.java.txt")); }
    private static String proposal(String before, String after) { return CaseInputs.JSON.toJson(Map.of("status", "PROPOSED", "summary", "Candidate modification.", "edits", List.of(Map.of("before", before, "after", after)))); }

    @Test void uniqueEditsAreAppliedOnlyToTheOriginalMethodRegion() throws Exception {
        String source = source(); String candidate = RepairProposal.apply(source, RepairProposal.parse(proposal(BEFORE, AFTER)));
        assertTrue(candidate.contains(AFTER));
        assertTrue(candidate.endsWith(source.substring(source.indexOf("    private void syncPaymentMethods"))));
        assertTrue(candidate.contains("Copyright 2020-2020 Equinix, Inc"));
        assertTrue(RepairProposal.patch(source, candidate).startsWith("--- a/consumer/baseline.java.txt\n+++ b/consumer/baseline.java.txt\n"));
    }
    @Test void unknownPathsAmbiguousEditsAndProtectedSourceAreRejected() throws Exception {
        String source = source();
        for (String before : List.of("Copyright 2020-2020 Equinix, Inc", "final", "not present", "return RequestOptions.builder()"))
            assertThrows(IllegalArgumentException.class, () -> RepairProposal.apply(source, RepairProposal.parse(proposal(before, "replacement"))));
        assertThrows(IllegalArgumentException.class, () -> RepairProposal.apply(source, RepairProposal.parse(proposal(BEFORE, BEFORE))));
        assertThrows(IllegalArgumentException.class, () -> RepairProposal.parse("{\"status\":\"PROPOSED\",\"summary\":\"x\",\"edits\":[{\"path\":\"../expectations.json\",\"before\":\"a\",\"after\":\"b\"}]}"));
    }
    @Test void overlappingEditsAreRejectedRatherThanAppliedSequentially() throws Exception {
        var proposal = new RepairProposal.Proposal("PROPOSED", "changes", List.of(new RepairProposal.Edit(BEFORE, AFTER),
                new RepairProposal.Edit("stripeObjectsTreated = new HashSet<String>();", "replacement")));
        assertThrows(IllegalArgumentException.class, () -> RepairProposal.apply(source(), proposal));
    }
    @Test void newlineChangesCannotCreateAPatchWithDifferentBytes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RepairProposal.apply(source(), RepairProposal.parse(proposal(BEFORE, AFTER + "\r\n"))));
    }
    @Test void materializedCheckoutIsInertBoundAndIdempotent() throws Exception {
        var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
        var result = fixture.candidates.generate(id);
        assertEquals("CANDIDATE_READY", result.get("status").getAsString()); assertFalse(result.get("executed").getAsBoolean());
        assertEquals(source(), Files.readString(state.resolve("candidates").resolve(id).resolve("baseline.java.txt")));
        assertEquals(result, fixture.candidates.generate(id)); assertEquals(1, fixture.models.calls);
        Files.writeString(state.resolve("candidates").resolve(id).resolve("checkout/consumer/baseline.java.txt"), "tampered");
        assertThrows(IllegalArgumentException.class, () -> fixture.candidates.generate(id));
    }
    @Test void abstentionAndInvalidProposalNeverMaterializeACheckout() throws Exception {
        for (String proposal : List.of("{\"status\":\"ABSTAINED\",\"summary\":\"Insufficient SDK evidence.\",\"edits\":[]}", proposal("unknown text", "change"))) {
            var fixture = fixture(proposal); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
            String status = fixture.candidates.generate(id).get("status").getAsString();
            assertTrue(Set.of("ABSTAINED", "INVALID_PROPOSAL").contains(status));
            assertFalse(Files.exists(state.resolve("candidates").resolve(id).resolve("checkout")));
        }
    }
    @Test void changedEvidencePreventsModelDispatch() throws Exception {
        var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
        fixture.models.changed = true;
        assertEquals("INPUT_IDENTITY_MISMATCH", fixture.candidates.generate(id).get("status").getAsString()); assertEquals(0, fixture.models.calls);
    }
    @Test void generationReceivesCompleteBoundSyntheticResponsesWithoutARepairControl() throws Exception {
        Path jar = Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID, "target/dependencies/stripe-java-20.33.0.jar");
        String sdk = RepairCandidates.sdkEvidence(jar);
        var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test", sdk);
        var payload = JsonParser.parseString(fixture.models.request.input()).getAsJsonObject();
        assertEquals(sdk, payload.get("targetSdkSignatures").getAsString());
        assertTrue(fixture.models.request.input().getBytes(StandardCharsets.UTF_8).length <= 64000);
        var responses = payload.getAsJsonObject("syntheticFixtures"); String content = caseInput("fixtures.json");
        assertEquals(content, responses.get("content").getAsString());
        assertEquals("authored-synthetic-responses", responses.get("authority").getAsString());
        assertEquals("untrusted-data", responses.get("contentTrust").getAsString());
        assertEquals(hash(content), responses.get("sha256").getAsString());
        var examples = JsonParser.parseString(content).getAsJsonObject().getAsJsonArray("scenarios");
        assertEquals(5, examples.size());
        var removed = examples.get(4).getAsJsonObject();
        assertFalse(removed.getAsJsonObject("target").getAsJsonObject("customerResponse").has("sources"));
        assertTrue(removed.getAsJsonObject("sourceListResponse").getAsJsonArray("data").isEmpty());
        assertEquals(Set.of("baselineSource", "expectations", "execution", "advisoryFindings", "targetSdkSignatures", "syntheticFixtures"), payload.keySet());
        var registration = CaseInputs.object(Files.readAllBytes(state.resolve("candidates").resolve(id).resolve("registration.json")));
        assertEquals(hash(content), registration.getAsJsonObject("evidenceInputHashes").get("fixtures").getAsString());
        assertEquals(hash(caseInput("expectations.json")), registration.getAsJsonObject("evidenceInputHashes").get("expectations").getAsString());
    }
    @Test void changedFixturesOrExpectationsStopDispatchEvenWithAnUnchangedReceipt() throws Exception {
        for (String input : List.of("fixtures", "expectations")) {
            var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
            fixture.models.changedInputs.put(input, caseInput(input + ".json") + "\n");
            assertEquals("INPUT_IDENTITY_MISMATCH", fixture.candidates.generate(id).get("status").getAsString());
            assertEquals(0, fixture.models.calls);
        }
    }
    @Test void evidenceChangedDuringDispatchCannotPublishAReadyCheckout() throws Exception {
        var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
        fixture.models.changeDuringDispatch = true;
        assertEquals("INPUT_IDENTITY_MISMATCH", fixture.candidates.generate(id).get("status").getAsString());
        assertEquals(1, fixture.models.calls);
        assertFalse(Files.exists(state.resolve("candidates").resolve(id).resolve("checkout")));
        fixture.candidates.generate(id); assertEquals(1, fixture.models.calls);
    }
    @Test void unavailableOversizedOrChangedFixturePagesStopRegistration() throws Exception {
        for (String condition : List.of("unavailable", "oversized", "changed-page")) {
            var fixture = fixture(proposal(BEFORE, AFTER));
            if (condition.equals("unavailable")) fixture.models.unavailableInput = "fixtures";
            if (condition.equals("oversized")) fixture.models.changedInputs.put("fixtures", "x".repeat(16001));
            if (condition.equals("changed-page")) fixture.models.unstableInput = "fixtures";
            assertThrows(IllegalArgumentException.class, () -> fixture.candidates.create(fixture.agentId, "gpt-test"));
            assertEquals(0, fixture.models.registrations); assertEquals(0, fixture.models.calls);
        }
    }
    @Test void earlierRegistrationsResumeWithoutRequiringNewFixtureInputs() throws Exception {
        var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
        Path path = state.resolve("candidates").resolve(id).resolve("registration.json");
        var registration = CaseInputs.object(Files.readAllBytes(path)); registration.remove("evidenceInputHashes");
        Files.writeString(path, CaseInputs.JSON.toJson(registration)); fixture.models.unavailableInput = "fixtures";
        assertEquals("CANDIDATE_READY", fixture.candidates.generate(id).get("status").getAsString());
        assertEquals(1, fixture.models.calls);
    }
    @Test void ambiguousModelOutcomeIsNotRetried() throws Exception {
        var fixture = fixture(proposal(BEFORE, AFTER)); String id = fixture.candidates.create(fixture.agentId, "gpt-test");
        fixture.models.failure = true;
        assertEquals("MODEL_UNKNOWN_REMOTE_OUTCOME", fixture.candidates.generate(id).get("status").getAsString());
        fixture.candidates.generate(id); assertEquals(1, fixture.models.calls);
    }
    @Test void sdkInspectionUsesOnlyThePinnedArtifact() throws Exception {
        Path jar = Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID, "target/dependencies/stripe-java-20.33.0.jar");
        String evidence = RepairCandidates.sdkEvidence(jar);
        assertTrue(evidence.contains("PaymentSourceCollection")); assertTrue(evidence.contains("autoPagingIterable"));
        String implementation = evidence.substring(evidence.indexOf("Pinned SDK implementation evidence"));
        assertTrue(implementation.contains("com/stripe/Stripe.getApiBase"));
        assertTrue(implementation.contains("Method getUrl:"));
        assertTrue(implementation.contains("com/stripe/net/ApiResource.requestCollection"));
        assertFalse(implementation.contains("PaymentSourceCollectionListParams"));
        assertFalse(implementation.contains(" create("));
        Path wrong = state.resolve("wrong.jar"); Files.writeString(wrong, "not the approved dependency");
        assertThrows(IllegalArgumentException.class, () -> RepairCandidates.sdkEvidence(wrong));
    }
    private record Fixture(RepairCandidates candidates, FakeModels models, String agentId) {}
    private static String caseInput(String file) throws Exception {
        return Files.readString(Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID, file));
    }
    private static String hash(String text) { return CaseInputs.hash(text.getBytes(StandardCharsets.UTF_8)); }
    private Fixture fixture(String response) throws Exception {
        String agentId = "agent-" + UUID.randomUUID(); Path agent = Files.createDirectories(state.resolve("agents").resolve(agentId));
        CaseInputs.write(agent.resolve("session.json"), Map.of("agentId", agentId, "contextId", "context"));
        CaseInputs.write(agent.resolve("result.json"), Map.of("agentId", agentId, "status", "INVESTIGATED", "findings", Map.of("summary", "advisory")));
        var models = new FakeModels(response);
        var inputs = Map.of("baseline-source", source(), "expectations", caseInput("expectations.json"), "fixtures", caseInput("fixtures.json"));
        var candidates = new RepairCandidates(state, models, (context, tool, args) -> {
            if (models.changed) return json(Map.of("ok", false));
            if (tool.equals("read_run")) return json(Map.of("ok", true, "value", Map.of("status", "COMPLETED", "authority", "recorded-execution", "receiptSha256", "a".repeat(64))));
            String input = args.get("inputId").getAsString();
            if (input.equals(models.unavailableInput)) return json(Map.of("ok", false));
            int offset = args.get("offset").getAsInt();
            String content = models.changedInputs.getOrDefault(input, inputs.get(input));
            if (input.equals(models.unstableInput) && offset > 0) content += "\n";
            int next = Math.min(content.length(), offset + 4000);
            return json(Map.of("ok", true, "value", Map.of("sha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)),
                    "content", content.substring(offset, next), "nextOffset", next, "totalCharacters", content.length())));
        });
        return new Fixture(candidates, models, agentId);
    }
    private static final class FakeModels implements ConsumerInvestigator.Models {
        final String response; int calls, registrations; boolean changed, failure, changeDuringDispatch;
        String unavailableInput, unstableInput; ModelCallBroker.Request request;
        final Map<String, String> changedInputs = new HashMap<>();
        FakeModels(String response) { this.response = response; }
        public String register(ModelCallBroker.Request request) { assertEquals("repair-v2", request.protocol()); this.request = request; registrations++; return "model-test"; }
        public JsonObject execute(String id) {
            calls++;
            if (changeDuringDispatch) changedInputs.put("fixtures", "changed during dispatch");
            return failure ? json(Map.of("status", "UNKNOWN_REMOTE_OUTCOME")) : json(Map.of("status", "COMPLETED", "inputTokens", 10, "outputTokens", 20, "outputText", response));
        }
    }
}
