package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.github.ConsumerSourceCapture;
import io.github.nktogo.apicompatibility.github.MigrationConfiguration;
import io.github.nktogo.apicompatibility.github.MigrationInputCapture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Compares retained source and declarations with the host's registered synthetic rollout. */
public final class InvoiceCapturePreflight {
    private static final String POLICY = "invoice-base-rollout-preflight-v1";
    private static final String CORPUS = "rollout-v1";
    private static final String WEBHOOK = "invoice-webhook";
    private static final String RECONCILIATION = "invoice-reconciliation";
    private static final Map<String, String> MODULES = Map.of(WEBHOOK, "webhook", RECONCILIATION, "reconciliation");
    private static final String SOURCE_ROOT = "cases/" + InvoiceRenewalFixtures.CASE_ID + "/" + InvoiceConsumerSourceCatalog.DIRECTORY;

    public enum State { MATCHES_REGISTERED_ROLLOUT, BLOCKED }
    public enum Phase { BASELINE, TARGET }
    public enum Issue {
        CAPTURE_PARENT_DIFFERS, CAPTURE_HAS_GAPS, DECLARATIONS_HAVE_GAPS, ROLE_POM_MISMATCH,
        HOST_CASE_UNAVAILABLE_OR_INVALID, SOURCE_DIFFERS_FROM_HOST_CASE,
        COMPANION_SOURCE_NOT_CAPTURED, NO_MATCHING_ROLLOUT
    }
    public record Finding(Issue code, Phase phase, String consumerId) {}
    public record Inputs(String inputId, String bindingIdentity, String captureId, String captureParentId,
                         String captureBindingIdentity, long repositoryId, String commitSha, String treeSha,
                         ConsumerSourceCapture.State captureState, ConsumerSourceCapture.CatalogState catalogState,
                         MigrationConfiguration.State declarationState) {}
    public record HostCase(String caseId, String registryIdentity, String buildRegistrationIdentity,
                           String dependencyIdentity, String rolloutCorpusIdentity) {}
    public record Delivery(String consumerId, String deliveryId, String inputIdentity, String evidenceIdentity,
                           String buildId, String sourceIdentity, String configurationIdentity) {}
    public record PhaseMatch(Phase phase, String scenarioId, List<Delivery> deliveries) {
        public PhaseMatch { deliveries = List.copyOf(deliveries); }
    }
    public record Assessment(int formatVersion, String policy, State state, Inputs inputs, HostCase hostCase,
                             List<Finding> findings, List<MigrationConfiguration.Issue> declarationIssues,
                             List<PhaseMatch> matches, List<String> unchecked, boolean executionAuthorized) {
        public Assessment {
            findings = List.copyOf(findings);
            declarationIssues = List.copyOf(declarationIssues);
            matches = List.copyOf(matches);
            unchecked = List.copyOf(unchecked);
        }
    }
    public record Report(String preflightIdentity, Assessment assessment) {}
    record Checked(Report report, InvoiceCaseCoverage.Plan plan) {}

    private InvoiceCapturePreflight() {}

    public static Report inspect(EvidenceStore store, Path hostCaseRoot, String inputId, String captureId) throws IOException {
        return check(store, hostCaseRoot, inputId, captureId).report();
    }

    static Checked check(EvidenceStore store, Path hostCaseRoot, String inputId, String captureId) throws IOException {
        var capture = new ConsumerSourceCapture(store).inspect(captureId);
        var input = MigrationInputCapture.reopen(store, inputId);
        var binding = input.binding();
        var observed = capture.observation();
        var inputs = new Inputs(inputId, input.summary().bindingIdentity(), captureId, observed.inputId(),
                observed.bindingIdentity(), observed.selection().repositoryId(), observed.selection().commitSha(),
                observed.treeSha(), capture.state(), capture.catalogState(), binding.state());
        var findings = new ArrayList<Finding>();
        if (!inputId.equals(observed.inputId())) add(findings, Issue.CAPTURE_PARENT_DIFFERS);
        if (capture.state() != ConsumerSourceCapture.State.CAPTURED_REGISTERED_SOURCE) add(findings, Issue.CAPTURE_HAS_GAPS);
        if (binding.state() != MigrationConfiguration.State.DECLARATIONS_COMPLETE_NOT_VERIFIED) add(findings, Issue.DECLARATIONS_HAVE_GAPS);
        var consumers = binding.declarations().consumers().stream().collect(Collectors.toMap(MigrationConfiguration.Consumer::id, Function.identity()));
        for (String role : List.of(WEBHOOK, RECONCILIATION)) {
            var consumer = consumers.get(role);
            if (consumer != null && !consumer.pomPath().equals(SOURCE_ROOT + "/" + MODULES.get(role) + "/pom.xml"))
                findings.add(new Finding(Issue.ROLE_POM_MISMATCH, null, role));
        }

        // The existing protected-case loader validates local source, fixtures, expectations and dependency bytes.
        // A repository capture cannot supply or redirect the host's case inputs.
        InvoiceCaseCoverage.Plan plan = null;
        try {
            plan = InvoiceCaseCoverage.load(hostCaseRoot);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            add(findings, Issue.HOST_CASE_UNAVAILABLE_OR_INVALID);
        }
        HostCase host = null;
        var matches = new ArrayList<PhaseMatch>();
        if (plan != null) {
            var builds = plan.builds();
            var fixtures = plan.business().fixtures();
            var rollout = fixtures.businessCorpora().get(CORPUS);
            host = new HostCase(InvoiceRenewalFixtures.CASE_ID, fixtures.identity(), builds.identity(),
                    builds.dependenciesIdentity(), rollout.identity());
            if (capture.state() == ConsumerSourceCapture.State.CAPTURED_REGISTERED_SOURCE
                    && (!builds.identity().equals(capture.expectedRegistrationSha256())
                    || observed.files().stream().anyMatch(file -> !CaseInputs.hash(builds.bytes(file.path())).equals(file.contentSha256())))) {
                add(findings, Issue.SOURCE_DIFFERS_FROM_HOST_CASE);
            }
            if (findings.isEmpty()) {
                for (Phase phase : Phase.values()) match(phase, consumers, plan, findings, matches);
            }
        }

        var state = findings.isEmpty() ? State.MATCHES_REGISTERED_ROLLOUT : State.BLOCKED;
        var assessment = new Assessment(1, POLICY, state, inputs, host, findings, binding.issues(), matches,
                List.of("EFFECTIVE_MAVEN_CONFIGURATION", "LIVE_STRIPE_SETTINGS_AND_EVENT_PROVENANCE",
                        "WORKER_IMAGE_AND_EXECUTION_PERMISSION", "CONSUMER_BEHAVIOR_AND_MIGRATION_APPROVAL"), false);
        return new Checked(new Report(CaseInputs.identity(assessment), assessment), plan);
    }

