package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StripeCaseRunnerTest {
    @TempDir static Path temporary;
    static Path caseRoot;
    static Path cache;
    static JsonObject report;
    static Path reportPath;

    @BeforeAll static void executeCase() throws Exception {
        caseRoot = Path.of(System.getProperty("workbench.repository"), "cases", CaseInputs.CASE_ID);
        cache = caseRoot.resolve("target/dependencies");
        reportPath = StripeCaseRunner.run(caseRoot, cache, Path.of(System.getProperty("workbench.repository"), "backend/target/test-case-runs"));
        report = CaseInputs.object(Files.readAllBytes(reportPath));
    }

    @Test void distinguishesOriginalRegressionFromIncompleteHistoricalRepair() {
        var assessments = report.getAsJsonObject("assessments");
        assertEquals("REGRESSION_OBSERVED", assessments.getAsJsonObject("target").get("conclusion").getAsString());
        assertEquals(List.of("confirmed-removed-legacy-method", "empty-account-survives-omission", "legacy-method-not-lost", "modern-method-survives-omission"),
                CaseInputs.strings(assessments.getAsJsonObject("target"), "regressedScenarioIds"));
        assertEquals("REGRESSION_OBSERVED", assessments.getAsJsonObject("upstream-reference").get("conclusion").getAsString());
        assertEquals(List.of("confirmed-removed-legacy-method", "legacy-method-not-lost"),
                CaseInputs.strings(assessments.getAsJsonObject("upstream-reference"), "regressedScenarioIds"));
        for (var item : report.getAsJsonObject("observations").getAsJsonArray("baseline"))
            assertEquals("PASSED", item.getAsJsonObject().get("assertionOutcome").getAsString());
    }

    @Test void recordsHeadersAndDeletionEvidenceRatherThanOnlyFinalState() {
        var details = report.getAsJsonObject("details");
        for (var variant : StripeCaseRunner.VARIANTS) {
            for (var item : details.getAsJsonArray(variant.id())) {
                var result = item.getAsJsonObject();
                assertEquals(variant.sdk(), result.get("sdkVersion").getAsString());
                assertEquals(variant.api(), result.get("apiVersion").getAsString());
                long requests = result.getAsJsonArray("events").asList().stream()
                        .map(v -> v.getAsJsonObject()).filter(v -> v.get("kind").getAsString().equals("request"))
                        .peek(v -> assertEquals(variant.api(), v.get("apiVersion").getAsString())).count();
                assertEquals(2, requests);
            }
        }
        var removed = details.getAsJsonArray("upstream-reference").asList().stream().map(v -> v.getAsJsonObject())
                .filter(v -> v.get("scenarioId").getAsString().equals("confirmed-removed-legacy-method")).findFirst().orElseThrow();
        assertTrue(removed.getAsJsonArray("finalMethodIds").isEmpty());
        assertEquals(List.of("card_curated_legacy"), CaseInputs.strings(removed, "unsafeDeactivationIds"));
        assertEquals("FAILED", removed.get("assertionOutcome").getAsString());
    }

    @Test void historicalRepairBaselineSeparatesFixedFailuresFromRemainingFailures() throws Exception {
        var evaluation = report.getAsJsonObject("historicalRepairEvaluation");
        assertEquals("REGRESSIONS_REMAIN", evaluation.get("conclusion").getAsString());
        var outcomes = new java.util.TreeMap<String, String>();
        for (var item : evaluation.getAsJsonArray("scenarios")) {
            var scenario = item.getAsJsonObject();
            outcomes.put(scenario.get("scenarioId").getAsString(), scenario.get("outcome").getAsString());
        }
        assertEquals(java.util.Map.of("modern-method-survives-omission", "FIXED", "empty-account-survives-omission", "FIXED",
                "legacy-method-not-lost", "STILL_FAILING", "confirmed-removed-legacy-method", "STILL_FAILING",
                "present-legacy-collection-control", "STILL_PASSING"), outcomes);
        assertTrue(Files.readString(reportPath.resolveSibling("report.html")).contains(
                "2 fixed; 2 still failing; 0 newly failing; 1 still passing; 0 unresolved."));
    }

    @Test void evidenceInventoryMatchesRetainedBytes() throws Exception {
        assertTrue(report.getAsJsonObject("artifacts").has("report.html"));
        for (var entry : report.getAsJsonObject("artifacts").entrySet()) {
            assertEquals(entry.getValue().getAsString(), CaseInputs.hash(Files.readAllBytes(reportPath.getParent().resolve(entry.getKey()))), entry.getKey());
        }
        var baseline = report.getAsJsonObject("assessments").getAsJsonObject("target").getAsJsonObject("definition").getAsJsonObject("baseline");
        var target = report.getAsJsonObject("assessments").getAsJsonObject("target").getAsJsonObject("definition").getAsJsonObject("target");
        for (String key : List.of("expectations", "fixtures", "environment", "harness", "source")) assertEquals(baseline.get(key), target.get(key));
        assertNotEquals(baseline.get("configuration"), target.get("configuration"));
    }

    @Test void readableViewContainsActualCoverageAndUnsafeDeletionEvidence() throws Exception {
        String html = Files.readString(reportPath.resolveSibling("report.html"));
        assertEquals(2, html.split("<h3>Regression observed</h3>", -1).length - 1);
        String coverage = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        assertEquals(9, coverage.split(">Passed</span>", -1).length - 1);
        assertEquals(6, coverage.split(">Failed</span>", -1).length - 1);
        assertFalse(coverage.contains(">Unresolved</span>"));
        assertTrue(html.contains("Payment methods were deactivated without recorded absence evidence"));
        assertTrue(html.contains("java.lang.NullPointerException"));
        assertTrue(html.contains(report.get("completedAt").getAsString()));
        assertTrue(html.contains("No live Stripe account or full Kill Bill installation was tested"));
    }

    @Test void refusesAlteredInputsAndUnreviewedExecutableEvenWithUpdatedIndex() throws Exception {
        Path copy = temporary.resolve("tampered"); Files.createDirectories(copy.resolve("consumer"));
        var index = CaseInputs.object(Files.readAllBytes(caseRoot.resolve("integrity.json")));
        Files.copy(caseRoot.resolve("integrity.json"), copy.resolve("integrity.json"));
        for (var entry : index.getAsJsonArray("files")) {
            String file = entry.getAsJsonObject().get("path").getAsString();
            Files.copy(caseRoot.resolve(file), copy.resolve(file));
        }
        Path source = copy.resolve("consumer/baseline.java.txt"); Files.writeString(source, "// unreviewed\n", StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> new CaseInputs(copy, cache));
        for (var entry : index.getAsJsonArray("files")) {
            var value = entry.getAsJsonObject();
            if (value.get("path").getAsString().equals("consumer/baseline.java.txt")) value.addProperty("sha256", CaseInputs.hash(Files.readAllBytes(source)));
        }
        CaseInputs.write(copy.resolve("integrity.json"), index);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CaseInputs(copy, cache)).getMessage().contains("Unreviewed executable"));
    }

    @Test void workerDeadlineAndProcessFailureCannotMasqueradeAsPassingChecks() throws Exception {
        Path workerDir = Files.createDirectory(temporary.resolve("process"));
        String classes = Path.of(ProcessProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var timeout = FixtureProcess.run(List.of(FixtureProcess.javaExecutable(), "-cp", classes, ProcessProbe.class.getName(), "sleep"), workerDir, Duration.ofMillis(200));
        assertTrue(timeout.timedOut());
        var failure = FixtureProcess.run(List.of(FixtureProcess.javaExecutable(), "-cp", classes, ProcessProbe.class.getName(), "fail"), workerDir, Duration.ofSeconds(10));
        assertFalse(failure.timedOut()); assertEquals(7, failure.code());
        var cleanEnvironment = FixtureProcess.run(List.of(FixtureProcess.javaExecutable(), "-cp", classes, ProcessProbe.class.getName(), "environment"), workerDir, Duration.ofSeconds(10));
        assertEquals(0, cleanEnvironment.code());
    }

    @Test void unsupportedPaginationStaysUnresolvedWithOtherRegressionsRetained() throws Exception {
        Path copy = Files.createDirectory(temporary.resolve("unsupported-pagination"));
        Files.createDirectory(copy.resolve("consumer"));
        var index = CaseInputs.object(Files.readAllBytes(caseRoot.resolve("integrity.json")));
        for (var entry : index.getAsJsonArray("files")) {
            String file = entry.getAsJsonObject().get("path").getAsString();
            Files.copy(caseRoot.resolve(file), copy.resolve(file));
        }
        var fixtures = CaseInputs.object(Files.readAllBytes(copy.resolve("fixtures.json")));
        fixtures.getAsJsonArray("scenarios").get(0).getAsJsonObject()
                .getAsJsonObject("paymentMethodListResponse").addProperty("has_more", true);
        fixtures.getAsJsonArray("scenarios").get(1).getAsJsonObject()
                .getAsJsonObject("paymentMethodListResponse").remove("data");
        CaseInputs.write(copy.resolve("fixtures.json"), fixtures);
        for (var entry : index.getAsJsonArray("files")) {
            var value = entry.getAsJsonObject();
            if (value.get("path").getAsString().equals("fixtures.json"))
                value.addProperty("sha256", CaseInputs.hash(Files.readAllBytes(copy.resolve("fixtures.json"))));
        }
        CaseInputs.write(copy.resolve("integrity.json"), index);
        Path invalidReport = StripeCaseRunner.run(copy, cache, temporary.resolve("negative-runs"));
        var invalid = CaseInputs.object(Files.readAllBytes(invalidReport));
        String html = Files.readString(invalidReport.resolveSibling("report.html"));
        assertTrue(html.contains("<h3>Unresolved</h3>"));
        assertTrue(html.contains("EXECUTION_INCOMPLETE"));
        assertTrue(html.contains("UNSUPPORTED_COLLECTION"));
        assertTrue(html.contains("INVALID_COLLECTION"));
        assertTrue(html.contains("<li>Keep a saved legacy card</li>"));
        var assessment = invalid.getAsJsonObject("assessments").getAsJsonObject("target");
        assertEquals("UNRESOLVED", assessment.get("conclusion").getAsString());
        assertTrue(CaseInputs.strings(assessment, "regressedScenarioIds").contains("legacy-method-not-lost"));
        var first = invalid.getAsJsonObject("details").getAsJsonArray("baseline").get(0).getAsJsonObject();
        assertEquals("UNSUPPORTED_COLLECTION", first.get("failureCode").getAsString());
        assertEquals("NOT_EVALUATED", first.get("assertionOutcome").getAsString());
        var malformed = invalid.getAsJsonObject("details").getAsJsonArray("baseline").get(1).getAsJsonObject();
        assertEquals("INVALID_COLLECTION", malformed.get("failureCode").getAsString());
        assertEquals("NOT_EVALUATED", malformed.get("assertionOutcome").getAsString());
    }

    public static class ProcessProbe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) Thread.sleep(60_000);
            if (args[0].equals("fail")) System.exit(7);
            if (args[0].equals("environment") && System.getenv().keySet().stream()
                    .anyMatch(key -> !List.of("SystemRoot", "TMP", "TEMP", "TMPDIR").contains(key))) System.exit(8);
        }
    }
}
