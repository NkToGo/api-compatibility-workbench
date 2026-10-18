package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBoundaryExecutionCoverage.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

class InvoiceBoundaryExecutionCoverageTest {
    @TempDir Path temporary;
    private static Plan plan() throws Exception { return load(InvoiceSandboxTest.root()); }
    @Test void capturesExactlyTheTenAdmittedControlsWithoutIncludingRejectedInputs() throws Exception {
        var plan = plan(); var controls = InvoiceRenewalBoundaryFixtures.load(InvoiceSandboxTest.root()).controls();
        assertEquals(10, plan.entries().size()); assertEquals(2, plan.entries().stream().filter(e -> e.mode() == Mode.HEALTHY).count());
        assertEquals(controls.stream().filter(c -> c.expected().admitted()).map(c -> c.id()).toList(), plan.entries().stream().map(Entry::id).toList());
        assertEquals(9, plan.entries().stream().filter(Entry::webhook).count());
        assertEquals("BOUNDARY_EXECUTION_ONLY", plan.metadata().get("coverageStage").getAsString());
        assertEquals(InvoiceRenewalRegistry.BUNDLE_SHA256, plan.metadata().get("registryIdentity").getAsString());
    }
    @Test void fixedFaultWrappersPreserveTheRealConsumerAlgorithmAndOriginalCatalog() throws Exception {
        var plan = plan(); var builds = InvoiceConsumerBuilds.load(InvoiceSandboxTest.root());
        String original = builds.workerSources("webhook-basil-source").get("InvoiceWebhookConsumer.java");
        for (var entry : plan.entries()) {
            assertEquals(builds.workerSources(entry.buildId()).keySet(), entry.sources().keySet());
            assertEquals(builds.workerSources(entry.buildId()).get("RenewalPolicy.java"), entry.sources().get("RenewalPolicy.java"));
            String source = entry.sources().get(entry.webhook() ? "InvoiceWebhookConsumer.java" : "InvoiceReconciliationConsumer.java");
            if (entry.mode() == Mode.HEALTHY) assertEquals(builds.workerSources(entry.buildId()), entry.sources());
            else {
                assertTrue(source.contains(original.substring(original.indexOf("        var input ="), original.lastIndexOf('}'))));
                assertTrue(source.contains("var fact = capturedConsume(exchange);")); assertFalse(source.contains(entry.expectedFact().inputIdentity())); assertFalse(source.contains(entry.expectedFact().evidenceIdentity()));
                assertFalse(source.contains("in_primary") || source.contains("cus_case") || source.contains("sub_primary"));
            }
        }
        assertEquals(original, builds.workerSources("webhook-basil-source").get("InvoiceWebhookConsumer.java"));
        // Timeout and cancellation intentionally share the same post-return wait; the host control differs.
        assertEquals(7, plan.entries().stream().filter(e -> e.mode() != Mode.HEALTHY).map(e -> e.metadata().get("controlledSourceIdentity").getAsString()).distinct().count());
    }
    @Test void runtimeExpectationsKeepProtocolErrorsAndNonzeroExitSeparateFromAuthoredStatuses() throws Exception {
        for (var entry : plan().entries()) {
            var expectation = entry.expected();
            if (entry.mode() == Mode.EXIT_AFTER_RETURN) { assertEquals("WORKER_FAILED", expectation.transport()); assertEquals("INFRASTRUCTURE_ERROR", expectation.disposition()); assertTrue(expectation.actual()); }
            if (Set.of(Mode.MALFORMED, Mode.TRAILING).contains(entry.mode())) { assertEquals("PROTOCOL_ERROR", expectation.transport()); assertEquals(Assertion.NOT_EVALUATED, expectation.assertion()); assertTrue(expectation.invalid()); }
            if (Set.of(Mode.UNBOUND, Mode.WRONG_VERSION).contains(entry.mode())) assertEquals(Assertion.FAILED, expectation.assertion());
            if (entry.mode() == Mode.UNBOUND) assertFalse(expectation.identitiesMatch());
        }
    }
    @Test void startupRejectsAuthoredWebhookAuthorityAndEntriesFromAnotherCapturedPlan() throws Exception {
        var plan = plan(); var controls = InvoiceRenewalBoundaryFixtures.load(InvoiceSandboxTest.root()).controls();
        var webhook = plan.entries().getFirst(); var authored = controls.stream().filter(c -> c.id().equals(webhook.id())).findFirst().orElseThrow().admission();
        assertTrue(authored.admitted()); assertFalse(authored.observedWebhookVerified()); assertThrows(IllegalArgumentException.class, () -> startup(plan, webhook, authored));
        var entry = plan.entries().get(1); var reconciliation = controls.stream().filter(c -> c.id().equals(entry.id())).findFirst().orElseThrow().admission();
        var value = startup(plan, entry, reconciliation); assertEquals(Set.of("sdk", "entryClass", "sources", "dependencies"), value.keySet());
        assertFalse(value.toString().contains(entry.expectedFact().inputIdentity()));
        var other = plan(); assertThrows(IllegalArgumentException.class, () -> startup(plan, other.entries().get(1), reconciliation));
    }
    @Test void snapshotsAndReturnedDiagnosticCopiesCannotAlterControlSourcesOrExpectations() throws Exception {
        var plan = plan(); var metadata = plan.metadata(); var entry = plan.entries().getFirst(); var input = entry.input(); input.addProperty("consumerId", "forged");
        plan.metadata().getAsJsonArray("controls").remove(0); entry.metadata().addProperty("mode", "forged");
        assertEquals(metadata, plan.metadata()); assertEquals("invoice-webhook", entry.input().get("consumerId").getAsString());
        assertThrows(UnsupportedOperationException.class, () -> entry.sources().clear()); assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear());
        assertFalse(metadata.toString().contains("\"payload\""));
    }
    @Test void returnTrackingRequiresHostAcceptanceAndKeepsSealedObservationsImmutable() throws Exception {
        var entry = plan().entries().getFirst(); var protocol = new TrackedProtocol(new InvoiceRenewalOracle(entry.consumer(), entry.input(), entry.expectedFact()));
        assertFalse(protocol.returned()); assertThrows(IllegalArgumentException.class, protocol::observation);
        protocol.request(InvoiceSandboxTest.compilation("SUCCEEDED")); protocol.request(InvoiceSandboxTest.read());
        protocol.request(InvoiceSandboxTest.returned(entry.expectedFact().json())); assertTrue(protocol.returned());
        var sealed = protocol.result("CANCELLED"); assertEquals(Assertion.NOT_EVALUATED, protocol.observation().assertion()); assertEquals(entry.expectedFact(), protocol.observation().actual());
        sealed.getAsJsonObject("observation").addProperty("assertionOutcome", "PASSED"); assertEquals(Assertion.NOT_EVALUATED, protocol.observation().assertion());
        assertThrows(IllegalArgumentException.class, () -> protocol.result("COMPLETED"));
        var malformed = new TrackedProtocol(new InvoiceRenewalOracle(entry.consumer(), entry.input(), entry.expectedFact()));
        malformed.request(InvoiceSandboxTest.compilation("SUCCEEDED")); malformed.request(InvoiceSandboxTest.read());
        JsonObject bad = entry.expectedFact().json(); bad.addProperty("assertionOutcome", "PASSED");
        assertThrows(IllegalArgumentException.class, () -> malformed.request(InvoiceSandboxTest.returned(bad))); assertFalse(malformed.returned());
        malformed.result("PROTOCOL_ERROR"); assertTrue(malformed.observation().protocolInvalid()); assertNull(malformed.observation().actual());
    }
    @Test void cancellationPreservesFullInventoryNullObservationsAndCreateOnlyHistory() throws Exception {
        Path output = temporary.resolve("cancelled"); var run = run(plan(), "sha256:" + "a".repeat(64), output, Duration.ofSeconds(30), () -> true);
        assertFalse(run.executionCoverageSatisfied()); assertEquals(10, run.controls().size()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        assertTrue(run.controls().stream().allMatch(c -> c.outcome() == Outcome.NOT_ATTEMPTED && c.observation() == null));
        assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertEquals(0, run.json().get("boundaryControlsVerified").getAsInt());
        try (var files = Files.list(output)) { assertEquals(12, files.count()); }
        assertEquals(run.controls().getFirst().json(), InvoiceRenewalFixtures.object(Files.readAllBytes(output.resolve("001.json"))));
        run.controls().getFirst().json().addProperty("outcome", "VERIFIED"); assertEquals(Outcome.NOT_ATTEMPTED, run.controls().getFirst().outcome());
        assertThrows(UnsupportedOperationException.class, () -> run.controls().clear());
        byte[] report = Files.readAllBytes(output.resolve("report.json")); assertThrows(FileAlreadyExistsException.class, () -> run(plan(), "sha256:" + "a".repeat(64), output, Duration.ofSeconds(30), () -> true)); assertArrayEquals(report, Files.readAllBytes(output.resolve("report.json")));
        var checks = new int[1]; var partial = run(plan(), "sha256:" + "a".repeat(64), temporary.resolve("after-authentication"), Duration.ofSeconds(30), () -> ++checks[0] >= 2);
        assertEquals(Outcome.CANCELLED, partial.controls().getFirst().outcome()); assertEquals("VERIFIED", partial.controls().getFirst().json().getAsJsonObject("signatureVerification").get("outcome").getAsString());
        assertNull(partial.controls().getFirst().observation()); assertEquals(9, partial.controls().stream().filter(c -> c.outcome() == Outcome.NOT_ATTEMPTED).count());
    }
    @Test void invalidExecutionConfigurationCreatesNoJournal() throws Exception {
        var plan = plan(); Path output = temporary.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> run(plan, "image:latest", output, Duration.ofSeconds(30), () -> true));
        assertThrows(IllegalArgumentException.class, () -> run(plan, "sha256:" + "a".repeat(64), output, Duration.ZERO, () -> true)); assertFalse(Files.exists(output));
    }
}
