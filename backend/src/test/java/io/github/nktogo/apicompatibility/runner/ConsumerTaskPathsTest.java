package io.github.nktogo.apicompatibility.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerTaskPathsTest {
    @TempDir Path root;

    @Test void ordinaryDirectoryNamesWithSpacesRemainReadable() throws Exception {
        Path directory = Files.createDirectories(root.resolve("ordinary directory/child"));
        Files.writeString(directory.resolve("input.json"), "first");
        ConsumerTasks.directory(directory);
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), ConsumerTasks.bytes(directory, "input.json"));
    }

    @Test void changedContentAndReplacedDirectoryAreCheckedOnEveryRead() throws Exception {
        Path directory = Files.createDirectories(root.resolve("inputs/child"));
        Path input = Files.writeString(directory.resolve("input.json"), "first");
        assertEquals("first", new String(ConsumerTasks.bytes(root, "inputs/child/input.json"), StandardCharsets.UTF_8));
        Files.writeString(input, "second");
        assertEquals("second", new String(ConsumerTasks.bytes(root, "inputs/child/input.json"), StandardCharsets.UTF_8));
        Files.move(directory, directory.resolveSibling("original"));
        Files.writeString(directory, "not a directory");
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "inputs/child/input.json"));
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(directory));
    }

    @Test void missingDirectoriesFilesAndInvalidArtifactPathsStayBlocked() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(root.resolve("missing")));
        Path file = Files.writeString(root.resolve("file"), "value");
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(file));
        for (String relative : new String[]{"../file", "./file", "/file", "a//file", "a/../file", "a/./file", "a\\file"})
            assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, relative));
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "missing"));
    }

    @Test void artifactSizeLimitIsUnchanged() throws Exception {
        Path file = root.resolve("input");
        byte[] maximum = new byte[1048576]; Files.write(file, maximum);
        assertArrayEquals(maximum, ConsumerTasks.bytes(root, "input"));
        Files.write(file, new byte[1048577]);
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "input"));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void symbolicFilesAndAncestorDirectoriesCannotRedirectReads() throws Exception {
        Path target = Files.createDirectories(root.resolve("target/child"));
        Files.writeString(target.resolve("input"), "outside");
        Path link = Files.createSymbolicLink(root.resolve("linked"), target.getParent());
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(link));
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(link.resolve("child")));
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "linked/child/input"));
        Files.createSymbolicLink(root.resolve("linked-file"), target.resolve("input"));
        assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "linked-file"));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void junctionAndOrdinaryDescendantsOfAJunctionStayBlocked() throws Exception {
        Path target = Files.createDirectories(root.resolve("target/child"));
        Files.writeString(target.resolve("input"), "outside");
        Path link = root.resolve("junction"); junction(link, target.getParent());
        try {
            assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(link));
            assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.directory(link.resolve("child")));
            assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "junction/child/input"));
        } finally { Files.delete(link); }
        assertEquals("outside", Files.readString(target.resolve("input")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void previouslyAcceptedDirectoryCannotBeReusedAfterReplacementWithAJunction() throws Exception {
        Path directory = Files.createDirectories(root.resolve("inputs/child"));
        Files.writeString(directory.resolve("input"), "original");
        assertEquals("original", new String(ConsumerTasks.bytes(root, "inputs/child/input"), StandardCharsets.UTF_8));
        Path original = directory.getParent().resolveSibling("original");
        Files.move(directory.getParent(), original);
        Path link = root.resolve("inputs"); junction(link, original);
        try { assertThrows(IllegalArgumentException.class, () -> ConsumerTasks.bytes(root, "inputs/child/input")); }
        finally { Files.delete(link); }
        assertEquals("original", Files.readString(original.resolve("child/input")));
    }

    private static void junction(Path link, Path target) throws Exception {
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction creation timed out");
            assertEquals(0, process.exitValue(), "Junction creation failed");
        } finally { process.destroyForcibly(); }
    }
}
