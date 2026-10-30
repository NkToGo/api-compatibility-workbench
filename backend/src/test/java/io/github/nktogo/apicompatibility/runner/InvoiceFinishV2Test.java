package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.nktogo.apicompatibility.runner.InvoiceReadiness.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceFinishV2Test {
    private static final ContentDigest INPUT = new ContentDigest("1".repeat(64)), ARTIFACT = new ContentDigest("2".repeat(64));
    private static final Subject OWNER = new Subject("invoice-single-agent-v2", INPUT, Scope.JOINT);
    private static TextPage text(int id, String alias, int start, int end, int total) {
        return new TextPage(OWNER, "read-" + id, alias, ARTIFACT, start, end, total);
    }
    private static List<Page> pages() {
        var values = new ArrayList<Page>(); int id = 0;
        for (var alias : new TreeSet<>(InvoiceInvestigationDecision.REQUIRED)) values.add(text(++id, alias, 0, 3, 3));
        values.add(text(++id, "business/example/webhook", 0, 3, 3)); values.add(text(++id, "order/example", 0, 3, 3));
        values.add(new CataloguePage(OWNER, "read-7")); return values;
    }
    private static JsonObject finish(String response, List<String> summary, List<String> finding) {
        return InvoiceDecisionV2.parse(CaseInputs.JSON.toJson(Map.of("decision", Map.of("action", "finish", "response", response,
                "summary", "Advisory evidence", "summaryCitations", summary, "findings", finding.isEmpty() ? List.of() : List.of(
                        Map.of("kind", "LIMITATION", "statement", "This is not semantic acceptance.", "citations", finding))))));
    }
    @Test void completeOwnAccessAllowsAStructuralAnswerWithoutSemanticAcceptance() {
        var readiness = inspect(OWNER, pages(), true);
        assertTrue(InvoiceFinishV2.supported(OWNER, finish("ANSWERED", List.of("read-1"), List.of("read-6")), readiness));
        assertEquals("NOT_PERFORMED", readiness.qualityReview());
    }
    @Test void honestAbstentionCanHaveNoReadsOrCitedPartialFindings() {
        assertTrue(InvoiceFinishV2.supported(OWNER, finish("ABSTAINED", List.of(), List.of()), inspect(OWNER, List.of(), false)));
        assertTrue(InvoiceFinishV2.supported(OWNER, finish("ABSTAINED", List.of("read-1"), List.of("read-1")),
                inspect(OWNER, List.of(text(1, "task", 0, 2, 8)), false)));
    }
    @Test void absentSummaryCitationsOrFindingsCannotBecomeAnswered() {
        var readiness = inspect(OWNER, pages(), true);
        assertFalse(InvoiceFinishV2.supported(OWNER, finish("ANSWERED", List.of(), List.of("read-1")), readiness));
        assertFalse(InvoiceFinishV2.supported(OWNER, finish("ANSWERED", List.of("read-1"), List.of()), readiness));
    }
    @Test void catalogueAndInventedIdsCannotSupportEitherDisposition() {
        for (String disposition : List.of("ANSWERED", "ABSTAINED")) for (String citation : List.of("read-7", "read-99")) {
            var readiness = inspect(OWNER, pages(), true);
            assertFalse(InvoiceFinishV2.supported(OWNER, finish(disposition, List.of(citation), List.of("read-1")), readiness));
            assertFalse(InvoiceFinishV2.supported(OWNER, finish(disposition, List.of("read-1"), List.of(citation)), readiness));
        }
    }
    @Test void missingRequiredArtifactAndGapsCannotBecomeAnswered() {
        for (String alias : InvoiceInvestigationDecision.REQUIRED) {
            var missing = new ArrayList<>(pages()); missing.removeIf(p -> p instanceof TextPage t && t.alias().equals(alias));
            assertFalse(InvoiceFinishV2.supported(OWNER, finish("ANSWERED", List.of("read-6"), List.of("read-6")), inspect(OWNER, missing, true)));
        }
        var gapped = new ArrayList<>(pages()); gapped.removeIf(p -> p instanceof TextPage t && t.alias().equals("task"));
        gapped.add(text(8, "task", 0, 2, 8)); gapped.add(text(9, "task", 4, 8, 8));
        assertFalse(InvoiceFinishV2.supported(OWNER, finish("ANSWERED", List.of("read-6"), List.of("read-6")), inspect(OWNER, gapped, true)));
        gapped.add(text(10, "task", 2, 4, 8));
        assertTrue(InvoiceFinishV2.supported(OWNER, finish("ANSWERED", List.of("read-6"), List.of("read-6")), inspect(OWNER, gapped, true)));
    }
    @Test void incompleteExecutionCoverageAndBusinessOrOrderAccessCannotBeOverriddenByACitation() {
        var decision = finish("ANSWERED", List.of("read-1"), List.of("read-1"));
        assertFalse(InvoiceFinishV2.supported(OWNER, decision, inspect(OWNER, pages(), false)));
        for (String prefix : List.of("business/", "order/")) {
            var missing = new ArrayList<>(pages()); missing.removeIf(p -> p instanceof TextPage t && t.alias().startsWith(prefix));
            assertFalse(InvoiceFinishV2.supported(OWNER, decision, inspect(OWNER, missing, true)));
        }
    }
    @Test void mismatchedParticipantInputOrRoleCannotBorrowAReadySnapshot() {
        for (var subject : List.of(new Subject("peer", INPUT, Scope.JOINT), new Subject(OWNER.participantId(), ARTIFACT, Scope.JOINT),
                new Subject(OWNER.participantId(), INPUT, Scope.WEBHOOK)))
            assertFalse(InvoiceFinishV2.supported(subject, finish("ANSWERED", List.of("read-1"), List.of("read-1")), inspect(OWNER, pages(), true)));
    }
}
