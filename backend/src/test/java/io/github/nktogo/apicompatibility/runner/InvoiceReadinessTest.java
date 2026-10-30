package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.nktogo.apicompatibility.runner.InvoiceReadiness.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceReadinessTest {
    private static final ContentDigest INPUT = new ContentDigest("1".repeat(64)), ARTIFACT = new ContentDigest("2".repeat(64));
    private static final Subject JOINT = new Subject("invoice-single-agent", INPUT, Scope.JOINT);
    private static final String WEBHOOK = "source/webhook-baseline/InvoiceWebhookConsumer.java";
    private static final String RECONCILIATION = "source/reconciliation-baseline/InvoiceReconciliationConsumer.java";
    private static Subject role(Scope scope) { return new Subject("invoice-" + scope.name().toLowerCase(Locale.ROOT), INPUT, scope); }
    private static TextPage text(Subject subject, int id, String alias, int start, int end, int total) {
        return new TextPage(subject, "read-" + id, alias, ARTIFACT, start, end, total);
    }
    private static TextPage whole(Subject subject, int id, String alias) { return text(subject, id, alias, 0, 100, 100); }
    private static List<Page> full(Subject subject) {
        var pages = new ArrayList<Page>(); pages.add(whole(subject, 1, "task")); pages.add(whole(subject, 2, "build-configuration"));
        if (subject.scope() != Scope.RECONCILIATION) pages.add(whole(subject, 3, WEBHOOK));
        if (subject.scope() != Scope.WEBHOOK) pages.add(whole(subject, 4, RECONCILIATION));
        pages.add(whole(subject, 5, "business/control/" + (subject.scope() == Scope.RECONCILIATION ? "reconciliation" : "webhook")));
        pages.add(whole(subject, 6, "order/control/webhook-first")); return pages;
    }
    private static ArtifactCoverage coverage(Snapshot snapshot, String alias) {
        return snapshot.requiredReads().stream().filter(c -> c.alias().equals(alias)).findFirst().orElseThrow();
    }

    @Test void unreadInputsKeepUnknownLengthAndCannotBecomeReady() {
        var result = inspect(JOINT, List.of(), true);
        assertFalse(result.structuralAccessReady()); assertEquals(4, result.requiredReads().size());
        for (var item : result.requiredReads()) {
            assertEquals(CoverageState.UNREAD, item.state()); assertNull(item.totalCharacters()); assertNull(item.artifactIdentity());
            assertTrue(item.coveredRanges().isEmpty()); assertTrue(item.missingRanges().isEmpty());
        }
        assertEquals(0, result.businessEvidence().observedArtifacts()); assertNull(result.businessEvidence().firstCompleteAlias());
        assertTrue(result.toJson().getAsJsonArray("requiredReads").get(0).getAsJsonObject().get("totalCharacters").isJsonNull());
    }
    @Test void completeAccessIsExplicitlySeparateFromSemanticReview() {
        var result = inspect(JOINT, full(JOINT), true);
        assertTrue(result.structuralAccessReady()); assertEquals(1, result.formatVersion()); assertEquals("invoice-readiness-v1", result.policy());
        assertEquals("NOT_PERFORMED", result.qualityReview()); assertEquals("supplied-read-metadata-not-semantic-acceptance", result.authority());
        assertEquals(JOINT, result.subject()); assertEquals(6, result.suppliedReadCount());
        assertEquals("business/control/webhook", result.businessEvidence().firstCompleteAlias());
        assertFalse(result.toJson().has("acceptedAnswer")); assertFalse(result.toJson().has("executionPermission"));
    }
    @Test void jointScopeRequiresBothBaselineSourcesWhileRolesRequireTheirOwn() {
        for (var scope : List.of(Scope.WEBHOOK, Scope.RECONCILIATION)) {
            var subject = role(scope); var result = inspect(subject, full(subject), true);
            assertTrue(result.structuralAccessReady()); assertEquals(3, result.requiredReads().size());
        }
        var pages = new ArrayList<>(full(JOINT)); pages.removeIf(p -> p instanceof TextPage t && t.alias().equals(RECONCILIATION));
        assertFalse(inspect(JOINT, pages, true).structuralAccessReady());
    }
    @Test void aSiblingBusinessObservationCannotFillRoleSpecificAccess() {
        var subject = role(Scope.RECONCILIATION); var pages = new ArrayList<>(full(subject));
        pages.removeIf(p -> p instanceof TextPage t && t.alias().startsWith("business/"));
        pages.add(whole(subject, 7, "business/control/webhook")); pages.add(text(subject, 8, "business/control/reconciliation", 0, 50, 100));
        var result = inspect(subject, pages, true);
        assertFalse(result.structuralAccessReady()); assertEquals(new EvidenceGroup(1, 0, null), result.businessEvidence());
    }
    @Test void missingMiddleOfAReadIsReportedRatherThanCountingLastOffsetAsComplete() {
        var pages = new ArrayList<>(full(JOINT)); pages.removeIf(p -> p.pageId().equals("read-1"));
        pages.add(text(JOINT, 7, "task", 0, 40, 100)); pages.add(text(JOINT, 8, "task", 60, 100, 100));
        var result = inspect(JOINT, pages, true); var task = coverage(result, "task");
        assertFalse(result.structuralAccessReady()); assertEquals(CoverageState.PARTIAL, task.state());
        assertEquals(List.of(new Span(0, 40), new Span(60, 100)), task.coveredRanges());
        assertEquals(List.of(new Span(40, 60)), task.missingRanges());
    }
    @Test void gapsAtBothEndsStayVisible() {
        var task = coverage(inspect(JOINT, List.of(text(JOINT, 1, "task", 20, 80, 100)), true), "task");
        assertEquals(List.of(new Span(0, 20), new Span(80, 100)), task.missingRanges());
    }
    @Test void overlappingAdjacentRepeatedAndOutOfOrderRangesHaveOneUnion() {
        var pages = List.of(text(JOINT, 10, "task", 40, 80, 100), text(JOINT, 2, "task", 0, 50, 100),
                text(JOINT, 9, "task", 80, 100, 100), text(JOINT, 3, "task", 0, 50, 100));
        var result = inspect(JOINT, pages, true); var task = coverage(result, "task");
        assertEquals(CoverageState.COMPLETE, task.state()); assertEquals(List.of(new Span(0, 100)), task.coveredRanges());
        assertTrue(task.missingRanges().isEmpty()); assertEquals(List.of("read-2", "read-3", "read-9", "read-10"), result.citableTextPageIds());
        var reversed = new ArrayList<>(pages); Collections.reverse(reversed); assertEquals(result.toJson(), inspect(JOINT, reversed, true).toJson());
    }
    @Test void changedArtifactIdentityCannotBeMergedIntoOneCompleteRead() {
        var changed = new TextPage(JOINT, "read-2", "task", new ContentDigest("3".repeat(64)), 50, 100, 100);
        assertThrows(IllegalArgumentException.class, () -> inspect(JOINT, List.of(text(JOINT, 1, "task", 0, 50, 100), changed), true));
    }
    @Test void changedArtifactLengthCannotBeMerged() {
        assertThrows(IllegalArgumentException.class, () -> inspect(JOINT,
                List.of(text(JOINT, 1, "task", 0, 50, 100), text(JOINT, 2, "task", 50, 101, 101)), true));
    }
    @Test void pagesFromAnotherInputAreRejected() {
        var other = new Subject(JOINT.participantId(), new ContentDigest("4".repeat(64)), Scope.JOINT);
        assertThrows(IllegalArgumentException.class, () -> inspect(JOINT, List.of(whole(other, 1, "task")), true));
    }
    @Test void pagesFromAnotherParticipantAreRejectedEvenWhenAliasesAndInputMatch() {
        var other = new Subject("another-investigator", INPUT, Scope.JOINT);
        assertThrows(IllegalArgumentException.class, () -> inspect(JOINT, List.of(whole(other, 1, "task")), true));
    }
    @Test void anAlteredRoleCannotReuseOldReadOwnership() {
        var subject = role(Scope.WEBHOOK); var other = new Subject(subject.participantId(), INPUT, Scope.RECONCILIATION);
        assertThrows(IllegalArgumentException.class, () -> inspect(subject, List.of(whole(other, 1, "task")), true));
    }
    @Test void duplicatePageIdsAcrossKindsAreAmbiguousAndRejected() {
        assertThrows(IllegalArgumentException.class, () -> inspect(JOINT,
                List.of(whole(JOINT, 1, "task"), new CataloguePage(JOINT, "read-1")), true));
    }
    @Test void cataloguesRemainNonCitableAndCannotEstablishTextCoverage() {
        var result = inspect(JOINT, List.of(new CataloguePage(JOINT, "read-33"), new CataloguePage(JOINT, "read-35")), true);
        assertTrue(result.citableTextPageIds().isEmpty()); assertEquals(List.of("read-33", "read-35"), result.nonCitableCataloguePageIds());
        assertTrue(result.requiredReads().stream().allMatch(r -> r.state() == CoverageState.UNREAD)); assertFalse(result.structuralAccessReady());
    }
    @Test void completeReadsCannotOverrideAnIncompleteExecutionCoverageFlag() {
        var result = inspect(JOINT, full(JOINT), false);
        assertFalse(result.structuralAccessReady()); assertFalse(result.sourceExecutionCoverageSatisfied());
        assertTrue(result.requiredReads().stream().allMatch(r -> r.state() == CoverageState.COMPLETE));
    }
    @Test void anObservedEmptyArtifactIsDifferentFromAnUnreadArtifact() {
        var result = inspect(JOINT, List.of(text(JOINT, 1, "task", 0, 0, 0)), true);
        assertEquals(CoverageState.COMPLETE, coverage(result, "task").state()); assertEquals(0, coverage(result, "task").totalCharacters());
        assertEquals(ARTIFACT, coverage(result, "task").artifactIdentity());
        assertEquals(CoverageState.UNREAD, coverage(result, "build-configuration").state());
    }
    @Test void zeroLengthProgressAtTheEndDoesNotInventPriorContentCoverage() {
        var task = coverage(inspect(JOINT, List.of(text(JOINT, 1, "task", 100, 100, 100)), true), "task");
        assertEquals(CoverageState.PARTIAL, task.state()); assertEquals(List.of(new Span(0, 100)), task.missingRanges());
    }
    @Test void invalidRangesIdsAndLogicalAliasesAreRejectedBeforeInspection() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 1, "task", -1, 10, 100)),
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 1, "task", 20, 10, 100)),
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 1, "task", 0, 101, 100)),
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 1, "task", 0, 4001, 5000)),
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 1, "task", 0, 1, 1048577)),
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 0, "task", 0, 1, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> text(JOINT, 1000, "task", 0, 1, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> whole(JOINT, 1, "../task")),
                () -> assertThrows(IllegalArgumentException.class, () -> whole(JOINT, 1, "/task")),
                () -> assertThrows(IllegalArgumentException.class, () -> whole(JOINT, 1, "task\\other")),
                () -> assertThrows(IllegalArgumentException.class, () -> whole(JOINT, 1, "task\nother")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Subject("../worker", INPUT, Scope.JOINT)));
    }
    @Test void theReadCountBoundCannotBeExpandedByCataloguePages() {
        var pages = new ArrayList<Page>(); for (int i = 1; i <= 101; i++) pages.add(new CataloguePage(JOINT, "read-" + i));
        assertThrows(IllegalArgumentException.class, () -> inspect(JOINT, pages, true));
    }
    @Test void snapshotsDoNotRetainMutableCallerCollections() {
        var pages = new ArrayList<>(full(JOINT)); var result = inspect(JOINT, pages, true); var before = result.toJson(); pages.clear();
        assertEquals(before, result.toJson()); assertThrows(UnsupportedOperationException.class, () -> result.citableTextPageIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> coverage(result, "task").coveredRanges().clear());
        result.toJson().remove("requiredReads"); assertEquals(before, result.toJson());
    }
    @Test void validStructuralAccessMatchesExistingGuardsWithoutChangingTheirContexts() {
        for (var scope : Scope.values()) {
            var subject = scope == Scope.JOINT ? JOINT : role(scope); var pages = full(subject); var legacy = new ArrayList<JsonObject>();
            for (var page : pages) {
                var text = (TextPage) page;
                legacy.add(CaseInputs.JSON.toJsonTree(Map.of("kind", "read_text", "pageId", text.pageId(), "alias", text.alias(),
                        "offset", text.offset(), "nextOffset", text.nextOffset(), "totalCharacters", text.totalCharacters())).getAsJsonObject());
            }
            var metadata = CaseInputs.JSON.toJsonTree(Map.of("sourceExecutionCoverageSatisfied", true)).getAsJsonObject();
            var decision = CaseInputs.JSON.toJsonTree(Map.of("response", "ANSWERED", "summaryCitations", List.of("read-1"),
                    "findings", List.of(Map.of("citations", List.of("read-1"))))).getAsJsonObject();
            assertTrue(inspect(subject, pages, true).structuralAccessReady());
            assertTrue(scope == Scope.JOINT ? InvoiceSingleAgent.supported(decision, legacy, metadata)
                    : InvoiceWorkerDecision.supported(subject.participantId(), decision, legacy, metadata));
            var context = CaseInputs.object(InvoiceSingleAgent.context(metadata, legacy, 1, 64000).getBytes(StandardCharsets.UTF_8));
            assertEquals(Set.of("availableTools", "case", "decisionsRemaining", "qualityReview", "readMetadata", "recentPages"), context.keySet());
        }
    }
    @Test void aHundredFragmentedReadsHaveBoundedMetadataOnlyDisclosure() {
        var pages = new ArrayList<Page>(); for (int i = 1; i <= 98; i++) pages.add(text(JOINT, i, "task", i * 2, i * 2 + 1, 1048576));
        String business = "business/" + "x".repeat(283) + "/webhook", order = "order/" + "x".repeat(294);
        pages.add(whole(JOINT, 99, business)); pages.add(whole(JOINT, 100, order));
        String json = inspect(JOINT, pages, true).toJson().toString();
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= MAX_OUTPUT_BYTES); assertFalse(json.contains("\"content\""));
    }
}
