package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.nktogo.apicompatibility.runner.InvoiceTeamReview.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTeamReconciliationTest {
    private static final ContentDigest INPUT = new ContentDigest("a".repeat(64));
    private static JsonObject json(Object value) { return new GsonBuilder().serializeNulls().create().toJsonTree(value).getAsJsonObject(); }
    private static String raw(JsonObject value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static JsonObject page(int n, String alias, String content) {
        return json(Map.of("kind", "read_text", "pageId", "read-" + n, "contentTrust", "untrusted-data", "alias", alias,
                "sha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)), "offset", 0, "nextOffset", content.length(), "totalCharacters", content.length(), "content", content));
    }
    private static JsonObject metadata() { return json(Map.of("caseId", InvoiceRenewalFixtures.CASE_ID, "bundleIdentity", INPUT.sha256(), "sourceExecutionCoverageSatisfied", true)); }
    private static JsonObject finish(String response, String... citations) { return json(Map.of("action", "finish", "response", response, "summary", "Advisory explanation", "summaryCitations", List.of(citations),
            "findings", List.of(Map.of("kind", "LIMITATION", "statement", "Captured outcomes remain limited.", "citations", List.of(citations))))); }
    private static List<JsonObject> ownPages() { return List.of(page(1, "task", "task"), page(2, "build-configuration", "config"),
            page(3, InvoiceWorkerDecision.baseline("invoice-reconciliation"), "recon source"), page(4, InvoiceWorkerDecision.baseline("invoice-webhook"), "webhook source"),
            page(5, "business/variant/reconciliation", "observation"), page(6, "business/variant/webhook", "observation"), page(7, "order/variant/both", "ledger")); }
    private static WorkerResult worker(String role, String response) { return new WorkerResult(role, INPUT, WorkerStatus.COMPLETED, "NONE", finish(response, "read-1"), metadata(), ownPages()); }
    private static JsonObject prefix(String response, boolean questions) {
        var workers = InvoiceWorkerDecision.WORKERS.stream().map(r -> worker(r, response)).toList(); var proposals = new ArrayList<Object>();
        if (questions) for (int i = 0; i < 2; i++) proposals.add(Map.of("workerId", InvoiceWorkerDecision.WORKERS.get(i), "question", "Which ledger effects require fresh inspection?", "citations", List.of("worker-" + (i + 1))));
        var d = json(Map.of("action", "finish", "summary", "Original advisory review", "citations", List.of("worker-1", "worker-2"), "followUps", proposals,
                "conflicts", List.of(Map.of("claimIds", List.of("claim-2", "claim-4"), "statement", "Scopes may differ."))));
        return json(Map.of("workers", workers, "coordinatorReview", Map.of("status", "COMPLETED", "stopReason", "NONE", "pages", List.of(), "selection", new InvoiceTeamReview(workers).select(d, List.of()))));
    }
    private static JsonArray fresh(JsonObject prefix, String response) {
        var fresh = new JsonArray(); for (var q : prefix.getAsJsonObject("coordinatorReview").getAsJsonObject("selection").getAsJsonArray("selectedFollowUps")) {
            var question = q.getAsJsonObject(); String role = question.get("workerId").getAsString(); fresh.add(json(Map.of("question", question, "participant", InvoiceTeamReconciliation.freshParticipant(role), "worker", worker(role, response))));
        } return fresh;
    }
    @Test void allOriginalAndFreshClaimsRemainDistinctAndConflictsStayUnresolved() {
        var prefix = prefix("ANSWERED", true); var guard = new InvoiceTeamReconciliation(prefix, fresh(prefix, "ANSWERED")); var evidence = guard.evidence();
        assertEquals(4, evidence.getAsJsonArray("originalClaims").size()); assertEquals(4, evidence.getAsJsonArray("freshClaims").size()); assertEquals(2, evidence.getAsJsonArray("freshWorkers").size());
        assertEquals("UNRESOLVED", evidence.getAsJsonArray("possibleConflicts").get(0).getAsJsonObject().get("state").getAsString());
        assertDoesNotThrow(() -> guard.validate(finish("ANSWERED", "claim-1", "freshclaim-3"), List.of()));
        var changed = guard.evidence(); changed.getAsJsonArray("originalClaims").remove(0); prefix.getAsJsonArray("workers").remove(0); assertEquals(evidence, guard.evidence());
    }
    @Test void answeredNeedsClaimsFromBothAnsweredRolesRatherThanOutcomesOrOneRole() {
        var prefix = prefix("ANSWERED", true); var guard = new InvoiceTeamReconciliation(prefix, fresh(prefix, "ANSWERED"));
        for (var d : List.of(finish("ANSWERED", "worker-1", "freshworker-2"), finish("ANSWERED", "claim-1", "freshclaim-1"), finish("ANSWERED", "claim-1")))
            assertThrows(RuntimeException.class, () -> guard.validate(d, List.of()));
        assertDoesNotThrow(() -> guard.validate(finish("ANSWERED", "claim-2", "claim-4"), List.of()));
    }
    @Test void abstentionClaimsDoNotBecomeAnsweredByAgreementButOwnJointReadsCanSatisfyAccess() {
        var prefix = prefix("ABSTAINED", true); var guard = new InvoiceTeamReconciliation(prefix, fresh(prefix, "ABSTAINED"));
        assertThrows(RuntimeException.class, () -> guard.validate(finish("ANSWERED", "claim-1", "freshclaim-3"), List.of()));
        assertDoesNotThrow(() -> guard.validate(finish("ABSTAINED", "claim-1", "freshclaim-3"), List.of()));
        assertDoesNotThrow(() -> guard.validate(finish("ANSWERED", "read-1"), ownPages()));
        assertThrows(RuntimeException.class, () -> guard.validate(finish("ANSWERED", "worker-1"), ownPages()));
        assertThrows(RuntimeException.class, () -> guard.validate(finish("ANSWERED", "read-1"), ownPages().subList(0, 6)));
    }
    @Test void missingCancelledAndUnknownFreshOutcomesNeverInventClaims() {
        for (var status : List.of(WorkerStatus.FAILED, WorkerStatus.CANCELLED, WorkerStatus.UNKNOWN_REMOTE_OUTCOME)) {
            var prefix = prefix("ABSTAINED", true); var fresh = fresh(prefix, "ABSTAINED"); var entry = fresh.get(0).getAsJsonObject();
            entry.add("worker", json(new WorkerResult("invoice-reconciliation", INPUT, status, "STOPPED", null, null, List.of())));
            var evidence = new InvoiceTeamReconciliation(prefix, fresh).evidence(); assertEquals(2, evidence.getAsJsonArray("freshClaims").size());
            assertEquals("NOT_PROVIDED", evidence.getAsJsonArray("freshWorkers").get(0).getAsJsonObject().get("response").getAsString());
        }
    }
    @Test void exactSelectedQuestionsRoleBundleParticipantAndOrderBindFreshOutcomes() {
        var prefix = prefix("ANSWERED", true); var correct = fresh(prefix, "ANSWERED");
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliation(prefix, new JsonArray()));
        var swapped = new JsonArray(); swapped.add(correct.get(1)); swapped.add(correct.get(0)); assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliation(prefix, swapped));
        for (String field : List.of("participant", "question")) { var altered = correct.deepCopy(); altered.get(0).getAsJsonObject().addProperty(field, "changed"); assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliation(prefix, altered)); }
        var changedWorker = correct.deepCopy(); changedWorker.get(0).getAsJsonObject().getAsJsonObject("worker").addProperty("workerId", "invoice-webhook"); assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliation(prefix, changedWorker));
        var changedInput = correct.deepCopy(); changedInput.get(0).getAsJsonObject().getAsJsonObject("worker").getAsJsonObject("inputIdentity").addProperty("sha256", "b".repeat(64)); assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliation(prefix, changedInput));
        var changedSelection = prefix.deepCopy(); changedSelection.getAsJsonObject("coordinatorReview").getAsJsonObject("selection").getAsJsonArray("selectedFollowUps").get(0).getAsJsonObject().addProperty("delegationDepth", 2);
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliation(changedSelection, correct));
    }
    @Test void stoppedReviewHasNoFollowUpsButRetainsAllOriginalOutcomes() {
        var prefix = prefix("ANSWERED", true); var review = prefix.getAsJsonObject("coordinatorReview"); review.addProperty("status", "FAILED"); review.addProperty("stopReason", "INVALID_MODEL_DECISION"); review.add("selection", JsonNull.INSTANCE);
        var evidence = new InvoiceTeamReconciliation(prefix, new JsonArray()).evidence(); assertEquals(4, evidence.getAsJsonArray("originalClaims").size()); assertTrue(evidence.getAsJsonArray("freshWorkers").isEmpty()); assertTrue(evidence.getAsJsonArray("possibleConflicts").isEmpty());
    }
    @Test void freshContextDisclosesOnlyTheSelectedQuestionAnchorsAndOwnPages() {
        var prefix = prefix("ANSWERED", true); var question = InvoiceTeamReconciliation.selection(prefix).getAsJsonArray("selectedFollowUps").get(0).getAsJsonObject();
        var c = JsonParser.parseString(InvoiceTeamReconciliation.freshContext(prefix, question, metadata(), List.of(page(1, "task", "fresh text")), 10, 64000)).getAsJsonObject();
        assertEquals(1, c.getAsJsonArray("questionAnchors").size()); assertEquals("worker-1", c.getAsJsonArray("questionAnchors").get(0).getAsJsonObject().get("referenceId").getAsString());
        assertFalse(c.has("workers")); assertFalse(c.has("originalClaims")); assertEquals(1, c.getAsJsonArray("recentPages").size());
        var forged = question.deepCopy(); forged.addProperty("question", "A different question"); assertThrows(RuntimeException.class, () -> InvoiceTeamReconciliation.freshContext(prefix, forged, metadata(), List.of(), 10, 64000));
    }
    @Test void allClaimsAndMetadataSurviveUtf8TrimmingOrContextStopsExplicitly() {
        var prefix = prefix("ANSWERED", true); var guard = new InvoiceTeamReconciliation(prefix, fresh(prefix, "ANSWERED")); var pages = new ArrayList<JsonObject>();
        for (int i = 1; i <= 7; i++) pages.add(page(i, "task", "é".repeat(3000)));
        var full = JsonParser.parseString(guard.context(pages, 6, 64000)).getAsJsonObject(); assertEquals(6, full.getAsJsonArray("recentPages").size());
        String trimmed = guard.context(pages, 6, 24000); assertTrue(trimmed.getBytes(StandardCharsets.UTF_8).length <= 24000); var c = JsonParser.parseString(trimmed).getAsJsonObject();
        assertEquals(7, c.getAsJsonArray("readMetadata").size()); assertEquals(4, c.getAsJsonArray("originalClaims").size()); assertEquals(4, c.getAsJsonArray("freshClaims").size()); assertTrue(c.getAsJsonArray("recentPages").size() < 6);
        assertEquals("CONTEXT_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> guard.context(pages, 6, 1)).getMessage());
    }
    @Test void unseenAndCatalogReadIdsCannotBeUsedAsFinalEvidence() {
        var guard = new InvoiceTeamReconciliation(prefix("ANSWERED", false), new JsonArray());
        for (String id : List.of("read-1", "freshclaim-1", "claim-99")) assertThrows(RuntimeException.class, () -> guard.validate(finish("ABSTAINED", id), List.of()));
        var catalog = json(Map.of("kind", "catalog", "pageId", "read-1", "contentTrust", "untrusted-data", "offset", 0, "nextOffset", 0, "totalArtifacts", 0, "artifacts", List.of()));
        assertThrows(RuntimeException.class, () -> guard.validate(finish("ABSTAINED", "read-1"), List.of(catalog)));
    }
    @Test void completeOwnReadsCannotHideMissingSourceExecutionCoverage() {
        var prefix = prefix("ABSTAINED", false); var workers = new ArrayList<WorkerResult>();
        for (var item : prefix.getAsJsonArray("workers")) {
            var w = InvoiceTeamReview.retainedWorker(item.getAsJsonObject()); var meta = w.metadata(); meta.addProperty("sourceExecutionCoverageSatisfied", false);
            workers.add(new WorkerResult(w.workerId(), INPUT, w.status(), w.stopReason(), w.finishDecision(), meta, w.pages()));
        }
        prefix.add("workers", CaseInputs.JSON.toJsonTree(workers)); var review = prefix.getAsJsonObject("coordinatorReview"); var d = review.getAsJsonObject("selection").getAsJsonObject("reviewDecision");
        review.add("selection", new InvoiceTeamReview(workers).select(d, List.of())); var guard = new InvoiceTeamReconciliation(prefix, new JsonArray());
        assertThrows(RuntimeException.class, () -> guard.validate(finish("ANSWERED", "read-1"), ownPages()));
        assertDoesNotThrow(() -> guard.validate(finish("ABSTAINED", "read-1"), ownPages()));
    }
    @Test void strictProtocolPreservesAdviceReferenceIdsAndRejectsExtraAuthorityMalformedOrRecursiveActions() {
        var d = finish("ABSTAINED", "claim-1", "worker-2", "freshclaim-3", "freshworker-1", "read-4"); assertEquals(d, InvoiceReconciliationDecision.parse(raw(d)));
        for (String id : List.of("claim-0", "claim-001", "worker-1000", "follow-up-1", "read-1/../key")) assertThrows(RuntimeException.class, () -> InvoiceReconciliationDecision.parse(raw(finish("ABSTAINED", id))));
        assertThrows(RuntimeException.class, () -> InvoiceReconciliationDecision.parse(raw(finish("ABSTAINED", "claim-1", "claim-1"))));
        d.addProperty("approved", true); assertThrows(RuntimeException.class, () -> InvoiceReconciliationDecision.parse(raw(d)));
        for (String value : List.of(raw(finish("ABSTAINED", "claim-1")) + " {}", "{\"decision\":{\"action\":\"finish\",\"action\":\"finish\"}}", raw(json(Map.of("action", "delegate"))))) assertThrows(RuntimeException.class, () -> InvoiceReconciliationDecision.parse(value));
    }
    @Test void brokerRegistersStrictFixedFunctionsAndDoesNotEnableParallelModelTools() {
        for (String protocol : List.of(InvoiceFollowUpDecision.WEBHOOK, InvoiceFollowUpDecision.RECONCILIATION, InvoiceReconciliationDecision.PROTOCOL)) {
            var request = new ModelCallBroker.Request("gpt-5.6-terra", "bounded test context", 2048, protocol); var body = CaseInputs.object(request.body());
            String function = protocol.equals(InvoiceReconciliationDecision.PROTOCOL) ? InvoiceReconciliationDecision.FUNCTION : InvoiceInvestigationDecision.FUNCTION;
            assertEquals(function, body.getAsJsonObject("tool_choice").get("name").getAsString()); var tool = body.getAsJsonArray("tools").get(0).getAsJsonObject(); assertTrue(tool.get("strict").getAsBoolean());
            assertFalse(body.get("parallel_tool_calls").getAsBoolean()); assertFalse(body.get("store").getAsBoolean()); assertFalse(body.get("background").getAsBoolean());
        }
        assertTrue(InvoiceFollowUpDecision.instructions(InvoiceFollowUpDecision.WEBHOOK).contains("delegatedQuestion")); assertFalse(InvoiceFollowUpDecision.instructions(InvoiceFollowUpDecision.WEBHOOK).contains("No sibling messages, claims or grading feedback are supplied."));
    }
}
