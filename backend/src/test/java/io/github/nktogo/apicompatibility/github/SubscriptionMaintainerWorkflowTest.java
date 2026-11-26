package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;
import io.github.nktogo.apicompatibility.runner.RepairCommitBindingTest;
import io.github.nktogo.apicompatibility.runner.RepairCommitBindingTest.Revisions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.runner.RepairCommitBindingTest.SOURCE;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the publication-to-handoff path with actual journals and a loopback GitHub fixture. */
public class SubscriptionMaintainerWorkflowTest {
    @TempDir Path temporary;

    @Test void reconcilesOneApprovedAttemptAndExportsItsEvidence() throws Exception {
        Path state = Files.createDirectory(temporary.resolve("state"));
        String verification = RepairCommitBindingTest.verification(state, false);
        var revisions = RepairCommitBindingTest.history(temporary.resolve("repo"), "baseline", "candidate");
        verify(state, revisions, verification);
    }

    public static void verify(Path state, Revisions revisions, String verification) throws Exception {
        GitHubRepositoryConnectionTest.keys();
        var clock = new MutableClock();
        var selection = new GitHubRepositoryConnection.Selection(1, 20, 30, 40, "sample-owner", "billing", revisions.base());
        var intent = new GitHubPullRequestLookup.Intent(1, 10, selection,
                "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", "main", revisions.head());
        var review = RepairCommitBinding.review(state, verification, revisions.repository(), intent, SOURCE);
        var local = review.binding();
        assertEquals(RepairCommitBinding.State.MATCHED, local.state());
        var reviewedAt = review.outcome().finishedAt().plusSeconds(1);
        clock.value = reviewedAt;
        var draft = draft(intent);
        String pullPath = selection.path() + "/pulls";
        String operationPath = "repair-publications/" + intent.operationId();
        Path response = state.resolve(operationPath).resolve("publication-response.json");

        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            fixture.replies.get(MINT).addProperty("expires_at", reviewedAt.plusSeconds(3600).toString());
            configureBinding(fixture, intent, local.proof());
            var connection = new GitHubRepositoryConnection(clock);
            var approvals = new RepairPublicationApproval(state, clock,
                    (proof, binding) -> GitHubRepairRefs.inspect(proof, binding, credentials, connection, transport));
            var approval = approvals.approve(intent, local, local.bindingIdentity());
            assertEquals(RepairPublicationApproval.State.RECORDED, approval.state());
            assertEquals(local.bindingIdentity(), approval.approval().bindingIdentity());

            var permissions = fixture.replies.get(MINT).getAsJsonObject("permissions");
            permissions.addProperty("pull_requests", "write");
            fixture.faultPath = pullPath;
            fixture.faultStatus = 502;
            var publisher = new DraftRepairPublication(state, clock, () -> credentials);
            var created = publisher.publish(intent, local, transport);
            assertEquals(DraftRepairPublication.State.UNKNOWN, created.state());
            assertEquals(local.bindingIdentity(), created.attempt().bindingIdentity());
            byte[] originalResponse = Files.readAllBytes(response);

            // Simulate a draft that exists even though the original create response was inconclusive.
            clock.value = reviewedAt.plusSeconds(90);
            permissions.addProperty("pull_requests", "read");
            var matches = new JsonArray();
            matches.add(draft);
            String lookup = pullPath + "?state=all&head=sample-owner%3Aworkbench%2Frepair-"
                    + intent.operationId() + "&base=main&per_page=10&page=1";
            fixture.additionalReplies.put(lookup, matches);
            var restarted = new DraftRepairPublication(state, clock, () -> credentials);
            var recovered = restarted.reconcile(intent, transport);
            assertEquals(DraftRepairPublication.State.RECONCILED, recovered.state());
            assertEquals(created.response(), recovered.response());
            var replay = restarted.publish(intent, local, transport);
            assertEquals(recovered.state(), replay.state());
            assertEquals(recovered.recovered(), replay.recovered());
            assertArrayEquals(originalResponse, Files.readAllBytes(response));
            assertEquals(1, fixture.requests.stream().filter(r -> "POST".equals(r.method()) && pullPath.equals(r.path())).count());

            permissions.remove("pull_requests");
            String merge = "d".repeat(40);
            RepairEvidenceExportTest.configureMergedDeployment(fixture, intent, draft, merge, clock.instant());
            var destination = state.resolve("maintainer-workflow-export");
            var export = new RepairEvidenceExport(state, clock, () -> credentials)
                    .export(verification, revisions.repository(), intent, SOURCE, destination, true, "production", transport);
            byte[] bytes = Files.readAllBytes(destination.resolve(export.file()));
            assertEquals(GitSourceObjects.sha256(bytes), export.sha256());
            verifyExport(GitHubJson.parse(bytes, 262144), local, merge, recovered.recovered().pull().number());
            assertArrayEquals(originalResponse, Files.readAllBytes(response));
            assertTrue(fixture.requests.stream().allMatch(r -> "GET".equals(r.method())
                    || "POST".equals(r.method()) && (MINT.equals(r.path()) || pullPath.equals(r.path()))));
        }
    }

    private static void configureBinding(Fixture fixture, GitHubPullRequestLookup.Intent intent, RepairCommitBinding.Proof proof) {
        var selection = intent.selection();
        var commit = new JsonObject();
        commit.addProperty("sha", selection.commitSha());
        var tree = new JsonObject();
        tree.addProperty("sha", proof.baseTreeSha());
        commit.add("tree", tree);
        fixture.replies.put(selection.path() + "/git/commits/" + selection.commitSha(), commit);
        addBranch(fixture, selection.path(), intent.baseRef(), selection.commitSha());
        addBranch(fixture, selection.path(), intent.headRef(), intent.headSha());
    }

    private static void addBranch(Fixture fixture, String repositoryPath, String name, String sha) {
        var tip = json("{\"object\":{\"type\":\"commit\"}}");
        tip.addProperty("ref", "refs/heads/" + name);
        tip.getAsJsonObject("object").addProperty("sha", sha);
        fixture.replies.put(repositoryPath + "/git/ref/heads/" + name, tip);
    }

    private static JsonObject draft(GitHubPullRequestLookup.Intent intent) {
        var draft = PublishedRepairStatusTest.pull();
        draft.addProperty("body", GitHubPullRequestLookup.describe(intent).marker());
        draft.getAsJsonObject("base").addProperty("sha", intent.selection().commitSha());
        draft.getAsJsonObject("head").addProperty("sha", intent.headSha());
        return draft;
    }

    private static void verifyExport(JsonObject report, RepairCommitBinding.Report local, String merge, long number) {
        assertEquals(local.bindingIdentity(), GitHubJson.text(report.getAsJsonObject("binding"), "identity"));
        var verification = report.getAsJsonObject("verification");
        assertEquals(local.verification().receiptSha256(), GitHubJson.text(verification.getAsJsonObject("receipt"), "receiptSha256"));
        assertEquals(8, verification.getAsJsonArray("outcomes").size());
        assertEquals("RECORDED", GitHubJson.text(report.getAsJsonObject("historicalApproval"), "state"));
        var publication = report.getAsJsonObject("publication");
        assertEquals("RECONCILED", GitHubJson.text(publication, "state"));
        assertEquals("UNKNOWN", GitHubJson.text(publication.getAsJsonObject("response"), "state"));
        var github = report.getAsJsonObject("github");
        var current = github.getAsJsonObject("pull").getAsJsonObject("current");
        assertEquals(merge, GitHubJson.text(current, "mergeCommitSha"));
        assertEquals(number, GitHubJson.number(current.getAsJsonObject("pull"), "number"));
        var deployment = github.getAsJsonObject("deployment");
        assertEquals("OBSERVED", GitHubJson.text(deployment, "state"));
        assertEquals("SUCCESS", GitHubJson.text(deployment.getAsJsonArray("records").get(0).getAsJsonObject(), "latestState"));
    }
}
