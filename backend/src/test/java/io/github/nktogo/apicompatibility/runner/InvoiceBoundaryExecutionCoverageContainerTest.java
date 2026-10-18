package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBoundaryExecutionCoverage.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceBoundaryExecutionCoverageContainerTest {
    private static String image() { return System.getProperty("workbench.invoice.image"); }
    private static Path output(String name) throws Exception {
        Path parent = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-boundary-execution-tests"); Files.createDirectories(parent); return parent.resolve(name + "-" + UUID.randomUUID());
    }
    @Test void realConsumersProduceAllEightFaultControlsAndBothHealthyControls() throws Exception {
        var plan = load(InvoiceSandboxTest.root()); Path directory = output("complete"); var run = run(plan, image(), directory, Duration.ofSeconds(30), () -> false);
        assertTrue(run.executionCoverageSatisfied(), run.json().toString()); assertEquals(8, run.json().get("executionControlsVerified").getAsInt());
        int passed = 0, failed = 0, incomplete = 0;
        for (int i = 0; i < 10; i++) {
            var result = run.controls().get(i); var entry = result.entry(); var row = result.json(); var observed = result.observation(); var expected = entry.expected();
            assertEquals(Outcome.VERIFIED, result.outcome()); assertNotNull(observed); var execution = row.getAsJsonObject("execution");
            assertEquals(row, InvoiceRenewalFixtures.object(Files.readAllBytes(directory.resolve(String.format(Locale.ROOT, "%03d.json", i + 1)))));
            assertEquals(expected.transport(), execution.get("transportStatus").getAsString()); assertEquals(expected.disposition(), observed.executionStatus());
            assertEquals(expected.assertion(), observed.assertion()); assertEquals(expected.invalid(), observed.protocolInvalid()); assertEquals(expected.actual(), observed.actual() != null); assertEquals(expected.identitiesMatch(), observed.identitiesMatch());
            assertEquals("SUCCEEDED", execution.getAsJsonObject("compilation").get("disposition").getAsString()); assertTrue(execution.get("containerRemoved").getAsBoolean()); assertEquals(image(), execution.get("imageId").getAsString());
            assertEquals(row.get("controlledSourceIdentity"), execution.get("controlledSourceIdentity")); assertEquals(entry.expectedFact(), observed.expected());
            assertFalse(row.toString().contains("\"payload\"") || row.toString().contains("whsec_") || row.toString().contains("v1="));
            if (entry.webhook()) { assertEquals("VERIFIED", row.getAsJsonObject("signatureVerification").get("outcome").getAsString()); assertEquals("VERIFIED", row.getAsJsonObject("adapterVerification").get("outcome").getAsString()); }
            switch (observed.assertion()) { case PASSED -> passed++; case FAILED -> failed++; case NOT_EVALUATED -> incomplete++; }
            if (observed.actual() != null) {
                if (entry.mode() == Mode.UNBOUND) { assertEquals("0".repeat(64), observed.actual().inputIdentity()); assertNotEquals(observed.expected(), observed.actual()); }
                else if (entry.mode() == Mode.WRONG_VERSION) { assertEquals("2025-02-24.acacia", observed.actual().effectiveApiVersion()); assertNotEquals(observed.expected(), observed.actual()); }
                else assertEquals(observed.expected(), observed.actual());
            }
            if (entry.mode() == Mode.EXIT_AFTER_RETURN) assertEquals(23, execution.get("workerExitCode").getAsInt());
            row.getAsJsonObject("execution").getAsJsonObject("observation").addProperty("assertionOutcome", "FORGED"); assertEquals(expected.assertion(), result.observation().assertion());
        }
        assertEquals(2, passed); assertEquals(2, failed); assertEquals(6, incomplete); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertEquals(0, run.json().get("boundaryPlacementsVerified").getAsInt()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        var savedReport = InvoiceRenewalFixtures.object(Files.readAllBytes(directory.resolve("report.json")));
        assertEquals(run.json(), savedReport);
        try (var files = Files.list(directory)) { assertEquals(12, files.count()); }
    }
    @Test void unavailableParserInfrastructureIsNotAnExpectedConsumerFailure() throws Exception {
        var run = run(load(InvoiceSandboxTest.root()), "sha256:" + "a".repeat(64), output("infrastructure"), Duration.ofSeconds(5), () -> false);
        assertFalse(run.executionCoverageSatisfied()); var first = run.controls().getFirst(); assertEquals(Outcome.NOT_EVALUATED, first.outcome()); assertNull(first.observation());
        assertEquals("ADAPTER_INCOMPLETE", first.json().get("stageFailure").getAsString()); assertEquals(9, run.controls().stream().filter(c -> c.outcome() == Outcome.NOT_ATTEMPTED).count());
        assertEquals(0, run.json().get("executionControlsVerified").getAsInt());
    }
    @Test void externalInterruptionBetweenControlsPreservesCompletedConsumerEvidence() throws Exception {
        Path directory = output("interrupted");
        var run = run(load(InvoiceSandboxTest.root()), image(), directory, Duration.ofSeconds(30), () -> Files.exists(directory.resolve("001.json")));
        assertFalse(run.executionCoverageSatisfied()); assertEquals(Outcome.VERIFIED, run.controls().getFirst().outcome()); assertEquals(Assertion.PASSED, run.controls().getFirst().observation().assertion());
        assertEquals(9, run.controls().stream().filter(c -> c.outcome() == Outcome.NOT_ATTEMPTED && c.observation() == null).count());
        assertTrue(run.controls().getFirst().json().getAsJsonObject("execution").get("containerRemoved").getAsBoolean());
    }
}
