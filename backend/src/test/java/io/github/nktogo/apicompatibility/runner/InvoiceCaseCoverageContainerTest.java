package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceCaseCoverage.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceCaseCoverageContainerTest {
    private static Path output(String name) throws Exception {
        Path parent = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-case-tests"); Files.createDirectories(parent); return parent.resolve(name + "-" + UUID.randomUUID());
    }
    @Test void completeSharedCapturePreservesAllActualEvidenceAndRejectsPartialReplayPromotion() throws Exception {
        var plan = load(InvoiceSandboxTest.root()); Path output = output("complete");
        var run = run(plan, System.getProperty("workbench.invoice.image"), output, Duration.ofSeconds(30), () -> false);
        assertTrue(run.fullCaseExecutionCoverageSatisfied(), run.json().toString()); run.requireFullCaseExecutionCoverage();
        assertEquals(99, run.business().deliveries().stream().filter(d -> d.observation() != null && d.observation().assertion() == InvoiceRenewalOracle.Assertion.PASSED).count());
        assertEquals(1, run.business().deliveries().stream().filter(d -> d.outcome() == InvoiceBusinessCoverage.Outcome.EXPECTED_BLOCK && d.observation() == null).count());
        assertEquals(188, run.ordered().json().get("orderedSequencesVerified").getAsInt()); assertEquals(102, run.boundary().json().get("boundaryPlacementsVerified").getAsInt());
        assertEquals(2, run.execution().controls().stream().filter(c -> c.observation() != null && c.observation().assertion() == InvoiceRenewalOracle.Assertion.FAILED).count());
        assertEquals(6, run.execution().controls().stream().filter(c -> c.observation() != null && c.observation().assertion() == InvoiceRenewalOracle.Assertion.NOT_EVALUATED).count());
        var components = run.json().getAsJsonObject("components");
        for (var value : components.entrySet()) {
            var row = value.getValue().getAsJsonObject(); var disk = InvoiceRenewalFixtures.object(Files.readAllBytes(output.resolve(row.get("journalDirectory").getAsString()).resolve("report.json")));
            assertTrue(row.get("coverageSatisfied").getAsBoolean()); assertEquals(InvoiceOrderedCoverage.identity(disk), row.get("evidenceIdentity").getAsString());
        }
        assertEquals(run.json(), InvoiceRenewalFixtures.object(Files.readAllBytes(output.resolve("report.json"))));
        try (var files = Files.walk(output)) {
            var saved = files.filter(Files::isRegularFile).toList(); assertEquals(438, saved.size());
            for (var file : saved) { String text = Files.readString(file); assertFalse(text.contains("\"payload\"") || text.contains("whsec_") || text.contains("sk-proj-") || text.contains("v1="), file.toString()); }
        }
        assertFalse(run.json().get("comparisonAdaptersAvailable").getAsBoolean()); assertFalse(run.json().get("semanticAcceptanceEstablished").getAsBoolean()); assertFalse(run.json().get("migrationApproved").getAsBoolean());
        var partialOrder = InvoiceOrderedCoverage.evaluate(plan.ordered(), run.business(), output("partial-ordered"), () -> true);
        var partial = assemble(plan, run.business(), partialOrder, run.admission(), run.execution(), run.boundary(), false);
        assertFalse(partial.fullCaseExecutionCoverageSatisfied()); assertTrue(partial.business().businessCoverageSatisfied()); assertTrue(partial.boundary().boundaryCoverageSatisfied());
        var partialBoundary = InvoiceBoundaryCoverage.evaluate(plan.boundary(), run.admission(), run.execution(), output("partial-boundary"), () -> true);
        assertFalse(assemble(plan, run.business(), run.ordered(), run.admission(), run.execution(), partialBoundary, false).fullCaseExecutionCoverageSatisfied());
        assertFalse(assemble(plan, run.business(), run.ordered(), run.admission(), run.execution(), run.boundary(), true).fullCaseExecutionCoverageSatisfied());
        assertTrue(run.fullCaseExecutionCoverageSatisfied()); assertThrows(FileAlreadyExistsException.class, () -> run(plan, System.getProperty("workbench.invoice.image"), output, Duration.ofSeconds(30), () -> false));
    }
    @Test void infrastructureFailureRetainsItsObservationAndStopsAllLaterDispatch() throws Exception {
        var run = run(load(InvoiceSandboxTest.root()), "sha256:" + "0".repeat(64), output("infrastructure"), Duration.ofSeconds(30), () -> false);
        assertFalse(run.fullCaseExecutionCoverageSatisfied()); assertTrue(run.json().get("dispatchStopped").getAsBoolean());
        var first = run.business().deliveries().getFirst(); assertEquals(InvoiceBusinessCoverage.Outcome.NOT_EVALUATED, first.outcome());
        assertNotNull(first.observation()); assertEquals("INFRASTRUCTURE_ERROR", first.observation().executionStatus()); assertNull(first.observation().actual());
        assertEquals(99, run.business().deliveries().stream().filter(d -> d.outcome() == InvoiceBusinessCoverage.Outcome.NOT_ATTEMPTED).count());
        assertTrue(run.ordered().sequences().stream().allMatch(s -> s.outcome() == InvoiceOrderedCoverage.Outcome.NOT_ATTEMPTED));
        assertTrue(run.admission().controls().stream().allMatch(c -> c.decision() == null)); assertTrue(run.execution().controls().stream().allMatch(c -> c.observation() == null));
        assertTrue(run.boundary().sequences().stream().allMatch(s -> s.outcome() == InvoiceBoundaryCoverage.Outcome.NOT_ATTEMPTED));
    }
}
