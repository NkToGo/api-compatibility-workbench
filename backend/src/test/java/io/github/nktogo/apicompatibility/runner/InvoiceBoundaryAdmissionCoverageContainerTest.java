package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBoundaryAdmissionCoverage.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceBoundaryAdmissionCoverageContainerTest {
    private static String image() { return System.getProperty("workbench.invoice.image"); }
    private static Path output(String name) throws Exception {
        Path parent = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-boundary-admission-tests"); Files.createDirectories(parent); return parent.resolve(name + "-" + UUID.randomUUID());
    }
    @Test void actualAuthenticationAndSdkParsingExerciseAll24RejectionsAndTwoHealthyAdmissions() throws Exception {
        var plan = load(InvoiceSandboxTest.root()); Path directory = output("complete"); var run = run(plan, image(), directory, Duration.ofSeconds(30), () -> false);
        assertTrue(run.admissionCoverageSatisfied(), run.json().toString()); assertEquals(24, run.json().get("rejectionControlsVerified").getAsInt());
        int admitted = 0, parsed = 0;
        for (int i = 0; i < 26; i++) {
            var entry = plan.entries().get(i); var result = run.controls().get(i); var row = result.json();
            var saved = InvoiceRenewalFixtures.object(Files.readAllBytes(directory.resolve(String.format(Locale.ROOT, "%03d.json", i + 1)))); assertEquals(row, saved);
            assertEquals(Outcome.VERIFIED, result.outcome()); assertEquals(entry.expected().admitted(), result.decision().admitted()); assertEquals(entry.expected().issues(), result.decision().issues());
            assertFalse(row.get("businessConsumerDispatched").getAsBoolean()); assertTrue(row.get("consumerObservation").isJsonNull()); assertFalse(row.toString().contains("\"payload\"") || row.toString().contains("whsec_"));
            if (result.decision().admitted()) { admitted++; if (entry.webhook()) assertTrue(result.decision().observedWebhookVerified()); }
            else assertThrows(IllegalArgumentException.class, result.decision()::oracle);
            if (!row.get("adapterVerification").isJsonNull()) {
                var adapter = row.getAsJsonObject("adapterVerification");
                if (adapter.get("executionAttempted").getAsBoolean()) {
                    parsed++; assertEquals("COMPLETED", adapter.get("executionStatus").getAsString()); assertTrue(adapter.get("containerRemoved").getAsBoolean()); assertEquals(0, adapter.get("workerExitCode").getAsInt()); assertEquals(image(), adapter.get("imageId").getAsString());
                }
            }
            if (entry.id().equals("adapter-rejected")) {
                var adapter = row.getAsJsonObject("adapterVerification"); assertTrue(adapter.get("executionAttempted").getAsBoolean());
                assertFalse(adapter.get("adapterAccepted").getAsBoolean()); assertEquals("REJECTED", adapter.get("outcome").getAsString());
            }
            if (entry.id().equals("forged-payload-permissions")) {
                assertEquals("SIGNATURE_REJECTED", row.getAsJsonObject("signatureVerification").get("outcome").getAsString()); assertTrue(row.get("adapterVerification").isJsonNull());
            }
            if (entry.id().equals("signature-rebound")) {
                assertEquals("VERIFIED", row.getAsJsonObject("signatureVerification").get("outcome").getAsString()); assertNotEquals(row.get("sourceIdentity"), row.getAsJsonObject("signatureVerification").get("sourceIdentity")); assertTrue(row.get("adapterVerification").isJsonNull());
            }
            row.addProperty("outcome", "FORGED"); assertEquals("VERIFIED", result.json().get("outcome").getAsString());
        }
        assertEquals(2, admitted); assertEquals(11, parsed);
        assertEquals(0, run.json().get("ledgerReceipts").getAsInt()); assertEquals(0, run.json().get("boundaryControlsVerified").getAsInt()); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        assertEquals(run.json(), InvoiceRenewalFixtures.object(Files.readAllBytes(directory.resolve("report.json"))));
        try (var files = Files.list(directory)) { assertEquals(28, files.count()); }
    }
    @Test void realParserInfrastructureFailureStaysIncompleteAndStopsFurtherChecks() throws Exception {
        Path directory = output("infrastructure"); var run = run(load(InvoiceSandboxTest.root()), "sha256:" + "a".repeat(64), directory, Duration.ofSeconds(5), () -> false);
        assertFalse(run.admissionCoverageSatisfied()); var first = run.controls().getFirst(); assertEquals(Outcome.NOT_EVALUATED, first.outcome());
        assertEquals("ADAPTER_INFRASTRUCTURE_ERROR", first.json().get("stageFailure").getAsString()); assertFalse(first.decision().admitted());
        assertEquals("NOT_EVALUATED", first.json().getAsJsonObject("adapterVerification").get("outcome").getAsString());
        assertEquals(25, run.controls().stream().filter(c -> c.outcome() == Outcome.NOT_ATTEMPTED && c.decision() == null).count());
        assertTrue(first.json().get("consumerObservation").isJsonNull()); assertEquals(0, run.json().get("rejectionControlsVerified").getAsInt());
    }
    @Test void interruptionAfterRealParsingKeepsReceiptsAndLeavesAllRemainingControlsUnattempted() throws Exception {
        Path directory = output("interrupted");
        // Interrupt between controls after the first actual parser receipt and admission are persisted.
        var run = run(load(InvoiceSandboxTest.root()), image(), directory, Duration.ofSeconds(30), () -> {
            try (var files = Files.list(directory)) { return files.anyMatch(p -> p.getFileName().toString().equals("001.json")); }
            catch (Exception e) { return false; }
        });
        assertEquals(Outcome.VERIFIED, run.controls().getFirst().outcome());
        assertTrue(run.controls().getFirst().json().getAsJsonObject("adapterVerification").get("containerRemoved").getAsBoolean());
        assertEquals(25, run.controls().stream().filter(c -> c.outcome() == Outcome.NOT_ATTEMPTED && c.decision() == null).count()); assertFalse(run.admissionCoverageSatisfied());
    }
}
