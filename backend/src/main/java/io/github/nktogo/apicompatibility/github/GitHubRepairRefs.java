package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** Revalidates remote branch tips against a reviewed local repair binding. */
public final class GitHubRepairRefs {
    public enum State { MATCHED, BLOCKED, INCOMPLETE }
    public enum Reason { LOCAL_CHECK_BLOCKED, LOCAL_BINDING_CHANGED, CONNECTION_FAILED, BASE_TREE_DIFFERS,
        BRANCH_MOVED, BRANCH_READ_INCOMPLETE }
    public enum TipState { MATCHED, MOVED, NOT_FOUND_OR_INACCESSIBLE, READ_FAILED }
    public record Observation(TipState state, String ref, String expectedSha, String observedSha, Instant observedAt,
                              GitHubRepositoryConnection.Failure failure) {}
    public record Report(int formatVersion, State state, Reason reason, String bindingIdentity,
                         RepairCommitBinding.Blocker localBlocker, Observation base, Observation head,
                         Observation baseRecheck, GitHubRepositoryConnection.Failure connectionFailure) {}

    @FunctionalInterface
    interface RemoteCheck {
        Report read(RepairCommitBinding.Proof proof, String bindingIdentity) throws IOException, InterruptedException;
    }

    private GitHubRepairRefs() {}

    static Report checkReviewed(RepairCommitBinding.Report local, String reviewedIdentity, RemoteCheck remote)
            throws IOException, InterruptedException {
        GitHubJson.require(reviewedIdentity != null && reviewedIdentity.matches("[0-9a-f]{64}"));
        if (local.state() != RepairCommitBinding.State.MATCHED)
            return new Report(1, State.BLOCKED, Reason.LOCAL_CHECK_BLOCKED, null, local.blocker(), null, null, null, null);
        if (!reviewedIdentity.equals(local.bindingIdentity()))
            return new Report(1, State.BLOCKED, Reason.LOCAL_BINDING_CHANGED, local.bindingIdentity(), null, null, null, null, null);
        return remote.read(local.proof(), local.bindingIdentity());
    }

    static Report inspect(RepairCommitBinding.Proof proof, String bindingIdentity, GitHubAppCredentials credentials,
                          GitHubRepositoryConnection connection, GitHubTransport transport) throws InterruptedException {
        var intent = proof.publication().intent();
        GitHubRepositoryConnection.VerifiedRepository repository;
        try {
            repository = connection.openVerified(intent.selection(), credentials, transport, intent.appId(), false);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            return new Report(1, State.INCOMPLETE, Reason.CONNECTION_FAILED, bindingIdentity, null, null, null, null, e.failure());
        }
        if (!repository.snapshot().treeSha().equals(proof.baseTreeSha()))
            return new Report(1, State.BLOCKED, Reason.BASE_TREE_DIFFERS, bindingIdentity, null, null, null, null, null);

        var base = observe(repository, intent.baseRef(), intent.selection().commitSha());
        if (base.state() != TipState.MATCHED) return summarize(bindingIdentity, base, null, null, base);
        var head = observe(repository, intent.headRef(), intent.headSha());
        if (head.state() != TipState.MATCHED) return summarize(bindingIdentity, base, head, null, head);
        var baseRecheck = observe(repository, intent.baseRef(), intent.selection().commitSha());
        return summarize(bindingIdentity, base, head, baseRecheck, baseRecheck);
    }

    private static Observation observe(GitHubRepositoryConnection.VerifiedRepository repository, String branch, String expected)
            throws InterruptedException {
        String ref = "refs/heads/" + branch;
        try {
            var tip = repository.branchTip(branch);
            return new Observation(expected.equals(tip.sha()) ? TipState.MATCHED : TipState.MOVED, ref, expected,
                    tip.sha(), tip.observedAt(), null);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            var failure = e.failure();
            var state = failure.httpStatus() == 404 ? TipState.NOT_FOUND_OR_INACCESSIBLE : TipState.READ_FAILED;
            return new Observation(state, ref, expected, null, null, failure);
        }
    }

    private static Report summarize(String identity, Observation base, Observation head, Observation baseRecheck, Observation last) {
        return switch (last.state()) {
            case MATCHED -> new Report(1, State.MATCHED, null, identity, null, base, head, baseRecheck, null);
            case MOVED -> new Report(1, State.BLOCKED, Reason.BRANCH_MOVED, identity, null, base, head, baseRecheck, null);
            case NOT_FOUND_OR_INACCESSIBLE, READ_FAILED ->
                    new Report(1, State.INCOMPLETE, Reason.BRANCH_READ_INCOMPLETE, identity, null, base, head, baseRecheck, null);
        };
    }

    public static void main(String[] args) {
        try {
            GitHubJson.require(args.length == 6);
            var local = RepairCommitBinding.check(Path.of(args[0]), args[1], Path.of(args[2]),
                    GitHubPullRequestLookup.loadIntent(Path.of(args[3])), args[4]);
            var report = checkReviewed(local, args[5], (proof, identity) -> {
                String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER");
                String key = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
                GitHubJson.require(issuer != null && key != null && !key.isBlank());
                var credentials = GitHubAppCredentials.load(issuer, Path.of(key));
                try (var transport = new GitHubTransport()) {
                    return inspect(proof, identity, credentials, new GitHubRepositoryConnection(), transport);
                }
            });
            System.out.println(GitHubJson.JSON.toJson(report));
            if (report.state() != State.MATCHED) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Remote repair branch check could not start. Check the selection and host-side App configuration.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Remote repair branch check interrupted.");
            System.exit(3);
        }
    }
}
