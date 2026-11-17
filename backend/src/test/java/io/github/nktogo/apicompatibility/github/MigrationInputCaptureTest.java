package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;

import static io.github.nktogo.apicompatibility.github.MigrationConfigurationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationInputCaptureTest {
    @TempDir Path temporary;
    @BeforeAll static void keys() throws Exception { GitHubSourceInventoryTest.key(); }
    static Map<String, byte[]> catalog() {
        return Map.of(ROOT, GitHubSourceInventoryTest.POM, WEBHOOK, GitHubSourceInventoryTest.POM, RECONCILIATION, GitHubSourceInventoryTest.POM);
    }
    static void install(GitHubRepositoryConnectionTest.Fixture fixture, Map<String, byte[]> catalog) throws Exception {
        var root = new GitHubSourceInventoryTest.Directory();
        for (var entry : catalog.entrySet()) {
            var segments = entry.getKey().split("/"); var directory = root;
            for (int i = 0; i < segments.length - 1; i++) directory = directory.directory(segments[i]);
            directory.files.put(segments[segments.length - 1], entry.getValue());
        }
        GitHubSourceInventoryTest.install(fixture, root);
    }
    static GitHubSourceInventory.Capture capture(GitHubRepositoryConnectionTest.Fixture fixture) throws Exception {
        try (var transport = fixture.transport()) {
            return new GitHubSourceInventory(Clock.fixed(GitHubRepositoryConnectionTest.NOW, ZoneOffset.UTC)).capture(
                    GitHubRepositoryConnectionTest.SELECTION, GitHubSourceInventoryTest.sourceCredentials,
                    new GitHubSourceInventory.Plan(1, List.of(ROOT, WEBHOOK, RECONCILIATION)), transport);
        }
    }
    static MigrationConfiguration.Plan plan(GitHubSourceInventory.Capture captured, Map<String, byte[]> catalog) {
        return new MigrationConfiguration.Plan(1, MigrationConfiguration.CASE_ID, MigrationConfiguration.Repository.from(captured.inventory().repository()),
                catalog.entrySet().stream().map(e -> new MigrationConfiguration.Pom(e.getKey(), GitSourceObjects.sha256(e.getValue()))).toList(),
                List.of(new MigrationConfiguration.Consumer("invoice-webhook", WEBHOOK, baseline(true), target(true)),
                        new MigrationConfiguration.Consumer("invoice-reconciliation", RECONCILIATION, baseline(false), target(false))));
    }
    @Test void captureIsOptInAndCopiesBytesWithoutLoggingRawSourceOrExtraRequests() throws Exception {
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            install(fixture, catalog()); var captured = capture(fixture); var original = captured.poms().get(ROOT).clone();
            assertEquals(3, captured.poms().size()); assertEquals(captured.inventory().sourceRequests() + 5, fixture.requests.size());
            captured.poms().get(ROOT)[0] = 0; assertArrayEquals(original, captured.poms().get(ROOT));
            assertFalse(captured.toString().contains("PRIVATE_")); assertFalse(GitHubJson.JSON.toJson(captured).contains("PRIVATE_"));
            var inspected = GitHubSourceInventoryTest.inspect(fixture, List.of(ROOT, WEBHOOK, RECONCILIATION));
            assertTrue(inspected.limitations().contains("SOURCE_BYTES_NOT_RETAINED"));
            assertTrue(captured.inventory().limitations().contains("SOURCE_BYTES_CAPTURED_IN_MEMORY"));
            var declarations = plan(captured, catalog());
            assertEquals(MigrationConfiguration.bind(inspected, declarations).bindingIdentity(), MigrationConfiguration.bind(captured.inventory(), declarations).bindingIdentity());
        }
    }
    @Test void exactInputsReopenOfflineAfterTheSourceServerAndOriginalCallerAreGone() throws Exception {
        String id; MigrationConfiguration.Result binding; byte[] source = GitHubSourceInventoryTest.POM.clone();
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            install(fixture, catalog()); var captured = capture(fixture); var store = new EvidenceStore(temporary.resolve("store"));
            var retained = MigrationInputCapture.retain(store, captured, plan(captured, catalog())); id = retained.summary().inputId(); binding = retained.binding();
            assertEquals(id, MigrationInputCapture.retain(store, captured, plan(captured, catalog())).summary().inputId());
            retained.poms().get(ROOT)[0] = 0; assertArrayEquals(source, retained.poms().get(ROOT));
            assertFalse(GitHubJson.JSON.toJson(retained).contains("PRIVATE_")); assertFalse(retained.toString().contains("PRIVATE_"));
        }
        var store = new EvidenceStore(temporary.resolve("store")); var reopened = MigrationInputCapture.reopen(store, id);
        assertEquals(binding, reopened.binding()); assertEquals(3, reopened.summary().retainedPoms());
        assertFalse(reopened.summary().executionAuthorized()); assertArrayEquals(source, reopened.poms().get(ROOT));
        assertTrue(reopened.summary().limitations().contains("NOT_AN_EXECUTABLE_CONSUMER_BUNDLE")); assertTrue(store.list().isEmpty());
    }
    @Test void rateLimitedPartialCaptureRetainsEarlierBytesRetryHintsAndUnreadStates() throws Exception {
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            var catalog = new TreeMap<>(catalog()); catalog.put(WEBHOOK, ("<!--different-->" + new String(GitHubSourceInventoryTest.POM, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            install(fixture, catalog); fixture.faultPath = GitHubSourceInventoryTest.blobPath(GitSourceObjects.gitHash("blob", catalog.get(WEBHOOK)));
            fixture.faultStatus = 429; fixture.faultHeaders.put("Retry-After", "120");
            var captured = capture(fixture); var store = new EvidenceStore(temporary.resolve("store")); var result = MigrationInputCapture.retain(store, captured, plan(captured, catalog));
            assertEquals(MigrationConfiguration.State.DECLARATIONS_HAVE_GAPS, result.summary().declarationState()); assertEquals(1, result.summary().retainedPoms());
            var restored = MigrationInputCapture.reopen(store, result.summary().inputId());
            assertEquals(GitHubRepositoryConnectionTest.NOW.plusSeconds(120), restored.binding().inventory().problem().connectionFailure().retryNotBefore());
            assertEquals(GitHubSourceInventory.FileState.READ_FAILED, restored.binding().inventory().files().get(1).state());
            assertEquals(GitHubSourceInventory.FileState.NOT_READ, restored.binding().inventory().files().get(2).state());
            assertArrayEquals(catalog.get(ROOT), restored.poms().get(ROOT));
        }
    }
    @Test void rejectedPomBytesAndUnknownConfigurationRemainUnresolvedAfterReopening() throws Exception {
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            var catalog = new TreeMap<>(catalog()); catalog.put(WEBHOOK, "<project>PRIVATE_MALFORMED".getBytes(StandardCharsets.UTF_8)); install(fixture, catalog);
            var captured = capture(fixture); var p = plan(captured, catalog);
            var unknown = new MigrationConfiguration.Versions("29.0.0", null, MigrationConfiguration.BASIL, MigrationConfiguration.BASIL, MigrationConfiguration.ACACIA, null);
            p = MigrationConfigurationTest.webhookTarget(p, unknown); var store = new EvidenceStore(temporary.resolve("store"));
            var retained = MigrationInputCapture.retain(store, captured, p); var reopened = MigrationInputCapture.reopen(store, retained.summary().inputId());
            assertEquals(GitHubSourceInventory.FileState.POM_REJECTED, reopened.binding().inventory().files().get(1).state());
            assertNull(reopened.binding().declarations().consumers().get(1).target().requestApiVersion());
            assertEquals(3, reopened.summary().retainedPoms()); assertEquals(MigrationConfiguration.State.DECLARATIONS_HAVE_GAPS, reopened.summary().declarationState());
            assertArrayEquals(catalog.get(WEBHOOK), reopened.poms().get(WEBHOOK)); assertFalse(GitHubJson.JSON.toJson(reopened.summary()).contains("PRIVATE_"));
        }
    }
    @Test void newCaptureCannotOverwriteEarlierInputsOrEraseAStaleDeclaration() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); String original; MigrationConfiguration.Plan oldPlan;
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            install(fixture, catalog()); var captured = capture(fixture); oldPlan = plan(captured, catalog());
            original = MigrationInputCapture.retain(store, captured, oldPlan).summary().inputId();
        }
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            var changed = new TreeMap<>(catalog()); changed.put(ROOT, (new String(changed.get(ROOT), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
            install(fixture, changed); var next = MigrationInputCapture.retain(store, capture(fixture), oldPlan);
            assertNotEquals(original, next.summary().inputId()); assertEquals(MigrationConfiguration.State.DECLARATIONS_HAVE_GAPS, next.summary().declarationState());
            assertEquals(2, store.listInputs().size()); assertArrayEquals(catalog().get(ROOT), MigrationInputCapture.reopen(store, original).poms().get(ROOT));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"binding-id", "declared-role", "file-digest", "file-state", "unsafe-path", "missing-pom", "extra-pom", "extra-field", "wrong-limitations"})
    void rejectsSemanticallyChangedBindingsEvenWhenTheStorageHashesHaveBeenRecomputed(String changed) throws Exception {
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            install(fixture, catalog()); var store = new EvidenceStore(temporary.resolve("store")); var captured = capture(fixture);
            var retained = MigrationInputCapture.retain(store, captured, plan(captured, catalog()));
            var artifacts = new TreeMap<>(store.readInputs(retained.summary().inputId())); var report = GitHubJson.parse(artifacts.get("binding.json"), 4 * 1024 * 1024);
            var file = report.getAsJsonObject("inventory").getAsJsonArray("files").get(0).getAsJsonObject();
            switch (changed) {
                case "binding-id" -> report.addProperty("bindingIdentity", "0".repeat(64));
                case "declared-role" -> report.getAsJsonObject("declarations").getAsJsonArray("consumers").get(0).getAsJsonObject().addProperty("id", "unknown-role");
                case "file-digest" -> file.addProperty("contentSha256", "0".repeat(64));
                case "file-state" -> file.addProperty("state", "MISSING");
                case "unsafe-path" -> file.addProperty("path", "../pom.xml");
                case "missing-pom" -> artifacts.remove("pom-0.bin");
                case "extra-pom" -> artifacts.put("pom-7.bin", new byte[0]);
                case "extra-field" -> report.addProperty("credential", "PRIVATE_VALUE");
                case "wrong-limitations" -> report.getAsJsonObject("inventory").getAsJsonArray("limitations").set(1, new com.google.gson.JsonPrimitive("SOURCE_BYTES_NOT_RETAINED"));
                default -> throw new IllegalArgumentException();
            }
            artifacts.put("binding.json", GitHubJson.JSON.toJson(report).getBytes(StandardCharsets.UTF_8)); String forged = store.saveInputs(artifacts);
            assertThrows(RuntimeException.class, () -> MigrationInputCapture.reopen(store, forged));
            assertEquals(MigrationConfiguration.State.DECLARATIONS_COMPLETE_NOT_VERIFIED, MigrationInputCapture.reopen(store, retained.summary().inputId()).summary().declarationState());
        }
    }
}
