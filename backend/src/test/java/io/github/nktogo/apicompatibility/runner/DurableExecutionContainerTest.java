package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "workbench.container.image", matches = "sha256:[0-9a-f]{64}")
class DurableExecutionContainerTest {
    private final Path root = Path.of(System.getProperty("workbench.repository"), "backend/target/durable-test-runs", UUID.randomUUID().toString());
    private String image() { return System.getProperty("workbench.container.image"); }
    private EvidenceStore store() throws Exception { return new EvidenceStore(root.resolve("evidence")); }
    private DurableExecution journal() throws Exception { return new DurableExecution(root.resolve("journal"), store()); }
    private String create(String mode) throws Exception { return journal().create(image(), mode, Duration.ofSeconds(120)); }

    @Test void killedControllerIsRecoveredWithoutReplayingOrInventingSuccess() throws Exception {
        String id = create("deadline-probe");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var controller = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Controller.class.getName(), root.toString(), id).redirectErrorStream(true)
                .redirectOutput(root.resolve("controller.log").toFile()).start();
        try {
            awaitRunning(id);
            assertEquals("ACTIVE", journal().recover(id));
            controller.destroyForcibly();
            assertTrue(controller.waitFor(15, TimeUnit.SECONDS));
            // Windows can signal process exit before its file-lock teardown becomes observable.
            long releasedBy = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (journal().status(id).equals("ACTIVE") && System.nanoTime() < releasedBy) Thread.sleep(50);
            assertEquals("RECOVERY_REQUIRED", journal().status(id));
            assertEquals("INTERRUPTED", journal().recover(id));
            var receipt = CaseInputs.object(store().read(id).get("execution.json"));
            assertFalse(receipt.has("exitCode"));
            assertFalse(store().read(id).containsKey("report.html"));
            assertEquals("INTERRUPTED", journal().recover(id));
            assertAbsent(id);
        } finally { controller.destroyForcibly(); cleanup(id); }
    }

    @Test void cancellationStopsAnActiveWorkerAndRetainsItsReceipt() throws Exception {
        String id = create("deadline-probe");
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<String> execution = executor.submit(() -> journal().execute(id));
            try {
                awaitRunning(id);
                assertEquals("CANCELLATION_REQUESTED", journal().cancel(id));
                assertEquals("CANCELLED", execution.get(30, TimeUnit.SECONDS));
                assertEquals("CANCELLED", journal().status(id));
                assertFalse(store().read(id).containsKey("report.html"));
                assertAbsent(id);
            } finally { execution.cancel(true); cleanup(id); }
        }
    }

    @Test void finalReceiptSurvivesInterruptedPublicationAndIsNotReexecuted() throws Exception {
        String id = create("probe");
        var journal = journal();
        assertThrows(IOException.class, () -> journal.execute(id, () -> { throw new IOException("Injected finalization interruption"); }));
        byte[] receipt = Files.readAllBytes(journal.receipt(id));
        assertArrayEquals(receipt, store().read(id).get("execution.json"));
        assertEquals("COMPLETED", CaseInputs.object(receipt).get("status").getAsString());
        assertEquals("RECOVERY_REQUIRED", journal().status(id));
        assertEquals("COMPLETED", journal().recover(id));
        assertArrayEquals(receipt, store().read(id).get("execution.json"));
        assertEquals("COMPLETED", journal().recover(id));
        assertAbsent(id);
    }

    @Test void recoveryRefusesAContainerWithDifferentOwnership() throws Exception {
        String id = create("probe");
        Files.writeString(root.resolve("journal").resolve(id).resolve("started.json"), "{}");
        var arguments = new ArrayList<>(DockerCaseExecutor.createArguments(image(), id, "probe"));
        arguments.set(arguments.indexOf("io.github.nktogo.acw.attempt=" + id), "io.github.nktogo.acw.attempt=unrelated");
        var created = BoundedCommand.run(arguments, Duration.ofSeconds(20), 65536);
        assertEquals(0, created.exitCode());
        try {
            assertThrows(IllegalArgumentException.class, () -> journal().recover(id));
            assertEquals("/" + id, DockerCaseExecutor.inspect(id).get("Name").getAsString());
            assertEquals("RECOVERY_REQUIRED", journal().status(id));
            assertTrue(store().list().isEmpty());
        } finally {
            // This test created the deliberately mismatched container; remove only its returned immutable ID.
            String containerId = new String(created.stdout(), java.nio.charset.StandardCharsets.UTF_8).trim();
            assertTrue(containerId.matches("[0-9a-f]{64}"));
            BoundedCommand.run(List.of("docker", "rm", "--force", containerId), Duration.ofSeconds(15), 65536);
        }
    }

    private static void awaitRunning(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var result = BoundedCommand.run(List.of("docker", "ps", "--filter", "name=^/" + id + "$", "--format", "{{.ID}}"), Duration.ofSeconds(5), 65536);
            if (result.exitCode() == 0 && result.stdout().length > 0) return;
            Thread.sleep(100);
        }
        fail("Worker did not become active: " + id);
    }
    private void cleanup(String id) throws Exception { DockerCaseExecutor.removeOwned(id, image()); }
    private static void assertAbsent(String id) throws Exception {
        var result = BoundedCommand.run(List.of("docker", "ps", "--all", "--filter", "name=^/" + id + "$", "--format", "{{.ID}}"), Duration.ofSeconds(10), 65536);
        assertEquals(0, result.exitCode()); assertEquals(0, result.stdout().length);
    }

    /** A real separate controller JVM lets the test kill ownership without running finally blocks. */
    public static final class Controller {
        private Controller() {}
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            new DurableExecution(root.resolve("journal"), new EvidenceStore(root.resolve("evidence"))).execute(args[1]);
        }
    }
}
