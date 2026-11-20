package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.*;
import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubPullRequestLookupTest {
    private static final String OPERATION = "b876052c-4c83-4d18-80a1-f9e2a35bcfa3";
    private static final Intent INTENT = new Intent(1, 10, SELECTION, OPERATION, "release/2026.1", "c".repeat(40));
    private static final String PATH = "/repos/sample-owner/billing/pulls?state=all&head=sample-owner%3Aworkbench%2Frepair-"
            + OPERATION + "&base=release%2F2026.1&per_page=10&page=1";
    @TempDir Path temporary;

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private static Result lookup(Fixture fixture, Intent intent) throws Exception {
        try (var transport = fixture.transport()) {
            return new GitHubPullRequestLookup(new GitHubRepositoryConnection(Clock.fixed(NOW, ZoneOffset.UTC)))
                    .lookup(intent, credentials, transport);
        }
    }

    private static void reply(Fixture fixture, JsonObject... rows) {
        fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("pull_requests", "read");
        fixture.faultPath = PATH;
        fixture.faultStatus = 200;
        var array = new JsonArray();
        for (var row : rows) array.add(row);
        fixture.faultBody = array.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject pull(long number) {
        var repo = json("{\"id\":40,\"full_name\":\"sample-owner/billing\",\"owner\":{\"id\":30,\"login\":\"sample-owner\"}}");
        var pull = json("{\"state\":\"open\",\"draft\":true,\"merged_at\":null,\"base\":{},\"head\":{},\"html_url\":\"https://attacker.invalid/ignored\"}");
        pull.addProperty("number", number);
        pull.addProperty("body", "PRIVATE_PR_BODY_SENTINEL\n\n" + describe(INTENT).marker());
        var base = pull.getAsJsonObject("base");
        base.add("repo", repo);
        base.addProperty("ref", INTENT.baseRef());
        base.addProperty("sha", SHA);
        var head = pull.getAsJsonObject("head");
        head.add("repo", repo.deepCopy());
        head.addProperty("ref", INTENT.headRef());
        head.addProperty("sha", INTENT.headSha());
        return pull;
    }

    @Test void matchesExactDraftUsingOneReadOnlyRepositoryAndSanitizesTheResult() throws Exception {
        try (var fixture = new Fixture()) {
            reply(fixture, pull(17));
            var result = lookup(fixture, INTENT);
            assertEquals(State.MATCHED, result.state());
            assertEquals(List.of(new ObservedPull(17, PullState.OPEN, true, List.of())), result.observed());
            assertEquals(NOW, result.observedAt());
            assertEquals(List.of("/app", INSTALLATION, MINT, SCOPE, COMMIT, PATH), fixture.requests.stream().map(Captured::path).toList());
            assertEquals(List.of("GET", "GET", "POST", "GET", "GET", "GET"), fixture.requests.stream().map(Captured::method).toList());
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\",\"pull_requests\":\"read\"}}"),
                    GitHubJson.parse(fixture.requests.get(2).body(), 8192));
            assertEquals("Bearer " + TOKEN, fixture.requests.getLast().authorization());
            assertFalse(GitHubJson.JSON.toJson(result).contains("PRIVATE_"));
            assertFalse(GitHubJson.JSON.toJson(result).contains(TOKEN));
            assertFalse(GitHubJson.JSON.toJson(result).contains("attacker.invalid"));
        }
    }

    @Test void readsClosedAndMergedResultsWithoutRequiringCurrentDraftStatus() throws Exception {
        try (var fixture = new Fixture()) {
            var row = pull(17);
            row.addProperty("state", "closed");
            row.remove("draft");
            reply(fixture, row);
            var closed = lookup(fixture, INTENT);
            assertEquals(State.MATCHED, closed.state());
            assertEquals(new ObservedPull(17, PullState.CLOSED, null, List.of()), closed.observed().getFirst());
            row.addProperty("merged_at", "2026-11-14T00:00:00Z");
            reply(fixture, row);
            var merged = lookup(fixture, INTENT);
            assertEquals(State.MATCHED, merged.state());
            assertEquals(PullState.MERGED, merged.observed().getFirst().state());
        }
    }

    @Test void flagsMovedRefsForksAndUnrelatedMarkers() throws Exception {
        try (var fixture = new Fixture()) {
            var row = pull(17);
            row.getAsJsonObject("base").addProperty("sha", TREE);
            var head = row.getAsJsonObject("head");
            head.add("repo", JsonNull.INSTANCE);
            head.addProperty("ref", "other");
            head.addProperty("sha", TREE);
            row.addProperty("body", "Copied marker: " + describe(INTENT).marker());
            reply(fixture, row);
            var result = lookup(fixture, INTENT);
            assertEquals(State.CONFLICT, result.state());
            assertEquals(List.of(Mismatch.REPOSITORY, Mismatch.HEAD_REF, Mismatch.BASE_SHA, Mismatch.HEAD_SHA, Mismatch.MARKER),
                    result.observed().getFirst().mismatches());
        }
    }

    @Test void distinguishesAbsenceFromAnIncompleteOrAmbiguousLookup() throws Exception {
        try (var fixture = new Fixture()) {
            reply(fixture);
            assertEquals(State.NOT_OBSERVED, lookup(fixture, INTENT).state());
            fixture.faultHeaders.put("Link", "<https://attacker.invalid/next>; rel=\"next\"");
            assertEquals(Reason.SEARCH_LIMIT, lookup(fixture, INTENT).reason());
            assertEquals(PATH, fixture.requests.getLast().path());
            fixture.faultHeaders.clear();
            reply(fixture, java.util.stream.LongStream.range(1, 11).mapToObj(GitHubPullRequestLookupTest::pull).toArray(JsonObject[]::new));
            assertEquals(Reason.SEARCH_LIMIT, lookup(fixture, INTENT).reason());
            reply(fixture, pull(17), pull(18));
            assertEquals(Reason.MULTIPLE_PULL_REQUESTS, lookup(fixture, INTENT).reason());
            reply(fixture, pull(17), pull(17));
            assertEquals(Reason.INVALID_RESPONSE, lookup(fixture, INTENT).reason());
            reply(fixture, pull(17), new JsonObject());
            assertEquals(Reason.INVALID_RESPONSE, lookup(fixture, INTENT).reason());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "write"})
    void refusesMissingOrWritePullRequestPermissionBeforeLookup(String permission) throws Exception {
        try (var fixture = new Fixture()) {
            if (permission.equals("write")) fixture.replies.get(MINT).getAsJsonObject("permissions").addProperty("pull_requests", "write");
            var result = lookup(fixture, INTENT);
            assertEquals(State.INCOMPLETE, result.state());
            assertEquals(GitHubRepositoryConnection.Stage.TOKEN, result.failure().stage());
            assertEquals(MINT, fixture.requests.getLast().path());
        }
    }

    @Test void refusesWrongAppBeforeMintAndKeepsRateLimitOutcomeUnknown() throws Exception {
        try (var fixture = new Fixture()) {
            var otherApp = new Intent(1, 11, SELECTION, OPERATION, INTENT.baseRef(), INTENT.headSha());
            var refused = lookup(fixture, otherApp);
            assertEquals(GitHubRepositoryConnection.Stage.APP, refused.failure().stage());
            assertEquals(List.of("/app"), fixture.requests.stream().map(Captured::path).toList());
            reply(fixture);
            fixture.faultStatus = 429;
            fixture.faultHeaders.put("Retry-After", "90");
            var limited = lookup(fixture, INTENT);
            assertEquals(State.INCOMPLETE, limited.state());
            assertEquals(GitHubRepositoryConnection.Stage.PULL_REQUESTS, limited.failure().stage());
            assertEquals(NOW.plusSeconds(90), limited.failure().retryNotBefore());
            assertEquals(1, fixture.requests.stream().filter(r -> r.path().equals(PATH)).count());
        }
    }

    @Test void intentionFileIsStrictAndMarkerBindsTheExactRevisions() throws Exception {
        var file = temporary.resolve("intent.json");
        Files.writeString(file, GitHubJson.JSON.toJson(INTENT));
        assertEquals(describe(INTENT), describe(loadIntent(file)));
        var changed = new Intent(1, 10, SELECTION, OPERATION, INTENT.baseRef(), "d".repeat(40));
        assertNotEquals(describe(INTENT).marker(), describe(changed).marker());
        var data = GitHubJson.JSON.toJsonTree(INTENT).getAsJsonObject();
        data.addProperty("baseRef", "main&state=open");
        Files.writeString(file, data.toString());
        assertThrows(IllegalArgumentException.class, () -> loadIntent(file));
        data.addProperty("baseRef", "main");
        data.addProperty("body", "unexpected untrusted input");
        Files.writeString(file, data.toString());
        assertThrows(IllegalArgumentException.class, () -> loadIntent(file));
    }
}
