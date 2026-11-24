package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.runner.LocalFileLease;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.nktogo.apicompatibility.github.DraftRepairPublication.State.*;
import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftRepairPublicationTest {
    private static final String BINDING = "a".repeat(64), HEAD_SHA = "c".repeat(40);
    private static final GitHubPullRequestLookup.Intent INTENT = new GitHubPullRequestLookup.Intent(1, 10, SELECTION,
            "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", "main", HEAD_SHA);
    private static final String BASE_PATH = "/repos/sample-owner/billing/git/ref/heads/main";
    private static final String HEAD_PATH = "/repos/sample-owner/billing/git/ref/heads/" + INTENT.headRef();
    private static final String POST_PATH = "/repos/sample-owner/billing/pulls";
    private static final String LIST_PATH = POST_PATH + "?state=all&head=sample-owner%3Aworkbench%2Frepair-"
            + INTENT.operationId() + "&base=main&per_page=10&page=1";
    @TempDir Path state;
    private final MutableClock clock = new MutableClock();

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private static RepairCommitBinding.Report local(String binding) {
        var proof = new RepairCommitBinding.Proof(1, GitHubPullRequestLookup.describe(INTENT),
                "src/main/PeriodConsumer.java", null, TREE, "d".repeat(40), "e".repeat(40), "f".repeat(40));
        return new RepairCommitBinding.Report(RepairCommitBinding.State.MATCHED, null, null, binding, proof);
    }

    private static JsonObject branch(String name, String sha) {
        var tip = json("{\"object\":{\"type\":\"commit\"}}");
        tip.addProperty("ref", "refs/heads/" + name);
        tip.getAsJsonObject("object").addProperty("sha", sha);
        return tip;
    }

    private static JsonObject pull() {
        var repo = json("{\"id\":40,\"full_name\":\"sample-owner/billing\",\"owner\":{\"id\":30,\"login\":\"sample-owner\"}}");
        var pull = json("{\"number\":17,\"state\":\"open\",\"draft\":true,\"merged_at\":null,\"base\":{},\"head\":{},\"html_url\":\"https://attacker.invalid/ignored\"}");
        pull.addProperty("body", "PRIVATE_BODY_SENTINEL\n\n" + GitHubPullRequestLookup.describe(INTENT).marker());
        var base = pull.getAsJsonObject("base");
        base.add("repo", repo);
        base.addProperty("ref", INTENT.baseRef());
        base.addProperty("sha", SHA);
        var head = pull.getAsJsonObject("head");
        head.add("repo", repo.deepCopy());
        head.addProperty("ref", INTENT.headRef());
        head.addProperty("sha", HEAD_SHA);
        return pull;
    }

    private static void reply(Fixture fixture, String path, int status, String body) {
        fixture.faultPath = path;
        fixture.faultStatus = status;
        fixture.faultBody = body.getBytes(StandardCharsets.UTF_8);
    }

    private RepairPublicationApproval approve(Fixture fixture) throws Exception {
        fixture.replies.put(BASE_PATH, branch(INTENT.baseRef(), SHA));
        fixture.replies.put(HEAD_PATH, branch(INTENT.headRef(), HEAD_SHA));
        var approvals = new RepairPublicationApproval(state, clock, (proof, binding) -> {
            try (var transport = fixture.transport()) {
                return GitHubRepairRefs.inspect(proof, binding, credentials, new GitHubRepositoryConnection(clock), transport);
            }
        });
        assertEquals(RepairPublicationApproval.State.RECORDED, approvals.approve(INTENT, local(BINDING), BINDING).state());
        fixture.requests.clear();
        fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("pull_requests", "write");
        return approvals;
    }

    private DraftRepairPublication publisher() { return new DraftRepairPublication(state, clock, () -> credentials); }
    private Path operation() { return state.resolve("repair-publications").resolve(INTENT.operationId()); }
    private static long creates(Fixture fixture) {
        return fixture.requests.stream().filter(r -> r.method().equals("POST") && r.path().equals(POST_PATH)).count();
    }

    @Test void journalsBeforePostingAndRetainsOneConfirmedDraftAcrossRestarts() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            approve(fixture);
            reply(fixture, POST_PATH, 201, pull().toString());
            var journalPresent = new AtomicBoolean();
            fixture.afterHeaders = path -> { if (path.equals(POST_PATH)) journalPresent.set(Files.isRegularFile(operation().resolve("publication-started.json"))); };
            var outcome = publisher().publish(INTENT, local(BINDING), transport);
            assertEquals(CREATED, outcome.state());
            assertTrue(journalPresent.get());
            assertEquals(17, outcome.response().pull().number());
            assertEquals(GitSourceObjects.sha256(fixture.requests.getLast().body()), outcome.attempt().requestSha256());
            var draft = GitHubJson.parse(fixture.requests.getLast().body(), 8192);
            assertEquals(Boolean.TRUE, draft.get("draft").getAsBoolean());
            assertEquals(INTENT.headRef(), draft.get("head").getAsString());
            assertTrue(draft.get("body").getAsString().endsWith(GitHubPullRequestLookup.describe(INTENT).marker()));
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\",\"pull_requests\":\"write\"}}"),
                    GitHubJson.parse(fixture.requests.get(2).body(), 8192));
            assertEquals(java.util.List.of("/app", INSTALLATION, MINT, SCOPE, COMMIT, BASE_PATH, HEAD_PATH, BASE_PATH, POST_PATH),
                    fixture.requests.stream().map(Captured::path).toList());
            int calls = fixture.requests.size();
            var reopened = publisher().publish(INTENT, local("b".repeat(64)), transport);
            assertEquals(CREATED, reopened.state());
            assertEquals(outcome.response(), reopened.response());
            assertEquals(calls, fixture.requests.size());
            assertEquals(1, creates(fixture));
            var metadata = GitHubJson.JSON.toJson(reopened);
            assertFalse(metadata.contains("PRIVATE_"));
            assertFalse(metadata.contains("attacker.invalid"));
            assertFalse(metadata.contains(TOKEN));
        }
    }

    @Test void uncertainCreateUsesOnlyExplicitReadRecoveryAndPreservesOriginalOutcome() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            var approvals = approve(fixture);
            reply(fixture, POST_PATH, 503, "PRIVATE_PROVIDER_SENTINEL");
            fixture.faultHeaders.put("Retry-After", "90");
            var lost = publisher().publish(INTENT, local(BINDING), transport);
            assertEquals(UNKNOWN, lost.state());
            assertEquals(503, lost.response().httpStatus());
            var response = Files.readAllBytes(operation().resolve("publication-response.json"));
            assertEquals(1, creates(fixture));
            assertEquals(RepairPublicationApproval.State.REVOKED, approvals.revoke(INTENT.operationId()).state());
            assertEquals(UNKNOWN, publisher().publish(INTENT, local(BINDING), transport).state());
            int calls = fixture.requests.size();
            assertEquals(UNKNOWN, publisher().reconcile(INTENT, transport).state());
            assertEquals(calls, fixture.requests.size());

            clock.value = NOW.plusSeconds(90);
            fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("pull_requests", "read");
            reply(fixture, LIST_PATH, 200, "[]");
            var absent = publisher().reconcile(INTENT, transport);
            assertEquals(UNKNOWN, absent.state());
            assertEquals(GitHubPullRequestLookup.State.NOT_OBSERVED, absent.lookup().state());
            reply(fixture, LIST_PATH, 200, "[" + pull() + "]");
            var found = publisher().reconcile(INTENT, transport);
            assertEquals(RECONCILED, found.state());
            assertEquals(17, found.recovered().pull().number());
            assertArrayEquals(response, Files.readAllBytes(operation().resolve("publication-response.json")));
            assertEquals(1, creates(fixture));
            assertEquals(found.recovered(), publisher().publish(INTENT, local(BINDING), transport).recovered());
        }
    }

    @Test void refusesWrongWriteScopeAndChangedBranchesBeforeStarting() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            assertEquals(BLOCKED, publisher().publish(INTENT, local(BINDING), transport).state());
            assertTrue(fixture.requests.isEmpty());
            approve(fixture);
            assertEquals(RepairPublicationApproval.State.STALE, publisher().publish(INTENT, local("b".repeat(64)), transport).approvalState());
            assertTrue(fixture.requests.isEmpty());
            fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("contents", "write");
            var excess = publisher().publish(INTENT, local(BINDING), transport);
            assertEquals(BLOCKED, excess.state());
            assertEquals(GitHubRepositoryConnection.Stage.TOKEN, excess.branchCheck().connectionFailure().stage());
            fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("contents", "read");
            fixture.replies.put(HEAD_PATH, branch(INTENT.headRef(), "b".repeat(40)));
            assertEquals(RepairPublicationApproval.State.STALE, publisher().publish(INTENT, local(BINDING), transport).approvalState());
            assertEquals(0, creates(fixture));
            assertFalse(Files.exists(operation().resolve("publication-started.json")));
        }
    }

    @Test void detectsRacedBranchOrNonDraftInGitHubsCreateResponse() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            approve(fixture);
            var pull = pull();
            pull.addProperty("draft", false);
            pull.getAsJsonObject("head").addProperty("sha", "b".repeat(40));
            reply(fixture, POST_PATH, 201, pull.toString());
            var result = publisher().publish(INTENT, local(BINDING), transport);
            assertEquals(UNKNOWN, result.state());
            assertTrue(result.response().pull().mismatches().contains(GitHubPullRequestLookup.Mismatch.HEAD_SHA));
            assertEquals(UNKNOWN, publisher().publish(INTENT, local(BINDING), transport).state());
            assertEquals(1, creates(fixture));
        }
    }

    @Test void anInterruptedJournalWithNoResponseCanOnlyBeReconciled() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            approve(fixture);
            var started = new DraftRepairPublication.Attempt(1, INTENT, BINDING,
                    GitSourceObjects.sha256(DraftRepairPublication.request(INTENT)), NOW.toString());
            Files.writeString(operation().resolve("publication-started.json"), GitHubJson.JSON.toJson(started));
            try (var lease = LocalFileLease.acquire(operation().resolve("publication.lock"))) {
                assertTrue(lease.lock().isValid());
                assertEquals(BUSY, publisher().publish(INTENT, local(BINDING), transport).state());
            }
            assertEquals(UNKNOWN, publisher().publish(INTENT, local(BINDING), transport).state());
            assertTrue(fixture.requests.isEmpty());
            fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("pull_requests", "read");
            reply(fixture, LIST_PATH, 200, "[" + pull() + "]");
            var repaired = publisher().reconcile(INTENT, transport);
            assertEquals(RECONCILED, repaired.state());
            assertNull(repaired.response());
            assertEquals(0, creates(fixture));
        }
    }

    @Test void expiryAfterJournalingStopsThePostAndIsRetained() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            approve(fixture);
            var dispatchClock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() {
                    return Files.exists(operation().resolve("publication-started.json")) ? NOW.plusSeconds(1800) : NOW;
                }
            };
            var outcome = new DraftRepairPublication(state, dispatchClock, () -> credentials).publish(INTENT, local(BINDING), transport);
            assertEquals(NOT_SENT, outcome.state());
            assertEquals(RepairPublicationApproval.State.EXPIRED, outcome.approvalState());
            assertEquals(0, creates(fixture));
            var retained = publisher().reconcile(INTENT, transport);
            assertEquals(NOT_SENT, retained.state());
            assertEquals(outcome.approvalState(), retained.approvalState());
        }
    }
}
