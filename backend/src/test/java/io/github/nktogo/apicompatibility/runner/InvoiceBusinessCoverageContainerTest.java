package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBusinessCoverage.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceBusinessCoverageContainerTest {
    private static String image() { return System.getProperty("workbench.invoice.image"); }
    private static Path output(String name) throws Exception {
        Path parent = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-business-tests"); Files.createDirectories(parent); return parent.resolve(name + "-" + UUID.randomUUID());
    }
    @Test void all47VariantsAnd100DistinctDeliveriesUseActualProtectedConsumersAndVerifierReceipts() throws Exception {
        var plan = load(InvoiceSandboxTest.root()); Path output = output("complete"); var run = run(plan, image(), output, Duration.ofSeconds(30), () -> false);
        assertTrue(run.businessCoverageSatisfied(), run.json().get("outcomes").toString()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        assertEquals(99, run.deliveries().stream().filter(d -> d.outcome() == Outcome.VERIFIED).count()); assertEquals(1, run.deliveries().stream().filter(d -> d.outcome() == Outcome.EXPECTED_BLOCK).count());
        int webhooks = 0, observed = 0;
        for (int index = 0; index < run.deliveries().size(); index++) {
            var result = run.deliveries().get(index); var entry = result.entry(); var json = result.json(); var disk = InvoiceRenewalFixtures.object(Files.readAllBytes(output.resolve(String.format(Locale.ROOT, "%03d.json", index + 1)))); assertEquals(json, disk);
            assertEquals(entry.metadata().get("inputIdentity"), json.get("inputIdentity")); assertFalse(json.toString().contains("\"payload\"") || json.toString().contains("whsec_"));
            if (entry.webhook()) { webhooks++; assertEquals("VERIFIED", json.getAsJsonObject("signatureVerification").get("outcome").getAsString()); assertEquals(entry.expectedBlock() ? "ABSENT" : "VERIFIED", json.getAsJsonObject("adapterVerification").get("outcome").getAsString()); }
            if (entry.expectedBlock()) { assertNull(result.observation()); assertTrue(json.get("execution").isJsonNull()); assertFalse(json.getAsJsonObject("admission").get("admitted").getAsBoolean()); continue; }
            var observation = result.observation(); assertNotNull(observation); assertEquals(InvoiceRenewalOracle.Assertion.PASSED, observation.assertion()); assertEquals(entry.expected(), observation.actual());
            assertEquals(entry.expected(), InvoiceRenewalOracle.Fact.parse(disk.getAsJsonObject("execution").getAsJsonObject("observation").getAsJsonObject("actual")));
            var execution = json.getAsJsonObject("execution"); assertTrue(execution.get("containerRemoved").getAsBoolean()); assertEquals(0, execution.get("workerExitCode").getAsInt()); assertEquals(image(), execution.get("imageId").getAsString()); assertEquals(entry.buildId(), execution.get("buildId").getAsString()); observed++;
        }
        assertEquals(51, webhooks); assertEquals(99, observed); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean());
    }
    @Test void interruptionAfterOneCompletedDeliveryPreservesItAndMarksAllRemainingWorkUnattempted() throws Exception {
        var plan = load(InvoiceSandboxTest.root()); Path output = output("interrupted");
        var run = run(plan, image(), output, Duration.ofSeconds(30), () -> Files.exists(output.resolve("001.json")));
        assertFalse(run.businessCoverageSatisfied()); assertEquals(Outcome.VERIFIED, run.deliveries().getFirst().outcome()); assertNotNull(run.deliveries().getFirst().observation());
        assertEquals(99, run.deliveries().stream().filter(d -> d.outcome() == Outcome.NOT_ATTEMPTED && d.observation() == null).count());
        assertTrue(Files.exists(output.resolve("001.json"))); assertTrue(Files.exists(output.resolve("100.json"))); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean());
    }
    @Test void infrastructureFailureStopsFurtherDispatchWithoutDroppingRegisteredCoverage() throws Exception {
        var plan = load(InvoiceSandboxTest.root()); Path output = output("infrastructure-failure");
        var run = run(plan, "sha256:" + "0".repeat(64), output, Duration.ofSeconds(30), () -> false);
        assertFalse(run.businessCoverageSatisfied()); var first = run.deliveries().getFirst(); assertEquals(Outcome.NOT_EVALUATED, first.outcome());
        assertNotNull(first.observation()); assertNull(first.observation().actual()); assertEquals("INFRASTRUCTURE_ERROR", first.observation().executionStatus()); assertEquals("WORKER_INFRASTRUCTURE_ERROR", first.json().get("stageFailure").getAsString());
        assertEquals(99, run.deliveries().stream().filter(d -> d.outcome() == Outcome.NOT_ATTEMPTED).count()); assertEquals(100, run.json().getAsJsonArray("deliveries").size());
    }
}
