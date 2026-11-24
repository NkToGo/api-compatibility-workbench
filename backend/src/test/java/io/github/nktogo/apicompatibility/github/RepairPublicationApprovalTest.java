package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.github.RepairPublicationApproval.State.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairPublicationApprovalTest {
    private static final String BINDING = "a".repeat(64), HEAD_SHA = "c".repeat(40);
    private static final GitHubPullRequestLookup.Intent INTENT = new GitHubPullRequestLookup.Intent(1, 10, SELECTION,
            "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", "main", HEAD_SHA);
    private static final String BASE_PATH = "/repos/sample-owner/billing/git/ref/heads/main";
    private static final String HEAD_PATH = "/repos/sample-owner/billing/git/ref/heads/" + INTENT.headRef();
    @TempDir Path state;
    private final MutableClock clock = new MutableClock();

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private static void reply(Fixture fixture, String path, String name, String sha) {
        var value = json("{\"object\":{\"type\":\"commit\"}}");
        value.addProperty("ref", "refs/heads/" + name);
        value.getAsJsonObject("object").addProperty("sha", sha);
        fixture.replies.put(path, value);
    }

    private static RepairCommitBinding.Report local(String binding) {
        var proof = new RepairCommitBinding.Proof(1, GitHubPullRequestLookup.describe(INTENT),
                "src/main/PeriodConsumer.java", null, TREE, "d".repeat(40), "e".repeat(40), "f".repeat(40));
        return new RepairCommitBinding.Report(RepairCommitBinding.State.MATCHED, null, null, binding, proof);
    }

    private RepairPublicationApproval approvals(Fixture fixture) {
        return new RepairPublicationApproval(state, clock, (proof, binding) -> {
            try (var transport = fixture.transport()) {
                return GitHubRepairRefs.inspect(proof, binding, credentials, new GitHubRepositoryConnection(clock), transport);
            }
        });
    }

    private Path approvalFile() {
        return state.resolve("repair-publications").resolve(INTENT.operationId()).resolve("approval.json");
    }

    private static void refs(Fixture fixture) {
        reply(fixture, BASE_PATH, INTENT.baseRef(), SHA);
        reply(fixture, HEAD_PATH, INTENT.headRef(), HEAD_SHA);
    }

    @Test void savesExactApprovalAndReopensItWithANewRemoteCheck() throws Exception {
        try (var fixture = new Fixture()) {
            refs(fixture);
            var service = approvals(fixture);
            var accepted = service.approve(INTENT, local(BINDING), BINDING);
            assertEquals(RECORDED, accepted.state());
            assertEquals(BINDING, accepted.approval().bindingIdentity());
            assertEquals(NOW.plusSeconds(1800).toString(), accepted.approval().expiresAt());
            assertEquals(HEAD_SHA, accepted.approval().reviewedBranches().head().commitSha());
            assertEquals(8, fixture.requests.size());
            byte[] written = Files.readAllBytes(approvalFile());
            clock.value = NOW.plusSeconds(120);
            var applicable = approvals(fixture).check(INTENT, local(BINDING));
            assertEquals(APPLICABLE, applicable.state());
            assertEquals(accepted.approval(), applicable.approval());
            assertEquals(clock.instant(), applicable.branchCheck().head().observedAt());
            assertArrayEquals(written, Files.readAllBytes(approvalFile()));
            assertEquals(ALREADY_RECORDED, service.approve(INTENT, local(BINDING), BINDING).state());
            assertEquals(16, fixture.requests.size());
            assertEquals(2, fixture.requests.stream().filter(r -> r.method().equals("POST") && r.path().equals(MINT)).count());
            assertTrue(fixture.requests.stream().allMatch(r -> r.method().equals("GET") || r.path().equals(MINT)));
        }
    }

    @Test void refusesUnreviewedInputsAndUnavailableBranchesAndRetainsDrift() throws Exception {
        try (var fixture = new Fixture()) {
            refs(fixture);
            var service = approvals(fixture);
            assertEquals(STALE, service.approve(INTENT, local(BINDING), "b".repeat(64)).state());
            assertTrue(fixture.requests.isEmpty());
            fixture.faultPath = HEAD_PATH;
            fixture.faultStatus = 404;
            assertEquals(UNCONFIRMED, service.approve(INTENT, local(BINDING), BINDING).state());
            assertFalse(Files.exists(approvalFile()));
            fixture.faultPath = "";
            assertEquals(RECORDED, service.approve(INTENT, local(BINDING), BINDING).state());
            var count = fixture.requests.size();
            assertEquals(STALE, service.check(INTENT, local("b".repeat(64))).state());
            assertEquals(count, fixture.requests.size());
            reply(fixture, HEAD_PATH, INTENT.headRef(), "b".repeat(40));
            var drift = service.check(INTENT, local(BINDING));
            assertEquals(STALE, drift.state());
            assertEquals(GitHubRepairRefs.TipState.MOVED, drift.branchCheck().head().state());
            assertEquals(BINDING, drift.approval().bindingIdentity());
        }
    }

    @Test void checksExpiryAndPermanentRevocationBeforeReadingGitHub() throws Exception {
        try (var fixture = new Fixture()) {
            refs(fixture);
            var service = approvals(fixture);
            assertEquals(UNAVAILABLE, service.check(INTENT, local(BINDING)).state());
            assertEquals(UNAVAILABLE, service.revoke(INTENT.operationId()).state());
            assertEquals(RECORDED, service.approve(INTENT, local(BINDING), BINDING).state());
            var count = fixture.requests.size();
            clock.value = NOW.plusSeconds(1800);
            assertEquals(EXPIRED, service.check(INTENT, local(BINDING)).state());
            assertEquals(REVOKED, service.revoke(INTENT.operationId()).state());
            assertEquals(REVOKED, service.revoke(INTENT.operationId()).state());
            assertEquals(REVOKED, approvals(fixture).check(INTENT, local(BINDING)).state());
            assertEquals(ALREADY_RECORDED, service.approve(INTENT, local(BINDING), BINDING).state());
            assertEquals(count, fixture.requests.size());
        }
    }

    @Test void expiryWhileGitHubIsBeingCheckedPreventsApplicability() throws Exception {
        try (var fixture = new Fixture()) {
            refs(fixture);
            var service = approvals(fixture);
            assertEquals(RECORDED, service.approve(INTENT, local(BINDING), BINDING).state());
            fixture.afterHeaders = path -> { if (path.equals(HEAD_PATH)) clock.value = NOW.plusSeconds(1801); };
            var report = service.check(INTENT, local(BINDING));
            assertEquals(EXPIRED, report.state());
            assertEquals(GitHubRepairRefs.State.MATCHED, report.branchCheck().state());
        }
    }

    @Test void revocationWhileGitHubIsBeingCheckedPreventsApplicability() throws Exception {
        try (var fixture = new Fixture()) {
            refs(fixture);
            var service = approvals(fixture);
            assertEquals(RECORDED, service.approve(INTENT, local(BINDING), BINDING).state());
            var revokedDuringCheck = new RepairPublicationApproval(state, clock, (proof, binding) -> {
                try (var transport = fixture.transport()) {
                    var report = GitHubRepairRefs.inspect(proof, binding, credentials, new GitHubRepositoryConnection(clock), transport);
                    assertEquals(REVOKED, service.revoke(INTENT.operationId()).state());
                    return report;
                }
            });
            assertEquals(REVOKED, revokedDuringCheck.check(INTENT, local(BINDING)).state());
        }
    }

    @Test void refusesMalformedApprovalAndBranchEvidenceOutsideTheCurrentCheck() throws Exception {
        try (var fixture = new Fixture()) {
            refs(fixture);
            var service = approvals(fixture);
            var approved = service.approve(INTENT, local(BINDING), BINDING);
            assertEquals(RECORDED, approved.state());
            clock.value = NOW.plusSeconds(3);
            var cached = new RepairPublicationApproval(state, clock, (proof, binding) -> approved.branchCheck());
            assertEquals(UNCONFIRMED, cached.check(INTENT, local(BINDING)).state());
            int count = fixture.requests.size();
            Files.writeString(approvalFile(), "{\"permission\":\"other\"}");
            assertEquals(INVALID, service.check(INTENT, local(BINDING)).state());
            assertEquals(count, fixture.requests.size());
        }
    }
}
