package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationComparisonReportTest {
    @TempDir Path root;
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private Path plan() throws Exception { Path path = root.resolve("plan.json"); Files.writeString(path, JSON.toJson(CoordinationComparisonReport.examplePlan())); return path; }
    private Path records() throws Exception { Path path = root.resolve("records.json"); Files.writeString(path, JSON.toJson(new CoordinationComparisonReport.Records(1, List.of()))); return path; }
    @Test void fileEvaluationCreatesNoStateAndPreservesExactInputIdentities() throws Exception {
        Path p = plan(), r = records(); byte[] originalPlan = Files.readAllBytes(p), originalRecords = Files.readAllBytes(r);
        var result = CoordinationComparisonReport.evaluateFiles(p, r);
        assertEquals(CaseInputs.hash(originalPlan), result.get("planSha256").getAsString());
        assertEquals(CaseInputs.hash(originalRecords), result.get("recordsSha256").getAsString());
        assertEquals("UNRESOLVED", result.getAsJsonObject("assessment").get("conclusion").getAsString());
        assertEquals(12, result.getAsJsonObject("assessment").getAsJsonArray("rows").size());
        assertArrayEquals(originalPlan, Files.readAllBytes(p)); assertArrayEquals(originalRecords, Files.readAllBytes(r));
        try (var entries = Files.list(root)) { assertEquals(2, entries.count()); }
    }
    @Test void readOnlyEvaluationRejectsUnversionedHistoricalReviewWithoutWriting() throws Exception {
        Path fixture = legacyFixture();
        assertThrows(RuntimeException.class, () -> CoordinationComparisonReport.evaluateFiles(fixture.getParent().resolve("plan.json"), fixture.resolve("records.json")));
        assertFalse(Files.exists(root.resolve("results")));
    }
    @Test void fileEvaluationPreservesExplicitMaterialUncertaintyAndStrictFields() throws Exception {
        Path fixture = legacyFixture(); var tree = CaseInputs.object(Files.readAllBytes(fixture.resolve("records.json"))); tree.addProperty("formatVersion", 2);
        for (var t : tree.getAsJsonArray("trials")) for (var c : t.getAsJsonObject().getAsJsonArray("cases")) {
            var review = c.getAsJsonObject().get("review"); if (!review.isJsonNull()) review.getAsJsonObject().addProperty("materialUnresolvedClaims", 3);
        }
        Path r = root.resolve("explicit-v2.json"); Files.writeString(r, tree.toString());
        var result = CoordinationComparisonReport.evaluateFiles(fixture.getParent().resolve("plan.json"), r);
        assertEquals("UNRESOLVED", result.getAsJsonObject("assessment").get("conclusion").getAsString());
        assertTrue(result.toString().contains("MATERIAL_UNRESOLVED_CLAIM"));
        tree.addProperty("winner", "COORDINATED_TEAM"); Files.writeString(r, tree.toString());
        assertThrows(RuntimeException.class, () -> CoordinationComparisonReport.evaluateFiles(fixture.getParent().resolve("plan.json"), r));
    }
    private Path legacyFixture() throws Exception {
        Path directory = root.resolve("coordination-comparisons/comparison-cc35117f-f36a-497e-a0da-82d3138aeccc");
        Path report = directory.resolve("report-0a58dd53-43bc-4600-80e4-c818c68223e2"); Files.createDirectories(report);
        for (String name : List.of("plan.json", "registration.json", "records.json", "result.json", "receipt.json")) {
            try (var input = getClass().getResourceAsStream("/coordination-v1-reviewed/" + name)) {
                assertNotNull(input); Files.copy(input, List.of("plan.json", "registration.json").contains(name) ? directory.resolve(name) : report.resolve(name));
            }
        }
        return report;
    }
    @Test void oldReviewedReportStillReadsUnderItsOriginalVersionWithoutClaimingV2Resolution() throws Exception {
        Path report = legacyFixture(); var reports = new CoordinationComparisonReport(root);
        byte[] original = Files.readAllBytes(report.resolve("result.json"));
        var read = reports.read(report.getParent().getFileName().toString(), report.getFileName().toString());
        assertEquals(CaseInputs.object(original), read); assertArrayEquals(original, Files.readAllBytes(report.resolve("result.json")));
        var row = read.getAsJsonObject("assessment").getAsJsonArray("rows").get(1).getAsJsonObject();
        assertTrue(row.get("acceptedBundle").getAsBoolean());
        assertFalse(row.getAsJsonArray("cases").get(0).getAsJsonObject().has("materialUnresolvedClaims"));
        assertThrows(RuntimeException.class, () -> reports.assess(report.getParent().getFileName().toString(), report.resolve("records.json")));
    }
    @Test void newVersionRequiresExplicitResolutionAndRetainsUncertaintyAcrossHistoricalRead() throws Exception {
        Path legacy = legacyFixture(); var reports = new CoordinationComparisonReport(root); String id = legacy.getParent().getFileName().toString();
        var tree = CaseInputs.object(Files.readAllBytes(legacy.resolve("records.json"))); tree.addProperty("formatVersion", 2);
        var review = tree.getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("review");
        Path input = root.resolve("v2.json"); Files.writeString(input, tree.toString());
        assertThrows(RuntimeException.class, () -> reports.assess(id, input));
        review.add("materialUnresolvedClaims", JsonNull.INSTANCE); Files.writeString(input, tree.toString());
        assertThrows(RuntimeException.class, () -> reports.assess(id, input));
        review.addProperty("materialUnresolvedClaims", 1); Files.writeString(input, tree.toString());
        String report = reports.assess(id, input); var result = reports.read(id, report);
        assertEquals(2, result.get("formatVersion").getAsInt());
        var row = result.getAsJsonObject("assessment").getAsJsonArray("rows").get(1).getAsJsonObject();
        assertFalse(row.get("acceptedBundle").getAsBoolean()); assertFalse(row.get("comparable").getAsBoolean());
        assertEquals(1, row.getAsJsonArray("cases").get(0).getAsJsonObject().get("materialUnresolvedClaims").getAsInt());
        var zero = root.resolve("zero.json"); review.addProperty("materialUnresolvedClaims", 0); Files.writeString(zero, tree.toString());
        var accepted = reports.read(id, reports.assess(id, zero));
        assertTrue(accepted.getAsJsonObject("assessment").getAsJsonArray("rows").get(1).getAsJsonObject().get("acceptedBundle").getAsBoolean());
        assertEquals(result, reports.read(id, report));
    }
    @Test void immutableRegistrationAndSeparateOfflineReportsKeepAnEmptyCampaignUnresolved() throws Exception {
        var reports = new CoordinationComparisonReport(root); String id = reports.register(plan()); String first = reports.assess(id, records()); var before = reports.read(id, first);
        var assessment = before.getAsJsonObject("assessment"); assertEquals("UNRESOLVED", assessment.get("conclusion").getAsString());
        assertEquals("SYNTHETIC_EXAMPLE", assessment.getAsJsonObject("definition").get("origin").getAsString()); assertEquals(12, assessment.getAsJsonArray("rows").size());
        String second = reports.assess(id, records()); assertNotEquals(first, second); assertEquals(before, reports.read(id, first));
        try (var paths = Files.walk(root.resolve("coordination-comparisons/" + id))) {
            assertTrue(paths.noneMatch(p -> List.of("grant.json", "model-calls", "authorization.json").contains(p.getFileName().toString())));
        }
    }
    @Test void alteredPlansRecordsAndClaimedFinalVerdictsAreRejectedOnHistoricalRead() throws Exception {
        var reports = new CoordinationComparisonReport(root); String id = reports.register(plan()), report = reports.assess(id, records());
        Path directory = root.resolve("coordination-comparisons/" + id); Path resultPath = directory.resolve(report + "/result.json"); byte[] original = Files.readAllBytes(resultPath);
        var result = CaseInputs.object(original); result.getAsJsonObject("assessment").addProperty("routingDecision", "COORDINATED_TEAM"); Files.writeString(resultPath, result.toString());
        assertThrows(RuntimeException.class, () -> reports.read(id, report)); Files.write(resultPath, original);
        result = CaseInputs.object(original); result.addProperty("recordedAt", "2000-01-01T00:00:00Z"); Files.writeString(resultPath, result.toString());
        assertThrows(RuntimeException.class, () -> reports.read(id, report)); Files.write(resultPath, original);
        Path receiptPath = directory.resolve(report + "/receipt.json"); byte[] receipt = Files.readAllBytes(receiptPath); Files.delete(receiptPath);
        assertThrows(RuntimeException.class, () -> reports.read(id, report)); Files.write(receiptPath, receipt);
        Path recordsPath = directory.resolve(report + "/records.json"); Files.writeString(recordsPath, Files.readString(recordsPath) + " "); assertThrows(RuntimeException.class, () -> reports.read(id, report));
        Path planPath = directory.resolve("plan.json"); Files.writeString(planPath, Files.readString(planPath) + " "); assertThrows(RuntimeException.class, () -> reports.assess(id, records()));
    }
    @Test void unknownFieldsAndInvalidReferencesCannotAddPermissionsOrRedirectReports() throws Exception {
        var reports = new CoordinationComparisonReport(root); Path permissionPlan = plan(); var tree = CaseInputs.object(Files.readAllBytes(permissionPlan)); tree.addProperty("permission", "execute"); Files.writeString(permissionPlan, tree.toString());
        assertThrows(RuntimeException.class, () -> reports.register(permissionPlan)); assertThrows(RuntimeException.class, () -> reports.read("../outside", "report-invalid"));
        Path winnerPlan = plan(); tree = CaseInputs.object(Files.readAllBytes(winnerPlan)); tree.getAsJsonObject("definition").addProperty("winner", "COORDINATED_TEAM"); Files.writeString(winnerPlan, tree.toString());
        assertThrows(RuntimeException.class, () -> reports.register(winnerPlan));
        Path valid = plan(); String id = reports.register(valid); assertThrows(RuntimeException.class, () -> reports.assess(id, root.resolve("missing.json")));
    }
}
