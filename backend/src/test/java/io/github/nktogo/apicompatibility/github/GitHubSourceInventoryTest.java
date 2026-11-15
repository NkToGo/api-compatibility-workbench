package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.github.GitSourceObjectsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubSourceInventoryTest {
    @TempDir Path temporary;
    static final byte[] POM = ("<project><modelVersion>4.0.0</modelVersion><properties><api.key>PRIVATE_SOURCE_SENTINEL</api.key></properties>"
            + "<dependencies><dependency><groupId>com.stripe</groupId><artifactId>stripe-java</artifactId><version>28.4.0</version></dependency></dependencies></project>").getBytes(StandardCharsets.UTF_8);
    static GitHubAppCredentials sourceCredentials;
    @BeforeAll static void key() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        sourceCredentials = GitHubAppCredentials.parse("Iv1.fixture", GitHubAppCredentialsTest.pem(generator.generateKeyPair(), false));
    }
    static class Directory {
        final Map<String, Directory> directories = new TreeMap<>(); final Map<String, byte[]> files = new TreeMap<>();
        Directory directory(String name) { return directories.computeIfAbsent(name, ignored -> new Directory()); }
        String register(Fixture fixture) throws Exception {
            var entries = new TreeMap<String, JsonObject>();
            for (var child : directories.entrySet()) entries.put(child.getKey(), entry(child.getKey(), "040000", child.getValue().register(fixture), null));
            for (var file : files.entrySet()) {
                var blob = blob(file.getValue()); String sha = blob.get("sha").getAsString(); fixture.replies.put(blobPath(sha), blob);
                entries.put(file.getKey(), entry(file.getKey(), "100644", sha, file.getValue().length));
            }
            var tree = tree(new ArrayList<>(entries.values())); String sha = tree.get("sha").getAsString(); fixture.replies.put(treePath(sha), tree); return sha;
        }
    }
    static String treePath(String sha) { return "/repos/sample-owner/billing/git/trees/" + sha; }
    static String blobPath(String sha) { return "/repos/sample-owner/billing/git/blobs/" + sha; }
    static String install(Fixture fixture, Directory directory) throws Exception {
        String root = directory.register(fixture); fixture.replies.get(COMMIT).getAsJsonObject("tree").addProperty("sha", root); return root;
    }
    static GitHubSourceInventory.Result inspect(Fixture fixture, List<String> paths) throws Exception {
        try (var transport = fixture.transport()) {
            return new GitHubSourceInventory(Clock.fixed(NOW, ZoneOffset.UTC)).inspect(SELECTION, sourceCredentials, new GitHubSourceInventory.Plan(1, paths), transport);
        }
    }
    @Test void readsOnlySelectedPinnedFilesCachesTreesAndEmitsDeclarationsWithoutRawSourceOrTokens() throws Exception {
        try (var fixture = new Fixture()) {
            var directory = new Directory(); directory.files.put("pom.xml", POM); directory.directory("module").files.put("pom.xml", POM);
            directory.files.put("PRIVATE_UNSELECTED.txt", new byte[]{1}); install(fixture, directory);
            var result = inspect(fixture, List.of("pom.xml", "module/pom.xml"));
            assertEquals(GitHubSourceInventory.State.COMPLETE_WITHIN_SELECTED_FILES, result.state());
            assertEquals(4, result.sourceRequests()); assertEquals(9, fixture.requests.size()); assertEquals(POM.length * 2, result.sourceBytes());
            assertTrue(result.files().stream().allMatch(f -> f.state() == GitHubSourceInventory.FileState.READ));
            assertEquals("28.4.0", result.files().getFirst().declarations().stripeJavaDeclarations().getFirst().declaredVersionExpression());
            assertEquals(GitSourceObjects.sha256(POM), result.files().getFirst().contentSha256());
            assertTrue(result.limitations().contains("MAVEN_EFFECTIVE_MODEL_NOT_RESOLVED"));
            assertTrue(result.limitations().contains("WEBHOOK_API_VERSION_NOT_ASSESSED")); assertFalse(result.repository().compatibilityAssessed());
            String output = GitHubJson.JSON.toJson(result); assertFalse(output.contains("PRIVATE_")); assertFalse(output.contains(TOKEN));
            assertFalse(output.contains("<project>")); assertFalse(fixture.requests.stream().anyMatch(r -> r.path().contains("attacker")));
        }
    }
    @Test void missingFileDoesNotEraseAnotherSuccessfulRead() throws Exception {
        try (var fixture = new Fixture()) {
            var directory = new Directory(); directory.files.put("pom.xml", POM); install(fixture, directory);
            var result = inspect(fixture, List.of("missing/pom.xml", "pom.xml"));
            assertEquals(GitHubSourceInventory.State.INCOMPLETE, result.state());
            assertEquals(GitHubSourceInventory.FileState.MISSING, result.files().getFirst().state());
            assertEquals(GitHubSourceInventory.FileState.READ, result.files().get(1).state()); assertEquals(2, result.sourceRequests());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"120000", "160000", "100644"})
    void refusesSymlinksSubmodulesAndFilesAsDirectoryComponents(String mode) throws Exception {
        try (var fixture = new Fixture()) {
            var root = tree(List.of(entry("module", mode, "c".repeat(40), mode.equals("160000") ? null : 5)));
            String sha = root.get("sha").getAsString(); fixture.replies.put(treePath(sha), root);
            fixture.replies.get(COMMIT).getAsJsonObject("tree").addProperty("sha", sha);
            var result = inspect(fixture, List.of("module/pom.xml"));
            assertEquals(GitHubSourceInventory.FileState.UNSUPPORTED_ENTRY, result.files().getFirst().state());
            assertEquals(1, result.sourceRequests()); assertEquals(6, fixture.requests.size());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"truncated", "tree-sha", "tree-content", "blob-sha", "blob-content", "blob-size"})
    void rejectsInconsistentGitObjectsAndStopsFurtherReads(String changed) throws Exception {
        try (var fixture = new Fixture()) {
            var directory = new Directory(); directory.files.put("pom.xml", POM); String root = install(fixture, directory);
            var tree = fixture.replies.get(treePath(root)); var blob = fixture.replies.get(blobPath(GitSourceObjects.gitHash("blob", POM)));
            switch (changed) {
                case "truncated" -> tree.addProperty("truncated", true);
                case "tree-sha" -> tree.addProperty("sha", "c".repeat(40));
                case "tree-content" -> tree.getAsJsonArray("tree").get(0).getAsJsonObject().addProperty("mode", "100755");
                case "blob-sha" -> blob.addProperty("sha", "c".repeat(40));
                case "blob-content" -> blob.addProperty("content", Base64.getEncoder().encodeToString("PRIVATE_BAD_BYTES".getBytes(StandardCharsets.UTF_8)));
                case "blob-size" -> blob.addProperty("size", 1);
                default -> throw new IllegalArgumentException();
            }
            var result = inspect(fixture, List.of("pom.xml", "later/pom.xml"));
            assertEquals(GitHubSourceInventory.Stop.INVALID_TREE_OR_BLOB, result.problem().reason());
            assertEquals(GitHubSourceInventory.FileState.READ_FAILED, result.files().getFirst().state());
            assertEquals(GitHubSourceInventory.FileState.NOT_READ, result.files().get(1).state());
            assertFalse(GitHubJson.JSON.toJson(result).contains("PRIVATE_"));
        }
    }
    @Test void providerRateLimitPreservesEarlierReadAndStopsWithTheOriginalFailure() throws Exception {
        try (var fixture = new Fixture()) {
            var directory = new Directory(); directory.files.put("pom.xml", POM); directory.directory("module").files.put("pom.xml", "<bad/>".getBytes(StandardCharsets.UTF_8)); install(fixture, directory);
            fixture.faultPath = blobPath(GitSourceObjects.gitHash("blob", "<bad/>".getBytes(StandardCharsets.UTF_8))); fixture.faultStatus = 429;
            fixture.faultHeaders.put("Retry-After", "120");
            var result = inspect(fixture, List.of("pom.xml", "module/pom.xml", "later/pom.xml"));
            assertEquals(GitHubSourceInventory.FileState.READ, result.files().getFirst().state());
            assertEquals(GitHubSourceInventory.Stop.CONNECTION_FAILURE, result.problem().reason());
            assertEquals(GitHubRepositoryConnection.Stage.SOURCE_BLOB, result.problem().connectionFailure().stage());
            assertEquals(NOW.plusSeconds(120), result.problem().connectionFailure().retryNotBefore());
            assertEquals(GitHubSourceInventory.FileState.NOT_READ, result.files().get(2).state()); assertEquals(9, fixture.requests.size());
        }
    }
    @Test void rejectsOversizedFilesWithoutRequestingTheirBlobAndCapsTotalVerifiedBytes() throws Exception {
        try (var fixture = new Fixture()) {
            var directory = new Directory(); directory.files.put("pom.xml", new byte[65537]); install(fixture, directory);
            var result = inspect(fixture, List.of("pom.xml")); assertEquals(GitHubSourceInventory.FileState.SIZE_LIMIT, result.files().getFirst().state());
            assertEquals(1, result.sourceRequests());
        }
        try (var fixture = new Fixture()) {
            var directory = new Directory(); String prefix = "<project><modelVersion>4.0.0</modelVersion><!--", suffix = "--></project>";
            byte[] large = (prefix + "x".repeat(65536 - prefix.length() - suffix.length()) + suffix).getBytes(StandardCharsets.UTF_8);
            assertEquals(65536, large.length);
            var paths = new ArrayList<String>();
            for (int i = 0; i < 5; i++) { String name = "module" + i; directory.directory(name).files.put("pom.xml", large); paths.add(name + "/pom.xml"); }
            install(fixture, directory); var result = inspect(fixture, paths);
            assertEquals(262144, result.sourceBytes()); assertEquals(GitHubSourceInventory.FileState.SIZE_LIMIT, result.files().get(4).state());
            assertEquals(4, result.files().stream().filter(f -> f.state() == GitHubSourceInventory.FileState.READ).count());
        }
    }
    @Test void requestBudgetAndWholeOperationDeadlineRemainVisible() throws Exception {
        try (var fixture = new Fixture()) {
            var root = new Directory(); var paths = new ArrayList<String>();
            for (int i = 0; i < 8; i++) {
                var current = root; var path = new StringBuilder();
                for (int depth = 0; depth < 6; depth++) { String name = "level" + i + "x" + depth; current = current.directory(name); path.append(name).append('/'); }
                current.files.put("pom.xml", (new String(POM, StandardCharsets.UTF_8).replace("28.4.0", "28.4." + i)).getBytes(StandardCharsets.UTF_8)); paths.add(path + "pom.xml");
            }
            install(fixture, root); var result = inspect(fixture, paths);
            assertEquals(24, result.sourceRequests()); assertEquals(GitHubSourceInventory.Stop.REQUEST_LIMIT, result.problem().reason());
            assertTrue(result.files().stream().anyMatch(f -> f.state() == GitHubSourceInventory.FileState.READ));
            assertTrue(result.files().stream().anyMatch(f -> f.state() == GitHubSourceInventory.FileState.NOT_READ)); assertEquals(29, fixture.requests.size());
        }
        var clock = new MutableClock();
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            var root = new Directory(); root.files.put("pom.xml", POM); install(fixture, root); fixture.afterScope = () -> clock.value = NOW.plusSeconds(181);
            var result = new GitHubSourceInventory(clock).inspect(SELECTION, sourceCredentials, new GitHubSourceInventory.Plan(1, List.of("pom.xml")), transport);
            assertEquals(GitHubSourceInventory.Stop.DEADLINE, result.problem().reason()); assertEquals(0, result.sourceRequests());
        }
    }
    @Test void malformedPomKeepsItsByteIdentitiesAndAnIncompleteConclusion() throws Exception {
        try (var fixture = new Fixture()) {
            var root = new Directory(); root.files.put("pom.xml", "<project>PRIVATE_MALFORMED".getBytes(StandardCharsets.UTF_8)); install(fixture, root);
            var result = inspect(fixture, List.of("pom.xml")); assertEquals(GitHubSourceInventory.State.INCOMPLETE, result.state());
            assertEquals(GitHubSourceInventory.FileState.POM_REJECTED, result.files().getFirst().state()); assertNotNull(result.files().getFirst().contentSha256());
            assertFalse(GitHubJson.JSON.toJson(result).contains("PRIVATE_MALFORMED"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"../pom.xml", "module/../pom.xml", "module\\pom.xml", "https://attacker/pom.xml", ".env", "module//pom.xml", "%2e%2e/pom.xml"})
    void refusesUnsafeOrUnselectedFileKindsBeforeAnyNetworkAccess(String path) {
        assertThrows(IllegalArgumentException.class, () -> new GitHubSourceInventory.Plan(1, List.of(path)));
    }
    @Test void planRequiresUniqueBoundedPathsAndStrictFields() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new GitHubSourceInventory.Plan(1, List.of("pom.xml", "pom.xml")));
        assertThrows(IllegalArgumentException.class, () -> new GitHubSourceInventory.Plan(1, List.of("d/".repeat(8) + "pom.xml")));
        Path path = temporary.resolve("plan.json"); Files.writeString(path, "{\"formatVersion\":1,\"pomPaths\":[\"pom.xml\"],\"token\":\"PRIVATE\"}");
        assertThrows(IllegalArgumentException.class, () -> GitHubSourceInventory.loadPlan(path));
        Files.writeString(path, "{\"formatVersion\":1,\"pomPaths\":[\"pom.xml\"]}"); assertEquals(List.of("pom.xml"), GitHubSourceInventory.loadPlan(path).pomPaths());
    }
}
