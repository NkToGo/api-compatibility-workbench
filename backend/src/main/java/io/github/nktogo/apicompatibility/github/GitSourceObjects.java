package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Validates selected bytes against Git object identities; returned URLs are never followed. */
final class GitSourceObjects {
    record Entry(String name, String mode, String type, String sha, Long size) {}
    private GitSourceObjects() {}
    static Map<String, Entry> tree(JsonObject object, String expected) {
        GitHubJson.require(expected.equals(GitHubJson.text(object, "sha")) && object.has("truncated")
                && object.get("truncated").isJsonPrimitive() && object.getAsJsonPrimitive("truncated").isBoolean()
                && !object.get("truncated").getAsBoolean() && object.has("tree") && object.get("tree").isJsonArray());
        var values = object.getAsJsonArray("tree"); GitHubJson.require(values.size() <= 1000);
        var entries = new HashMap<String, Entry>();
        for (var value : values) {
            var item = GitHubJson.object(value); String name = GitHubJson.text(item, "path"), mode = GitHubJson.text(item, "mode");
            String type = GitHubJson.text(item, "type"), sha = GitHubJson.text(item, "sha");
            GitHubJson.require(!name.isEmpty() && !name.equals(".") && !name.equals("..") && !name.contains("/") && !name.contains("\0")
                    && sha.matches("[0-9a-f]{40}") && !entries.containsKey(name));
            String expectedType = switch (mode) { case "040000" -> "tree"; case "160000" -> "commit";
                case "100644", "100755", "120000" -> "blob"; default -> throw new IllegalArgumentException("Unsupported Git mode"); };
            GitHubJson.require(type.equals(expectedType));
            Long size = item.has("size") ? GitHubJson.number(item, "size") : null;
            GitHubJson.require(size == null || size >= 0);
            entries.put(name, new Entry(name, mode, type, sha, size));
        }
        var ordered = new ArrayList<>(entries.values());
        ordered.sort((a, b) -> Arrays.compareUnsigned(sortName(a), sortName(b)));
        var bytes = new ByteArrayOutputStream();
        for (var entry : ordered) {
            String mode = entry.mode().equals("040000") ? "40000" : entry.mode();
            bytes.writeBytes((mode + " ").getBytes(StandardCharsets.US_ASCII)); bytes.writeBytes(utf8(entry.name()));
            bytes.write(0); bytes.writeBytes(HexFormat.of().parseHex(entry.sha()));
        }
        GitHubJson.require(gitHash("tree", bytes.toByteArray()).equals(expected));
        return Map.copyOf(entries);
    }
    static byte[] blob(JsonObject object, Entry entry) {
        GitHubJson.require(entry.sha().equals(GitHubJson.text(object, "sha")) && "base64".equals(GitHubJson.text(object, "encoding")));
        String content = GitHubJson.text(object, "content", 131072).replace("\r", "").replace("\n", "");
        byte[] bytes = Base64.getDecoder().decode(content);
        GitHubJson.require(bytes.length <= 65536 && GitHubJson.number(object, "size") == bytes.length
                && entry.size() != null && entry.size() == bytes.length && gitHash("blob", bytes).equals(entry.sha()));
        return bytes;
    }
    static String gitHash(String type, byte[] bytes) {
        var digest = digest("SHA-1"); digest.update((type + " " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
    static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest("SHA-256").digest(bytes)); }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] sortName(Entry entry) { return utf8(entry.name() + (entry.type().equals("tree") ? "/" : "")); }
    private static byte[] utf8(String value) {
        try { var buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)); var bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes; }
        catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Invalid Git filename encoding"); }
    }
}
