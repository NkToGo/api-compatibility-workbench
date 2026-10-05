package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;
import static org.junit.jupiter.api.Assertions.*;

class CapturedTeamReconciliationTest {
    private static JsonObject dossier(String id, String response, String ref) { return json(Map.of("caseId", id, "response", response, "summary", "Scoped uncertainty.", "summaryCitations", List.of(ref), "findings", List.of(Map.of("kind", "LIMITATION", "statement", "Evidence remains limited.", "citations", List.of(ref))))); }
    private static JsonObject finish(String response, String ref) { return json(Map.of("action", "finish", "cases", List.of(dossier(CaseInputs.CASE_ID, response, ref), dossier(SubscriptionPeriodCase.CASE_ID, "ABSTAINED", "worker-2")))); }
    private static CapturedTeamReconciliation evidence() {
        var workers = new ArrayList<CapturedTeamReview.WorkerResult>();
        for (String id : List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)) {
            var task = new CaseTask(id, digest("case" + id), ExpectedResponse.ANSWER, List.of("hidden-grade"));
            var decision = json(Map.of("action", "finish", "cases", List.of(Map.of("caseId", id, "response", "ABSTAINED", "summary", "Insufficient evidence.", "summaryCitations", List.of(), "findings", List.of()))));
            workers.add(new CapturedTeamReview.WorkerResult(task, CapturedTeamReview.WorkerStatus.COMPLETED, "NONE", decision, List.of()));
        }
        return new CapturedTeamReconciliation(json(Map.of("workers", workers, "coordinatorReview", Map.of("status", "FAILED", "stopReason", "INVALID_MODEL_DECISION", "selection", JsonNull.INSTANCE))), new JsonArray());
    }
    private static JsonObject page(int number, String alias, String content) { return json(Map.of("pageId", "read-" + number, "caseId", CaseInputs.CASE_ID, "inputId", alias, "artifactSha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)),
            "pageSha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)), "offset", 0, "nextOffset", content.length(), "totalCharacters", content.length(), "content", content, "contentTrust", "untrusted-data")); }
    @Test void everyOriginalClaimAndDispositionStaysVisibleWithoutGradingFields() {
        var evidence = evidence(); var context = JsonParser.parseString(evidence.context(List.of(), 1, 64000)).getAsJsonObject();
        assertEquals(2, context.getAsJsonArray("originalClaims").size()); assertEquals(2, context.getAsJsonArray("workers").size()); assertFalse(context.toString().contains("hidden-grade")); assertTrue(context.getAsJsonArray("freshWorkers").isEmpty());
    }
    @Test void answeredClaimsRequireAnAnsweredWorkerOrOwnCompleteRequiredReads() {
        var evidence = evidence(); assertThrows(RuntimeException.class, () -> evidence.validate(finish("ANSWERED", "claim-1"), List.of()));
        var pages = List.of(page(1, "baseline-source", "captured source"), page(2, "execution", "[]"));
        assertDoesNotThrow(() -> evidence.validate(finish("ANSWERED", "read-1"), pages));
        assertThrows(RuntimeException.class, () -> evidence.validate(finish("ANSWERED", "read-1"), pages.subList(0, 1)));
        assertThrows(RuntimeException.class, () -> evidence.validate(finish("ABSTAINED", "worker-2"), pages));
    }
    @Test void metadataCannotBeSilentlyDroppedToFitTheContextCeiling() {
        var evidence = evidence(); var pages = List.of(page(1, "description", "x".repeat(4000)));
        var noContent = JsonParser.parseString(evidence.context(List.of(), 1, 64000)).getAsJsonObject();
        assertThrows(CoordinationRuntime.Stop.class, () -> evidence.context(pages, 1, 1));
        int ceiling = evidence.context(pages, 1, 64000).getBytes(StandardCharsets.UTF_8).length - 3000;
        var trimmed = JsonParser.parseString(evidence.context(pages, 1, ceiling)).getAsJsonObject(); assertTrue(trimmed.getAsJsonArray("recentPages").isEmpty());
        assertEquals(1, trimmed.getAsJsonArray("readMetadata").size()); assertEquals(noContent.get("originalClaims"), trimmed.get("originalClaims"));
        assertThrows(RuntimeException.class, () -> evidence.context(List.of(), 0, 64000));
    }
    @Test void stoppedWorkerWithNoDossierHasAnExactNullableRoundTrip() {
        var task = new CaseTask(CaseInputs.CASE_ID, digest("case"), ExpectedResponse.ANSWER, List.of("criterion"));
        var failed = new CapturedTeamReview.WorkerResult(task, CapturedTeamReview.WorkerStatus.UNKNOWN_REMOTE_OUTCOME, "CALL_REGISTRATION_UNCERTAIN", null, List.of());
        var record = json(failed); var retained = CapturedTeamReview.retainedWorker(record); assertNull(retained.finishDecision()); assertEquals(failed, retained);
        record.addProperty("unexpected", true); assertThrows(RuntimeException.class, () -> CapturedTeamReview.retainedWorker(record));
    }
}
