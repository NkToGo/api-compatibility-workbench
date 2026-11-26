package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;
import io.github.nktogo.apicompatibility.runner.RepairCommitBindingTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.runner.RepairCommitBindingTest.SOURCE;
import static org.junit.jupiter.api.Assertions.*;

class RepairEvidenceExportTest {
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DETAIL = "/repos/sample-owner/billing/pulls/17";
    private static final String MERGE = "d".repeat(40);
    @TempDir Path temporary;

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private record Case(Path state, Path repository, String verification, GitHubPullRequestLookup.Intent intent) {
        RepairCommitBinding.Report binding() throws InterruptedException {
            return RepairCommitBinding.check(state, verification, repository, intent, SOURCE);
        }

        JsonObject export(String name, boolean observe, String environment, GitHubTransport transport,
                          DraftRepairPublication.Credentials credentials) throws Exception {
            Path output = state.getParent().resolve(name);
            var result = new RepairEvidenceExport(state, CLOCK, credentials)
                    .export(verification, repository, intent, SOURCE, output, observe, environment, transport);
            assertEquals("evidence.json", result.file());
            byte[] written = Files.readAllBytes(output.resolve(result.file()));
            assertEquals(GitSourceObjects.sha256(written), result.sha256());
            try (var files = Files.list(output)) { assertEquals(1, files.count()); }
            return GitHubJson.parse(written, 262144);
        }
    }

    private Case prepare(boolean cancelled) throws Exception {
        Path state = Files.createDirectory(temporary.resolve("state"));
        String id = RepairCommitBindingTest.verification(state, cancelled);
        var revisions = RepairCommitBindingTest.history(temporary.resolve("repo"), "baseline", "candidate");
        var selection = new GitHubRepositoryConnection.Selection(1, 20, 30, 40, "sample-owner", "billing", revisions.base());
        var intent = new GitHubPullRequestLookup.Intent(1, 10, selection,
                "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", "main", revisions.head());
        return new Case(state, revisions.repository(), id, intent);
    }

    private static JsonObject pull(Case test) {
        var detail = PublishedRepairStatusTest.pull();
        detail.addProperty("body", "PRIVATE_BODY_SENTINEL\n\n" + GitHubPullRequestLookup.describe(test.intent()).marker());
        detail.getAsJsonObject("base").addProperty("sha", test.intent().selection().commitSha());
        detail.getAsJsonObject("head").addProperty("sha", test.intent().headSha());
        return detail;
    }

    private static Path recordPublication(Case test, String binding) throws IOException {
        var directory = Files.createDirectories(test.state().resolve("repair-publications").resolve(test.intent().operationId()));
        var attempt = new DraftRepairPublication.Attempt(1, test.intent(), binding,
                GitSourceObjects.sha256(DraftRepairPublication.request(test.intent())), NOW.toString());
        String data = GitHubJson.JSON.toJson(attempt) + "\n";
        Files.writeString(directory.resolve("publication-started.json"), data);
        String attemptIdentity = GitSourceObjects.sha256(data.getBytes(StandardCharsets.UTF_8));
        var matched = GitHubPullRequestLookup.inspect(GitHubPullRequestLookup.describe(test.intent()), pull(test));
        var unknown = new DraftRepairPublication.Receipt(1, attemptIdentity, DraftRepairPublication.State.UNKNOWN, null,
                502, GitHubRepositoryConnection.Code.PROVIDER_UNAVAILABLE, null, NOW.toString(), null);
        var recovered = new DraftRepairPublication.Receipt(1, attemptIdentity, DraftRepairPublication.State.RECONCILED, matched,
                200, null, null, NOW.toString(), null);
        Files.writeString(directory.resolve("publication-response.json"), GitHubJson.JSON.toJson(unknown));
        Files.writeString(directory.resolve("publication-recovered.json"), GitHubJson.JSON.toJson(recovered));
        return directory;
    }

