package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment;
import io.github.nktogo.apicompatibility.compatibility.RepairEvaluation;
import io.github.nktogo.apicompatibility.github.DraftRepairPublication.Credentials;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Failure;
import io.github.nktogo.apicompatibility.runner.DurableExecution;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;
import io.github.nktogo.apicompatibility.runner.RepairVerification.RecordedOutcome;
import io.github.nktogo.apicompatibility.runner.RepairVerification.SubscriptionRepairEvidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Publishes a bounded summary of one locally selected, verified subscription repair. */
public final class RepairEvidenceExport {
    private static final String FILE = "evidence.json";
    private final Path state;
    private final Clock clock;
    private final DraftRepairPublication publication;
    private final RepairPublicationApproval approvals;
    private final PublishedRepairStatus published;
    private final RepairDeploymentStatus deployments;

    public enum PublicationLink { BINDING_REQUIRED, NOT_STARTED, MATCHED, DIFFERENT_BINDING, JOURNAL_UNAVAILABLE }
    public enum ObservationGap { BINDING_OR_JOURNAL_UNAVAILABLE, PR_READ_UNAVAILABLE, DEPLOYMENT_READ_UNAVAILABLE }
    public record Selection(String repository, long repositoryId, String operationId, String verificationId,
                            String sourcePath, String baseRef, String headRef, String baseSha, String headSha) {}
    public record Verification(SubscriptionRepairEvidence receipt, RecordedOutcome execution, RepairEvaluation.Definition inputs,
                               List<RepairEvaluation.Scenario> outcomes, ComparisonAssessment.Conclusion originalConclusion,
                               ComparisonAssessment.Conclusion repairedConclusion, List<ComparisonAssessment.Reason> issues,
                               Boolean targetConditionsDiffer) {}
    public record Binding(RepairCommitBinding.State state, RepairCommitBinding.Blocker blocker, String identity,
                          String baseTreeSha, String headTreeSha, String baseBlobSha, String headBlobSha) {}
    public record HistoricalApproval(RepairPublicationApproval.State state, String issuedAt, String expiresAt) {}
    public record Publication(PublicationLink link, DraftRepairPublication.State state, String startedAt, String requestSha256,
                              DraftRepairPublication.Receipt response, DraftRepairPublication.Receipt recovered) {}
    public record PullObservation(PublishedRepairStatus.State state, PublishedRepairStatus.Reason reason,
                                  PublishedRepairStatus.Current current, Failure failure) {}
    public record DeploymentObservation(RepairDeploymentStatus.State state, RepairDeploymentStatus.Reason reason,
                                        Instant observedAt, List<RepairDeploymentStatus.Deployment> records, Failure failure) {}
    public record GitHubObservation(boolean requested, String environment, ObservationGap gap, PullObservation pull,
                                    DeploymentObservation deployment) {}
    public record Report(int formatVersion, Instant exportedAt, Selection selection, Verification verification, Binding binding,
                         HistoricalApproval historicalApproval, Publication publication, GitHubObservation github) {}
    public record Exported(String file, String sha256, RepairCommitBinding.State bindingState) {}

    RepairEvidenceExport(Path state, Clock clock, Credentials credentials) {
        this.state = state;
        this.clock = clock;
        publication = new DraftRepairPublication(state, clock, credentials);
        approvals = new RepairPublicationApproval(state, clock, GitHubRepairRefs::readWithConfiguredApp);
        published = new PublishedRepairStatus(state, clock, credentials);
        deployments = new RepairDeploymentStatus(state, clock, credentials);
    }

