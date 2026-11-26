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
            var detail = connection.pullRequest(intent, number, credentials.load(), transport);
            return project(intent, retained, number, detail);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            return new Report(1, State.INCOMPLETE, Reason.ACCESS_INCOMPLETE, retained, null, e.failure());
        }
    }

    private Report project(Intent intent, DraftRepairPublication.Report retained, long number, JsonObject detail) {
        try {
            var base = GitHubJson.object(detail.get("base"));
            if (GitHubJson.number(detail, "number") != number || !GitHubPullRequestLookup.matchesRepository(base, intent.selection()))
                return new Report(1, State.INCOMPLETE, Reason.IDENTITY_MISMATCH, retained, null, null);
            var current = GitHubPullRequestLookup.inspect(GitHubPullRequestLookup.describe(intent), detail);
            var merged = detail.get("merged");
            boolean isMerged = current.state() == PullState.MERGED;
            GitHubJson.require(merged != null && merged.isJsonPrimitive() && merged.getAsJsonPrimitive().isBoolean()
                    && merged.getAsBoolean() == isMerged);
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
