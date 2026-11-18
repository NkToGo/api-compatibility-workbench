package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.github.nktogo.apicompatibility.github.ConsumerSourceCapture.*;
import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.github.GitHubSourceInventoryTest.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceConsumerSourceCatalog.FILES;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerSourceCaptureTest {
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String LAST_SOURCE = FILES.getLast();
    private static final String OUTSIDE_NAME = "PRIVATE_UNSELECTED_NAME.txt";
    private static final byte[] OUTSIDE = "PRIVATE_UNSELECTED_BODY".getBytes(StandardCharsets.UTF_8);
    @TempDir Path temporary;

    @BeforeAll static void appKey() throws Exception { GitHubSourceInventoryTest.key(); }

    private static Map<String, byte[]> sourceFiles() throws Exception {
        var files = new TreeMap<String, byte[]>();
        for (String name : FILES) files.put(name, Files.readAllBytes(Path.of("..", ROOT, name)));
        return files;
    }

    private static void installSources(Fixture fixture, Map<String, byte[]> files) throws Exception {
        var root = new Directory();
        root.files.put(OUTSIDE_NAME, OUTSIDE);
        for (var entry : files.entrySet()) {
            var directory = root;
            var parts = (ROOT + "/" + entry.getKey()).split("/");
            for (int i = 0; i < parts.length - 1; i++) directory = directory.directory(parts[i]);
            directory.files.put(parts[parts.length - 1], entry.getValue());
        }
        install(fixture, root);
    }

    private static String planningInput(Fixture fixture, EvidenceStore store) throws Exception {
        var paths = List.of(ROOT + "/pom.xml", ROOT + "/webhook/pom.xml", ROOT + "/reconciliation/pom.xml");
        try (var transport = fixture.transport()) {
            var baseline = new GitHubSourceInventory(CLOCK).capture(SELECTION, sourceCredentials, new GitHubSourceInventory.Plan(1, paths), transport);
            var inventory = baseline.inventory();
            var declarations = new MigrationConfiguration.Plan(1, MigrationConfiguration.CASE_ID,
                    MigrationConfiguration.Repository.from(inventory.repository()),
                    inventory.files().stream().map(f -> new MigrationConfiguration.Pom(f.path(), f.contentSha256())).toList(),
                    List.of(
                            new MigrationConfiguration.Consumer("invoice-webhook", paths.get(1),
                                    MigrationConfigurationTest.baseline(true), MigrationConfigurationTest.target(true)),
                            new MigrationConfiguration.Consumer("invoice-reconciliation", paths.get(2),
                                    MigrationConfigurationTest.baseline(false), MigrationConfigurationTest.target(false))));
            return MigrationInputCapture.retain(store, baseline, declarations).summary().inputId();
        }
    }

    private static Summary capture(Fixture fixture, EvidenceStore store, String input) throws Exception {
        try (var transport = fixture.transport()) {
            return new ConsumerSourceCapture(store, CLOCK).capture(input, SELECTION, sourceCredentials, transport);
        }
    }

    @Test void retainsTheExactCatalogAndReopensOfflineWithoutExposingSourceOrGrantingExecution() throws Exception {
        var originals = sourceFiles();
        var store = new EvidenceStore(temporary);
        Summary result;
        String input;
        try (var fixture = new Fixture()) {
            installSources(fixture, originals);
            input = planningInput(fixture, store);
            fixture.requests.clear();
            result = capture(fixture, store, input);
            assertEquals(result.observation().sourceRequests() + 5, fixture.requests.size());
            assertEquals(10, fixture.requests.stream().filter(r -> r.path().contains("/git/blobs/")).count());
            assertFalse(fixture.requests.stream().anyMatch(r -> r.path().equals(blobPath(GitSourceObjects.gitHash("blob", OUTSIDE)))));
        }
        assertEquals(State.CAPTURED_REGISTERED_SOURCE, result.state());
        assertEquals(CatalogState.MATCHED, result.catalogState());
        assertEquals(3, result.planningPomsCompared());
        assertEquals(input, result.observation().inputId());
        assertFalse(result.executionAuthorized());
        assertFalse(GitHubJson.JSON.toJson(result).contains("PRIVATE_"));
        assertFalse(GitHubJson.JSON.toJson(result).contains("<project"));
        assertFalse(GitHubJson.JSON.toJson(result).contains(TOKEN));
        var retained = store.readConsumerSources(result.captureId());
        for (int i = 0; i < FILES.size(); i++) assertArrayEquals(originals.get(FILES.get(i)), retained.get("source-" + i + ".bin"));
        var reopened = new ConsumerSourceCapture(new EvidenceStore(temporary));
        assertEquals(result, reopened.inspect(result.captureId()));
        assertEquals(List.of(result), reopened.list());
        assertEquals(List.of(input), store.listInputs());
        assertTrue(store.list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.readInputs(result.captureId()));
        assertThrows(IllegalArgumentException.class, () -> store.readConsumerSources(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "changed", "extra", "registration"})
    void incompleteOrChangedSourceStaysVisibleWithoutFollowingRepositoryInstructions(String variant) throws Exception {
        var files = sourceFiles();
        var store = new EvidenceStore(temporary);
        switch (variant) {
            case "missing" -> files.remove(LAST_SOURCE);
            case "changed" -> files.put(LAST_SOURCE, OUTSIDE);
            case "extra" -> files.put("core/" + OUTSIDE_NAME, OUTSIDE);
            case "registration" -> files.put(FILES.getFirst(), OUTSIDE);
            default -> throw new AssertionError();
        }
        try (var fixture = new Fixture()) {
            installSources(fixture, files);
            var result = capture(fixture, store, planningInput(fixture, store));
            assertEquals(State.CAPTURED_WITH_GAPS, result.state());
            assertFalse(result.executionAuthorized());
            assertFalse(GitHubJson.JSON.toJson(result).contains("PRIVATE_"));
            switch (variant) {
                case "missing" -> {
                    assertEquals(CatalogState.INCOMPLETE, result.catalogState());
                    assertEquals(FileState.MISSING, result.observation().files().getLast().state());
                }
                case "changed" -> assertEquals(List.of(LAST_SOURCE), result.catalogMismatches());
                case "extra" -> {
                    assertEquals(List.of(new DirectoryGap("core", 1)), result.observation().directoryGaps());
                    assertFalse(fixture.requests.stream().anyMatch(r -> r.path().equals(blobPath(GitSourceObjects.gitHash("blob", OUTSIDE)))));
                }
                case "registration" -> assertEquals(CatalogState.REGISTRATION_MISMATCH, result.catalogState());
                default -> throw new AssertionError();
            }
            assertEquals(result, new ConsumerSourceCapture(store).inspect(result.captureId()));
        }
    }

    @Test void rateLimitRetainsEarlierBytesAndStopsWithoutRetry() throws Exception {
        var files = sourceFiles();
        var store = new EvidenceStore(temporary);
        try (var fixture = new Fixture()) {
            installSources(fixture, files);
            String input = planningInput(fixture, store);
            fixture.requests.clear();
            String fault = blobPath(GitSourceObjects.gitHash("blob", files.get("core/pom.xml")));
            fixture.faultPath = fault;
            fixture.faultStatus = 429;
            fixture.faultHeaders.put("Retry-After", "120");
            var result = capture(fixture, store, input);
            assertEquals(State.CAPTURED_WITH_GAPS, result.state());
            assertEquals(List.of(FileState.READ, FileState.READ_FAILED), result.observation().files().subList(0, 2).stream().map(FileObservation::state).toList());
            assertTrue(result.observation().files().subList(2, FILES.size()).stream().allMatch(row -> row.state() == FileState.NOT_READ));
            var problem = result.observation().problem();
            assertEquals(GitHubSourceInventory.Stop.CONNECTION_FAILURE, problem.reason());
            assertEquals(429, problem.providerFailure().httpStatus());
            assertEquals(NOW.plusSeconds(120).toString(), problem.providerFailure().retryNotBefore());
            assertEquals(fault, fixture.requests.getLast().path());
            assertEquals(1, fixture.requests.stream().filter(r -> r.path().equals(fault)).count());
            assertEquals(result, new ConsumerSourceCapture(store).inspect(result.captureId()));
        }
    }

    @Test void wrongSelectionStopsBeforeAuthenticationAndWrongTreeStopsBeforeSourceRequests() throws Exception {
        var store = new EvidenceStore(temporary);
        try (var fixture = new Fixture()) {
            installSources(fixture, sourceFiles());
            String input = planningInput(fixture, store);
            fixture.requests.clear();
            var wrong = new GitHubRepositoryConnection.Selection(1, 20, 30, 40, "sample-owner", "billing", "d".repeat(40));
            try (var transport = fixture.transport()) {
                var service = new ConsumerSourceCapture(store, CLOCK);
                assertThrows(IllegalArgumentException.class, () -> service.capture(input, wrong, sourceCredentials, transport));
            }
            assertTrue(fixture.requests.isEmpty());
            fixture.replies.get(COMMIT).getAsJsonObject("tree").addProperty("sha", "e".repeat(40));
            assertThrows(IllegalArgumentException.class, () -> capture(fixture, store, input));
            assertEquals(List.of("/app", INSTALLATION, MINT, SCOPE, COMMIT), fixture.requests.stream().map(Captured::path).toList());
            assertTrue(store.listConsumerSources().isEmpty());
        }
    }

    @Test void newStorageChecksumCannotHideAnInconsistentBlobOrPlanningBinding() throws Exception {
        var store = new EvidenceStore(temporary);
        String captureId;
        try (var fixture = new Fixture()) {
            installSources(fixture, sourceFiles());
            captureId = capture(fixture, store, planningInput(fixture, store)).captureId();
        }
        var original = store.readConsumerSources(captureId);
        var corrupted = new TreeMap<>(original);
        corrupted.put("source-0.bin", OUTSIDE);
        var reopened = new ConsumerSourceCapture(store);
        String wrongBytes = store.saveConsumerSources(corrupted);
        assertThrows(IllegalArgumentException.class, () -> reopened.inspect(wrongBytes));
        var report = GitHubJson.parse(original.get("capture.json"), 65536);
        report.addProperty("bindingIdentity", "b".repeat(64));
        corrupted = new TreeMap<>(original);
        corrupted.put("capture.json", GitHubJson.JSON.toJson(report).getBytes(StandardCharsets.UTF_8));
        String wrongBinding = store.saveConsumerSources(corrupted);
        assertThrows(IllegalArgumentException.class, () -> reopened.inspect(wrongBinding));
        assertEquals(State.CAPTURED_REGISTERED_SOURCE, reopened.inspect(captureId).state());
    }
}
