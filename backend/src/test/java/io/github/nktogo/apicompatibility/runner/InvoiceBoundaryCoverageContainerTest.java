package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBoundaryCoverage.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceBoundaryCoverageContainerTest {
    @Test void actualRuntimeControlsReplayAll102PlacementsAndPreserveInterruptions() throws Exception {
        String image = System.getProperty("workbench.invoice.image"); var plan = load(InvoiceSandboxTest.root());
        Path parent = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-boundary-tests"); Files.createDirectories(parent); Path root = Files.createDirectory(parent.resolve(UUID.randomUUID().toString()));
        var admission = InvoiceBoundaryAdmissionCoverage.run(plan.admission(), image, root.resolve("admission"), Duration.ofSeconds(30), () -> false);
        var execution = InvoiceBoundaryExecutionCoverage.run(plan.execution(), image, root.resolve("execution"), Duration.ofSeconds(30), () -> false);
        assertTrue(admission.admissionCoverageSatisfied()); assertTrue(execution.executionCoverageSatisfied());
        var run = evaluate(plan, admission, execution, root.resolve("placements"), () -> false); assertTrue(run.boundaryCoverageSatisfied(), run.json().toString());
        assertEquals(34, run.json().get("boundaryControlsVerified").getAsInt()); assertEquals(102, run.json().get("boundaryPlacementsVerified").getAsInt()); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        int positions = 0, receipts = 0, blocked = 0, failedAssertions = 0, incompleteAssertions = 0;
        for (int i = 0; i < 102; i++) {
            var sequence = run.sequences().get(i); var json = sequence.json(); assertEquals(Outcome.VERIFIED, sequence.outcome()); assertTrue(json.get("finalEffectsMatch").getAsBoolean());
            var saved = InvoiceRenewalFixtures.object(Files.readAllBytes(root.resolve("placements").resolve(String.format(Locale.ROOT, "%03d.json", i + 1)))); assertEquals(json, saved);
            int sequenceReceipts = 0;
            for (var value : json.getAsJsonArray("positions")) {
                var row = value.getAsJsonObject(); positions++; assertEquals("VERIFIED", row.get("outcome").getAsString()); assertEquals(row.get("expectedOutcome"), row.get("actualOutcome"));
                if (row.get("observation").isJsonNull()) { blocked++; assertEquals("BLOCKED", row.get("actualOutcome").getAsString()); assertFalse(row.getAsJsonObject("admission").get("admitted").getAsBoolean()); assertTrue(row.get("ledgerReceipt").isJsonNull()); }
                else {
                    receipts++; sequenceReceipts++; assertEquals(sequenceReceipts, row.getAsJsonObject("ledgerReceipt").get("sequence").getAsInt());
                    String assertion = row.getAsJsonObject("observation").get("assertionOutcome").getAsString(); if (assertion.equals("FAILED")) failedAssertions++; if (assertion.equals("NOT_EVALUATED")) incompleteAssertions++;
                    if (row.get("controlId").getAsString().equals("wrong-version-return")) assertEquals("FAILED", assertion);
                }
                assertFalse(row.toString().contains("\"payload\"") || row.toString().contains("whsec_"));
            }
            assertEquals(sequenceReceipts, json.get("ledgerReceipts").getAsInt());
        }
        assertEquals(170, positions); assertEquals(98, receipts); assertEquals(72, blocked); assertEquals(6, failedAssertions); assertEquals(18, incompleteAssertions);
        var before = evaluate(plan, admission, execution, root.resolve("before"), () -> true); assertTrue(before.sequences().stream().allMatch(s -> s.outcome() == Outcome.NOT_ATTEMPTED));
        var between = evaluate(plan, admission, execution, root.resolve("between"), () -> Files.exists(root.resolve("between/001.json"))); assertEquals(Outcome.VERIFIED, between.sequences().getFirst().outcome()); assertEquals(101, between.sequences().stream().filter(s -> s.outcome() == Outcome.NOT_ATTEMPTED).count());
        var calls = new AtomicInteger(); var within = evaluate(plan, admission, execution, root.resolve("within"), () -> calls.incrementAndGet() >= 5);
        assertEquals(Outcome.VERIFIED, within.sequences().getFirst().outcome()); var partial = within.sequences().get(1); assertEquals(Outcome.CANCELLED, partial.outcome());
        var partialPositions = partial.json().getAsJsonArray("positions"); assertEquals("VERIFIED", partialPositions.get(0).getAsJsonObject().get("outcome").getAsString()); assertFalse(partialPositions.get(0).getAsJsonObject().get("ledgerReceipt").isJsonNull()); assertEquals("NOT_ATTEMPTED", partialPositions.get(1).getAsJsonObject().get("outcome").getAsString());
        assertFalse(before.boundaryCoverageSatisfied()); assertFalse(between.boundaryCoverageSatisfied()); assertFalse(within.boundaryCoverageSatisfied());
        for (String directory : List.of("placements", "before", "between", "within")) try (var files = Files.list(root.resolve(directory))) { assertEquals(104, files.count()); }
        var unavailable = InvoiceBoundaryAdmissionCoverage.run(plan.admission(), image, root.resolve("unavailable-admission"), Duration.ofSeconds(30), () -> true);
        var incomplete = evaluate(plan, unavailable, execution, root.resolve("partial-sources"), () -> false);
        assertFalse(incomplete.boundaryCoverageSatisfied()); assertEquals(0, incomplete.json().get("boundaryControlsVerified").getAsInt());
        assertEquals(8, incomplete.json().get("boundaryPlacementsVerified").getAsInt());
        var retained = incomplete.sequences().getFirst().json().getAsJsonArray("positions").get(0).getAsJsonObject();
        assertEquals("NOT_EVALUATED", retained.get("outcome").getAsString()); assertEquals("PASSED", retained.getAsJsonObject("observation").get("assertionOutcome").getAsString()); assertFalse(retained.get("ledgerReceipt").isJsonNull());
    }
}
