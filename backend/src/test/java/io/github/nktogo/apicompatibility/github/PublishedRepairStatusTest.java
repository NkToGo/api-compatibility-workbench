package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.github.PublishedRepairStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;

class PublishedRepairStatusTest {
    private static final String HEAD_SHA = "c".repeat(40);
    private static final String DETAIL = "/repos/sample-owner/billing/pulls/17";
    private static final GitHubPullRequestLookup.Intent INTENT = new GitHubPullRequestLookup.Intent(1, 10, SELECTION,
            "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", "main", HEAD_SHA);
    @TempDir Path state;

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private Path operation() {
        return state.resolve("repair-publications").resolve(INTENT.operationId());
    }

    private static JsonObject pull() {
        var repo = json("{\"id\":40,\"full_name\":\"sample-owner/billing\",\"owner\":{\"id\":30,\"login\":\"sample-owner\"}}");
        var detail = json("{\"number\":17,\"state\":\"open\",\"draft\":true,\"merged\":false,\"merged_at\":null,\"base\":{},\"head\":{},\"html_url\":\"https://attacker.invalid/ignored\"}");
        detail.addProperty("body", "PRIVATE_BODY_SENTINEL\n\n" + GitHubPullRequestLookup.describe(INTENT).marker());
        detail.addProperty("title", "PRIVATE_TITLE_SENTINEL");
        var base = detail.getAsJsonObject("base");
        base.add("repo", repo);
        base.addProperty("ref", INTENT.baseRef());
        base.addProperty("sha", SHA);
        var head = detail.getAsJsonObject("head");
        head.add("repo", repo.deepCopy());
        head.addProperty("ref", INTENT.headRef());
        head.addProperty("sha", HEAD_SHA);
        return detail;
    }

    private DraftRepairPublication.Receipt journal(DraftRepairPublication.State publicationState) throws Exception {
        Files.createDirectories(operation());
        var attempt = new DraftRepairPublication.Attempt(1, INTENT, "a".repeat(64),
                GitSourceObjects.sha256(DraftRepairPublication.request(INTENT)), NOW.toString());
        String encoded = GitHubJson.JSON.toJson(attempt) + "\n";
        Files.writeString(operation().resolve("publication-started.json"), encoded);
        String identity = GitSourceObjects.sha256(encoded.getBytes(StandardCharsets.UTF_8));
        var pull = GitHubPullRequestLookup.inspect(GitHubPullRequestLookup.describe(INTENT), pull());
        var receipt = new DraftRepairPublication.Receipt(1, identity, publicationState, pull,
                publicationState == DraftRepairPublication.State.RECONCILED ? 200 : 201,
                publicationState == DraftRepairPublication.State.UNKNOWN ? GitHubRepositoryConnection.Code.INVALID_METADATA : null,
                null, NOW.toString(), null);
        String file = publicationState == DraftRepairPublication.State.RECONCILED ? "publication-recovered.json" : "publication-response.json";
        Files.writeString(operation().resolve(file), GitHubJson.JSON.toJson(receipt) + "\n");
        return receipt;
    }

    private PublishedRepairStatus.Report observe(Fixture fixture) throws Exception {
        try (var transport = fixture.transport()) {
            return new PublishedRepairStatus(state, Clock.fixed(NOW, ZoneOffset.UTC), () -> credentials).observe(INTENT, transport);
        }
    }

