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