    Exported export(String verificationId, Path repository, Intent intent, String sourcePath, Path destination,
                    boolean observeGitHub, String environment, GitHubTransport transport) throws IOException, InterruptedException {
        GitHubJson.require(verificationId.matches("verification-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                && (environment == null || observeGitHub && RepairDeploymentStatus.validEnvironment(environment)));
        Path directory = destination.toAbsolutePath().normalize();
        Path parent = directory.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().equals(parent.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Export requires an existing trusted parent directory");
        if (RepairPublicationApproval.exists(directory)) throw new FileAlreadyExistsException("Export destination");

        var review = RepairCommitBinding.review(state, verificationId, repository, intent, sourcePath);
        var local = review.binding();
        var selection = new Selection(intent.selection().fullName(), intent.selection().repositoryId(), intent.operationId(),
                verificationId, sourcePath, intent.baseRef(), intent.headRef(), intent.selection().commitSha(), intent.headSha());
        var historical = historical(intent, local);
        var approval = local.state() == RepairCommitBinding.State.MATCHED ? readApproval(intent, local.bindingIdentity()) : null;
        var github = observe(intent, historical, observeGitHub, environment, transport);
        var report = new Report(1, clock.instant(), selection, verification(review), binding(local), approval, historical.summary(), github);
        byte[] bytes = (GitHubJson.JSON.toJson(report) + "\n").getBytes(StandardCharsets.UTF_8);
        GitHubJson.require(bytes.length <= 262144);

        // Claim the directory exclusively. Publish the completion file only after all bytes have been written.
        Files.createDirectory(directory);
        DurableExecution.publishMetadata(directory.resolve(FILE), bytes);
        return new Exported(FILE, GitSourceObjects.sha256(bytes), local.state());
    }

    private static Verification verification(RepairCommitBinding.Review review) {
        var receipt = review.binding().verification();
        if (receipt == null) return null;
        var assessment = review.assessment();
        if (assessment == null) return new Verification(receipt, review.outcome(), null, List.of(), null, null, List.of(), null);
        var original = assessment.originalComparison();
        var repaired = assessment.repairedComparison();
        var issues = Stream.concat(original.issues().stream(), repaired.issues().stream())
                .map(ComparisonAssessment.Issue::reason).distinct().toList();
        return new Verification(receipt, review.outcome(), assessment.definition(), assessment.scenarios(), original.conclusion(),
                repaired.conclusion(), issues, !assessment.boundaryIssues().isEmpty());
    }

    private static Binding binding(RepairCommitBinding.Report report) {
        var proof = report.proof();
        return proof == null ? new Binding(report.state(), report.blocker(), null, null, null, null, null)
                : new Binding(report.state(), null, report.bindingIdentity(), proof.baseTreeSha(), proof.headTreeSha(),
                proof.baseBlobSha(), proof.headBlobSha());
    }

    private record Historical(PublicationLink link, DraftRepairPublication.Report report) {
        Publication summary() {
            if (report == null) return new Publication(link, null, null, null, null, null);
            var attempt = report.attempt();
            return new Publication(link, report.state(), attempt == null ? null : attempt.startedAt(),
                    attempt == null ? null : attempt.requestSha256(), report.response(), report.recovered());
        }
    }

    private Historical historical(Intent intent, RepairCommitBinding.Report local) {
        if (local.state() != RepairCommitBinding.State.MATCHED) return new Historical(PublicationLink.BINDING_REQUIRED, null);
        try {
            var report = publication.recorded(intent);
            if (report.attempt() == null) return new Historical(PublicationLink.NOT_STARTED, report);
            if (!local.bindingIdentity().equals(report.attempt().bindingIdentity()))
                return new Historical(PublicationLink.DIFFERENT_BINDING, null);
            return new Historical(PublicationLink.MATCHED, report);
        } catch (IOException | RuntimeException e) {
            return new Historical(PublicationLink.JOURNAL_UNAVAILABLE, null);
        }
    }

    private HistoricalApproval readApproval(Intent intent, String bindingIdentity) {
        try {
            var recorded = approvals.recorded(intent, bindingIdentity);
            var approval = recorded.approval();
            return new HistoricalApproval(recorded.state(), approval == null ? null : approval.issuedAt(),
                    approval == null ? null : approval.expiresAt());
        } catch (IOException | RuntimeException e) {
            return new HistoricalApproval(RepairPublicationApproval.State.INVALID, null, null);
        }
    }

    private GitHubObservation observe(Intent intent, Historical historical, boolean requested, String environment,
                                      GitHubTransport transport) throws InterruptedException {
        if (!requested) return new GitHubObservation(false, null, null, null, null);
        if (historical.report() == null)
            return new GitHubObservation(true, environment, ObservationGap.BINDING_OR_JOURNAL_UNAVAILABLE, null, null);
        PublishedRepairStatus.Report observed;
        try {
            observed = published.observeRecorded(intent, historical.report(), transport);
        } catch (IOException | IllegalArgumentException e) {
            return new GitHubObservation(true, environment, ObservationGap.PR_READ_UNAVAILABLE, null, null);
        }
        var pull = new PullObservation(observed.state(), observed.reason(), observed.current(), observed.failure());
        if (environment == null) return new GitHubObservation(true, null, null, pull, null);
        try {
            var deployment = deployments.observeForPull(intent, environment, observed, transport);
            var current = new DeploymentObservation(deployment.state(), deployment.reason(), deployment.deploymentsObservedAt(),
                    deployment.deployments(), deployment.failure());
            return new GitHubObservation(true, environment, null, pull, current);
        } catch (IOException | IllegalArgumentException e) {
            return new GitHubObservation(true, environment, ObservationGap.DEPLOYMENT_READ_UNAVAILABLE, pull, null);
        }
    }

    public static void main(String[] args) {
        try {
            GitHubJson.require(args.length == 7 || args.length == 8);
            var intent = GitHubPullRequestLookup.loadIntent(Path.of(args[3]));
            boolean observe = switch (args[6]) {
                case "offline" -> false;
                case "observe" -> true;
                default -> throw new IllegalArgumentException("Invalid export mode");
            };
            String environment = args.length == 8 ? args[7] : null;
            var exporter = new RepairEvidenceExport(Path.of(args[0]), Clock.systemUTC(), GitHubRepairRefs::configuredApp);
            try (var transport = observe ? new GitHubTransport() : null) {
                var result = exporter.export(args[1], Path.of(args[2]), intent, args[4], Path.of(args[5]), observe, environment, transport);
                System.out.println(GitHubJson.JSON.toJson(result));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Repair evidence export could not complete. Check the selection and use a new destination directory.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Repair evidence export interrupted.");
            System.exit(3);
        }
    }
}
