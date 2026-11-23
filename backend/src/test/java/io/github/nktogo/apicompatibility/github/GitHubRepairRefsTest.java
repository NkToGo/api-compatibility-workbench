package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static io.github.nktogo.apicompatibility.github.GitHubRepairRefs.*;
import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubRepairRefsTest {
    private static final String BINDING = "a".repeat(64), HEAD_SHA = "c".repeat(40);
    private static final String BASE = "release/2026.1";
    private static final GitHubPullRequestLookup.Intent INTENT = new GitHubPullRequestLookup.Intent(1, 10, SELECTION,
            "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", BASE, HEAD_SHA);
    private static final String REF = "/repos/sample-owner/billing/git/ref/heads/";
    private static final String BASE_PATH = REF + BASE, HEAD_PATH = REF + INTENT.headRef();

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private static RepairCommitBinding.Proof proof(GitHubPullRequestLookup.Intent intent, String baseTree) {
        return new RepairCommitBinding.Proof(1, GitHubPullRequestLookup.describe(intent), "src/main/PeriodConsumer.java",
                null, baseTree, "d".repeat(40), "e".repeat(40), "f".repeat(40));
    }

    private static JsonObject branch(String name, String sha) {
        var result = json("{\"object\":{\"type\":\"commit\",\"url\":\"https://attacker.invalid/ignored\"},\"other\":\"PRIVATE_PROVIDER_SENTINEL\"}");
        result.addProperty("ref", "refs/heads/" + name);
        result.getAsJsonObject("object").addProperty("sha", sha);
        return result;
    }

    private static void branches(Fixture fixture) {
        fixture.replies.put(BASE_PATH, branch(BASE, SHA));
        fixture.replies.put(HEAD_PATH, branch(INTENT.headRef(), HEAD_SHA));
    }

    private static Report probe(Fixture fixture, RepairCommitBinding.Proof proof) throws Exception {
        try (var transport = fixture.transport()) {
            return inspect(proof, BINDING, credentials,
                    new GitHubRepositoryConnection(Clock.fixed(NOW, ZoneOffset.UTC)), transport);
        }
    }

    @Test void matchesExactBranchTipsAndRechecksBaseWithContentsReadScope() throws Exception {
        try (var fixture = new Fixture()) {
            branches(fixture);
            var report = probe(fixture, proof(INTENT, TREE));
            assertEquals(State.MATCHED, report.state());
            assertEquals(BINDING, report.bindingIdentity());
            assertEquals(SHA, report.base().observedSha());
            assertEquals(HEAD_SHA, report.head().observedSha());
            assertEquals(report.base(), report.baseRecheck());
            assertEquals(NOW, report.head().observedAt());
            assertEquals(List.of("/app", INSTALLATION, MINT, SCOPE, COMMIT, BASE_PATH, HEAD_PATH, BASE_PATH),
                    fixture.requests.stream().map(Captured::path).toList());
            assertEquals(List.of("POST"), fixture.requests.stream().map(Captured::method).filter(method -> !method.equals("GET")).toList());
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"),
                    GitHubJson.parse(fixture.requests.get(2).body(), 8192));
            String projection = GitHubJson.JSON.toJson(report);
            assertFalse(projection.contains("PRIVATE_"));
            assertFalse(projection.contains(TOKEN));
            assertFalse(projection.contains("attacker.invalid"));
        }
    }

    @Test void detectsBaseMovementDuringTheHeadRead() throws Exception {
        try (var fixture = new Fixture()) {
            branches(fixture);
            fixture.afterHeaders = path -> {
                if (path.equals(HEAD_PATH)) fixture.replies.put(BASE_PATH, branch(BASE, "d".repeat(40)));
            };
            var report = probe(fixture, proof(INTENT, TREE));
            assertEquals(State.BLOCKED, report.state());
            assertEquals(Reason.BRANCH_MOVED, report.reason());
            assertEquals(TipState.MATCHED, report.base().state());
            assertEquals(TipState.MATCHED, report.head().state());
            assertEquals(TipState.MOVED, report.baseRecheck().state());
        }
    }

    @Test void stopsWithoutRecheckingWhenTheRepairBranchMoved() throws Exception {
        try (var fixture = new Fixture()) {
            branches(fixture);
            fixture.replies.put(HEAD_PATH, branch(INTENT.headRef(), "d".repeat(40)));
            var report = probe(fixture, proof(INTENT, TREE));
            assertEquals(State.BLOCKED, report.state());
            assertEquals(TipState.MOVED, report.head().state());
            assertNull(report.baseRecheck());
            assertEquals(HEAD_PATH, fixture.requests.getLast().path());
        }
    }

    @Test void absentAndRateLimitedReferencesRemainIncomplete() throws Exception {
        try (var fixture = new Fixture()) {
            branches(fixture);
            fixture.faultPath = HEAD_PATH;
            fixture.faultStatus = 404;
            var absent = probe(fixture, proof(INTENT, TREE));
            assertEquals(State.INCOMPLETE, absent.state());
            assertEquals(TipState.NOT_FOUND_OR_INACCESSIBLE, absent.head().state());
            assertNull(absent.head().observedSha());
            assertNull(absent.baseRecheck());
            fixture.faultPath = BASE_PATH;
            fixture.faultStatus = 429;
            fixture.faultHeaders.put("Retry-After", "90");
            var limited = probe(fixture, proof(INTENT, TREE));
            assertEquals(State.INCOMPLETE, limited.state());
            assertEquals(TipState.READ_FAILED, limited.base().state());
            assertEquals(NOW.plusSeconds(90), limited.base().failure().retryNotBefore());
            assertNull(limited.head());
            assertEquals(BASE_PATH, fixture.requests.getLast().path());
        }
    }

    @Test void refusesPrefixAndNonCommitResponses() throws Exception {
        try (var fixture = new Fixture()) {
            branches(fixture);
            fixture.replies.put(HEAD_PATH, branch(INTENT.headRef() + "-other", HEAD_SHA));
            var wrongName = probe(fixture, proof(INTENT, TREE));
            assertEquals(State.INCOMPLETE, wrongName.state());
            assertEquals(GitHubRepositoryConnection.Code.INVALID_METADATA, wrongName.head().failure().code());
            var tag = branch(INTENT.headRef(), HEAD_SHA);
            tag.getAsJsonObject("object").addProperty("type", "tag");
            fixture.replies.put(HEAD_PATH, tag);
            assertEquals(TipState.READ_FAILED, probe(fixture, proof(INTENT, TREE)).head().state());
        }
    }

    @Test void refusesWrongAppBeforeMintAndWrongBaseTreeBeforeReadingRefs() throws Exception {
        try (var fixture = new Fixture()) {
            var otherApp = new GitHubPullRequestLookup.Intent(1, 11, SELECTION, INTENT.operationId(), BASE, HEAD_SHA);
            var blocked = probe(fixture, proof(otherApp, TREE));
            assertEquals(Reason.CONNECTION_FAILED, blocked.reason());
            assertEquals(GitHubRepositoryConnection.Stage.APP, blocked.connectionFailure().stage());
            assertEquals(List.of("/app"), fixture.requests.stream().map(Captured::path).toList());
            var wrongTree = probe(fixture, proof(INTENT, "0".repeat(40)));
            assertEquals(State.BLOCKED, wrongTree.state());
            assertEquals(Reason.BASE_TREE_DIFFERS, wrongTree.reason());
            assertEquals(COMMIT, fixture.requests.getLast().path());
        }
    }

    @Test void blockedOrChangedLocalBindingDoesNotOpenRemoteAccess() throws Exception {
        RemoteCheck failIfInvoked = (proof, identity) -> fail("Remote access must not start");
        var blockedLocal = new RepairCommitBinding.Report(RepairCommitBinding.State.BLOCKED,
                RepairCommitBinding.Blocker.OTHER_TREE_CHANGES, null, null, null);
        var blocked = checkReviewed(blockedLocal, BINDING, failIfInvoked);
        assertEquals(Reason.LOCAL_CHECK_BLOCKED, blocked.reason());
        assertEquals(RepairCommitBinding.Blocker.OTHER_TREE_CHANGES, blocked.localBlocker());
        var changed = new RepairCommitBinding.Report(RepairCommitBinding.State.MATCHED, null, null, "b".repeat(64), proof(INTENT, TREE));
        assertEquals(Reason.LOCAL_BINDING_CHANGED, checkReviewed(changed, BINDING, failIfInvoked).reason());
        try (var fixture = new Fixture()) {
            branches(fixture);
            var correct = checkReviewed(changed, changed.bindingIdentity(), (proof, identity) -> {
                assertEquals(changed.bindingIdentity(), identity);
                try (var transport = fixture.transport()) {
                    return inspect(proof, identity, credentials,
                            new GitHubRepositoryConnection(Clock.fixed(NOW, ZoneOffset.UTC)), transport);
                }
            });
            assertEquals(State.MATCHED, correct.state());
            assertEquals(changed.bindingIdentity(), correct.bindingIdentity());
        }
    }
}
