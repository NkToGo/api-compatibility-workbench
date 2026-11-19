package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import io.github.nktogo.apicompatibility.runner.InvoiceRolloutDelivery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static io.github.nktogo.apicompatibility.github.ConsumerSourceCaptureTest.capture;
import static io.github.nktogo.apicompatibility.github.ConsumerSourceCaptureTest.installSources;
import static io.github.nktogo.apicompatibility.github.ConsumerSourceCaptureTest.planningInput;
import static io.github.nktogo.apicompatibility.github.ConsumerSourceCaptureTest.sourceFiles;
import static io.github.nktogo.apicompatibility.runner.InvoiceCapturePreflight.Phase.BASELINE;
import static io.github.nktogo.apicompatibility.runner.InvoiceCapturePreflight.Phase.TARGET;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceRolloutDeliveryTest {
    private static final Path CASE_ROOT = Path.of("..", "cases", MigrationConfiguration.CASE_ID);
    private static final String SAMPLE_IMAGE = "sha256:" + "1".repeat(64);
    @TempDir Path temporary;

    @BeforeAll static void key() throws Exception { GitHubSourceInventoryTest.key(); }

    private record Setup(InvoiceRolloutDelivery runner, String input, String capture, Path runs) {}

    private Setup prepare(boolean partial) throws Exception {
        var store = new EvidenceStore(temporary.resolve("evidence"));
        var runs = temporary.resolve("runs");
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            var files = sourceFiles();
            if (partial) files.remove("core/pom.xml");
            installSources(fixture, files);
            var input = planningInput(fixture, store);
            return new Setup(new InvoiceRolloutDelivery(store, CASE_ROOT, runs), input, capture(fixture, store, input).captureId(), runs);
        }
    }

    private static String attempt() { return "acw-" + UUID.randomUUID(); }

    @Test void previewBindsOneDeliveryAndChangedApprovalScopeCannotStartAnAttempt() throws Exception {
        var test = prepare(false);
        var id = attempt();
        var approval = test.runner().preview(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id);
        assertEquals(approval, test.runner().preview(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id));
        assertEquals("target-agreement", approval.scope().scenarioId());
        assertEquals("invoice-reconciliation", approval.scope().delivery().consumerId());
        assertEquals("reconciliation-basil-source", approval.scope().delivery().buildId());
        assertFalse(Files.exists(test.runs()));
        assertThrows(IllegalArgumentException.class, () -> test.runner().run(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id, null));
        assertThrows(IllegalArgumentException.class, () -> test.runner().run(test.input(), test.capture(), BASELINE, SAMPLE_IMAGE, id, approval.scopeIdentity()));
        assertThrows(IllegalArgumentException.class, () -> test.runner().run(test.input(), test.capture(), TARGET, "sha256:" + "2".repeat(64), id, approval.scopeIdentity()));
        assertThrows(IllegalArgumentException.class, () -> test.runner().run(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, attempt(), approval.scopeIdentity()));
        assertFalse(Files.exists(test.runs()));
    }

    @Test void partialCaptureCannotBePreviewedOrDispatched() throws Exception {
        var test = prepare(true);
        var id = attempt();
        assertThrows(IllegalArgumentException.class, () -> test.runner().preview(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id));
        assertThrows(IllegalArgumentException.class, () -> test.runner().run(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id, "0".repeat(64)));
        assertFalse(Files.exists(test.runs()));
    }

    @Test void anInterruptedAttemptDirectoryBlocksASecondDispatch() throws Exception {
        var test = prepare(false);
        var id = attempt();
        var approval = test.runner().preview(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id);
        var directory = Files.createDirectories(test.runs().resolve(id));
        Files.writeString(directory.resolve("started.json"), "{}");
        assertThrows(FileAlreadyExistsException.class, () -> test.runner().run(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, id, approval.scopeIdentity()));
        assertEquals("{}", Files.readString(directory.resolve("started.json")));
        assertFalse(Files.exists(directory.resolve("authorization.json")));
    }

    @Test
    @EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
    void reviewedCaptureRunsOneRegisteredDeliveryAndRetainsItsAttempt() throws Exception {
        var test = prepare(false);
        var id = attempt();
        var image = System.getProperty("workbench.invoice.image");
        var approval = test.runner().preview(test.input(), test.capture(), TARGET, image, id);
        var result = test.runner().run(test.input(), test.capture(), TARGET, image, id, approval.scopeIdentity());
        var directory = test.runs().resolve(id);
        assertEquals("COMPLETED", result.status());
        assertEquals("PASSED", result.assertionOutcome());
        assertEquals("ALLOWED", result.authorizationDecision());
        assertEquals(Boolean.TRUE, result.containerRemoved());
        assertEquals(approval.scopeIdentity(), result.scopeIdentity());
        var grant = GitHubJson.parse(Files.readAllBytes(directory.resolve("authorization.json")), 8192);
        assertEquals("execute-invoice-rollout-delivery", grant.get("permission").getAsString());
        assertEquals("invoice-rollout-delivery-v1:" + approval.scopeIdentity(), grant.get("mode").getAsString());
        var receipt = GitHubJson.parse(Files.readAllBytes(directory.resolve("result.json")), 65536);
        var started = GitHubJson.parse(Files.readAllBytes(directory.resolve("started.json")), 8192);
        var execution = receipt.getAsJsonObject("protectedExecution");
        assertEquals(approval.scope().delivery().sourceIdentity(), execution.get("sourceIdentity").getAsString());
        assertEquals(approval.scope().delivery().configurationIdentity(), execution.get("configurationIdentity").getAsString());
        assertEquals(image, execution.get("imageId").getAsString());
        assertEquals(started.get("containerName"), execution.get("containerName"));
        assertNotEquals(id, execution.get("containerName").getAsString());

        byte[] original = Files.readAllBytes(directory.resolve("result.json"));
        test.runner().revoke(id);
        assertTrue(Files.exists(directory.resolve("authorization.revoked")));
        assertThrows(FileAlreadyExistsException.class, () -> test.runner().run(test.input(), test.capture(), TARGET, image, id, approval.scopeIdentity()));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("result.json")));
    }
}
