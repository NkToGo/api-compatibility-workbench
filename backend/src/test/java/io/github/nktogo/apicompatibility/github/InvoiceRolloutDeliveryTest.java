package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Conclusion;
import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import io.github.nktogo.apicompatibility.runner.InvoiceRolloutComparison;
import io.github.nktogo.apicompatibility.runner.InvoiceRolloutDelivery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

    private InvoiceRolloutComparison.Report compare(Setup test, String baseline, String target) throws Exception {
        return InvoiceRolloutComparison.inspect(new EvidenceStore(temporary.resolve("evidence")),
                CASE_ROOT, test.runs(), test.input(), test.capture(), baseline, target);
    }

    @Test void comparisonPreservesMissingInterruptedAndWrongPhaseSelections() throws Exception {
        var test = prepare(false);
        var id = attempt();
        var missing = attempt();
        var approval = test.runner().preview(test.input(), test.capture(), BASELINE, SAMPLE_IMAGE, id);
        var directory = Files.createDirectories(test.runs().resolve(id));
        write(directory, "scope.json", approval);
        var partial = compare(test, id, missing);
        assertEquals(Conclusion.UNRESOLVED, partial.conclusion());
        assertEquals(InvoiceRolloutComparison.ReceiptState.INCOMPLETE_ATTEMPT, partial.baseline().state());
        assertEquals(InvoiceRolloutComparison.ReceiptState.MISSING_ATTEMPT, partial.target().state());
        assertEquals(InvoiceRolloutComparison.ReceiptState.SCOPE_MISMATCH, compare(test, null, id).target().state());
        assertThrows(IllegalArgumentException.class, () -> compare(test, id, id));
        assertFalse(Files.exists(directory.resolve("result.json")));
    }

    @Test void comparisonRechecksBoundAssertionsAndRetainsFailureAndDeniedStates() throws Exception {
        var test = prepare(false);
        var baseline = test.runner().preview(test.input(), test.capture(), BASELINE, SAMPLE_IMAGE, attempt());
        var target = test.runner().preview(test.input(), test.capture(), TARGET, SAMPLE_IMAGE, attempt());
        retain(test, baseline, true);
        var receipt = retain(test, target, false);
        var result = compare(test, baseline.scope().runId(), target.scope().runId());
        assertEquals(Conclusion.REGRESSION_OBSERVED, result.conclusion());
        assertEquals("PASSED", result.baseline().checkedAssertion());
        assertEquals("FAILED", result.target().checkedAssertion());
        assertTrue(result.target().receiptSha256().matches("[0-9a-f]{64}"));

        var directory = test.runs().resolve(target.scope().runId());
        receipt.addProperty("assertionOutcome", "PASSED");
        write(directory, "result.json", receipt);
        var inconsistent = compare(test, baseline.scope().runId(), target.scope().runId());
        assertEquals(Conclusion.UNRESOLVED, inconsistent.conclusion());
        assertEquals(InvoiceRolloutComparison.ReceiptState.INVALID_RECORD, inconsistent.target().state());

        receipt.remove("protectedExecution");
        receipt.remove("containerRemoved");
        receipt.addProperty("status", "AUTHORIZATION_DENIED");
        receipt.addProperty("authorizationDecision", "REVOKED");
        receipt.addProperty("assertionOutcome", "NOT_EVALUATED");
        write(directory, "result.json", receipt);
        var denied = compare(test, baseline.scope().runId(), target.scope().runId());
        assertEquals(Conclusion.UNRESOLVED, denied.conclusion());
        assertEquals("AUTHORIZATION_DENIED", denied.target().executionStatus());
        assertEquals("REVOKED", denied.target().authorizationDecision());
    }

    @Test void passingReceiptsWithDifferentImagesCannotFormAComparison() throws Exception {
        var test = prepare(false);
        var baseline = test.runner().preview(test.input(), test.capture(), BASELINE, SAMPLE_IMAGE, attempt());
        var target = test.runner().preview(test.input(), test.capture(), TARGET, "sha256:" + "2".repeat(64), attempt());
        retain(test, baseline, true);
        retain(test, target, true);
        var result = compare(test, baseline.scope().runId(), target.scope().runId());
        assertEquals(Conclusion.UNRESOLVED, result.conclusion());
        assertTrue(result.blockers().contains(InvoiceRolloutComparison.Blocker.WORKER_OR_RUNTIME_DIFFERS));
        assertEquals("PASSED", result.target().checkedAssertion());
    }

    private static JsonObject retain(Setup test, InvoiceRolloutDelivery.Preview preview, boolean passes) throws Exception {
        var scope = preview.scope();
        var directory = Files.createDirectories(test.runs().resolve(scope.runId()));
        String worker = attempt();
        write(directory, "scope.json", preview);
        write(directory, "authorization.json", Map.of("formatVersion", 1, "runId", scope.runId(), "imageId", scope.imageId(),
                "mode", "invoice-rollout-delivery-v1:" + preview.scopeIdentity(), "permission", "execute-invoice-rollout-delivery",
                "issuedAt", "2026-01-01T00:00:00Z", "expiresAt", "2026-01-01T00:02:00Z"));
        write(directory, "started.json", Map.of("startedAt", "2026-01-01T00:00:01Z", "containerName", worker));
        write(directory, "admission.json", Map.of("admitted", true, "issues", java.util.List.of()));
        var expected = GitHubJson.parse(Files.readAllBytes(CASE_ROOT.resolve("fixtures/rollout-v1/expectations.json")), 65536)
                .getAsJsonArray("scenarios").asList().stream().map(element -> element.getAsJsonObject())
                .filter(row -> row.get("id").getAsString().equals(scope.scenarioId())).findFirst().orElseThrow()
                .getAsJsonObject("facts").getAsJsonObject("reconciliation").deepCopy();
        expected.addProperty("inputIdentity", scope.delivery().inputIdentity());
        expected.addProperty("evidenceIdentity", scope.delivery().evidenceIdentity());
        var actual = expected.deepCopy();
        if (!passes) actual.addProperty("subscriptionId", "sub_secondary");
        String assertion = passes ? "PASSED" : "FAILED";
        var observation = Map.of("consumerId", "invoice-reconciliation", "executionStatus", "COMPLETED",
                "protocolInvalid", false, "identitiesMatch", true, "assertionOutcome", assertion, "expected", expected, "actual", actual);
        var execution = GitHubJson.JSON.toJsonTree(Map.ofEntries(
                Map.entry("formatVersion", 1), Map.entry("imageId", scope.imageId()), Map.entry("containerName", worker),
                Map.entry("buildId", scope.delivery().buildId()), Map.entry("sourceIdentity", scope.delivery().sourceIdentity()),
                Map.entry("configurationIdentity", scope.delivery().configurationIdentity()),
                Map.entry("dependenciesIdentity", scope.hostCase().dependencyIdentity()), Map.entry("harnessIdentity", "3".repeat(64)),
                Map.entry("environmentIdentity", "4".repeat(64)), Map.entry("transportStatus", "COMPLETED"),
                Map.entry("executionStatus", "COMPLETED"), Map.entry("transportInvalid", false), Map.entry("workerExitCode", 0),
                Map.entry("containerRemoved", true), Map.entry("compilation", Map.of("disposition", "SUCCEEDED")),
                Map.entry("observation", observation)));
        var receipt = GitHubJson.JSON.toJsonTree(new InvoiceRolloutDelivery.Result(preview.scopeIdentity(), scope.runId(),
                "COMPLETED", assertion, "ALLOWED", true)).getAsJsonObject();
        receipt.add("protectedExecution", execution);
        write(directory, "result.json", receipt);
        return receipt;
    }

    private static void write(Path directory, String name, Object value) throws Exception {
        Files.writeString(directory.resolve(name), GitHubJson.JSON.toJson(value));
    }

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

        String baselineId = attempt();
        var baseline = test.runner().preview(test.input(), test.capture(), BASELINE, image, baselineId);
        assertEquals("PASSED", test.runner().run(test.input(), test.capture(), BASELINE, image, baselineId, baseline.scopeIdentity()).assertionOutcome());
        var pair = compare(test, baselineId, id);
        assertEquals(Conclusion.NO_REGRESSION_OBSERVED, pair.conclusion(), pair.toString());
        assertEquals("PASSED", pair.baseline().checkedAssertion());
        assertEquals("PASSED", pair.target().checkedAssertion());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("result.json")));
    }
}
