package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import io.github.nktogo.apicompatibility.runner.InvoiceCapturePreflight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.nktogo.apicompatibility.github.ConsumerSourceCaptureTest.*;
import static io.github.nktogo.apicompatibility.github.MigrationConfiguration.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceConsumerSourceCatalog.FILES;
import static io.github.nktogo.apicompatibility.runner.InvoiceCapturePreflight.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceCapturePreflightTest {
    private static final Path CASE = Path.of("..", "cases", CASE_ID);
    @TempDir Path temporary;

    @BeforeAll static void appKey() throws Exception { GitHubSourceInventoryTest.key(); }

    private record Captured(EvidenceStore store, String inputId, String captureId) {
        InvoiceCapturePreflight.Report inspect(Path caseRoot) throws Exception {
            return InvoiceCapturePreflight.inspect(store, caseRoot, inputId, captureId);
        }
    }

    private Captured prepare(UnaryOperator<MigrationConfiguration.Plan> change, boolean incompleteSource) throws Exception {
        var store = new EvidenceStore(temporary);
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            var sources = sourceFiles();
            if (incompleteSource) sources.remove(FILES.getLast());
            installSources(fixture, sources);
            String input = planningInput(fixture, store, change);
            return new Captured(store, input, capture(fixture, store, input).captureId());
        }
    }

    private static MigrationConfiguration.Plan webhookTarget(MigrationConfiguration.Plan plan, Versions target) {
        return MigrationConfigurationTest.consumers(plan, plan.consumers().stream()
                .map(consumer -> consumer.id().equals("invoice-webhook")
                        ? new Consumer(consumer.id(), consumer.pomPath(), consumer.baseline(), target) : consumer)
                .toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void matchesTheExactPairedRolloutAndBindsTheRegisteredBuildsWithoutExecution(boolean requestFirst) throws Exception {
        var captured = prepare(plan -> {
            if (!requestFirst) return plan;
            var webhook = plan.consumers().stream().filter(c -> c.id().equals("invoice-webhook")).findFirst().orElseThrow();
            return webhookTarget(plan, webhook.baseline());
        }, false);
        var result = captured.inspect(CASE);
        var assessment = result.assessment();
        assertEquals(InvoiceCapturePreflight.State.MATCHES_REGISTERED_ROLLOUT, assessment.state());
        assertEquals(List.of("legacy-agreement", requestFirst ? "request-first" : "target-agreement"),
                assessment.matches().stream().map(PhaseMatch::scenarioId).toList());
        assertEquals(List.of("reconciliation-basil-source", requestFirst ? "webhook-baseline" : "webhook-basil-source"),
                assessment.matches().getLast().deliveries().stream().map(InvoiceCapturePreflight.Delivery::buildId).toList());
        assertEquals(captured.inputId(), assessment.inputs().captureParentId());
        assertEquals(captured.captureId(), assessment.inputs().captureId());
        assertEquals(2, assessment.matches().getFirst().deliveries().size());
        assertFalse(assessment.executionAuthorized());
        assertTrue(captured.store().list().isEmpty());
        assertEquals(result, captured.inspect(CASE));
    }

    @Test void selectedInputMustStillBeTheExactParentEvenAtTheSameRevision() throws Exception {
        var store = new EvidenceStore(temporary);
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            installSources(fixture, sourceFiles());
            String original = planningInput(fixture, store);
            String captureId = capture(fixture, store, original).captureId();
            String current = planningInput(fixture, store, plan -> webhookTarget(plan,
                    new Versions("29.0.0", BASIL, BASIL, BASIL, ACACIA, BASIL)));
            var report = InvoiceCapturePreflight.inspect(store, CASE, current, captureId);
            assertEquals(List.of(new Finding(InvoiceCapturePreflight.Issue.CAPTURE_PARENT_DIFFERS, null, null)),
                    report.assessment().findings());
            assertEquals(original, report.assessment().inputs().captureParentId());
            assertTrue(report.assessment().matches().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"companion", "account"})
    void targetOutsideTheCapturedRolloutLeavesTheBaselineAndNamesTheBlocker(String variant) throws Exception {
        boolean companion = variant.equals("companion");
        var target = companion ? new Versions("29.0.0", BASIL, BASIL, ACACIA, ACACIA, ACACIA)
                : new Versions("29.0.0", BASIL, BASIL, BASIL, BASIL, null);
        var result = prepare(plan -> webhookTarget(plan, target), false).inspect(CASE).assessment();
        assertEquals(InvoiceCapturePreflight.State.BLOCKED, result.state());
        assertEquals(MigrationConfiguration.State.DECLARATIONS_COMPLETE_NOT_VERIFIED, result.inputs().declarationState());
        assertEquals(List.of("legacy-agreement"), result.matches().stream().map(PhaseMatch::scenarioId).toList());
        assertEquals(List.of(companion
                ? new Finding(InvoiceCapturePreflight.Issue.COMPANION_SOURCE_NOT_CAPTURED, Phase.TARGET, "invoice-webhook")
                : new Finding(InvoiceCapturePreflight.Issue.NO_MATCHING_ROLLOUT, Phase.TARGET, null)), result.findings());
    }

    @Test void sourceAndDeclarationGapsRemainVisibleTogether() throws Exception {
        var target = new Versions("29.0.0", null, BASIL, BASIL, ACACIA, null);
        var result = prepare(plan -> webhookTarget(plan, target), true).inspect(CASE).assessment();
        assertEquals(InvoiceCapturePreflight.State.BLOCKED, result.state());
        assertEquals(List.of(InvoiceCapturePreflight.Issue.CAPTURE_HAS_GAPS, InvoiceCapturePreflight.Issue.DECLARATIONS_HAVE_GAPS),
                result.findings().stream().map(Finding::code).toList());
        assertTrue(result.declarationIssues().stream().anyMatch(issue -> issue.code() == Code.UNKNOWN_VERSION && "requestApiVersion".equals(issue.field())));
        assertTrue(result.matches().isEmpty());
    }

    @Test void refusesARoleBoundToTheWrongCapturedPom() throws Exception {
        var result = prepare(plan -> MigrationConfigurationTest.consumers(plan, plan.consumers().stream()
                .map(consumer -> consumer.id().equals("invoice-webhook")
                        ? new Consumer(consumer.id(), ConsumerSourceCapture.ROOT + "/reconciliation/pom.xml", consumer.baseline(), consumer.target()) : consumer)
                .toList()), false).inspect(CASE).assessment();
        assertTrue(result.findings().contains(new Finding(InvoiceCapturePreflight.Issue.ROLE_POM_MISMATCH, null, "invoice-webhook")));
        assertEquals(InvoiceCapturePreflight.State.BLOCKED, result.state());
        assertTrue(result.matches().isEmpty());
    }

    @Test void missingHostCaseProducesABlockerInsteadOfTrustingTheCapture() throws Exception {
        var result = prepare(UnaryOperator.identity(), false).inspect(temporary.resolve("unavailable-case")).assessment();
        assertEquals(InvoiceCapturePreflight.State.BLOCKED, result.state());
        assertEquals(List.of(new Finding(InvoiceCapturePreflight.Issue.HOST_CASE_UNAVAILABLE_OR_INVALID, null, null)), result.findings());
        assertEquals(ConsumerSourceCapture.State.CAPTURED_REGISTERED_SOURCE, result.inputs().captureState());
        assertNull(result.hostCase());
        assertTrue(result.matches().isEmpty());
    }
}
