package io.github.nktogo.apicompatibility.runner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Loader for one reviewed case, not an arbitrary repository execution interface. */
final class CaseInputs {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final String CASE_ID = "stripe-customer-sources-2020-08-27";
    static final Map<String, String> EXECUTABLES = Map.of(
            "consumer/baseline.java.txt", "c383bcc6696d43daa875690ba81de46874c3b98c832eef8455d935135b10c6df",
            "consumer/upstream-reference.java.txt", "92e6e05bfca15ad3b7af3a72f164c79b2d7c56ae004168ad5a1cbbfcf282fcc0",
            "stripe-java-17.4.0.jar", "dd21617fd65fefe5d13f9cfb4a9a117b77c211518ed1bb9ba661bb511e6740c1",
            "stripe-java-20.33.0.jar", "aa27965aadf401d65c6e6c80bc82d77d7b66d09e7f036a999c79510118af9a8c",
            "gson-2.8.6.jar", "c8fb4839054d280b3033f800d1f5a97de2f028eb8ba2eb458ad287e536f3f25f");
    final Map<String, byte[]> files = new TreeMap<>();
    final Map<String, JsonObject> scenarios;
    final Map<String, JsonObject> expectations;

    CaseInputs(Path root, Path dependencies) throws IOException {
        files.put("integrity.json", Files.readAllBytes(root.resolve("integrity.json")));
        var index = object(files.get("integrity.json"));
        require(index.get("formatVersion").getAsInt() == 1, "Unknown case index format");
        for (var element : index.getAsJsonArray("files")) {
            var entry = element.getAsJsonObject();
            String name = entry.get("path").getAsString();
            require(name.matches("[a-zA-Z0-9_./-]+") && !name.contains("..") && !name.startsWith("/"), "Invalid input path");
            require(!files.containsKey(name), "Duplicate input path");
            byte[] bytes = Files.readAllBytes(root.resolve(name));
            require(hash(bytes).equals(entry.get("sha256").getAsString()), "Input digest mismatch: " + name);
            files.put(name, bytes);
        }
        require(files.keySet().equals(Set.of("integrity.json", "provenance.json", "fixtures.json", "expectations.json",
                "consumer/baseline.java.txt", "consumer/upstream-reference.java.txt")), "Unexpected case input inventory");
        for (var entry : EXECUTABLES.entrySet()) {
            byte[] bytes = entry.getKey().endsWith(".jar")
                    ? Files.readAllBytes(dependencies.resolve(entry.getKey())) : files.get(entry.getKey());
            require(hash(bytes).equals(entry.getValue()), "Unreviewed executable input: " + entry.getKey());
            files.put(entry.getKey(), bytes);
        }
        var fixture = object(files.get("fixtures.json"));
        var oracle = object(files.get("expectations.json"));
        require(CASE_ID.equals(fixture.get("caseId").getAsString()) && CASE_ID.equals(oracle.get("caseId").getAsString()), "Wrong case identity");
        require(fixture.get("formatVersion").getAsInt() == 1 && oracle.get("formatVersion").getAsInt() == 1, "Unknown case format");
        require(fixture.get("origin").getAsString().equals("authored-synthetic"), "Unsupported fixture origin");
        require(oracle.get("requireEvidenceBeforeDeactivation").getAsBoolean(), "Deactivation evidence must remain required");
        require(fixture.getAsJsonObject("apiVersions").get("baseline").getAsString().equals("2019-12-03")
                && fixture.getAsJsonObject("apiVersions").get("target").getAsString().equals("2020-08-27"), "Unsupported API mapping");
        scenarios = scenarios(fixture);
        expectations = scenarios(oracle);
        require(!scenarios.isEmpty() && scenarios.keySet().equals(expectations.keySet()), "Scenario coverage mismatch");
        for (var expected : expectations.values()) {
            require(expected.get("mustComplete").getAsBoolean(), "Unsupported completion expectation");
            strings(expected, "finalMethodIds");
            strings(expected, "deactivatedMethodIds");
        }
    }

    private static Map<String, JsonObject> scenarios(JsonObject object) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (var item : object.getAsJsonArray("scenarios")) {
            var scenario = item.getAsJsonObject();
            String id = scenario.get("id").getAsString();
            require(id.matches("[a-z0-9-]+") && result.putIfAbsent(id, scenario) == null, "Invalid or duplicate scenario ID");
        }
        return result;
    }

    static java.util.List<String> strings(JsonObject object, String key) {
        return object.getAsJsonArray(key).asList().stream().map(v -> v.getAsString()).sorted().toList();
    }

    static JsonObject object(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String identity(Object value) { return hash(JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }

    static Map<String, String> inventory(Path directory) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                entries.put(directory.relativize(path).toString().replace('\\', '/'), hash(Files.readAllBytes(path)));
            }
        }
        return entries;
    }

    static void write(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.toJson(value) + "\n", StandardCharsets.UTF_8);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
