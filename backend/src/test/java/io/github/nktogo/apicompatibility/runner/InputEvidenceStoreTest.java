package io.github.nktogo.apicompatibility.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class InputEvidenceStoreTest {
    @TempDir Path temporary;
    static Map<String, byte[]> inputs() { return Map.of("binding.json", "{\"fixture\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "pom-0.bin", new byte[]{1, 2, 3}); }
    @Test void inputNamespaceSurvivesReopeningAndNeverBecomesAnExecutionReceipt() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); assertTrue(store.listInputs().isEmpty());
        var inputs = inputs(); String id = store.saveInputs(inputs); var reopened = new EvidenceStore(temporary.resolve("store"));
        assertEquals(List.of(id), reopened.listInputs()); assertTrue(reopened.list().isEmpty());
        assertArrayEquals(inputs.get("pom-0.bin"), reopened.readInputs(id).get("pom-0.bin"));
        assertThrows(IllegalArgumentException.class, () -> reopened.read(id));
        assertThrows(IllegalArgumentException.class, () -> reopened.readInputs("acw-" + UUID.randomUUID()));
    }
    @Test void identicalPublicationIsIdempotentAndChangedBytesPreserveBothSnapshots() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var values = inputs(); String original = store.saveInputs(values);
        assertEquals(original, store.saveInputs(values)); values.get("pom-0.bin")[0] = 9; String next = store.saveInputs(values);
        assertNotEquals(original, next); assertEquals(2, store.listInputs().size());
        assertEquals(1, store.readInputs(original).get("pom-0.bin")[0]); assertEquals(9, store.readInputs(next).get("pom-0.bin")[0]);
    }
    @Test void interruptedPublicationIsInvisibleAndReusesVerifiedObjectsOnRetry() throws Exception {
        var root = temporary.resolve("store"); var store = new EvidenceStore(root); var values = inputs();
        assertThrows(IOException.class, () -> store.saveInputs(values, () -> { throw new IOException("Injected publication stop"); }));
        assertTrue(store.listInputs().isEmpty());
        try (var files = Files.list(root.resolve("objects"))) { assertTrue(files.count() >= 2); }
        Files.writeString(root.resolve("inputs/orphan.pending"), "unfinished");
        String id = store.saveInputs(values); assertEquals(List.of(id), store.listInputs()); assertEquals(id, store.saveInputs(values));
    }
    @Test void cooperatingWritersPublishOneReferenceAndShareTheExistingStoreLock() throws Exception {
        var first = new EvidenceStore(temporary.resolve("store")); var second = new EvidenceStore(temporary.resolve("store"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> first.saveInputs(inputs())); var b = executor.submit(() -> second.saveInputs(inputs()));
            assertEquals(a.get(), b.get());
        }
        assertEquals(1, first.listInputs().size());
    }
    @Test void damagedObjectsCannotBeReadOrHiddenByAnIdempotentRetry() throws Exception {
        var root = temporary.resolve("store"); var store = new EvidenceStore(root); var values = inputs(); String id = store.saveInputs(values);
        Files.write(root.resolve("objects").resolve(CaseInputs.hash(values.get("pom-0.bin"))), new byte[]{4});
        assertThrows(IllegalArgumentException.class, () -> store.readInputs(id));
        assertThrows(IllegalArgumentException.class, () -> store.saveInputs(values));
        assertThrows(IllegalArgumentException.class, store::listInputs);
    }
    @ParameterizedTest @ValueSource(strings = {"../outside", "execution.json", "pom-8.bin", "source/pom.xml", ".env"})
    void refusesNamesOutsideTheSelectedInputSchema(String name) throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var values = new HashMap<>(inputs()); values.put(name, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> store.saveInputs(values)); assertTrue(store.listInputs().isEmpty());
    }
    @Test void refusesMissingBindingAndPerFileOrAggregateOverflowBeforePublication() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store"));
        assertThrows(IllegalArgumentException.class, () -> store.saveInputs(Map.of("pom-0.bin", new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> store.saveInputs(Map.of("binding.json", new byte[4 * 1024 * 1024 + 1])));
        assertThrows(IllegalArgumentException.class, () -> store.saveInputs(Map.of("binding.json", new byte[0], "pom-0.bin", new byte[65537])));
        var values = new HashMap<String, byte[]>(); values.put("binding.json", new byte[0]);
        for (int i = 0; i < 5; i++) values.put("pom-" + i + ".bin", new byte[65536]);
        assertThrows(IllegalArgumentException.class, () -> store.saveInputs(values)); assertTrue(store.listInputs().isEmpty());
    }
    @Test void rejectsReferenceSubstitutionAndUnexpectedPublishedNames() throws Exception {
        var root = temporary.resolve("store"); var store = new EvidenceStore(root); String id = store.saveInputs(inputs());
        Files.writeString(root.resolve("inputs").resolve(id + ".json"), "{\"formatVersion\":1,\"manifestSha256\":\"" + "a".repeat(64) + "\"}");
        assertThrows(IllegalArgumentException.class, () -> store.readInputs(id));
        assertThrows(IllegalArgumentException.class, () -> store.readInputs("../outside"));
        Files.writeString(root.resolve("inputs/unexpected.json"), "{}"); assertThrows(IllegalArgumentException.class, store::listInputs);
    }
    @Test void linkedInputObjectsAreNotFollowed() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        var root = temporary.resolve("store"); var store = new EvidenceStore(root); var values = inputs(); String id = store.saveInputs(values);
        Path object = root.resolve("objects").resolve(CaseInputs.hash(values.get("pom-0.bin")));
        Path outside = temporary.resolve("outside"); Files.write(outside, values.get("pom-0.bin")); Files.delete(object); Files.createSymbolicLink(object, outside);
        assertThrows(IllegalArgumentException.class, () -> store.readInputs(id));
    }
}