    private static void match(Phase phase, Map<String, MigrationConfiguration.Consumer> consumers,
                              InvoiceCaseCoverage.Plan plan, List<Finding> findings, List<PhaseMatch> matches) {
        var webhook = versions(consumers.get(WEBHOOK), phase);
        var reconciliation = versions(consumers.get(RECONCILIATION), phase);
        if (!Objects.equals(webhook.eventApiVersion(), webhook.requestApiVersion())) {
            findings.add(new Finding(Issue.COMPANION_SOURCE_NOT_CAPTURED, phase, WEBHOOK));
            return;
        }
        var eligible = plan.business().fixtures().businessCorpora().get(CORPUS).scenarios().stream()
                .filter(scenario -> matches(scenario.deliveries().get("webhook"), webhook, true)
                        && matches(scenario.deliveries().get("reconciliation"), reconciliation, false))
                .toList();
        if (eligible.size() != 1) {
            findings.add(new Finding(Issue.NO_MATCHING_ROLLOUT, phase, null));
            return;
        }
        var selected = eligible.getFirst();
        var deliveries = plan.business().entries().stream()
                .filter(entry -> entry.corpus().equals(CORPUS) && entry.scenario().equals(selected.id()))
                .map(entry -> delivery(entry, plan.builds()))
                .toList();
        CaseInputs.require(deliveries.size() == 2, "Incomplete registered rollout");
        matches.add(new PhaseMatch(phase, selected.id(), deliveries));
    }

    private static boolean matches(InvoiceRenewalFixtures.Delivery delivery, MigrationConfiguration.Versions versions, boolean webhook) {
        var input = delivery.input();
        var config = input.getAsJsonObject("configuration");
        if (!versions.sdkVersion().equals(config.get("sdkVersion").getAsString())
                || !versions.requestApiVersion().equals(config.get("requestApiVersion").getAsString())
                || !versions.accountApiVersion().equals(config.get("accountApiVersion").getAsString())) return false;
        if (!webhook) return true;  // Fixture endpoint metadata is shared across roles; the reconciliation role declares none.
        return versions.endpointApiVersion().equals(config.get("endpointApiVersion").getAsString())
                && versions.eventApiVersion().equals(input.get("effectiveApiVersion").getAsString())
                && (versions.adapterApiVersion() == null || versions.adapterApiVersion().equals(config.get("adapterApiVersion").getAsString()));
    }

    private static Delivery delivery(InvoiceBusinessCoverage.Entry entry, InvoiceConsumerBuilds.Registration builds) {
        String buildId = entry.buildId();
        var build = builds.build(buildId);
        CaseInputs.require(build.consumerId().equals(entry.consumer()) && build.expectedCompilation() == InvoiceConsumerBuilds.Disposition.SUCCEEDED,
                "Registered rollout has no matching base build");
        return new Delivery(entry.consumer(), entry.delivery(), InvoiceRenewalOracle.inputIdentity(entry.input()),
                entry.evidenceIdentity(), buildId, builds.sourceIdentity(buildId), builds.configurationIdentity(buildId));
    }

    private static MigrationConfiguration.Versions versions(MigrationConfiguration.Consumer consumer, Phase phase) {
        return phase == Phase.BASELINE ? consumer.baseline() : consumer.target();
    }

    private static void add(List<Finding> findings, Issue issue) {
        findings.add(new Finding(issue, null, null));
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: InvoiceCapturePreflight HOST_CASE EVIDENCE_DIRECTORY INPUT_ID CAPTURE_ID");
            System.exit(2);
        }
        try {
            var report = inspect(new EvidenceStore(Path.of(args[1])), Path.of(args[0]), args[2], args[3]);
            System.out.println(CaseInputs.JSON.toJson(report));
            if (report.assessment().state() == State.BLOCKED) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Invoice preflight failed. Check the retained input and capture IDs and evidence store integrity.");
            System.exit(2);
        }
    }
}
