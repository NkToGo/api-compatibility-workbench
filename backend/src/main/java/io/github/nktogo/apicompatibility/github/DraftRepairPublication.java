package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.ObservedPull;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Code;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.PullAccess;
import io.github.nktogo.apicompatibility.runner.DurableExecution;
import io.github.nktogo.apicompatibility.runner.LocalFileLease;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.nktogo.apicompatibility.github.RepairPublicationApproval.exists;

/** One durable create attempt; recovery may observe GitHub but never submits that attempt again. */
public final class DraftRepairPublication {
    private static final String STARTED = "publication-started.json";
    private static final String RESPONSE = "publication-response.json";
    private static final String RECOVERED = "publication-recovered.json";
    private static final int RECORD_LIMIT = 8192;
    private final Clock clock;
    private final Credentials credentials;
    private final RepairPublicationApproval approvals;
    private final GitHubRepositoryConnection connection;

    public enum State { BLOCKED, BUSY, NOT_STARTED, NOT_SENT, UNKNOWN, CREATED, RECONCILED }
    public record Attempt(int formatVersion, Intent intent, String bindingIdentity, String requestSha256, String startedAt) {}
    public record Receipt(int formatVersion, String attemptIdentity, State state, ObservedPull pull,
                          int httpStatus, Code failureCode, String retryNotBefore, String recordedAt,
                          RepairPublicationApproval.State dispatchBlocker) {}
    public record Report(State state, String operationId, Attempt attempt, Receipt response, Receipt recovered,
                         RepairPublicationApproval.State approvalState, GitHubRepairRefs.Report branchCheck,
                         GitHubPullRequestLookup.Result lookup) {}

    @FunctionalInterface interface Credentials { GitHubAppCredentials load() throws IOException; }

    DraftRepairPublication(Path state, Clock clock, Credentials credentials) {
        this.clock = clock;
        this.credentials = credentials;
        this.approvals = new RepairPublicationApproval(state, clock, GitHubRepairRefs::readWithConfiguredApp);
        this.connection = new GitHubRepositoryConnection(clock);
    }