    @Test void tracksTheNumberedPullWithContentsReadAndKeepsDriftSeparateFromPublication() throws Exception {
        var original = journal(DraftRepairPublication.State.CREATED);
        Path launch = operation().resolve("publication-started.json"), receipt = operation().resolve("publication-response.json");
        byte[] launchBytes = Files.readAllBytes(launch), receiptBytes = Files.readAllBytes(receipt);
        try (var fixture = new Fixture()) {
            fixture.replies.remove(COMMIT);
            var detail = pull();
            detail.addProperty("draft", false);
            detail.addProperty("body", "PRIVATE_BODY_SENTINEL");
            detail.getAsJsonObject("base").addProperty("ref", "other-base");
            detail.getAsJsonObject("head").addProperty("sha", "d".repeat(40));
            detail.addProperty("merge_commit_sha", "e".repeat(40));
            fixture.replies.put(DETAIL, detail);
            var report = observe(fixture);
            assertEquals(OBSERVED, report.state());
            assertEquals(original, report.publication().response());
            assertTrue(original.pull().draft());
            assertFalse(report.current().pull().draft());
            assertEquals(GitHubPullRequestLookup.PullState.OPEN, report.current().pull().state());
            assertEquals(List.of(GitHubPullRequestLookup.Mismatch.BASE_REF, GitHubPullRequestLookup.Mismatch.HEAD_SHA,
                    GitHubPullRequestLookup.Mismatch.MARKER), report.current().pull().mismatches());
            assertEquals("d".repeat(40), report.current().headSha());
            assertNull(report.current().mergeCommitSha());
            assertEquals(List.of("/app", INSTALLATION, MINT, SCOPE, DETAIL), fixture.requests.stream().map(Captured::path).toList());
            assertEquals(List.of("GET", "GET", "POST", "GET", "GET"), fixture.requests.stream().map(Captured::method).toList());
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"),
                    GitHubJson.parse(fixture.requests.get(2).body(), 8192));
            assertArrayEquals(launchBytes, Files.readAllBytes(launch));
            assertArrayEquals(receiptBytes, Files.readAllBytes(receipt));
            var output = GitHubJson.JSON.toJson(report);
            assertFalse(output.contains("PRIVATE_"));
            assertFalse(output.contains(TOKEN));
            assertFalse(output.contains("attacker.invalid"));
        }
    }

    @Test void followsReconciledPublicationThroughClosedAndMergedStatesEvenIfHeadRepoIsGone() throws Exception {
        var recovered = journal(DraftRepairPublication.State.RECONCILED);
        try (var fixture = new Fixture()) {
            var detail = pull();
            detail.addProperty("state", "closed");
            detail.getAsJsonObject("head").add("repo", JsonNull.INSTANCE);
            fixture.replies.put(DETAIL, detail);
            var closed = observe(fixture);
            assertEquals(OBSERVED, closed.state());
            assertEquals(recovered, closed.publication().recovered());
            assertEquals(GitHubPullRequestLookup.PullState.CLOSED, closed.current().pull().state());
            assertEquals(List.of(GitHubPullRequestLookup.Mismatch.REPOSITORY), closed.current().pull().mismatches());
            detail.addProperty("merged", true);
            detail.addProperty("merged_at", NOW.toString());
            detail.addProperty("merge_commit_sha", "e".repeat(40));
            var merged = observe(fixture);
            assertEquals(OBSERVED, merged.state());
            assertEquals(GitHubPullRequestLookup.PullState.MERGED, merged.current().pull().state());
            assertEquals(NOW, merged.current().mergedAt());
            assertEquals("e".repeat(40), merged.current().mergeCommitSha());
        }
    }

    @Test void keepsUnavailableOrRateLimitedReadsDistinctFromClosed() throws Exception {
        var original = journal(DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            fixture.faultPath = DETAIL;
            fixture.faultStatus = 404;
            var missing = observe(fixture);
            assertEquals(INCOMPLETE, missing.state());
            assertEquals(original, missing.publication().response());
            assertNull(missing.current());
            assertEquals(404, missing.failure().httpStatus());
            fixture.faultStatus = 429;
            fixture.faultHeaders.put("Retry-After", "90");
            var limited = observe(fixture);
            assertEquals(INCOMPLETE, limited.state());
            assertEquals(NOW.plusSeconds(90), limited.failure().retryNotBefore());
            assertEquals(2, fixture.requests.stream().filter(r -> DETAIL.equals(r.path())).count());
        }
    }

    @Test void refusesWrongNumberWrongBaseRepoAndContradictoryMergeStatus() throws Exception {
        journal(DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            var detail = pull();
            fixture.replies.put(DETAIL, detail);
            detail.addProperty("number", 18);
            assertEquals(PublishedRepairStatus.Reason.IDENTITY_MISMATCH, observe(fixture).reason());
            detail.addProperty("number", 17);
            detail.getAsJsonObject("base").getAsJsonObject("repo").addProperty("id", 41);
            assertEquals(PublishedRepairStatus.Reason.IDENTITY_MISMATCH, observe(fixture).reason());
            detail.getAsJsonObject("base").getAsJsonObject("repo").addProperty("id", 40);
            detail.addProperty("merged", true);
            assertEquals(PublishedRepairStatus.Reason.INVALID_METADATA, observe(fixture).reason());
        }
    }

    @Test void usesNeitherCredentialsNorAnUnconfirmedNumber() throws Exception {
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            var status = new PublishedRepairStatus(state, Clock.fixed(NOW, ZoneOffset.UTC), () -> fail("Should not load credentials"));
            assertEquals(NOT_CONFIRMED, status.observe(INTENT, transport).state());
            journal(DraftRepairPublication.State.UNKNOWN);
            var result = status.observe(INTENT, transport);
            assertEquals(NOT_CONFIRMED, result.state());
            assertNull(result.current());
            assertTrue(fixture.requests.isEmpty());
        }
    }
}