    @Test void exportsRecomputedEvidenceAndRevokedApprovalOfflineWithoutTouchingTheJournal() throws Exception {
        var test = prepare(false);
        var identity = test.binding().bindingIdentity();
        var directory = recordPublication(test, identity);
        var base = new RepairPublicationApproval.Tip("refs/heads/main", test.intent().selection().commitSha(), NOW.toString());
        var head = new RepairPublicationApproval.Tip("refs/heads/" + test.intent().headRef(), test.intent().headSha(), NOW.toString());
        var approval = new RepairPublicationApproval.Approval(1, "create-draft-repair-pull-request", test.intent().operationId(), identity,
                new RepairPublicationApproval.ReviewedBranches(base, head, base), NOW.toString(), NOW.plusSeconds(1800).toString());
        Files.writeString(directory.resolve("approval.json"), GitHubJson.JSON.toJson(approval));
        Files.createFile(directory.resolve("approval.revoked"));
        var retained = directory.resolve("publication-response.json");
        byte[] before = Files.readAllBytes(retained);

        var report = test.export("offline", false, null, null, () -> fail("Offline export loaded credentials"));
        assertEquals(1, report.get("formatVersion").getAsInt());
        assertEquals(identity, GitHubJson.text(report.getAsJsonObject("binding"), "identity"));
        var evidence = report.getAsJsonObject("verification");
        assertEquals("ALL_OBSERVED_REGRESSIONS_FIXED", GitHubJson.text(evidence.getAsJsonObject("receipt"), "conclusion"));
        assertEquals("COMPLETED", GitHubJson.text(evidence.getAsJsonObject("execution"), "status"));
        assertEquals(8, evidence.getAsJsonArray("outcomes").size());
        assertEquals("2025-07-30.basil", GitHubJson.text(evidence.getAsJsonObject("inputs").getAsJsonObject("repairedTarget"), "providerVersion"));
        assertFalse(report.getAsJsonObject("github").get("requested").getAsBoolean());
        var publication = report.getAsJsonObject("publication");
        assertEquals("MATCHED", GitHubJson.text(publication, "link"));
        assertEquals("RECONCILED", GitHubJson.text(publication, "state"));
        assertEquals("UNKNOWN", GitHubJson.text(publication.getAsJsonObject("response"), "state"));
        assertEquals("REVOKED", GitHubJson.text(report.getAsJsonObject("historicalApproval"), "state"));
        assertFalse(report.toString().contains("create-draft-repair-pull-request"));
        assertFalse(report.toString().contains(temporary.toString()));
        assertArrayEquals(before, Files.readAllBytes(retained));
    }

    @Test void preservesCancelledVerificationAndRequiresABindingBeforeObservingGitHub() throws Exception {
        var test = prepare(true);
        recordPublication(test, "a".repeat(64));
        var report = test.export("cancelled", true, "production", null, () -> fail("Blocked binding loaded credentials"));
        var verification = report.getAsJsonObject("verification");
        assertEquals("CANCELLED", GitHubJson.text(verification.getAsJsonObject("execution"), "status"));
        assertTrue(verification.getAsJsonArray("outcomes").isEmpty());
        assertEquals("REPAIR_NOT_ESTABLISHED", GitHubJson.text(report.getAsJsonObject("binding"), "blocker"));
        assertEquals("BINDING_REQUIRED", GitHubJson.text(report.getAsJsonObject("publication"), "link"));
        assertTrue(report.getAsJsonObject("publication").get("recovered").isJsonNull());
        assertEquals("BINDING_OR_JOURNAL_UNAVAILABLE", GitHubJson.text(report.getAsJsonObject("github"), "gap"));
    }

    @Test void omitsUnrelatedOrCorruptedPublicationAndKeepsLocalVerification() throws Exception {
        var test = prepare(false);
        assertNotEquals("a".repeat(64), test.binding().bindingIdentity());
        var directory = recordPublication(test, "a".repeat(64));
        var mismatch = test.export("mismatch", true, null, null, () -> fail("Unrelated binding loaded credentials"));
        assertEquals("DIFFERENT_BINDING", GitHubJson.text(mismatch.getAsJsonObject("publication"), "link"));
        assertTrue(mismatch.getAsJsonObject("publication").get("response").isJsonNull());
        assertEquals("MATCHED", GitHubJson.text(mismatch.getAsJsonObject("binding"), "state"));
        Files.writeString(directory.resolve("publication-started.json"), "{\"reason\":\"PRIVATE_JOURNAL_SENTINEL\"}");
        var invalid = test.export("invalid", true, null, null, () -> fail("Invalid journal loaded credentials"));
        assertEquals("JOURNAL_UNAVAILABLE", GitHubJson.text(invalid.getAsJsonObject("publication"), "link"));
        assertFalse(invalid.toString().contains("PRIVATE_JOURNAL_SENTINEL"));
    }

