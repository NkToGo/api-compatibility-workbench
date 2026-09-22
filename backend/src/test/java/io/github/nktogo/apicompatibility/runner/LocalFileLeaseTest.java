package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileLeaseTest {
    @TempDir Path root;

    @Test void localContentionDoesNotReleaseOwnershipToAnotherProcess() throws Exception {
        Path file = root.resolve("owner.lock");
        try (var lease = LocalFileLease.tryAcquire(file)) {
            assertNotNull(lease);
            for (int i = 0; i < 3; i++) assertNull(LocalFileLease.tryAcquire(file));
            assertEquals(3, probe(file), "Local contenders must not release the cross-process lock");
        }
        assertEquals(0, probe(file));
    }

    private int probe(Path file) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), file.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("probe.log").toFile()).start();
        try { assertTrue(process.waitFor(10, TimeUnit.SECONDS)); return process.exitValue(); }
        finally { process.destroyForcibly(); }
    }

    public static final class Probe {
        private Probe() {}
        public static void main(String[] args) throws Exception {
            int result;
            try (var lease = LocalFileLease.tryAcquire(Path.of(args[0]))) { result = lease == null ? 3 : 0; }
            System.exit(result);
        }
    }
}
