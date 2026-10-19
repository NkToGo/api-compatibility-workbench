package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceCaseCoverage.*;

class InvoiceCaseCoverageTest {
    @TempDir Path temporary;
    private static final String IMAGE = "sha256:" + "1".repeat(64);
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static Plan plan() throws Exception { return load(InvoiceSandboxTest.root()); }
    private Run empty(Plan plan, String name) throws Exception { return run(plan, IMAGE, temporary.resolve(name), DEADLINE, () -> true); }
    @Test void oneCapturedPlanPreservesAllComponentInventoriesAndBuildMembership() throws Exception {
        var plan = plan(); var json = plan.metadata();
        assertSame(plan.business(), plan.ordered().business()); assertEquals(100, plan.business().entries().size());
        assertEquals(188, plan.ordered().sequences().size()); assertEquals(26, plan.boundary().admission().entries().size());
        assertEquals(10, plan.boundary().execution().entries().size()); assertEquals(102, plan.boundary().sequences().size());
        for (var value : json.getAsJsonObject("components").entrySet()) assertEquals(json.get("registryIdentity"), value.getValue().getAsJsonObject().get("registryIdentity"));
        assertEquals(312, json.get("declaredBusinessPositions").getAsInt()); assertEquals(170, json.get("declaredBoundaryPositions").getAsInt());
        json.getAsJsonObject("components").remove("business"); assertTrue(plan.metadata().getAsJsonObject("components").has("business"));
        assertFalse(plan.metadata().toString().contains("\"payload\"") || plan.metadata().toString().contains("\"expected\""));
        var builds = InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()); var versioned = InvoiceVersionedConsumerBuilds.load(InvoiceSandboxTest.root(), builds);
        assertTrue(versioned.usesBase(builds)); assertFalse(versioned.usesBase(InvoiceConsumerBuilds.load(InvoiceSandboxTest.root())));
        assertThrows(IllegalArgumentException.class, () -> InvoiceBusinessCoverage.from(plan.business().fixtures(), InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()), versioned));
    }
    @Test void stoppedCaptureRetainsEveryInventoryWithoutInventedFactsOrCoverage() throws Exception {
        var plan = plan(); var run = empty(plan, "stopped"); assertTrue(run.belongsTo(plan)); assertFalse(run.fullCaseExecutionCoverageSatisfied());
        assertThrows(IllegalArgumentException.class, run::requireFullCaseExecutionCoverage); assertTrue(run.json().get("dispatchStopped").getAsBoolean());
        assertTrue(run.business().deliveries().stream().allMatch(d -> d.observation() == null && d.outcome() == InvoiceBusinessCoverage.Outcome.NOT_ATTEMPTED));
        assertTrue(run.ordered().sequences().stream().allMatch(s -> s.outcome() == InvoiceOrderedCoverage.Outcome.NOT_ATTEMPTED));
        assertTrue(run.admission().controls().stream().allMatch(c -> c.decision() == null));
        assertTrue(run.execution().controls().stream().allMatch(c -> c.observation() == null));
        assertTrue(run.boundary().sequences().stream().allMatch(s -> s.outcome() == InvoiceBoundaryCoverage.Outcome.NOT_ATTEMPTED));
        Path root = temporary.resolve("stopped"); try (var files = Files.walk(root)) { assertEquals(438, files.filter(Files::isRegularFile).count()); }
        assertEquals(run.json(), InvoiceRenewalFixtures.object(Files.readAllBytes(root.resolve("report.json"))));
        var copy = run.json(); copy.addProperty("fullCaseExecutionCoverageSatisfied", true); copy.getAsJsonObject("components").remove("business"); assertFalse(run.fullCaseExecutionCoverageSatisfied());
        byte[] saved = Files.readAllBytes(root.resolve("report.json")); assertThrows(FileAlreadyExistsException.class, () -> run(plan, IMAGE, root, DEADLINE, () -> true)); assertArrayEquals(saved, Files.readAllBytes(root.resolve("report.json")));
    }
    @Test void invalidConfigurationFailsBeforeCreatingAnAttempt() throws Exception {
        var plan = plan(); Path output = temporary.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> run(plan, "latest", output, DEADLINE, () -> false)); assertFalse(Files.exists(output));
        assertThrows(IllegalArgumentException.class, () -> run(plan, IMAGE, output, Duration.ZERO, () -> false)); assertFalse(Files.exists(output));
        assertThrows(IllegalArgumentException.class, () -> run(plan, IMAGE, output, Duration.ofSeconds(181), () -> false)); assertFalse(Files.exists(output));
    }
    @Test void missingOrForeignPlansAndDifferentBusinessSourceRunsAreRejected() throws Exception {
        var plan = plan(); var original = empty(plan, "original");
        assertThrows(IllegalArgumentException.class, () -> assemble(plan, original.business(), original.ordered(), null, original.execution(), original.boundary(), false));
        assertThrows(IllegalArgumentException.class, () -> assemble(plan(), original.business(), original.ordered(), original.admission(), original.execution(), original.boundary(), false));
        var replacement = InvoiceBusinessCoverage.run(plan.business(), IMAGE, temporary.resolve("other-business"), DEADLINE, () -> true);
        assertEquals(original.business().json(), replacement.json());
        assertThrows(IllegalArgumentException.class, () -> assemble(plan, replacement, original.ordered(), original.admission(), original.execution(), original.boundary(), false));
        var replay = InvoiceOrderedCoverage.evaluate(plan.ordered(), replacement, temporary.resolve("other-ordered"), () -> true);
        assertThrows(IllegalArgumentException.class, () -> assemble(plan, original.business(), replay, original.admission(), original.execution(), original.boundary(), false));
    }
    @Test void identicalDiagnosticRecordsCannotReplaceTheBoundaryReplaySource() throws Exception {
        var plan = plan(); var original = empty(plan, "original");
        var admission = InvoiceBoundaryAdmissionCoverage.run(plan.boundary().admission(), IMAGE, temporary.resolve("other-admission"), DEADLINE, () -> true);
        assertEquals(original.admission().json(), admission.json());
        assertThrows(IllegalArgumentException.class, () -> assemble(plan, original.business(), original.ordered(), admission, original.execution(), original.boundary(), false));
        var replay = InvoiceBoundaryCoverage.evaluate(plan.boundary(), admission, original.execution(), temporary.resolve("other-boundary"), () -> true);
        assertThrows(IllegalArgumentException.class, () -> assemble(plan, original.business(), original.ordered(), original.admission(), original.execution(), replay, false));
    }
    @Test void consistentBoundaryReplayFromAnotherImageCannotCompleteTheCase() throws Exception {
        var plan = plan(); var original = empty(plan, "original"); String other = "sha256:" + "2".repeat(64);
        var admission = InvoiceBoundaryAdmissionCoverage.run(plan.boundary().admission(), other, temporary.resolve("other-admission"), DEADLINE, () -> true);
        var execution = InvoiceBoundaryExecutionCoverage.run(plan.boundary().execution(), other, temporary.resolve("other-execution"), DEADLINE, () -> true);
        var replay = InvoiceBoundaryCoverage.evaluate(plan.boundary(), admission, execution, temporary.resolve("other-boundary"), () -> true);
        assertThrows(IllegalArgumentException.class, () -> assemble(plan, original.business(), original.ordered(), admission, execution, replay, false));
    }
    @Test void aTransientStopRequestStaysLatchedAcrossAllLaterComponents() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger(); var run = run(plan(), IMAGE, temporary.resolve("transient"), DEADLINE, () -> calls.incrementAndGet() == 1);
        assertTrue(run.json().get("dispatchStopped").getAsBoolean()); assertFalse(run.fullCaseExecutionCoverageSatisfied());
        assertTrue(run.business().deliveries().stream().allMatch(d -> d.observation() == null)); assertTrue(run.execution().controls().stream().allMatch(c -> c.observation() == null));
    }
}
