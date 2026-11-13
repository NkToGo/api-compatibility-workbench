package io.github.nktogo.apicompatibility.maintainer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordedCaseTest {
    @TempDir Path temporary;

    static JsonObject report() {
        var shared = new ContentDigest("a".repeat(64));
        var config = new ContentDigest("b".repeat(64));
        var source = new ContentDigest("c".repeat(64));
        var baseline = new InputSnapshot(RecordedCase.CASE_ID, "stripe", "2019-12-03", shared, shared, shared, shared, shared, shared);
        var target = new InputSnapshot(RecordedCase.CASE_ID, "stripe", "2020-08-27", shared, config, shared, shared, shared, shared);
        var patch = new InputSnapshot(RecordedCase.CASE_ID, "stripe", "2020-08-27", source, config, shared, shared, shared, shared);
        var report = new JsonObject(); report.addProperty("formatVersion", 1);
        report.addProperty("caseId", RecordedCase.CASE_ID); report.addProperty("evidenceKind", RecordedCase.EVIDENCE_KIND);
        report.addProperty("completedAt", "2026-11-13T00:00:00Z");
        var assessments = new JsonObject();
        for (var entry : List.of(new Object[]{"target", target}, new Object[]{"upstream-reference", patch})) {
            var assessment = new JsonObject();
            assessment.add("definition", RecordedCase.JSON.toJsonTree(new ComparisonDefinition(baseline,
                    (InputSnapshot) entry[1], RecordedCase.SCENARIOS)));
            // Deliberately false producer labels must not be used by the viewer.
            assessment.addProperty("conclusion", "NO_REGRESSION_OBSERVED");
            assessments.add((String) entry[0], assessment);
        }
        report.add("assessments", assessments);
        var observations = new JsonObject();
        var snapshots = List.of(baseline, target, patch);
        var names = List.of("baseline", "target", "upstream-reference");
        for (int variant = 0; variant < 3; variant++) {
            var values = new ArrayList<ExecutionObservation>();
            for (int i = 0; i < 5; i++) {
                boolean passed = variant == 0 || i == 3 || variant == 2 && i < 2;
                values.add(new ExecutionObservation(RecordedCase.SCENARIOS.get(i), snapshots.get(variant),
                        ExecutionObservation.Disposition.COMPLETED,
                        passed ? ExecutionObservation.AssertionOutcome.PASSED : ExecutionObservation.AssertionOutcome.FAILED));
            }
            observations.add(names.get(variant), RecordedCase.JSON.toJsonTree(values));
        }
        report.add("observations", observations);
        report.addProperty("details", "PRIVATE_PAYLOAD_SENTINEL");
        report.addProperty("artifacts", "C:\\private\\PRIVATE_PATH_SENTINEL");
        report.addProperty("historicalRepairEvaluation", "ALL_FIXED_FALSE_LABEL");
        return report;
    }
    static byte[] bytes(JsonObject report) { return RecordedCase.JSON.toJson(report).getBytes(StandardCharsets.UTF_8); }
    static JsonArray observations(JsonObject report, String variant) { return report.getAsJsonObject("observations").getAsJsonArray(variant); }

    @Test void derivesComparisonsAndRepairInsteadOfTrustingSuppliedLabels() throws IOException {
        var result = RecordedCase.decode(bytes(report()));
        assertEquals(ComparisonAssessment.Conclusion.REGRESSION_OBSERVED, result.upgrade().conclusion());
        assertEquals(4, result.upgrade().regressedScenarioIds().size());
        assertEquals(2, result.historicalPatch().regressedScenarioIds().size());
        assertEquals(2, result.repair().count(RepairEvaluation.Outcome.FIXED));
        assertEquals(2, result.repair().count(RepairEvaluation.Outcome.STILL_FAILING));
        assertEquals(1, result.repair().count(RepairEvaluation.Outcome.STILL_PASSING));
        assertFalse(result.currentInputsVerified()); assertFalse(result.executionAuthenticated());
        assertFalse(result.retainedArtifactsVerified());
        String json = RecordedCase.JSON.toJson(result);
        assertFalse(json.contains("PRIVATE_")); assertFalse(json.contains("ALL_FIXED_FALSE_LABEL"));
        assertEquals(64, result.reportSha256().length());
        assertThrows(UnsupportedOperationException.class, () -> result.variants().clear());
    }

    @Test void loadsBoundedSnapshotWithoutChangingTheSource() throws IOException {
        Path path = temporary.resolve("report.json"); byte[] bytes = bytes(report()); Files.write(path, bytes);
        var loaded = RecordedCase.load(path); Files.writeString(path, "changed");
        assertEquals(4, loaded.upgrade().regressedScenarioIds().size());
        assertEquals(RecordedCase.decode(bytes).reportSha256(), loaded.reportSha256());
        Files.write(path, bytes); RecordedCase.load(path); assertArrayEquals(bytes, Files.readAllBytes(path));
        Files.write(path, new byte[RecordedCase.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.load(path));
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.load(temporary));
    }

    @Test void missingObservationsAreUnresolvedWithOtherRegressionsRetained() throws IOException {
        var report = report(); observations(report, "target").remove(0);
        var result = RecordedCase.decode(bytes(report));
        assertEquals(ComparisonAssessment.Conclusion.UNRESOLVED, result.upgrade().conclusion());
        assertEquals(3, result.upgrade().regressedScenarioIds().size());
        assertTrue(result.upgrade().issues().stream().anyMatch(i -> i.reason() == ComparisonAssessment.Reason.MISSING_OBSERVATION));
        report.getAsJsonObject("observations").remove("baseline");
        assertEquals(ComparisonAssessment.Conclusion.UNRESOLVED, RecordedCase.decode(bytes(report)).upgrade().conclusion());
        report.remove("observations");
        assertEquals(5, RecordedCase.decode(bytes(report)).repair().count(RepairEvaluation.Outcome.UNRESOLVED));
    }

    @Test void duplicatesUnknownScenariosAndStaleIdentitiesStayVisible() throws IOException {
        var report = report(); var values = observations(report, "target");
        values.add(values.get(0).deepCopy());
        var unknown = values.get(1).deepCopy().getAsJsonObject(); unknown.addProperty("scenarioId", "undeclared-control"); values.add(unknown);
        values.get(2).getAsJsonObject().getAsJsonObject("inputs").getAsJsonObject("source").addProperty("sha256", "e".repeat(64));
        var result = RecordedCase.decode(bytes(report));
        assertEquals(ComparisonAssessment.Conclusion.UNRESOLVED, result.upgrade().conclusion());
        assertTrue(result.upgrade().issues().stream().anyMatch(i -> i.reason() == ComparisonAssessment.Reason.DUPLICATE_OBSERVATIONS));
        assertTrue(result.upgrade().issues().stream().anyMatch(i -> i.reason() == ComparisonAssessment.Reason.UNDECLARED_SCENARIO));
        assertTrue(result.upgrade().issues().stream().anyMatch(i -> i.reason() == ComparisonAssessment.Reason.INPUT_MISMATCH));
        assertTrue(RecordedCase.JSON.toJson(result).contains("undeclared-control"));
    }

    @ParameterizedTest @ValueSource(strings = {"TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR", "COMPLETED"})
    void incompleteAssertionDoesNotBecomePass(String disposition) throws IOException {
        var report = report(); var value = observations(report, "target").get(0).getAsJsonObject();
        value.addProperty("disposition", disposition); value.addProperty("assertionOutcome", "NOT_EVALUATED");
        var result = RecordedCase.decode(bytes(report));
        assertEquals(ComparisonAssessment.Conclusion.UNRESOLVED, result.upgrade().conclusion());
        assertTrue(result.upgrade().issues().stream().anyMatch(i -> i.reason() == ComparisonAssessment.Reason.EXECUTION_INCOMPLETE));
        assertTrue(MaintainerPage.render(result).contains("class=\"status unresolved\""));
    }

    @Test void failedBaselineAndChangedRepairConditionsCannotBeCertified() throws IOException {
        var report = report(); observations(report, "baseline").get(0).getAsJsonObject().addProperty("assertionOutcome", "FAILED");
        var definition = report.getAsJsonObject("assessments").getAsJsonObject("upstream-reference").getAsJsonObject("definition");
        definition.getAsJsonObject("target").getAsJsonObject("fixtures").addProperty("sha256", "e".repeat(64));
        var result = RecordedCase.decode(bytes(report));
        assertEquals(ComparisonAssessment.Conclusion.UNRESOLVED, result.upgrade().conclusion());
        assertEquals(RepairEvaluation.Conclusion.UNRESOLVED, result.repair().conclusion());
        assertFalse(result.repair().boundaryIssues().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"formatVersion", "caseId", "evidenceKind", "completedAt"})
    void refusesChangedRequiredMetadata(String field) {
        var report = report(); report.addProperty(field, "unsupported");
        assertThrows(RuntimeException.class, () -> RecordedCase.decode(bytes(report)));
    }

    @Test void refusesMalformedStructureAndUnsafeMetadata() {
        var report = report(); observations(report, "target").get(0).getAsJsonObject().addProperty("scenarioId", "<script>alert(1)</script>");
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.decode(bytes(report)));
        var nullRecord = report(); observations(nullRecord, "target").add(com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.decode(bytes(nullRecord)));
        var changedScenarios = report(); changedScenarios.getAsJsonObject("assessments").getAsJsonObject("target")
                .getAsJsonObject("definition").getAsJsonArray("requiredScenarioIds").remove(0);
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.decode(bytes(changedScenarios)));
    }

    @Test void strictJsonRefusesDuplicateFieldsTrailingContentDepthAndInvalidUtf8() {
        for (String text : List.of("{\"formatVersion\":1,\"formatVersion\":1}", "{} {}", "/* comment */{}",
                "[".repeat(34) + "0" + "]".repeat(34))) {
            assertThrows(IllegalArgumentException.class, () -> RecordedCase.decode(text.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.decode(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.decode(new byte[RecordedCase.MAX_BYTES + 1]));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void rejectsLinkedReportsAndLinkedParentDirectories() throws IOException {
        Path actual = temporary.resolve("actual"); Files.createDirectory(actual);
        Path file = actual.resolve("report.json"); Files.write(file, bytes(report()));
        Path linkedFile = temporary.resolve("linked.json"); Files.createSymbolicLink(linkedFile, file);
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.load(linkedFile));
        Path linkedDirectory = temporary.resolve("linked-directory"); Files.createSymbolicLink(linkedDirectory, actual);
        assertThrows(IllegalArgumentException.class, () -> RecordedCase.load(linkedDirectory.resolve("report.json")));
    }

    @Test void pageExplainsAuthorityAndDoesNotEmbedIgnoredReportData() throws IOException {
        var report = report(); report.addProperty("details", "</pre><script>PRIVATE_PAYLOAD_SENTINEL</script>");
        String html = MaintainerPage.render(RecordedCase.decode(bytes(report)));
        assertFalse(html.contains("<script")); assertFalse(html.contains("PRIVATE_"));
        assertTrue(html.contains("Current repository inputs have not been checked"));
        assertTrue(html.contains("This view cannot approve a release"));
        assertTrue(html.contains("<details>")); assertTrue(html.contains("Historical reference patch"));
        assertTrue(html.contains("4<span> / 5</span>")); assertTrue(html.contains("2 fixed · 2 still failing"));
    }
}
