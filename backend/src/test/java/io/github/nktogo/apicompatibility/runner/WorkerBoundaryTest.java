package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerBoundaryTest {
    @Test void mutableImageReferencesAndUnrecognizedCommandsAreRejected() {
        String name = "acw-00000000-0000-0000-0000-000000000000";
        assertThrows(IllegalArgumentException.class, () -> DockerCaseExecutor.createArguments("image:latest", name, "case"));
        assertThrows(IllegalArgumentException.class, () -> DockerCaseExecutor.createArguments("sha256:" + "a".repeat(64), name, "sh"));
        assertThrows(IllegalArgumentException.class, () -> DockerCaseExecutor.createArguments("sha256:" + "a".repeat(64), "other-project", "case"));
    }

    @Test void noisyAndLongRunningProcessesCannotExceedCaptureLimits() throws Exception {
        String classes = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var flood = BoundedCommand.run(List.of(FixtureProcess.javaExecutable(), "-cp", classes, Probe.class.getName(), "flood"), Duration.ofSeconds(10), 8192);
        assertEquals(BoundedCommand.Status.OUTPUT_LIMIT, flood.status());
        assertEquals(8192, flood.stdout().length);
        var timeout = BoundedCommand.run(List.of(FixtureProcess.javaExecutable(), "-cp", classes, Probe.class.getName(), "sleep"), Duration.ofMillis(300), 8192);
        assertEquals(BoundedCommand.Status.TIMED_OUT, timeout.status());
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) Thread.sleep(60_000);
            else while (true) System.out.print("x".repeat(8192));
        }
    }
}
