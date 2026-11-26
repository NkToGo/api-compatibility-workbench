package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonArray;
import io.github.nktogo.apicompatibility.github.DraftRepairPublication.Credentials;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.ConnectionFailure;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Failure;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Selection;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Mismatch.BASE_SHA;

/** Reads deployment metadata for the exact revision reported by a confirmed repair PR. */
public final class RepairDeploymentStatus {
    private final Credentials credentials;
    private final PublishedRepairStatus published;
    private final GitHubRepositoryConnection connection;

    public enum State { OBSERVED, NONE_OBSERVED, NOT_READY, INCOMPLETE }
    public enum Reason {
        PUBLICATION_UNCONFIRMED, PR_STATUS_INCOMPLETE, PR_NOT_MERGED, PR_DRIFT, MERGE_SHA_MISSING,
        ACCESS_INCOMPLETE, INVALID_DEPLOYMENT_METADATA, DEPLOYMENT_LIMIT, PARTIAL_STATUSES
    }
    public enum StatusIssue {
        ACCESS_INCOMPLETE, NOT_READ, INVALID_METADATA, STATUS_LIMIT, AMBIGUOUS_LATEST, ENVIRONMENT_CHANGED, ENVIRONMENT_UNREPORTED
    }
    public enum DeploymentState { ERROR, FAILURE, INACTIVE, IN_PROGRESS, QUEUED, PENDING, SUCCESS }
    public record Status(long id, DeploymentState state, Instant createdAt, Boolean selectedEnvironment) {}
    public record Deployment(long id, Instant createdAt, Instant statusesObservedAt, List<Status> statuses,
                             DeploymentState latestState, StatusIssue issue, Failure failure) {
        public Deployment { statuses = List.copyOf(statuses); }
    }
    public record Report(int formatVersion, State state, Reason reason, String environment,
                         PublishedRepairStatus.Report pullRequest, Instant deploymentsObservedAt,
                         List<Deployment> deployments, Failure failure) {
        public Report { deployments = List.copyOf(deployments); }
    }
    private record DeploymentIdentity(long id, Instant createdAt) {
        Deployment withoutStatuses(StatusIssue issue, Failure failure) {
            return new Deployment(id, createdAt, null, List.of(), null, issue, failure);
        }
    }

    RepairDeploymentStatus(Path state, Clock clock, Credentials credentials) {
        this.credentials = credentials;
        published = new PublishedRepairStatus(state, clock, credentials);
        connection = new GitHubRepositoryConnection(clock);
    }

    Report observe(Intent intent, String environment, GitHubTransport transport) throws IOException, InterruptedException {
        GitHubJson.require(validEnvironment(environment));
        return observeForPull(intent, environment, published.observe(intent, transport), transport);
    }

    Report observeForPull(Intent intent, String environment, PublishedRepairStatus.Report pull, GitHubTransport transport)
            throws IOException, InterruptedException {
        if (pull.state() == PublishedRepairStatus.State.NOT_CONFIRMED)
            return unavailable(State.NOT_READY, Reason.PUBLICATION_UNCONFIRMED, environment, pull, null);
        if (pull.state() == PublishedRepairStatus.State.INCOMPLETE)
            return unavailable(State.INCOMPLETE, Reason.PR_STATUS_INCOMPLETE, environment, pull, null);
        var current = pull.current();
        if (current.pull().state() != GitHubPullRequestLookup.PullState.MERGED)
            return unavailable(State.NOT_READY, Reason.PR_NOT_MERGED, environment, pull, null);
        if (Boolean.TRUE.equals(current.pull().draft()))
            return unavailable(State.INCOMPLETE, Reason.PR_STATUS_INCOMPLETE, environment, pull, null);
        // The target branch can move independently. Its drift remains in the PR observation.
        if (current.pull().mismatches().stream().anyMatch(mismatch -> mismatch != BASE_SHA))
            return unavailable(State.INCOMPLETE, Reason.PR_DRIFT, environment, pull, null);
        if (current.mergeCommitSha() == null)
            return unavailable(State.INCOMPLETE, Reason.MERGE_SHA_MISSING, environment, pull, null);

        GitHubRepositoryConnection.DeploymentReader reader;
        GitHubRepositoryConnection.DeploymentPage page;
        try {
            reader = connection.openDeployments(intent, credentials.load(), transport);
            page = reader.deployments(current.mergeCommitSha(), environment);
        } catch (ConnectionFailure e) {
            return unavailable(State.INCOMPLETE, Reason.ACCESS_INCOMPLETE, environment, pull, e.failure());
        }
        List<DeploymentIdentity> identities;
        try {
            identities = select(page.items(), intent.selection(), current.mergeCommitSha(), environment);
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
            return new Report(1, State.INCOMPLETE, Reason.INVALID_DEPLOYMENT_METADATA, environment, pull,
                    page.observedAt(), List.of(), null);
        }
        var deployments = observeStatuses(reader, identities, intent.selection(), environment);
        var reason = page.possiblyTruncated() ? Reason.DEPLOYMENT_LIMIT
                : deployments.stream().anyMatch(value -> value.issue() != null) ? Reason.PARTIAL_STATUSES : null;
        var state = reason != null ? State.INCOMPLETE : deployments.isEmpty() ? State.NONE_OBSERVED : State.OBSERVED;
        return new Report(1, state, reason, environment, pull, page.observedAt(), deployments, null);
    }

    private static Report unavailable(State state, Reason reason, String environment, PublishedRepairStatus.Report pull,
                                      Failure failure) {
        return new Report(1, state, reason, environment, pull, null, List.of(), failure);
    }

