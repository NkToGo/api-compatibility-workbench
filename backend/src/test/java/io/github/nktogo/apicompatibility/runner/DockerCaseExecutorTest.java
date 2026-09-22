package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real Linux-container tests; CI explicitly supplies the prepared image on Linux. */
@EnabledIfSystemProperty(named = "workbench.container.image", matches = "sha256:[0-9a-f]{64}")
class DockerCaseExecutorTest {
    private static Path run(String mode, Duration timeout) throws Exception {
        return DockerCaseExecutor.run(System.getProperty("workbench.container.image"),
                Path.of(System.getProperty("workbench.repository"), "backend/target/isolated-test-runs"), mode, timeout);
    }

    @Test void realCaseKeepsRegressionsAndEnforcesKernelLimits() throws Exception {
        Path receipt = run("case", Duration.ofSeconds(180));
        var record = CaseInputs.object(Files.readAllBytes(receipt));
        assertEquals("COMPLETED", record.get("status").getAsString(), receipt.toString());
        assertTrue(record.get("containerRemoved").getAsBoolean());
        var isolation = record.getAsJsonObject("isolationProbe");
        assertTrue(isolation.get("rootWritesDenied").getAsBoolean());
        assertEquals("loopback-only", isolation.get("network").getAsString());
        assertEquals("1073741824", isolation.getAsJsonObject("cgroupLimits").get("memory.max").getAsString());
        assertEquals("128", isolation.getAsJsonObject("cgroupLimits").get("pids.max").getAsString());
        var report = CaseInputs.object(Files.readAllBytes(receipt.resolveSibling("worker-report.json")));
        var environment = CaseInputs.object(Files.readAllBytes(receipt.resolveSibling("worker-environment.json")));
        assertEquals(System.getProperty("workbench.container.image"), environment.get("execution.imageId").getAsString());
        assertEquals(4, report.getAsJsonObject("assessments").getAsJsonObject("target").getAsJsonArray("regressedScenarioIds").size());
        assertEquals(2, report.getAsJsonObject("assessments").getAsJsonObject("upstream-reference").getAsJsonArray("regressedScenarioIds").size());
        assertTrue(Files.readString(receipt.resolveSibling("report.html")).contains("Regression observed"));
        for (var artifact : record.getAsJsonObject("artifacts").entrySet())
            assertEquals(artifact.getValue().getAsString(), CaseInputs.hash(Files.readAllBytes(receipt.getParent().resolve(artifact.getKey()))));
        assertContainerAbsent(record.get("containerName").getAsString());
    }

    @Test void timeoutAndOutputFloodStopAndRemoveContainersWithoutReports() throws Exception {
        for (String mode : List.of("deadline-probe", "output-probe", "failure-probe")) {
            Path receipt = run(mode, Duration.ofSeconds(mode.equals("deadline-probe") ? 3 : 30));
            var record = CaseInputs.object(Files.readAllBytes(receipt));
            String expected = switch (mode) { case "deadline-probe" -> "TIMED_OUT"; case "output-probe" -> "OUTPUT_LIMIT"; default -> "FAILED"; };
            assertEquals(expected, record.get("status").getAsString(), receipt.toString());
            assertTrue(record.get("containerRemoved").getAsBoolean());
            if (record.get("runningBeforeCleanup").getAsBoolean()) assertFalse(record.has("exitCode"), "A running worker has no exit code yet");
            assertFalse(Files.exists(receipt.resolveSibling("worker-report.json")));
            assertFalse(Files.exists(receipt.resolveSibling("report.html")));
            assertContainerAbsent(record.get("containerName").getAsString());
        }
    }

    @Test void scratchCannotGrowPastItsConfiguredCapacity() throws Exception {
        Path receipt = run("scratch-probe", Duration.ofSeconds(30));
        var record = CaseInputs.object(Files.readAllBytes(receipt));
        assertEquals("COMPLETED", record.get("status").getAsString(), receipt.toString());
        assertTrue(CaseInputs.object(Files.readAllBytes(receipt.resolveSibling("probe.json"))).get("scratchLimitEnforced").getAsBoolean());
        assertContainerAbsent(record.get("containerName").getAsString());
    }

    @Test void unavailableImageFailsWithoutFallbackOrLeftoverContainer() throws Exception {
        Path receipt = DockerCaseExecutor.run("sha256:" + "0".repeat(64),
                Path.of(System.getProperty("workbench.repository"), "backend/target/isolated-test-runs"), "case", Duration.ofSeconds(30));
        var record = CaseInputs.object(Files.readAllBytes(receipt));
        assertEquals("FAILED", record.get("status").getAsString());
        assertEquals("CREATE", record.get("phase").getAsString());
        assertTrue(record.get("containerRemoved").getAsBoolean());
        assertFalse(Files.exists(receipt.resolveSibling("worker-report.json")));
        assertContainerAbsent(record.get("containerName").getAsString());
    }

    private static void assertContainerAbsent(String name) throws Exception {
        var result = BoundedCommand.run(List.of("docker", "ps", "--all", "--filter", "name=^/" + name + "$", "--format", "{{.ID}}"), Duration.ofSeconds(10), 65536);
        assertEquals(0, result.exitCode());
        assertEquals(0, result.stdout().length, "Container survived cleanup");
    }
}
