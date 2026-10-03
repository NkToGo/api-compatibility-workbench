package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationTest {
    @TempDir static Path fixture;
    @TempDir Path root;
    private static List<String> rounds;
    private final FakeModels models = new FakeModels(); private final MutableClock clock = new MutableClock();
    @BeforeAll static void prepare() throws Exception { rounds = FollowUpDispatchTest.reconciliationFixtures(fixture); }
    @BeforeEach void copyInputs() throws IOException {
        try (var paths = Files.walk(fixture)) {
            for (Path path : paths.toList()) {
                Path target = root.resolve(fixture.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
    }
    private CoordinationReview service() throws IOException { return new CoordinationReview(root, clock, models::backend); }
    private Path review(String id) { return root.resolve("coordination-reviews/" + id); }
    private String register() throws IOException { return service().registerReconciliation(rounds.get(0), "gpt-test"); }
    private static JsonObject json(Object o) { return CaseInputs.JSON.toJsonTree(o).getAsJsonObject(); }
    private static JsonObject read(Path p) throws IOException { return CaseInputs.object(Files.readAllBytes(p)); }
    private static String sha(Path p) throws IOException { return CaseInputs.hash(Files.readAllBytes(p)); }
    private static String ref(JsonObject p, String stage, String kind, int index) {
        return p.getAsJsonObject("handoff").getAsJsonObject("references").entrySet().stream().filter(e -> {
            var r = e.getValue().getAsJsonObject(); return r.get("stage").getAsString().equals(stage) && r.get("kind").getAsString().equals(kind);
        }).map(Map.Entry::getKey).toList().get(index);
    }
    private static Object readRef(String id) { return Map.of("action", "read_reference", "referenceId", id, "offset", 0, "maxCharacters", 4000); }
    private static String proposal(JsonObject p) { return p.getAsJsonObject("handoff").getAsJsonObject("proposals").keySet().iterator().next(); }
    private static Object response(JsonObject p, String coverage) {
        String question = ref(p, "FOLLOW_UP", "QUESTION", 0), answer = ref(p, "FOLLOW_UP", "CLAIM", 0), tool = ref(p, "FOLLOW_UP", "TOOL_EVENT", 0);
        return Map.of("action", "assess_response", "proposalId", proposal(p), "coverage", coverage, "statement", "Synthetic coverage advice, not semantic acceptance.", "citations", List.of(question, answer, tool));
    }
    private static Object relation(JsonObject p) {
        return Map.of("action", "relate_claims", "originalClaimId", ref(p, "ORIGINAL", "CLAIM", 0), "followUpClaimId", ref(p, "FOLLOW_UP", "CLAIM", 0),
                "relationship", "SUPPORTS", "statement", "Unreviewed synthetic relationship.", "citations", List.of(ref(p, "ORIGINAL", "CLAIM", 0), ref(p, "FOLLOW_UP", "CLAIM", 0),
                        ref(p, "ORIGINAL", "TOOL_EVENT", 0), ref(p, "FOLLOW_UP", "TOOL_EVENT", 0)));
    }
    private static Object conflict(JsonObject p, String assessment) {
        String id = p.getAsJsonObject("handoff").getAsJsonObject("conflicts").keySet().iterator().next();
        return Map.of("action", "assess_conflict", "conflictId", id, "assessment", assessment, "statement", "Earlier possible disagreement remains uncertain.",
                "citations", List.of(ref(p, "ORIGINAL", "CONFLICT", 0), ref(p, "ORIGINAL", "CLAIM", 0), ref(p, "ORIGINAL", "CLAIM", 1)));
    }
    private static Object finish(JsonObject p) { return Map.of("action", "finish", "summary", "Everything resolved according to an untrusted model summary.", "citations", List.of(ref(p, "FOLLOW_UP", "QUESTION", 0))); }
    private static Object completeDecision(JsonObject p) {
        int turn = 13 - p.get("decisionsRemaining").getAsInt();
        return switch (turn) {
            case 1 -> readRef(ref(p, "FOLLOW_UP", "QUESTION", 0));
            case 2 -> readRef(ref(p, "FOLLOW_UP", "CLAIM", 0));
            case 3 -> readRef(ref(p, "FOLLOW_UP", "TOOL_EVENT", 0));
            case 4 -> readRef(ref(p, "ORIGINAL", "CLAIM", 0));
            case 5 -> readRef(ref(p, "ORIGINAL", "CLAIM", 1));
            case 6 -> readRef(ref(p, "ORIGINAL", "CONFLICT", 0));
            case 7 -> readRef(ref(p, "ORIGINAL", "TOOL_EVENT", 0));
            case 8 -> response(p, "ANSWERED"); case 9 -> relation(p); case 10 -> conflict(p, "DISAGREEMENT_REMAINS"); default -> finish(p);
        };
    }

    @Test void freshReconciliationLinksAdviceWithoutChangingClaimsConflictsOrEarlierHistory() throws Exception {
        Path round = root.resolve("follow-ups/" + rounds.get(0)); String before = sha(round.resolve("result.json")); String id = register();
        assertEquals(0, models.registered); models.decisions = ReconciliationTest::completeDecision; var result = service().run(id);
        assertEquals("RECONCILIATION_WITH_UNRESOLVED_CONFLICTS", result.get("status").getAsString());
        var ledger = result.getAsJsonObject("reconciliation"); assertEquals("NOT_ESTABLISHED", ledger.get("semanticAcceptance").getAsString());
        assertEquals("ANSWERED", ledger.getAsJsonObject("responses").getAsJsonObject("follow-up-2").get("coverage").getAsString());
        assertEquals(3, ledger.getAsJsonObject("claimReferences").size()); assertEquals(1, ledger.getAsJsonArray("relationships").size());
        assertEquals("UNRESOLVED", ledger.getAsJsonObject("conflicts").getAsJsonObject("conflict-5").get("state").getAsString());
        assertEquals(before, sha(round.resolve("result.json"))); assertEquals(11, models.dispatched);
        assertTrue(models.requests.stream().allMatch(r -> r.protocol().equals("reconciliation-v1")));
        assertEquals(11, result.getAsJsonObject("usage").get("completedResponses").getAsInt());
        service().revoke(id); clock.now = clock.now.plus(Duration.ofHours(1)); assertEquals(result, service().run(id)); assertEquals(11, models.registered);
        Files.delete(review(id).resolve("result.json")); assertEquals(result, service().run(id)); assertEquals(11, models.dispatched);
    }

    @Test void unobservedSupportPartialPagesAndMissingCoverageRemainExplicit() throws Exception {
        String id = register(); models.decisions = p -> {
            int turn = 13 - p.get("decisionsRemaining").getAsInt();
            if (turn == 1) return response(p, "ANSWERED");
            if (turn == 2) return Map.of("action", "read_reference", "referenceId", ref(p, "FOLLOW_UP", "QUESTION", 0), "offset", 0, "maxCharacters", 1);
            return finish(p);
        };
        var result = service().run(id); assertEquals("UNSUPPORTED_REVIEW", result.get("status").getAsString());
        assertEquals("UNOBSERVED_RECONCILIATION_SUPPORT", result.getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonObject("result").get("error").getAsString());
        assertEquals("NOT_REVIEWED", result.getAsJsonObject("reconciliation").getAsJsonObject("responses").getAsJsonObject("follow-up-2").get("coverage").getAsString());
        id = register(); models.decisions = p -> 13 - p.get("decisionsRemaining").getAsInt() == 1 ? readRef(ref(p, "FOLLOW_UP", "QUESTION", 0)) : finish(p);
        assertEquals("RECONCILIATION_INCOMPLETE", service().run(id).get("status").getAsString());
    }

    @Test void stoppedChildrenCannotBecomeAnswersAndCaptureNeedsNoLiveSourcePermission() throws Exception {
        String id = service().registerReconciliation(rounds.get(1), "gpt-test");
        models.decisions = p -> {
            int turn = 13 - p.get("decisionsRemaining").getAsInt();
            if (turn == 1) return readRef(ref(p, "FOLLOW_UP", "QUESTION", 0));
            if (turn == 2) return readRef(ref(p, "FOLLOW_UP", "TASK_OUTCOME", 0));
            if (turn == 3 || turn == 4) return Map.of("action", "assess_response", "proposalId", proposal(p), "coverage", turn == 3 ? "ANSWERED" : "INSUFFICIENT_EVIDENCE",
                    "statement", "Insufficient retained answer.", "citations", List.of(ref(p, "FOLLOW_UP", "QUESTION", 0), ref(p, "FOLLOW_UP", "TASK_OUTCOME", 0)));
            return finish(p);
        };
        Files.delete(root.resolve("follow-ups/" + rounds.get(1) + "/result.json"));
        var result = service().run(id); assertEquals("UNSUPPORTED_ANSWER_COVERAGE", result.getAsJsonArray("events").get(2).getAsJsonObject().getAsJsonObject("result").get("error").getAsString());
        assertEquals("INSUFFICIENT_EVIDENCE", result.getAsJsonObject("reconciliation").getAsJsonObject("responses").getAsJsonObject("follow-up-4").get("coverage").getAsString());
        assertEquals("RECONCILIATION_INCOMPLETE", result.get("status").getAsString());
        String unfinished = rounds.get(0); Files.delete(root.resolve("follow-ups/" + unfinished + "/result.json"));
        int before = models.registered; assertThrows(IllegalArgumentException.class, () -> service().registerReconciliation(unfinished, "gpt-test")); assertEquals(before, models.registered);
    }

    @Test void copiedGrantsExpiryRevocationAndChangedCapturesStopBeforePaidDispatch() throws Exception {
        String revoked = register(), foreign = register(), expired = register(), changed = register();
        service().revoke(revoked); assertEquals("REVIEW_REVOKED", service().run(revoked).get("status").getAsString());
        Files.copy(review(revoked).resolve("grant.json"), review(foreign).resolve("grant.json"), StandardCopyOption.REPLACE_EXISTING);
        assertEquals("REVIEW_AUTHORIZATION_DENIED", service().run(foreign).get("status").getAsString());
        Files.writeString(review(changed).resolve("inputs/handoff.json"), "changed");
        assertThrows(RuntimeException.class, () -> service().run(changed));
        clock.now = clock.now.plus(Duration.ofMinutes(20)); assertEquals("REVIEW_EXPIRED", service().run(expired).get("status").getAsString()); assertEquals(0, models.dispatched);
    }

    @Test void activeCallRecoveryUnknownOutcomesAndRegistrationGapsNeverCreateReplacementCalls() throws Exception {
        String id = register(); models.activeOnce = true;
        assertEquals("ACTIVE", service().run(id).get("status").getAsString()); assertEquals(1, models.registered);
        var result = service().run(id); assertEquals("RECONCILIATION_WITH_UNRESOLVED_CONFLICTS", result.get("status").getAsString()); assertEquals(11, models.dispatched);
        String uncertain = register(); models.gapOnce = true; assertThrows(IOException.class, () -> service().run(uncertain));
        result = service().run(uncertain); assertEquals("CALL_REGISTRATION_UNCERTAIN", result.get("status").getAsString()); assertEquals(1, result.getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        String unknown = register(); models.unknownOnce = true;
        result = service().run(unknown); assertEquals("MODEL_UNKNOWN_REMOTE_OUTCOME", result.get("status").getAsString()); assertEquals(1, result.getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
        int before = models.registered; assertEquals(result, service().run(unknown)); assertEquals(before, models.registered);
    }

    @Test void changedLedgerCannotEraseAConflictAcceptSemanticsOrMoveClaimsBetweenTasks() throws Exception {
        String id = register(); var original = service().run(id);
        for (String change : List.of("conflicts", "semanticAcceptance", "claimReferences", "responses", "relationships", "status")) {
            var edited = original.deepCopy(); var ledger = edited.getAsJsonObject("reconciliation");
            if (change.equals("status")) edited.addProperty("status", "RECONCILIATION_RECORDED");
            else if (change.equals("semanticAcceptance")) ledger.addProperty(change, "ACCEPTED");
            else ledger.add(change, new JsonObject());
            DurableExecution.publish(review(id).resolve("result.json"), edited); assertThrows(RuntimeException.class, () -> service().run(id));
        }
        DurableExecution.publish(review(id).resolve("result.json"), original); assertEquals(original, service().run(id));
        models.afterResponse = () -> { try { service().revoke(models.currentReview); } catch (IOException e) { throw new IllegalStateException(e); } };
        String revokeDuring = register(); models.currentReview = revokeDuring;
        var stopped = service().run(revokeDuring); assertEquals("REVIEW_REVOKED", stopped.get("status").getAsString());
        assertEquals(1, stopped.getAsJsonObject("usage").get("completedResponses").getAsInt()); assertEquals(0, stopped.getAsJsonArray("events").size());
    }

    @Test void protocolCannotAddExecutionResolutionOrExtraFields() {
        for (String decision : List.of("{\"action\":\"run_case\"}", "{\"action\":\"resolve_conflict\",\"state\":\"RESOLVED\"}",
                "{\"action\":\"assess_response\",\"proposalId\":\"follow-up-2\",\"coverage\":\"ANSWERED\",\"statement\":\"claim\",\"citations\":[\"ref-1\"],\"permission\":\"execute\"}"))
            assertThrows(RuntimeException.class, () -> ReconciliationDecision.parse("{\"decision\":" + decision + "}"));
        var body = CaseInputs.object(new ModelCallBroker.Request("gpt-test", "test", 2048, "reconciliation-v1").body());
        assertEquals("reconciliation_decision", body.getAsJsonObject("tool_choice").get("name").getAsString());
        assertEquals(ReconciliationDecision.INSTRUCTIONS, body.get("instructions").getAsString());
    }
    @Test void scopeClaimsForeignResponsesAndDuplicateAssessmentsAreRejectedByDeterministicGuards() throws Exception {
        String id = register(); Path directory = review(id); var handoff = read(directory.resolve("inputs/handoff.json"));
        var p = json(Map.of("handoff", handoff)); Set<String> seen = handoff.getAsJsonObject("references").keySet(); var history = new JsonArray();
        var scope = json(conflict(p, "SCOPE_DIFFERENCE"));
        assertEquals("UNPROVEN_SCOPE_DIFFERENCE", ReconciliationEvidence.act(directory, handoff, scope, seen, history, 1).get("error").getAsString());
        var foreign = json(relation(p)); foreign.addProperty("originalClaimId", ref(p, "ORIGINAL", "CLAIM", 1));
        foreign.add("citations", CaseInputs.JSON.toJsonTree(List.of(ref(p, "ORIGINAL", "CLAIM", 1), ref(p, "FOLLOW_UP", "CLAIM", 0))));
        assertEquals("FOREIGN_CLAIM_RELATIONSHIP", ReconciliationEvidence.act(directory, handoff, foreign, seen, history, 1).get("error").getAsString());
        var answer = json(response(p, "ANSWERED")); var record = ReconciliationEvidence.act(directory, handoff, answer, seen, history, 1);
        assertTrue(record.get("ok").getAsBoolean()); history.add(json(Map.of("result", record)));
        assertEquals("RESPONSE_ALREADY_ASSESSED", ReconciliationEvidence.act(directory, handoff, answer, seen, history, 2).get("error").getAsString());
        Path tool = directory.resolve("inputs/references/" + ref(p, "FOLLOW_UP", "TOOL_EVENT", 0) + ".json"); byte[] original = Files.readAllBytes(tool);
        var failed = read(tool); failed.getAsJsonObject("value").getAsJsonObject("result").addProperty("ok", false); DurableExecution.publish(tool, failed);
        assertEquals("UNSUPPORTED_ANSWER_COVERAGE", ReconciliationEvidence.act(directory, handoff, answer, seen, new JsonArray(), 1).get("error").getAsString());
        Files.write(tool, original); assertEquals(0, models.registered);
        try (var paths = Files.walk(directory.resolve("inputs"))) { assertTrue(paths.noneMatch(path -> Set.of("grant.json", "authorization.json", "request.json").contains(path.getFileName().toString()))); }
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.now(); public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
    }
    private static final class FakeModels {
        int registered, dispatched; boolean activeOnce, gapOnce, unknownOnce; Runnable afterResponse; String currentReview;
        Function<JsonObject,Object> decisions = ReconciliationTest::completeDecision; final List<ModelCallBroker.Request> requests = new ArrayList<>();
        ConsumerInvestigator.Models backend(Path state) throws IOException {
            var broker = new ModelCallBroker(state.resolve("model-calls"));
            return new ConsumerInvestigator.Models() {
                public String register(ModelCallBroker.Request r) throws IOException {
                    var payload = CaseInputs.object(r.input().getBytes(StandardCharsets.UTF_8)); Object decision = decisions.apply(payload);
                    String id = broker.register(r, Duration.ofMinutes(2)); registered++; requests.add(r);
                    DurableExecution.publish(state.resolve("model-calls/" + id + "/fixture.json"), json(Map.of("status", "COMPLETED", "remoteOutcome", "RESPONSE_RECEIVED", "inputTokens", 10,
                            "outputTokens", 3, "outputText", CaseInputs.JSON.toJson(Map.of("decision", decision)))));
                    if (gapOnce) { gapOnce = false; throw new IOException("Synthetic registration interruption"); } return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path target = state.resolve("model-calls/" + id + "/result.json"); if (Files.exists(target)) return read(target);
                    if (activeOnce) { activeOnce = false; return json(Map.of("status", "ACTIVE")); }
                    var result = unknownOnce ? json(Map.of("status", "UNKNOWN_REMOTE_OUTCOME", "remoteOutcome", "UNKNOWN")) : read(state.resolve("model-calls/" + id + "/fixture.json")); unknownOnce = false;
                    DurableExecution.publish(target, result); dispatched++;
                    if (afterResponse != null) { var callback = afterResponse; afterResponse = null; callback.run(); } return result;
                }
            };
        }
    }
}
