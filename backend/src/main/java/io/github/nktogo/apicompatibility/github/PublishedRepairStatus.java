package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.github.DraftRepairPublication.Credentials;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.ObservedPull;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.PullState;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import static io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Mismatch.BASE_SHA;
import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Stage.PULL_REQUEST_MERGE;

/** Observes a confirmed repair PR without changing its publication record. */
public final class PublishedRepairStatus {
    private final Clock clock;
    private final Credentials credentials;
    private final DraftRepairPublication publication;
    private final GitHubRepositoryConnection connection;

    public enum State { OBSERVED, NOT_CONFIRMED, INCOMPLETE }
    public enum Reason { ACCESS_INCOMPLETE, IDENTITY_MISMATCH, INVALID_METADATA }
    public record Current(ObservedPull pull, String baseSha, String headSha, Instant mergedAt,
                          String mergeCommitSha, Instant observedAt) {}
    public record Report(int formatVersion, State state, Reason reason, DraftRepairPublication.Report publication,
                         Current current, GitHubRepositoryConnection.Failure failure) {}

    PublishedRepairStatus(Path state, Clock clock, Credentials credentials) {
        this.clock = clock;
        this.credentials = credentials;
        publication = new DraftRepairPublication(state, clock, credentials);
        connection = new GitHubRepositoryConnection(clock);
    }

    Report observe(Intent intent, GitHubTransport transport) throws IOException, InterruptedException {
        return observeRecorded(intent, publication.recorded(intent), transport);
    }

    Report observeRecorded(Intent intent, DraftRepairPublication.Report retained, GitHubTransport transport)
            throws IOException, InterruptedException {
        GitHubJson.require(intent.operationId().equals(retained.operationId())
                && (retained.attempt() == null || intent.equals(retained.attempt().intent())));
        var receipt = switch (retained.state()) {
            case CREATED -> retained.response();
            case RECONCILED -> retained.recovered();
            default -> null;
        };
        if (receipt == null) return new Report(1, State.NOT_CONFIRMED, null, retained, null, null);
        long number = receipt.pull().number();
        try {
            var reader = connection.openPull(intent, number, credentials.load(), transport);
            var report = project(intent, retained, number, reader.detail());
            var current = report.current();
            if (current == null || current.mergeCommitSha() != null || current.pull().state() != PullState.MERGED
                    || Boolean.TRUE.equals(current.pull().draft())
                    || current.pull().mismatches().stream().anyMatch(mismatch -> mismatch != BASE_SHA)) return report;
            return observeMergeCommit(intent, report, reader);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            return new Report(1, State.INCOMPLETE, Reason.ACCESS_INCOMPLETE, retained, null, e.failure());
        }
    }

    private Report observeMergeCommit(Intent intent, Report report, GitHubRepositoryConnection.PullReader reader)
            throws InterruptedException {
        var current = report.current();
        try {
            var response = reader.mergeMetadata();
            GitHubJson.require(!response.has("errors"));
            var data = GitHubJson.object(response.get("data"));
            var repository = GitHubJson.object(data.get("repository"));
            var pull = GitHubJson.object(repository.get("pullRequest"));
            boolean matches = GitHubJson.number(repository, "databaseId") == intent.selection().repositoryId()
                    && GitHubJson.number(pull, "number") == current.pull().number()
                    && booleanValue(pull, "merged") && !booleanValue(pull, "isDraft")
                    && Instant.parse(GitHubJson.text(pull, "mergedAt")).equals(current.mergedAt())
                    && GitHubJson.text(pull, "baseRefName").equals(intent.baseRef())
                    && GitHubJson.text(pull, "headRefName").equals(intent.headRef())
                    && GitHubJson.text(pull, "baseRefOid").equals(current.baseSha())
                    && GitHubJson.text(pull, "headRefOid").equals(current.headSha());
            if (!matches) return withMergeFailure(report, mergeFailure(GitHubRepositoryConnection.Code.IDENTITY_OR_SCOPE_MISMATCH));
            var merge = pull.get("mergeCommit");
            if (merge != null && merge.isJsonNull()) return report;
            String sha = GitHubJson.text(GitHubJson.object(merge), "oid");
            GitHubJson.require(sha.matches("[0-9a-f]{40}"));
            var completed = new Current(current.pull(), current.baseSha(), current.headSha(), current.mergedAt(), sha, clock.instant());
            return new Report(1, State.OBSERVED, null, report.publication(), completed, null);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            return withMergeFailure(report, e.failure());
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
            return withMergeFailure(report, mergeFailure(GitHubRepositoryConnection.Code.INVALID_METADATA));
        }
    }

    private static boolean booleanValue(JsonObject object, String field) {
        var value = object.get(field);
        GitHubJson.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean());
        return value.getAsBoolean();
    }

    private static GitHubRepositoryConnection.Failure mergeFailure(GitHubRepositoryConnection.Code code) {
        return new GitHubRepositoryConnection.Failure(code, PULL_REQUEST_MERGE, 200, null, false);
    }

    private static Report withMergeFailure(Report report, GitHubRepositoryConnection.Failure failure) {
        return new Report(1, State.OBSERVED, null, report.publication(), report.current(), failure);
    }

    private Report project(Intent intent, DraftRepairPublication.Report retained, long number, JsonObject detail) {
        try {
            var base = GitHubJson.object(detail.get("base"));
            if (GitHubJson.number(detail, "number") != number || !GitHubPullRequestLookup.matchesRepository(base, intent.selection()))
                return new Report(1, State.INCOMPLETE, Reason.IDENTITY_MISMATCH, retained, null, null);
            var current = GitHubPullRequestLookup.inspect(GitHubPullRequestLookup.describe(intent), detail);
            boolean isMerged = current.state() == PullState.MERGED;
            GitHubJson.require(booleanValue(detail, "merged") == isMerged);
            Instant mergedAt = isMerged ? Instant.parse(GitHubJson.text(detail, "merged_at")) : null;
            String mergeSha = isMerged && detail.has("merge_commit_sha") && !detail.get("merge_commit_sha").isJsonNull()
                    ? GitHubJson.text(detail, "merge_commit_sha") : null;
            GitHubJson.require(mergeSha == null || mergeSha.matches("[0-9a-f]{40}"));
            var observed = new Current(current, GitHubJson.text(base, "sha"),
                    GitHubJson.text(GitHubJson.object(detail.get("head")), "sha"), mergedAt, mergeSha, clock.instant());
            return new Report(1, State.OBSERVED, null, retained, observed, null);
        } catch (RuntimeException e) {
            return new Report(1, State.INCOMPLETE, Reason.INVALID_METADATA, retained, null, null);
        }
    }

    public static void main(String[] args) {
        try {
            GitHubJson.require(args.length == 2);
            var intent = GitHubPullRequestLookup.loadIntent(Path.of(args[1]));
            var service = new PublishedRepairStatus(Path.of(args[0]), Clock.systemUTC(), GitHubRepairRefs::configuredApp);
            Report report;
            try (var transport = new GitHubTransport()) {
                report = service.observe(intent, transport);
            }
            System.out.println(GitHubJson.JSON.toJson(report));
            if (report.state() != State.OBSERVED) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Published repair status could not be checked. Review the intent, journal and App configuration.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Published repair status check interrupted.");
            System.exit(3);
        }
    }
}