    static boolean validEnvironment(String name) {
        return name != null && !name.isBlank() && name.length() <= 255 && name.equals(name.strip())
                && name.codePoints().noneMatch(character -> Character.isISOControl(character)
                        || Character.getType(character) == Character.SURROGATE
                        || Character.getType(character) == Character.FORMAT
                        || Character.getType(character) == Character.LINE_SEPARATOR
                        || Character.getType(character) == Character.PARAGRAPH_SEPARATOR);
    }

    private static List<DeploymentIdentity> select(JsonArray items, Selection selection, String sha, String environment) {
        var identities = new ArrayList<DeploymentIdentity>();
        var ids = new HashSet<Long>();
        for (var item : items) {
            var value = GitHubJson.object(item);
            long id = GitHubJson.number(value, "id");
            GitHubJson.require(id > 0 && ids.add(id)
                    && GitHubJson.text(value, "sha").equals(sha)
                    && GitHubJson.text(value, "environment", 255).equalsIgnoreCase(environment)
                    && matchesApiIdentity(selection, GitHubJson.text(value, "repository_url"), ""));
            identities.add(new DeploymentIdentity(id, Instant.parse(GitHubJson.text(value, "created_at"))));
        }
        return identities;
    }

    private static List<Deployment> observeStatuses(GitHubRepositoryConnection.DeploymentReader reader,
                                                    List<DeploymentIdentity> identities, Selection selection,
                                                    String environment) throws InterruptedException {
        var observed = new ArrayList<Deployment>();
        boolean accessStopped = false;
        for (var identity : identities) {
            if (accessStopped) {
                observed.add(identity.withoutStatuses(StatusIssue.NOT_READ, null));
                continue;
            }
            try {
                var page = reader.statuses(identity.id());
                observed.add(projectStatuses(identity, page, selection, environment));
            } catch (ConnectionFailure e) {
                observed.add(identity.withoutStatuses(StatusIssue.ACCESS_INCOMPLETE, e.failure()));
                accessStopped = true;
            }
        }
        return observed;
    }

    private static Deployment projectStatuses(DeploymentIdentity deployment, GitHubRepositoryConnection.DeploymentPage page,
                                              Selection selection, String environment) {
        var statuses = new ArrayList<Status>();
        var ids = new HashSet<Long>();
        String path = "/deployments/" + deployment.id();
        try {
            for (var item : page.items()) {
                var value = GitHubJson.object(item);
                long id = GitHubJson.number(value, "id");
                Instant createdAt = Instant.parse(GitHubJson.text(value, "created_at"));
                GitHubJson.require(id > 0 && ids.add(id) && !createdAt.isBefore(deployment.createdAt())
                        && matchesApiIdentity(selection, GitHubJson.text(value, "repository_url"), "")
                        && matchesApiIdentity(selection, GitHubJson.text(value, "deployment_url"), path));
                String reported = GitHubJson.text(value, "state", 16);
                var state = DeploymentState.valueOf(reported.toUpperCase(Locale.ROOT));
                GitHubJson.require(state.name().toLowerCase(Locale.ROOT).equals(reported));
                String statusEnvironment = value.has("environment") ? GitHubJson.text(value, "environment", 255) : "";
                GitHubJson.require(statusEnvironment.isEmpty() || validEnvironment(statusEnvironment));
                Boolean selected = statusEnvironment.isEmpty() ? null : statusEnvironment.equalsIgnoreCase(environment);
                statuses.add(new Status(id, state, createdAt, selected));
            }
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
            return new Deployment(deployment.id(), deployment.createdAt(), page.observedAt(), List.of(), null,
                    StatusIssue.INVALID_METADATA, null);
        }
        statuses.sort(Comparator.comparing(Status::createdAt).thenComparingLong(Status::id).reversed());
        var issue = statusIssue(page, statuses);
        var latestState = issue == null && !statuses.isEmpty() ? statuses.getFirst().state() : null;
        return new Deployment(deployment.id(), deployment.createdAt(), page.observedAt(), statuses, latestState, issue, null);
    }

    private static StatusIssue statusIssue(GitHubRepositoryConnection.DeploymentPage page, List<Status> statuses) {
        if (page.possiblyTruncated()) return StatusIssue.STATUS_LIMIT;
        if (statuses.isEmpty()) return null;
        var latest = statuses.getFirst();
        for (var status : statuses) {
            if (!status.createdAt().equals(latest.createdAt())) break;
            if (status.state() != latest.state() || !Objects.equals(status.selectedEnvironment(), latest.selectedEnvironment()))
                return StatusIssue.AMBIGUOUS_LATEST;
        }
        if (latest.selectedEnvironment() == null) return StatusIssue.ENVIRONMENT_UNREPORTED;
        return latest.selectedEnvironment() ? null : StatusIssue.ENVIRONMENT_CHANGED;
    }

    private static boolean matchesApiIdentity(Selection selection, String url, String suffix) {
        String origin = GitHubTransport.ORIGIN.toString();
        return url.equalsIgnoreCase(origin + selection.path() + suffix)
                || url.equals(origin + "/repositories/" + selection.repositoryId() + suffix);
    }

    public static void main(String[] args) {
        try {
            GitHubJson.require(args.length == 3);
            var intent = GitHubPullRequestLookup.loadIntent(Path.of(args[1]));
            var service = new RepairDeploymentStatus(Path.of(args[0]), Clock.systemUTC(), GitHubRepairRefs::configuredApp);
            Report report;
            try (var transport = new GitHubTransport()) {
                report = service.observe(intent, args[2], transport);
            }
            System.out.println(GitHubJson.JSON.toJson(report));
            if (report.state() == State.NOT_READY || report.state() == State.INCOMPLETE) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Repair deployment status could not be checked. Review the intent, journal, environment and App configuration.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Repair deployment status check interrupted.");
            System.exit(3);
        }
    }
}
