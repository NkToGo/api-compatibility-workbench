package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableExecutionTest {
    @TempDir Path root;
    private EvidenceStore store() throws Exception { return new EvidenceStore(root.resolve("evidence")); }
    private DurableExecution journal() throws Exception { return new DurableExecution(root.resolve("journal"), store()); }
    private String create(DurableExecution journal) throws Exception {
        return journal.create("sha256:" + "0".repeat(64), "case", Duration.ofSeconds(30));
    }

    @Test void queuedCancellationIsDurableAndDoesNotNeedDocker() throws Exception {
        var journal = journal(); String id = create(journal);
        assertEquals("QUEUED", journal.status(id));
        assertEquals("CANCELLED", journal.cancel(id));
        byte[] receipt = store().read(id).get("execution.json");
        assertEquals("CANCELLED", journal().recover(id));
        assertEquals("CANCELLED", journal().execute(id));
        assertArrayEquals(receipt, store().read(id).get("execution.json"));
        assertFalse(store().read(id).containsKey("report.html"));
    }

    @Test void explicitRecoveryClosesAnUnstartedAttemptWithoutInventingResults() throws Exception {
        var journal = journal(); String id = create(journal);
        assertEquals("INTERRUPTED", journal.recover(id));
        var receipt = CaseInputs.object(store().read(id).get("execution.json"));
        assertFalse(receipt.has("exitCode"));
        assertEquals("INTERRUPTED", journal.cancel(id));
        assertEquals(java.util.List.of(id), journal.list());
    }

    @Test void activeLeasePreventsConcurrentRecoveryAndMakesCancellationARequest() throws Exception {
        var journal = journal(); String id = create(journal);
        try (var channel = FileChannel.open(root.resolve("journal").resolve(id).resolve("owner.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertEquals("ACTIVE", journal().status(id));
            assertEquals("ACTIVE", journal().recover(id));
            assertEquals("CANCELLATION_REQUESTED", journal().cancel(id));
            assertTrue(store().list().isEmpty());
        }
        assertEquals("RECOVERY_REQUIRED", journal().status(id));
        assertEquals("CANCELLED", journal().recover(id));
    }

    @Test void tamperedIdentityAndMissingEvidenceFailClosed() throws Exception {
        var journal = journal(); String id = create(journal);
        assertThrows(IllegalArgumentException.class, () -> journal.recover("../outside"));
        journal.cancel(id);
        Files.delete(root.resolve("evidence/runs").resolve(id + ".json"));
        assertThrows(IllegalArgumentException.class, () -> journal.status(id));
        Path spec = root.resolve("journal").resolve(id).resolve("spec.json");
        var altered = CaseInputs.object(Files.readAllBytes(spec)); altered.addProperty("runId", "wrong");
        CaseInputs.write(spec, altered);
        assertThrows(IllegalArgumentException.class, () -> journal.recover(id));
    }

    @Test void interruptedRegistrationDoesNotBlockListingPublishedAttempts() throws Exception {
        var journal = journal(); String id = create(journal);
        Files.createDirectory(root.resolve("journal/acw-00000000-0000-0000-0000-000000000000"));
        assertEquals(java.util.List.of(id), journal.list());
    }

    @Test void interruptedRecoveryCannotRestartAQueuedAttempt() throws Exception {
        var journal = journal(); String id = create(journal);
        Files.writeString(root.resolve("journal").resolve(id).resolve("recovery-started.json"), "{}");
        assertEquals("RECOVERY_REQUIRED", journal.status(id));
        assertThrows(IllegalArgumentException.class, () -> journal.execute(id));
        assertEquals("INTERRUPTED", journal.recover(id));
    }
}