    Report publish(Intent intent, RepairCommitBinding.Report local, GitHubTransport transport) throws IOException, InterruptedException {
        Path directory = approvals.operation(intent.operationId());
        if (!exists(directory)) return blocked(intent, RepairPublicationApproval.State.UNAVAILABLE, null);
        approvals.checkDirectories(directory);
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("publication.lock"))) {
            if (lease == null) return empty(State.BUSY, intent);
            var attempt = readAttempt(directory, intent);
            if (attempt != null) return retained(directory, attempt, null);
            var access = new AtomicReference<GitHubRepositoryConnection.VerifiedRepository>();
            var gate = approvals.check(intent, local, (proof, binding) -> {
                try {
                    var repository = connection.openVerified(intent.selection(), credentials.load(), transport, intent.appId(), PullAccess.WRITE);
                    access.set(repository);
                    return GitHubRepairRefs.inspect(proof, binding, repository);
                } catch (GitHubRepositoryConnection.ConnectionFailure failure) {
                    return GitHubRepairRefs.connectionFailed(binding, failure.failure());
                }
            });
            if (gate.state() != RepairPublicationApproval.State.APPLICABLE)
                return blocked(intent, gate.state(), gate.branchCheck());
            byte[] request = request(intent);
            attempt = new Attempt(1, intent, gate.approval().bindingIdentity(), GitSourceObjects.sha256(request), clock.instant().toString());
            write(directory.resolve(STARTED), attempt);
            var revoked = approvals.dispatchBlocker(gate.approval());
            if (revoked != null) {
                var response = new Receipt(1, attemptIdentity(attempt), State.NOT_SENT, null, 0, null, null,
                        clock.instant().toString(), revoked);
                write(directory.resolve(RESPONSE), response);
                return new Report(State.NOT_SENT, intent.operationId(), attempt, response, null, revoked, gate.branchCheck(), null);
            }
            var response = create(access.get(), attempt, request);
            write(directory.resolve(RESPONSE), response);
            return new Report(response.state(), intent.operationId(), attempt, response, null, gate.state(), gate.branchCheck(), null);
        }
    }

    Report reconcile(Intent intent, GitHubTransport transport) throws IOException, InterruptedException {
        Path directory = approvals.operation(intent.operationId());
        if (!exists(directory)) return empty(State.NOT_STARTED, intent);
        approvals.checkDirectories(directory);
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("publication.lock"))) {
            if (lease == null) return empty(State.BUSY, intent);
            var attempt = readAttempt(directory, intent);
            if (attempt == null) return empty(State.NOT_STARTED, intent);
            var previous = retained(directory, attempt, null);
            if (previous.state() != State.UNKNOWN) return previous;
            var response = previous.response();
            if (response != null && response.retryNotBefore() != null
                    && clock.instant().isBefore(Instant.parse(response.retryNotBefore()))) return previous;
            var lookup = new GitHubPullRequestLookup(connection).lookup(intent, credentials.load(), transport);
            if (lookup.state() != GitHubPullRequestLookup.State.MATCHED)
                return retained(directory, attempt, lookup);
            var recovered = receipt(attempt, State.RECONCILED, lookup.observed().getFirst(), 200, null, null);
            write(directory.resolve(RECOVERED), recovered);
            return retained(directory, attempt, lookup);
        }
    }

    private Receipt create(GitHubRepositoryConnection.VerifiedRepository repository, Attempt attempt, byte[] body)
            throws InterruptedException {
        try {
            var reply = repository.createDraft(attempt.intent(), body);
            try {
                var pull = GitHubPullRequestLookup.inspect(GitHubPullRequestLookup.describe(attempt.intent()), reply);
                if (pull.mismatches().isEmpty() && pull.state() == GitHubPullRequestLookup.PullState.OPEN && Boolean.TRUE.equals(pull.draft()))
                    return receipt(attempt, State.CREATED, pull, 201, null, null);
                return receipt(attempt, State.UNKNOWN, pull, 201, Code.IDENTITY_OR_SCOPE_MISMATCH, null);
            } catch (RuntimeException invalid) {
                return receipt(attempt, State.UNKNOWN, null, 201, Code.INVALID_METADATA, null);
            }
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            var failure = e.failure();
            return receipt(attempt, State.UNKNOWN, null, failure.httpStatus(), failure.code(), failure.retryNotBefore());
        }
    }

    static byte[] request(Intent intent) {
        var body = new JsonObject();
        body.addProperty("title", "Repair subscription period handling");
        body.addProperty("body", "This draft contains a subscription-period adapter repair checked against the registered synthetic scenarios.\n\n"
                + GitHubPullRequestLookup.describe(intent).marker());
        body.addProperty("head", intent.headRef());
        body.addProperty("base", intent.baseRef());
        body.addProperty("draft", true);
        return GitHubJson.JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    }

    private static Attempt readAttempt(Path directory, Intent intent) throws IOException {
        var attempt = read(directory.resolve(STARTED), Attempt.class);
        if (attempt != null) {
            GitHubJson.require(attempt.formatVersion() == 1 && intent.equals(attempt.intent())
                    && attempt.bindingIdentity().matches("[0-9a-f]{64}") && attempt.requestSha256().equals(GitSourceObjects.sha256(request(intent))));
            Instant.parse(attempt.startedAt());
        } else {
            GitHubJson.require(!exists(directory.resolve(RESPONSE)) && !exists(directory.resolve(RECOVERED)));
        }
        return attempt;
    }

    private static Report retained(Path directory, Attempt attempt, GitHubPullRequestLookup.Result lookup) throws IOException {
        var response = read(directory.resolve(RESPONSE), Receipt.class);
        var recovered = read(directory.resolve(RECOVERED), Receipt.class);
        if (response != null) {
            validateReceipt(attempt, response);
            switch (response.state()) {
                case CREATED -> GitHubJson.require(response.httpStatus() == 201 && response.failureCode() == null
                        && response.pull().mismatches().isEmpty() && response.pull().state() == GitHubPullRequestLookup.PullState.OPEN
                        && Boolean.TRUE.equals(response.pull().draft()));
                case NOT_SENT -> GitHubJson.require(response.httpStatus() == 0 && response.pull() == null && response.failureCode() == null
                        && response.dispatchBlocker() != null);
                case UNKNOWN -> GitHubJson.require(response.failureCode() != null);
                default -> throw new IllegalArgumentException("Invalid create receipt");
            }
        }
        if (recovered != null) {
            validateReceipt(attempt, recovered);
            GitHubJson.require(recovered.state() == State.RECONCILED && recovered.httpStatus() == 200
                    && recovered.failureCode() == null && recovered.pull().mismatches().isEmpty()
                    && (response == null || response.state() == State.UNKNOWN));
        }
        State state = recovered != null ? State.RECONCILED : response == null ? State.UNKNOWN : response.state();
        return new Report(state, attempt.intent().operationId(), attempt, response, recovered,
                response == null ? null : response.dispatchBlocker(), null, lookup);
    }

    private static void validateReceipt(Attempt attempt, Receipt receipt) {
        GitHubJson.require(receipt.formatVersion() == 1 && attemptIdentity(attempt).equals(receipt.attemptIdentity()));
        Instant.parse(receipt.recordedAt());
        if (receipt.retryNotBefore() != null) Instant.parse(receipt.retryNotBefore());
        if (receipt.pull() != null) GitHubJson.require(receipt.pull().number() > 0 && receipt.pull().state() != null);
    }

    private Receipt receipt(Attempt attempt, State state, ObservedPull pull, int status, Code code, Instant retryAt) {
        return new Receipt(1, attemptIdentity(attempt), state, pull, status, code, retryAt == null ? null : retryAt.toString(),
                clock.instant().toString(), null);
    }

    private static String attemptIdentity(Attempt attempt) { return GitSourceObjects.sha256(bytes(attempt)); }
    private static byte[] bytes(Object record) { return (GitHubJson.JSON.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8); }

    private static void write(Path file, Object value) throws IOException {
        DurableExecution.publishMetadata(file, bytes(value));
    }

    private static <T> T read(Path file, Class<T> type) throws IOException {
        if (!exists(file)) return null;
        GitHubJson.require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var json = GitHubJson.parse(input.readNBytes(RECORD_LIMIT + 1), RECORD_LIMIT);
            var value = GitHubJson.JSON.fromJson(json, type);
            GitHubJson.require(json.equals(GitHubJson.JSON.toJsonTree(value)));
            return value;
        }
    }

    private static Report empty(State state, Intent intent) {
        return new Report(state, intent.operationId(), null, null, null, null, null, null);
    }

    private static Report blocked(Intent intent, RepairPublicationApproval.State state, GitHubRepairRefs.Report branches) {
        return new Report(State.BLOCKED, intent.operationId(), null, null, null, state, branches, null);
    }

    public static void main(String[] args) {
        try (var transport = new GitHubTransport()) {
            GitHubJson.require(args.length >= 3);
            var state = Path.of(args[1]);
            var intent = GitHubPullRequestLookup.loadIntent(Path.of(args[2]));
            var publisher = new DraftRepairPublication(state, Clock.systemUTC(), GitHubRepairRefs::configuredApp);
            Report report;
            if (args[0].equals("reconcile")) {
                GitHubJson.require(args.length == 3);
                report = publisher.reconcile(intent, transport);
            } else {
                GitHubJson.require(args[0].equals("publish") && args.length == 6);
                var local = RepairCommitBinding.check(state, args[3], Path.of(args[4]), intent, args[5]);
                report = publisher.publish(intent, local, transport);
            }
            System.out.println(GitHubJson.JSON.toJson(report));
            if (report.state() != State.CREATED && report.state() != State.RECONCILED) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Draft repair publication could not complete. Check local records and App configuration before reconciling.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Draft repair publication interrupted. Use reconciliation to check for a recorded attempt.");
            System.exit(3);
        }
    }
}
