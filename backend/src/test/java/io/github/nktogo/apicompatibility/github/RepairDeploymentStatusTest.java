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

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.github.PublishedRepairStatusTest.*;
import static io.github.nktogo.apicompatibility.github.RepairDeploymentStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairDeploymentStatusTest {
    private static final String MERGE = "e".repeat(40);
    private static final String ENVIRONMENT = "Production & rollout";
    private static final String REPOSITORY_URL = "https://api.github.com/repos/sample-owner/billing";
    private static final String DEPLOYMENTS = SELECTION.path() + "/deployments?sha=" + MERGE
            + "&environment=Production+%26+rollout&per_page=10&page=1";
    @TempDir Path state;

    @BeforeAll static void key() throws Exception { GitHubRepositoryConnectionTest.keys(); }

    private static String statuses(long id) {
        return SELECTION.path() + "/deployments/" + id + "/statuses?per_page=10&page=1";
    }

    private static JsonObject merged() {
        var pull = pull();
        pull.addProperty("state", "closed");
        pull.addProperty("draft", false);
        pull.addProperty("merged", true);
        pull.addProperty("merged_at", NOW.toString());
        pull.addProperty("merge_commit_sha", MERGE);
        return pull;
    }

    private static JsonObject deployment(long id) {
        var deployment = new JsonObject();
        deployment.addProperty("id", id);
        deployment.addProperty("sha", MERGE);
        deployment.addProperty("environment", ENVIRONMENT);
        deployment.addProperty("repository_url", REPOSITORY_URL);
        deployment.addProperty("created_at", NOW.plusSeconds(id).toString());
        deployment.addProperty("statuses_url", "https://unrelated.invalid/never-follow");
        deployment.addProperty("description", "PRIVATE_DEPLOYMENT_SENTINEL");
        return deployment;
    }

    private static JsonObject status(long deployment, long id, String state, long age, String environment) {
        var result = json("{\"description\":\"PRIVATE_STATUS_SENTINEL\",\"log_url\":\"https://unrelated.invalid/log\"}");
        result.addProperty("id", id);
        result.addProperty("state", state);
        result.addProperty("environment", environment);
        result.addProperty("deployment_url", REPOSITORY_URL + "/deployments/" + deployment);
        result.addProperty("repository_url", REPOSITORY_URL);
        result.addProperty("created_at", NOW.plusSeconds(deployment + age).toString());
        return result;
    }

    private static JsonArray array(JsonObject... items) {
        var result = new JsonArray();
        for (var item : items) result.add(item);
        return result;
    }

    private RepairDeploymentStatus.Report observe(Fixture fixture) throws Exception {
        return observe(fixture, "read");
    }

    private RepairDeploymentStatus.Report observe(Fixture fixture, String deploymentPermission) throws Exception {
        var deploymentToken = fixture.replies.get(MINT).deepCopy();
        var permissions = deploymentToken.getAsJsonObject("permissions");
        permissions.remove("contents");
        if (deploymentPermission != null) permissions.addProperty("deployments", deploymentPermission);
        fixture.additionalReplies.remove(MINT);
        fixture.afterHeaders = path -> {
            if (DETAIL.equals(path)) fixture.additionalReplies.put(MINT, deploymentToken);
        };
        try (var transport = fixture.transport()) {
            return new RepairDeploymentStatus(state, Clock.fixed(NOW, ZoneOffset.UTC), () -> credentials)
                    .observe(INTENT, ENVIRONMENT, transport);
        }
    }

    @Test void observesTheExactMergedCommitWithSeparateReadScopeAndPreservesThePublication() throws Exception {
        var original = journal(state, DraftRepairPublication.State.RECONCILED);
        var retained = operation(state).resolve("publication-recovered.json");
        byte[] receipt = Files.readAllBytes(retained);
        try (var fixture = new Fixture()) {
            var detail = merged();
            detail.getAsJsonObject("base").addProperty("sha", "d".repeat(40));
            fixture.replies.put(DETAIL, detail);
            fixture.additionalReplies.put(DEPLOYMENTS, array(deployment(31), deployment(32)));
            fixture.additionalReplies.put(statuses(31), array(
                    status(31, 600, "failure", 1, ENVIRONMENT),
                    status(31, 500, "success", 3, "production & rollout"),
                    status(31, 700, "in_progress", 2, ENVIRONMENT)));
            fixture.additionalReplies.put(statuses(32), array());
            var report = observe(fixture);
            assertEquals(OBSERVED, report.state());
            assertEquals(original, report.pullRequest().publication().recovered());
            assertEquals(MERGE, report.pullRequest().current().mergeCommitSha());
            assertEquals(List.of(GitHubPullRequestLookup.Mismatch.BASE_SHA), report.pullRequest().current().pull().mismatches());
            var observed = report.deployments().getFirst();
            assertEquals(RepairDeploymentStatus.DeploymentState.SUCCESS, observed.latestState());
            assertEquals(List.of(500L, 700L, 600L), observed.statuses().stream().map(RepairDeploymentStatus.Status::id).toList());
            assertNull(report.deployments().get(1).latestState());
            var paths = fixture.requests.stream().map(Captured::path).toList();
            assertEquals(List.of("/app", INSTALLATION, MINT, SCOPE, DETAIL, "/app", INSTALLATION, MINT, SCOPE, DEPLOYMENTS,
                    statuses(31), statuses(32)), paths);
            assertTrue(fixture.requests.stream().allMatch(request -> request.path().equals(MINT)
                    ? request.method().equals("POST") : request.method().equals("GET") && request.body().length == 0));
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"),
                    GitHubJson.parse(fixture.requests.get(2).body(), 8192));
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"deployments\":\"read\",\"metadata\":\"read\"}}"),
                    GitHubJson.parse(fixture.requests.get(7).body(), 8192));
            assertArrayEquals(receipt, Files.readAllBytes(retained));
            String output = GitHubJson.JSON.toJson(report);
            assertFalse(output.contains("PRIVATE_"));
            assertFalse(output.contains(TOKEN));
            assertFalse(output.contains("unrelated.invalid"));
        }
    }

    @Test void neverLoadsCredentialsForAnUnconfirmedPublication() throws Exception {
        journal(state, DraftRepairPublication.State.UNKNOWN);
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            var result = new RepairDeploymentStatus(state, Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> fail("Should not load credentials")).observe(INTENT, ENVIRONMENT, transport);
            assertEquals(NOT_READY, result.state());
            assertEquals(RepairDeploymentStatus.Reason.PUBLICATION_UNCONFIRMED, result.reason());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "closed", "missing-merge-sha", "head-replaced", "contradictory-draft"})
    void doesNotReadDeploymentsWithoutAUsableMerge(String condition) throws Exception {
        journal(state, DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            var detail = merged();
            var reason = switch (condition) {
                case "open", "closed" -> {
                    detail.addProperty("state", condition);
                    detail.addProperty("merged", false);
                    detail.add("merged_at", JsonNull.INSTANCE);
                    yield RepairDeploymentStatus.Reason.PR_NOT_MERGED;
                }
                case "missing-merge-sha" -> {
                    detail.remove("merge_commit_sha");
                    yield RepairDeploymentStatus.Reason.MERGE_SHA_MISSING;
                }
                case "head-replaced" -> {
                    detail.getAsJsonObject("head").addProperty("sha", "d".repeat(40));
                    yield RepairDeploymentStatus.Reason.PR_DRIFT;
                }
                case "contradictory-draft" -> {
                    detail.addProperty("draft", true);
                    yield RepairDeploymentStatus.Reason.PR_STATUS_INCOMPLETE;
                }
                default -> throw new AssertionError(condition);
            };
            fixture.replies.put(DETAIL, detail);
            var report = observe(fixture);
            assertEquals(reason, report.reason());
            assertEquals(reason == RepairDeploymentStatus.Reason.PR_NOT_MERGED ? NOT_READY : INCOMPLETE, report.state());
            assertEquals(5, fixture.requests.size());
            assertTrue(report.deployments().isEmpty());
        }
    }

    @Test void distinguishesAnEmptyPageFromAccessFailureAndRequiresExactReadOnlyPermission() throws Exception {
        journal(state, DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            fixture.replies.put(DETAIL, merged());
            fixture.additionalReplies.put(DEPLOYMENTS, array());
            assertEquals(NONE_OBSERVED, observe(fixture).state());
            for (String unusableGrant : new String[]{null, "write"}) {
                fixture.requests.clear();
                var report = observe(fixture, unusableGrant);
                assertEquals(INCOMPLETE, report.state());
                assertEquals(GitHubRepositoryConnection.Stage.TOKEN, report.failure().stage());
                assertEquals(8, fixture.requests.size());
            }
            fixture.faultPath = DEPLOYMENTS;
            fixture.faultStatus = 404;
            var inaccessible = observe(fixture);
            assertEquals(INCOMPLETE, inaccessible.state());
            assertEquals(404, inaccessible.failure().httpStatus());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sha", "environment", "repository_url"})
    void rejectsAnUnrelatedDeploymentBeforeReadingStatuses(String field) throws Exception {
        journal(state, DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            fixture.replies.put(DETAIL, merged());
            var wrong = deployment(31);
            wrong.addProperty(field, "different");
            fixture.additionalReplies.put(DEPLOYMENTS, array(deployment(32), wrong));
            var report = observe(fixture);
            assertEquals(INCOMPLETE, report.state());
            assertEquals(RepairDeploymentStatus.Reason.INVALID_DEPLOYMENT_METADATA, report.reason());
            assertEquals(10, fixture.requests.size());
        }
    }

    @Test void keepsCompletedReceiptsAndUnattemptedReadsVisibleAfterRateLimiting() throws Exception {
        journal(state, DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            fixture.replies.put(DETAIL, merged());
            fixture.additionalReplies.put(DEPLOYMENTS, array(deployment(31), deployment(32), deployment(33)));
            fixture.additionalReplies.put(statuses(31), array(status(31, 1, "inactive", 1, ENVIRONMENT)));
            fixture.faultPath = statuses(32);
            fixture.faultStatus = 429;
            fixture.faultHeaders.put("Retry-After", "90");
            var report = observe(fixture);
            assertEquals(INCOMPLETE, report.state());
            assertEquals(RepairDeploymentStatus.Reason.PARTIAL_STATUSES, report.reason());
            assertEquals(RepairDeploymentStatus.DeploymentState.INACTIVE, report.deployments().getFirst().latestState());
            assertEquals(NOW.plusSeconds(90), report.deployments().get(1).failure().retryNotBefore());
            assertEquals(RepairDeploymentStatus.StatusIssue.NOT_READ, report.deployments().get(2).issue());
            assertTrue(fixture.requests.stream().noneMatch(request -> request.path().equals(statuses(33))));
        }
    }

    @Test void refusesToProjectAStatusWhenTheLatestStateOrEnvironmentIsUnresolved() throws Exception {
        journal(state, DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            fixture.replies.put(DETAIL, merged());
            fixture.additionalReplies.put(DEPLOYMENTS, array(deployment(31), deployment(32), deployment(33), deployment(34)));
            fixture.additionalReplies.put(statuses(31), array(status(31, 1, "success", 1, ENVIRONMENT),
                    status(31, 2, "inactive", 1, ENVIRONMENT)));
            fixture.additionalReplies.put(statuses(32), array(status(32, 1, "success", 2, "another environment"),
                    status(32, 2, "pending", 1, ENVIRONMENT)));
            fixture.additionalReplies.put(statuses(33), array(status(34, 1, "success", 1, ENVIRONMENT)));
            var omitted = status(34, 1, "success", 2, ENVIRONMENT);
            omitted.remove("environment");
            fixture.additionalReplies.put(statuses(34), array(omitted, status(34, 2, "pending", 1, ENVIRONMENT)));
            var report = observe(fixture);
            assertEquals(INCOMPLETE, report.state());
            assertEquals(List.of(RepairDeploymentStatus.StatusIssue.AMBIGUOUS_LATEST,
                    RepairDeploymentStatus.StatusIssue.ENVIRONMENT_CHANGED, RepairDeploymentStatus.StatusIssue.INVALID_METADATA,
                    RepairDeploymentStatus.StatusIssue.ENVIRONMENT_UNREPORTED),
                    report.deployments().stream().map(RepairDeploymentStatus.Deployment::issue).toList());
            assertTrue(report.deployments().stream().allMatch(value -> value.latestState() == null));
            assertFalse(report.deployments().get(1).statuses().getFirst().selectedEnvironment());
            assertNull(report.deployments().get(3).statuses().getFirst().selectedEnvironment());
        }
    }

    @Test void keepsBoundedPagesIncompleteAndNeverFollowsPaginationUrls() throws Exception {
        journal(state, DraftRepairPublication.State.CREATED);
        try (var fixture = new Fixture()) {
            fixture.replies.put(DETAIL, merged());
            fixture.faultPath = DEPLOYMENTS;
            fixture.faultStatus = 200;
            fixture.faultBody = GitHubJson.JSON.toJson(array(deployment(31))).getBytes(StandardCharsets.UTF_8);
            fixture.faultHeaders.put("Link", "<https://unrelated.invalid/deployments?page=2>; rel=\"next\"");
            var entries = new JsonArray();
            for (int id = 1; id <= 10; id++) entries.add(status(31, id, "success", id, ENVIRONMENT));
            fixture.additionalReplies.put(statuses(31), entries);
            var report = observe(fixture);
            assertEquals(INCOMPLETE, report.state());
            assertEquals(RepairDeploymentStatus.Reason.DEPLOYMENT_LIMIT, report.reason());
            var observed = report.deployments().getFirst();
            assertEquals(RepairDeploymentStatus.StatusIssue.STATUS_LIMIT, observed.issue());
            assertEquals(10, observed.statuses().size());
            assertNull(observed.latestState());
            assertEquals(11, fixture.requests.size());
        }
    }
}
