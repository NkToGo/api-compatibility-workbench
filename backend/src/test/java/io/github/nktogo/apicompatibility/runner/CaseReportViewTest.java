package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.AssertionOutcome.*;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.Disposition.*;

class CaseReportViewTest {
    private static final String SCENARIO = "modern-method-survives-omission";
    private static final InputSnapshot BASELINE = snapshot("2019-12-03");
    private static final InputSnapshot TARGET = snapshot("2020-08-27");
    private static final ComparisonDefinition DEFINITION = new ComparisonDefinition(BASELINE, TARGET, List.of(SCENARIO));

    @Test void passingEvidenceIsBoundedAndMarkedAsHistorical() throws Exception {
        String html = view(List.of(observation(TARGET, PASSED)), Map.of());
        assertTrue(html.contains("<h3>No regression observed</h3>"));
        assertTrue(html.contains("limited coverage, not a release approval"));
        assertTrue(html.contains("They have not been compared with your current checkout"));
        assertTrue(html.contains("Supporting details are missing or ambiguous"));
    }

    @Test void missingDuplicateStaleAndTimedOutEvidenceNeverAppearsPassing() throws Exception {
        var invalidSets = List.of(
                List.<ExecutionObservation>of(),
                List.of(observation(TARGET, PASSED), observation(TARGET, PASSED)),
                List.of(observation(snapshot("stale-version"), PASSED)),
                List.of(new ExecutionObservation(SCENARIO, TARGET, TIMED_OUT, NOT_EVALUATED)));
        for (var invalid : invalidSets) {
            String html = view(invalid, Map.of());
            assertEquals(2, html.split("<h3>Unresolved</h3>", -1).length - 1);
            assertFalse(html.contains("<h3>No regression observed</h3>"));
            String table = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
            assertEquals(1, table.split(">Passed</span>", -1).length - 1, "Only the baseline may pass");
            assertEquals(2, table.split(">Unresolved</span>", -1).length - 1);
        }
    }

    @Test void undeclaredResultsRemainVisibleAsBlockingIssues() throws Exception {
        String html = view(List.of(observation(TARGET, PASSED), new ExecutionObservation("extra-check", TARGET, COMPLETED, PASSED)), Map.of());
        assertTrue(html.contains("UNDECLARED_SCENARIO"));
        assertTrue(html.contains("extra-check"));
        assertTrue(html.contains("<h3>Unresolved</h3>"));
    }

    @Test void untrustedOutputCannotBecomeActiveHtml() throws Exception {
        String attack = "</pre><script>alert('x')</script><img src=https://invalid.example onerror=alert(1)>";
        var detail = new JsonObject();
        detail.addProperty("scenarioId", SCENARIO);
        detail.addProperty("consumerException", attack);
        String html = view(List.of(observation(TARGET, FAILED)), Map.of("target", List.of(detail)));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;/pre&gt;"));
        assertTrue(html.contains("default-src &#39;none&#39;"));
        assertFalse(html.contains("unsafe-inline"));
        String css = html.substring(html.indexOf("<style>") + 7, html.indexOf("</style>"));
        assertFalse(css.contains("\r"), "CSP must hash browser-normalized stylesheet text");
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(css.getBytes(StandardCharsets.UTF_8)));
        assertTrue(html.contains("style-src &#39;sha256-" + hash + "&#39;"));
    }

    @Test void incompatibleComparisonLayoutsAreRejectedInsteadOfMerged() {
        var other = new ComparisonDefinition(BASELINE, TARGET, List.of("other-scenario"));
        assertThrows(IllegalArgumentException.class, () -> CaseReportView.render(Instant.EPOCH,
                Map.of("target", DEFINITION, "upstream-reference", other), Map.of(), Map.of(), Map.of()));
    }

    private static String view(List<ExecutionObservation> target, Map<String, List<JsonObject>> details) throws Exception {
        return CaseReportView.render(Instant.EPOCH, Map.of("target", DEFINITION, "upstream-reference", DEFINITION),
                Map.of("baseline", List.of(observation(BASELINE, PASSED)), "target", target, "upstream-reference", target), details, Map.of());
    }
    private static ExecutionObservation observation(InputSnapshot snapshot, ExecutionObservation.AssertionOutcome outcome) {
        return new ExecutionObservation(SCENARIO, snapshot, COMPLETED, outcome);
    }
    private static InputSnapshot snapshot(String version) {
        var digest = new ContentDigest("a".repeat(64));
        return new InputSnapshot(CaseInputs.CASE_ID, "stripe", version, digest, digest, digest, digest, digest, digest);
    }
}
