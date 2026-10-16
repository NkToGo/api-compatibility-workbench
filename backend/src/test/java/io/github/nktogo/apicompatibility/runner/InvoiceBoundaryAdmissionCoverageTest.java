package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBoundaryAdmissionCoverage.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;

class InvoiceBoundaryAdmissionCoverageTest {
    @TempDir Path temporary;
    private static Plan plan() throws Exception { return load(InvoiceSandboxTest.root()); }
    @Test void planCoversEveryRejectedControlAndKeepsTheEightExecutionControlsPending() throws Exception {
        var plan = plan(); var bundle = InvoiceRenewalBoundaryFixtures.load(InvoiceSandboxTest.root());
        assertEquals(26, plan.entries().size()); assertEquals(24, plan.entries().stream().filter(e -> !e.expected().admitted()).count());
        assertEquals(23, plan.entries().stream().filter(Entry::webhook).count());
        assertEquals(bundle.controls().stream().filter(c -> !c.expected().admitted()).map(InvoiceRenewalBoundaryFixtures.Control::id).toList(),
                plan.entries().stream().filter(e -> !e.expected().admitted()).map(Entry::id).toList());
        assertEquals(List.of("completed-missing-result", "returned-timed-out", "returned-cancelled", "returned-infrastructure-error", "malformed-return", "trailing-return", "unbound-return", "wrong-version-return"),
                plan.metadata().getAsJsonArray("pendingExecutionControls").asList().stream().map(v -> v.getAsString()).toList());
        assertEquals(InvoiceRenewalRegistry.BUNDLE_SHA256, plan.metadata().get("registryIdentity").getAsString());
        assertEquals("BOUNDARY_ADMISSION_ONLY", plan.metadata().get("coverageStage").getAsString());
    }
    @Test void sdkRefusalUsesABoundDerivedFaultRatherThanTrustingTheAuthoredRejectedFlag() throws Exception {
        var original = InvoiceRenewalBoundaryFixtures.load(InvoiceSandboxTest.root()).controls().stream().filter(c -> c.id().equals("adapter-rejected")).findFirst().orElseThrow();
        var entry = plan().entries().stream().filter(e -> e.id().equals("adapter-rejected")).findFirst().orElseThrow();
        assertNotEquals(original.input(), entry.input()); assertNotEquals(InvoiceRenewalOracle.inputIdentity(original.input()), entry.host().inputIdentity());
        assertEquals(InvoiceRenewalOracle.inputIdentity(entry.input()), entry.host().inputIdentity());
        assertEquals(CaseInputs.hash(entry.host().sourceBytes()), entry.input().getAsJsonObject("source").get("sha256").getAsString());
        assertEquals(InvoiceRenewalFixtures.object(entry.host().sourceBytes()), entry.input().getAsJsonObject("source").get("payload"));
        assertEquals(List.of(Issue.ADAPTER_REJECTED), entry.expected().issues());
        assertTrue(entry.metadata().get("derivedSdkSchemaFault").getAsBoolean());
        assertEquals(Verification.NOT_CHECKED, entry.host().signature()); assertEquals(Verification.NOT_CHECKED, entry.host().adapter());
        assertFalse(original.input().getAsJsonObject("source").getAsJsonObject("payload").getAsJsonObject("data").getAsJsonObject("object").has("amount_due"));
    }
    @Test void allOtherCapturedInputsAndDeliberatelyBrokenIdentityTuplesStayUnchanged() throws Exception {
        var originals = new HashMap<String, InvoiceRenewalBoundaryFixtures.Control>();
        InvoiceRenewalBoundaryFixtures.load(InvoiceSandboxTest.root()).controls().forEach(c -> originals.put(c.id(), c));
        for (var entry : plan().entries()) {
            var original = originals.get(entry.id());
            if (entry.id().equals("adapter-rejected")) continue;
            assertEquals(original.input(), entry.input()); assertArrayEquals(original.host().sourceBytes(), entry.host().sourceBytes());
            assertEquals(original.host().inputIdentity(), entry.host().inputIdentity()); assertEquals(original.host().evidenceIdentity(), entry.host().evidenceIdentity());
            assertEquals(original.host().bundleIdentity(), entry.host().bundleIdentity());
            assertEquals(Verification.NOT_CHECKED, entry.host().signature()); assertEquals(Verification.NOT_CHECKED, entry.host().adapter()); assertNull(entry.host().signedSourceIdentity());
        }
        var wrongVersion = plan().entries().stream().filter(e -> e.id().equals("adapter-version-mismatch")).findFirst().orElseThrow();
        assertEquals(List.of(Issue.ADAPTER_REJECTED), wrongVersion.expected().issues());
    }
    @Test void callersCannotChangeCapturedInputsBytesExpectationsOrInventory() throws Exception {
        var plan = plan(); var entry = plan.entries().getFirst(); var original = plan.metadata();
        entry.input().addProperty("consumerId", "forged"); entry.host().sourceBytes()[0] ^= 1; plan.metadata().getAsJsonArray("controls").remove(0);
        assertEquals(original, plan.metadata()); assertEquals("invoice-webhook", entry.consumer());
        assertEquals(entry.input().getAsJsonObject("source").get("sha256").getAsString(), CaseInputs.hash(entry.host().sourceBytes()));
        assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear()); assertThrows(UnsupportedOperationException.class, () -> entry.expected().issues().add(Issue.MISSING_SOURCE));
        assertFalse(plan.metadata().toString().contains("\"payload\""));
    }
    @Test void preDispatchCancellationRetainsEveryControlAndNeverCreatesAuthorityOrOverwritesHistory() throws Exception {
        Path output = temporary.resolve("cancelled"); var run = run(plan(), "sha256:" + "a".repeat(64), output, Duration.ofSeconds(30), () -> true);
        assertFalse(run.admissionCoverageSatisfied()); assertEquals(26, run.controls().size()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        assertTrue(run.controls().stream().allMatch(c -> c.outcome() == Outcome.NOT_ATTEMPTED && c.decision() == null));
        assertEquals(0, run.json().get("boundaryControlsVerified").getAsInt()); assertEquals(0, run.json().get("businessConsumerDispatches").getAsInt()); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean());
        try (var files = Files.list(output)) { assertEquals(28, files.count()); }
        for (int i = 0; i < 26; i++) {
            var saved = InvoiceRenewalFixtures.object(Files.readAllBytes(output.resolve(String.format(Locale.ROOT, "%03d.json", i + 1))));
            assertEquals(run.controls().get(i).json(), saved); assertTrue(saved.get("admission").isJsonNull()); assertTrue(saved.get("consumerObservation").isJsonNull());
        }
        var report = run.json(); run.json().getAsJsonArray("controls").remove(0); run.controls().getFirst().json().addProperty("outcome", "VERIFIED"); assertEquals(report, run.json());
        assertThrows(UnsupportedOperationException.class, () -> run.controls().clear());
        byte[] original = Files.readAllBytes(output.resolve("report.json"));
        assertThrows(FileAlreadyExistsException.class, () -> run(plan(), "sha256:" + "a".repeat(64), output, Duration.ofSeconds(30), () -> true));
        assertArrayEquals(original, Files.readAllBytes(output.resolve("report.json")));
    }
    @Test void invalidRuntimeConfigurationCreatesNoJournal() throws Exception {
        var plan = plan(); Path output = temporary.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> run(plan, "image:latest", output, Duration.ofSeconds(30), () -> true));
        assertThrows(IllegalArgumentException.class, () -> run(plan, "sha256:" + "a".repeat(64), output, Duration.ZERO, () -> true));
        assertFalse(Files.exists(output));
    }
    @Test void interruptionAfterAuthenticationKeepsItsReceiptWithoutDispatchingAParser() throws Exception {
        var checks = new int[1]; var run = run(plan(), "sha256:" + "a".repeat(64), temporary.resolve("mid-control"), Duration.ofSeconds(30), () -> ++checks[0] >= 2);
        var first = run.controls().getFirst(); assertEquals(Outcome.CANCELLED, first.outcome()); assertNull(first.decision());
        assertEquals("VERIFIED", first.json().getAsJsonObject("signatureVerification").get("outcome").getAsString());
        assertTrue(first.json().get("adapterVerification").isJsonNull()); assertTrue(first.json().get("admission").isJsonNull());
        assertEquals(25, run.controls().stream().filter(c -> c.outcome() == Outcome.NOT_ATTEMPTED).count()); assertFalse(run.admissionCoverageSatisfied());
    }
}
