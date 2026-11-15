package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GitSourceObjectsTest {
    static JsonObject blob(byte[] bytes) {
        var value = new JsonObject(); value.addProperty("sha", GitSourceObjects.gitHash("blob", bytes));
        value.addProperty("size", bytes.length); value.addProperty("encoding", "base64");
        value.addProperty("content", Base64.getMimeEncoder(60, new byte[]{10}).encodeToString(bytes)); return value;
    }
    static JsonObject entry(String name, String mode, String sha, Integer size) {
        var value = new JsonObject(); value.addProperty("path", name); value.addProperty("mode", mode);
        value.addProperty("type", mode.equals("040000") ? "tree" : mode.equals("160000") ? "commit" : "blob");
        value.addProperty("sha", sha); if (size != null) value.addProperty("size", size);
        value.addProperty("url", "https://attacker.invalid/ignored"); return value;
    }
    // Caller supplies canonical Git ordering; response order can be changed independently in tests.
    static JsonObject tree(List<JsonObject> ordered) throws Exception {
        var bytes = new ByteArrayOutputStream(); var array = new JsonArray();
        for (var item : ordered) {
            String mode = item.get("mode").getAsString(); if (mode.equals("040000")) mode = "40000";
            bytes.writeBytes((mode + " " + item.get("path").getAsString()).getBytes(StandardCharsets.UTF_8));
            bytes.write(0); bytes.writeBytes(HexFormat.of().parseHex(item.get("sha").getAsString())); array.add(item);
        }
        byte[] content = bytes.toByteArray(); var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("tree " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        var result = new JsonObject(); result.addProperty("sha", HexFormat.of().formatHex(digest.digest(content)));
        result.addProperty("truncated", false); result.add("tree", array); return result;
    }
    @Test void matchesKnownGitObjectIdsAndDirectorySorting() throws Exception {
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", GitSourceObjects.gitHash("tree", new byte[0]));
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitSourceObjects.gitHash("blob", "hello\n".getBytes(StandardCharsets.UTF_8)));
        var file = entry("foo.bar", "100644", "a".repeat(40), 1); var directory = entry("foo", "040000", "b".repeat(40), null);
        var response = tree(List.of(file, directory)); var reversed = new JsonArray(); reversed.add(directory); reversed.add(file); response.add("tree", reversed);
        assertEquals(2, GitSourceObjects.tree(response, response.get("sha").getAsString()).size());
    }
    @Test void rejectsDuplicateAndHashChangedTreeEntries() throws Exception {
        var file = entry("pom.xml", "100644", "a".repeat(40), 1); var response = tree(List.of(file)); String sha = response.get("sha").getAsString();
        response.getAsJsonArray("tree").add(file.deepCopy()); assertThrows(IllegalArgumentException.class, () -> GitSourceObjects.tree(response, sha));
        var changed = tree(List.of(file)); changed.getAsJsonArray("tree").get(0).getAsJsonObject().addProperty("path", "changed.xml");
        assertThrows(IllegalArgumentException.class, () -> GitSourceObjects.tree(changed, sha));
    }
    @Test void checksBase64SizeAndContentAgainstBlobId() {
        byte[] bytes = "<project/>".getBytes(StandardCharsets.UTF_8); var response = blob(bytes);
        var entry = new GitSourceObjects.Entry("pom.xml", "100644", "blob", response.get("sha").getAsString(), (long) bytes.length);
        assertArrayEquals(bytes, GitSourceObjects.blob(response, entry));
        response.addProperty("content", Base64.getEncoder().encodeToString("other".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> GitSourceObjects.blob(response, entry));
        response.addProperty("content", "!!!!"); assertThrows(IllegalArgumentException.class, () -> GitSourceObjects.blob(response, entry));
    }
}