    @Test void distinguishesUnstartedPublicationFromInvalidVerificationEvidence() throws Exception {
        var test = prepare(false);
        var unstarted = test.export("unstarted", true, null, null, () -> fail("Unstarted publication loaded credentials"));
        assertEquals("NOT_STARTED", GitHubJson.text(unstarted.getAsJsonObject("publication"), "state"));
        assertEquals("NOT_CONFIRMED", GitHubJson.text(unstarted.getAsJsonObject("github").getAsJsonObject("pull"), "state"));
        Path receipt = test.state().resolve("verifications").resolve(test.verification()).resolve("result.json");
        var altered = GitHubJson.parse(Files.readAllBytes(receipt), 1048576);
        altered.getAsJsonObject("evaluation").add("scenarios", new JsonArray());
        Files.writeString(receipt, GitHubJson.JSON.toJson(altered));

        var invalid = test.export("invalid", false, null, null, () -> fail("Offline export loaded credentials"));
        assertTrue(invalid.get("verification").isJsonNull());
        assertEquals("VERIFICATION_UNAVAILABLE", GitHubJson.text(invalid.getAsJsonObject("binding"), "blocker"));
        assertEquals("BINDING_REQUIRED", GitHubJson.text(invalid.getAsJsonObject("publication"), "link"));
    }

    @Test void includesFreshPrAndDeploymentMetadataFromOneExactPublication() throws Exception {
        var test = prepare(false);
        recordPublication(test, test.binding().bindingIdentity());
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            var merged = pull(test);
            merged.addProperty("state", "closed");
            merged.addProperty("draft", false);
            merged.addProperty("merged", true);
            merged.addProperty("merged_at", NOW.toString());
            merged.addProperty("merge_commit_sha", MERGE);
            fixture.replies.put(DETAIL, merged);
            var deploymentToken = fixture.replies.get(MINT).deepCopy();
            var permissions = deploymentToken.getAsJsonObject("permissions");
            permissions.remove("contents");
            permissions.addProperty("deployments", "read");
            fixture.afterHeaders = path -> {
                if (DETAIL.equals(path)) fixture.additionalReplies.put(MINT, deploymentToken);
            };
            String repositoryUrl = "https://api.github.com" + test.intent().selection().path();
            var deployment = json("{\"id\":31,\"environment\":\"production\",\"description\":\"PRIVATE_DEPLOYMENT_SENTINEL\"}");
            deployment.addProperty("sha", MERGE);
            deployment.addProperty("repository_url", repositoryUrl);
            deployment.addProperty("created_at", NOW.toString());
            var status = json("{\"id\":1,\"environment\":\"production\",\"state\":\"success\",\"log_url\":\"https://unrelated.invalid\"}");
            status.addProperty("repository_url", repositoryUrl);
            status.addProperty("deployment_url", repositoryUrl + "/deployments/31");
            status.addProperty("created_at", NOW.toString());
            String path = test.intent().selection().path() + "/deployments";
            var records = new JsonArray();
            records.add(deployment);
            var statuses = new JsonArray();
            statuses.add(status);
            fixture.additionalReplies.put(path + "?sha=" + MERGE + "&environment=production&per_page=10&page=1", records);
            fixture.additionalReplies.put(path + "/31/statuses?per_page=10&page=1", statuses);
            var report = test.export("online", true, "production", transport, () -> credentials);
            var github = report.getAsJsonObject("github");
            assertEquals(MERGE, GitHubJson.text(github.getAsJsonObject("pull").getAsJsonObject("current"), "mergeCommitSha"));
            var lookup = github.getAsJsonObject("deployment");
            assertEquals("OBSERVED", GitHubJson.text(lookup, "state"));
            assertEquals("SUCCESS", GitHubJson.text(lookup.getAsJsonArray("records").get(0).getAsJsonObject(), "latestState"));
            assertEquals(1, fixture.requests.stream().filter(r -> DETAIL.equals(r.path())).count());
            assertTrue(fixture.requests.stream().allMatch(r -> r.path().equals(MINT) ? r.method().equals("POST") : r.method().equals("GET")));
            var data = report.toString();
            assertFalse(data.contains("PRIVATE_"));
            assertFalse(data.contains("unrelated.invalid"));
            assertFalse(data.contains(TOKEN));
        }
    }

    @Test void keepsUnavailableCurrentStatusAndNeverOverwritesAnExistingExport() throws Exception {
        var test = prepare(false);
        recordPublication(test, test.binding().bindingIdentity());
        var report = test.export("unavailable", true, "production", null,
                () -> { throw new IOException("PRIVATE_CONFIGURATION_SENTINEL"); });
        assertEquals("PR_READ_UNAVAILABLE", GitHubJson.text(report.getAsJsonObject("github"), "gap"));
        assertEquals("RECONCILED", GitHubJson.text(report.getAsJsonObject("publication"), "state"));
        assertFalse(report.toString().contains("PRIVATE_CONFIGURATION_SENTINEL"));
        Path directory = temporary.resolve("unavailable");
        byte[] before = Files.readAllBytes(directory.resolve("evidence.json"));
        assertThrows(FileAlreadyExistsException.class, () -> test.export("unavailable", true, null, null,
                () -> fail("An existing destination must stop before credentials")));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("evidence.json")));
    }
}
