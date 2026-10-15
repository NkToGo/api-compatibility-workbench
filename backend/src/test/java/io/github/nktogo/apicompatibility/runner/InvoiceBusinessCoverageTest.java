package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBusinessCoverage.*;

class InvoiceBusinessCoverageTest {
    @TempDir Path temporary;
    private static Plan plan() throws Exception { return load(InvoiceSandboxTest.root()); }
    @Test void completePlanPreservesEveryVariantDistinctDeliveryAndMissingAdapter() throws Exception {
        var plan = plan(); assertEquals(100, plan.entries().size()); assertEquals(47, plan.entries().stream().map(e -> e.corpus() + "/" + e.scenario()).distinct().count());
        assertEquals(51, plan.entries().stream().filter(Entry::webhook).count()); assertEquals(49, plan.entries().stream().filter(e -> !e.webhook()).count());
        assertEquals(15, plan.entries().stream().map(e -> e.metadata().get("familyId").getAsString()).distinct().count());
        var blocked = plan.entries().stream().filter(Entry::expectedBlock).toList(); assertEquals(1, blocked.size());
        assertEquals("old-delivery-v1/legacy-adapter-absent/webhook", blocked.getFirst().key());
        assertEquals("BUSINESS_DELIVERIES_ONLY", plan.metadata().get("coverageStage").getAsString());
        assertEquals(InvoiceRenewalRegistry.BUNDLE_SHA256, plan.metadata().get("registryIdentity").getAsString());
    }
    @Test void routesComeFromDeclaredInputAndNeverChooseAnUnrepairedOrSilentBaselineBuild() throws Exception {
        var plan = plan(); var builds = InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()); int versioned = 0;
        for (var entry : plan.entries()) {
            var configuration = entry.input().getAsJsonObject("configuration"); String selected = entry.buildId();
            if (selected.equals(InvoiceVersionedConsumerBuilds.BUILD_ID)) { assertTrue(entry.webhook()); assertEquals("29.0.0", configuration.get("sdkVersion").getAsString()); assertEquals(InvoiceVersionedConsumerBuilds.ACACIA, entry.input().get("effectiveApiVersion").getAsString()); versioned++; }
            else { var build = builds.build(selected); assertEquals(InvoiceConsumerBuilds.Disposition.SUCCEEDED, build.expectedCompilation()); assertEquals(entry.consumer(), build.consumerId()); assertEquals(configuration.get("sdkVersion").getAsString(), build.sdkVersion()); assertEquals(entry.input().get("effectiveApiVersion").getAsString(), build.apiVersion()); }
        }
        assertEquals(2, versioned); assertEquals(6, builds.builds().size());
    }
    @Test void capturedSourcesInputsAndCoverageMetadataCannotBeChangedByCallers() throws Exception {
        var plan = plan(); var original = plan.metadata(); var entry = plan.entries().getFirst(); byte[] source = plan.source(entry);
        entry.input().addProperty("consumerId", "forged"); plan.metadata().getAsJsonArray("deliveries").remove(0); source[0] ^= 1;
        assertEquals(original, plan.metadata()); assertEquals(entry.input().getAsJsonObject("source").get("sha256").getAsString(), CaseInputs.hash(plan.source(entry)));
        assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear()); assertFalse(plan.metadata().toString().contains("\"payload\"")); assertFalse(plan.metadata().toString().contains("\"expected\""));
        assertFalse(entry.toString().contains("payload"));
    }
    @Test void persistencePreservesEveryNullableFactFieldAndNeverOverwritesHistory() throws Exception {
        var fact = plan().entries().stream().map(Entry::expected).filter(Objects::nonNull).filter(f -> f.status() == InvoiceRenewalOracle.Status.NON_SUBSCRIPTION).findFirst().orElseThrow();
        var evidence = new JsonObject(); evidence.add("fact", fact.json()); evidence.add("execution", JsonNull.INSTANCE); Path path = temporary.resolve("record.json"); write(path, evidence);
        var saved = InvoiceRenewalFixtures.object(Files.readAllBytes(path)); assertEquals(fact, InvoiceRenewalOracle.Fact.parse(saved.getAsJsonObject("fact"))); assertTrue(saved.get("execution").isJsonNull());
        assertTrue(saved.getAsJsonObject("fact").get("subscriptionId").isJsonNull()); assertTrue(saved.getAsJsonObject("fact").get("reason").isJsonNull());
        byte[] original = Files.readAllBytes(path); assertThrows(FileAlreadyExistsException.class, () -> write(path, new JsonObject())); assertArrayEquals(original, Files.readAllBytes(path));
    }
    @Test void typedObservationCannotBeRestoredOrChangedByEditingItsDiagnosticEnvelope() throws Exception {
        var protocol = InvoiceSandboxTest.protocol(); assertThrows(IllegalArgumentException.class, protocol::observation);
        protocol.request(InvoiceSandboxTest.compilation("SUCCEEDED")); protocol.request(InvoiceSandboxTest.read()); protocol.request(InvoiceSandboxTest.returned(InvoiceSandboxTest.delivery("target-agreement", "reconciliation").expected().json()));
        var json = protocol.result("COMPLETED"); var observed = protocol.observation(); var execution = new InvoiceConsumerExecution(json, observed);
        json.getAsJsonObject("observation").addProperty("assertionOutcome", "FORGED"); assertThrows(IllegalArgumentException.class, () -> new InvoiceConsumerExecution(json, observed));
        execution.evidence().remove("observation"); assertEquals(observed.json(), execution.evidence().get("observation")); assertSame(observed, protocol.observation());
        assertEquals(InvoiceRenewalOracle.Assertion.PASSED, observed.assertion());
    }
    @Test void versionedTypedObservationKeepsIncompleteFactsAndCompilerResults() throws Exception {
        var protocol = InvoiceVersionedSandboxTest.protocol(); assertThrows(IllegalArgumentException.class, protocol::observation); InvoiceVersionedSandboxTest.compiled(protocol);
        protocol.request(InvoiceSandboxTest.read()); protocol.request(InvoiceSandboxTest.returned(InvoiceSandboxTest.delivery("target-agreement", "reconciliation").expected().json()));
        var json = protocol.result("TIMED_OUT"); var execution = new InvoiceConsumerExecution(json, protocol.observation());
        assertEquals(InvoiceRenewalOracle.Assertion.NOT_EVALUATED, execution.observation().assertion()); assertNotNull(execution.observation().actual()); assertEquals(2, execution.evidence().getAsJsonArray("compilations").size());
        assertThrows(IllegalArgumentException.class, () -> protocol.result("COMPLETED")); assertSame(execution.observation(), protocol.observation());
    }
    @Test void cancellationBeforeDispatchRetainsTheFullInventoryWithoutInventedObservations() throws Exception {
        Path output = temporary.resolve("cancelled"); var run = run(plan(), "sha256:" + "a".repeat(64), output, Duration.ofSeconds(30), () -> true);
        assertEquals(100, run.deliveries().size()); assertTrue(run.deliveries().stream().allMatch(d -> d.outcome() == Outcome.NOT_ATTEMPTED && d.observation() == null)); assertFalse(run.businessCoverageSatisfied());
        assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertEquals(0, run.json().get("orderedSequencesVerified").getAsInt()); assertEquals(0, run.json().get("boundaryControlsVerified").getAsInt()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        try (var files = Files.list(output)) { assertEquals(102, files.count()); }
        var first = InvoiceRenewalFixtures.object(Files.readAllBytes(output.resolve("001.json"))); assertTrue(first.get("execution").isJsonNull());
        run.json().getAsJsonArray("deliveries").remove(0); assertEquals(100, run.json().getAsJsonArray("deliveries").size());
        assertThrows(FileAlreadyExistsException.class, () -> run(plan(), "sha256:" + "a".repeat(64), output, Duration.ofSeconds(30), () -> true));
    }
    @Test void invalidExecutionConfigurationCannotCreateAnOutputDirectory() throws Exception {
        var plan = plan(); Path output = temporary.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> run(plan, "image:latest", output, Duration.ofSeconds(30), () -> true));
        assertThrows(IllegalArgumentException.class, () -> run(plan, "sha256:" + "a".repeat(64), output, Duration.ZERO, () -> true)); assertFalse(Files.exists(output));
    }
}
