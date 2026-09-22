package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceStoreTest {
    @TempDir Path temporary;

    @Test void evidenceSurvivesSourceRemovalAndStoreReopening() throws Exception {
        Path receipt = fixture("FAILED");
        String id = receipt.getParent().getFileName().toString();
        var store = new EvidenceStore(temporary.resolve("store"));
        assertEquals(id, store.save(receipt));
        byte[] original = Files.readAllBytes(receipt);
        Files.delete(receipt.resolveSibling("request.json")); Files.delete(receipt);
        var reopened = new EvidenceStore(temporary.resolve("store"));
        assertEquals(List.of(id), reopened.list());
        assertArrayEquals(original, reopened.read(id).get("execution.json"));
        Path exported = temporary.resolve("exported"); reopened.export(id, exported);
        assertArrayEquals(original, Files.readAllBytes(exported.resolve("execution.json")));
        assertThrows(FileAlreadyExistsException.class, () -> reopened.export(id, exported));
    }

    @Test void retryIsIdempotentButConflictingHistoryCannotOverwrite() throws Exception {
        Path receipt = fixture("FAILED"); var store = new EvidenceStore(temporary.resolve("store"));
        String id = store.save(receipt);
        assertEquals(id, store.save(receipt));
        var changed = CaseInputs.object(Files.readAllBytes(receipt)); changed.addProperty("status", "TIMED_OUT");
        CaseInputs.write(receipt, changed);
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        assertEquals("FAILED", CaseInputs.object(store.read(id).get("execution.json")).get("status").getAsString());
    }

    @Test void interruptedPublicationIsInvisibleAndRetryCompletes() throws Exception {
        Path receipt = fixture("FAILED"); var store = new EvidenceStore(temporary.resolve("store"));
        assertThrows(IOException.class, () -> store.save(receipt, () -> { throw new IOException("Injected interruption"); }));
        assertTrue(store.list().isEmpty());
        String id = store.save(receipt);
        assertEquals(List.of(id), store.list());
    }

    @Test void missingOrChangedArtifactsCannotBeImported() throws Exception {
        Path receipt = fixture("FAILED"); var store = new EvidenceStore(temporary.resolve("store"));
        Files.writeString(receipt.resolveSibling("request.json"), "altered");
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        Files.delete(receipt.resolveSibling("request.json"));
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        assertTrue(store.list().isEmpty());
    }

    @Test void damagedStoredObjectsFailReadsAndIdempotentImports() throws Exception {
        Path receipt = fixture("FAILED"); Path root = temporary.resolve("store"); var store = new EvidenceStore(root);
        String id = store.save(receipt);
        String hash = CaseInputs.hash(Files.readAllBytes(receipt.resolveSibling("request.json")));
        Files.writeString(root.resolve("objects").resolve(hash), "damaged");
        assertThrows(IllegalArgumentException.class, () -> store.read(id));
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        assertThrows(IllegalArgumentException.class, store::list);
    }

    @Test void unsafeNamesAndExtraFilesAreRejected() throws Exception {
        Path receipt = fixture("FAILED"); var store = new EvidenceStore(temporary.resolve("store"));
        Files.writeString(receipt.resolveSibling("unexpected.txt"), "extra");
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        Files.delete(receipt.resolveSibling("unexpected.txt"));
        var record = CaseInputs.object(Files.readAllBytes(receipt));
        record.getAsJsonObject("artifacts").addProperty("../outside", "a".repeat(64)); CaseInputs.write(receipt, record);
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        assertThrows(IllegalArgumentException.class, () -> store.read("../../outside"));
    }

    @Test void twoWritersCanPublishTheSameRunWithoutDuplicatingIt() throws Exception {
        Path receipt = fixture("FAILED"); Path root = temporary.resolve("store");
        var first = new EvidenceStore(root); var second = new EvidenceStore(root);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> first.save(receipt)); var b = executor.submit(() -> second.save(receipt));
            assertEquals(a.get(), b.get());
        }
        assertEquals(1, first.list().size());
    }

    @Test void unfinishedAndOversizedInputsCannotBePublished() throws Exception {
        Path receipt = fixture("FAILED"); var store = new EvidenceStore(temporary.resolve("store"));
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt.resolveSibling("execution.json.pending")));
        Files.write(receipt.resolveSibling("request.json"), new byte[4 * 1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> store.save(receipt));
        assertTrue(store.list().isEmpty());
    }

    private Path fixture(String status) throws IOException {
        String id = "acw-" + UUID.randomUUID(); Path folder = Files.createDirectory(temporary.resolve(id));
        Files.writeString(folder.resolve("request.json"), "{\"fixture\":true}\n");
        CaseInputs.write(folder.resolve("execution.json"), Map.of("formatVersion", 1, "containerName", id,
                "status", status, "mode", "case", "artifacts", CaseInputs.inventory(folder)));
        return folder.resolve("execution.json");
    }
}
