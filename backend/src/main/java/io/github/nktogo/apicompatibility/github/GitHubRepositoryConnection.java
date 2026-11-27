package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/** Host-side read-only probe. A returned snapshot is metadata, never an execution or publication grant. */
public final class GitHubRepositoryConnection {
    public record Selection(int formatVersion, long installationId, long accountId, long repositoryId,
                            String owner, String name, String commitSha) {
        public Selection {
            if (formatVersion != 1 || installationId <= 0 || accountId <= 0 || repositoryId <= 0
                    || owner == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
                    || name == null || !name.matches("[A-Za-z0-9_.-]{1,100}") || name.equals(".") || name.equals("..")
                    || commitSha == null || !commitSha.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("Invalid repository selection");
        }
        String path() { return "/repos/" + owner + "/" + name; }
        String fullName() { return owner + "/" + name; }
    }
    public record Snapshot(int formatVersion, long appId, Selection selection, String treeSha,
                           Instant observedAt, Instant tokenExpiresAt, String authority,
                           boolean compatibilityAssessed) {}
    public enum Stage { APP, INSTALLATION, TOKEN, REPOSITORY_SCOPE, REVISION, SOURCE_TREE, SOURCE_BLOB, PULL_REQUESTS, BRANCH_REF,
        CREATE_PULL_REQUEST, PULL_REQUEST_DETAIL, PULL_REQUEST_MERGE, DEPLOYMENTS, DEPLOYMENT_STATUSES }
    enum PullAccess { NONE, READ, WRITE }
    public enum Code { ACCESS_DENIED_OR_RATE_LIMITED, AUTHENTICATION_FAILED, NOT_FOUND_OR_NOT_INSTALLED,
        SCOPE_REJECTED, REDIRECT_REFUSED, PROVIDER_UNAVAILABLE, UNEXPECTED_STATUS,
        INVALID_METADATA, IDENTITY_OR_SCOPE_MISMATCH, TOKEN_EXPIRED, TRANSPORT_INCOMPLETE }
    public record Failure(Code code, Stage stage, int httpStatus, Instant retryNotBefore,
                          boolean tokenMayRemainActive) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }
    public static final class ConnectionFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final Failure failure;
        private ConnectionFailure(Failure failure) {
            super(failure.code() + " at " + failure.stage()); this.failure = failure;
        }
        public Failure failure() { return failure; }
    }

    private final Clock clock;
    public GitHubRepositoryConnection() { this(Clock.systemUTC()); }
    GitHubRepositoryConnection(Clock clock) { this.clock = clock; }

    public Snapshot inspect(Selection selection, GitHubAppCredentials credentials) throws ConnectionFailure, InterruptedException {
        try (var transport = new GitHubTransport()) { return inspect(selection, credentials, transport); }
    }
    Snapshot inspect(Selection selection, GitHubAppCredentials credentials, GitHubTransport transport)
            throws ConnectionFailure, InterruptedException {
        return openVerified(selection, credentials, transport).snapshot();
    }
    VerifiedRepository openVerified(Selection selection, GitHubAppCredentials credentials, GitHubTransport transport)
            throws ConnectionFailure, InterruptedException {
        return openVerified(selection, credentials, transport, null, PullAccess.NONE);
    }
    VerifiedRepository openVerified(Selection selection, GitHubAppCredentials credentials, GitHubTransport transport,
                                    Long expectedAppId, PullAccess pullAccess) throws ConnectionFailure, InterruptedException {
        var access = authenticateScope(selection, credentials, transport, expectedAppId, sourcePermissions(pullAccess));
        live(access.expires(), Stage.REVISION);
        var commit = request(transport, Stage.REVISION, "GET", selection.path() + "/git/commits/" + selection.commitSha(),
                new byte[0], access.token(), 200);
        try {
            String tree = GitHubJson.text(GitHubJson.object(commit.get("tree")), "sha");
            check(GitHubJson.text(commit, "sha").equals(selection.commitSha()) && tree.matches("[0-9a-f]{40}"), Stage.REVISION);
            live(access.expires(), Stage.REVISION);
            return new VerifiedRepository(new Snapshot(1, access.appId(), selection, tree, clock.instant(), access.expires(),
                    "AUTHENTICATED_GITHUB_METADATA", false), access.token(), transport, pullAccess);
        } catch (RuntimeException e) { throw failed(Code.INVALID_METADATA, Stage.REVISION, 0, null, false); }
    }

    PullReader openPull(GitHubPullRequestLookup.Intent intent, long number, GitHubAppCredentials credentials,
                        GitHubTransport transport) throws ConnectionFailure, InterruptedException {
        GitHubJson.require(number > 0);
        var selection = intent.selection();
        var access = authenticateScope(selection, credentials, transport, intent.appId(), sourcePermissions(PullAccess.NONE));
        return new PullReader(selection, number, access, transport);
    }

    final class PullReader {
        private final Selection selection;
        private final long number;
        private final ScopedAccess access;
        private final GitHubTransport transport;

        private PullReader(Selection selection, long number, ScopedAccess access, GitHubTransport transport) {
            this.selection = selection;
            this.number = number;
            this.access = access;
            this.transport = transport;
        }

        JsonObject detail() throws ConnectionFailure, InterruptedException {
            Stage stage = Stage.PULL_REQUEST_DETAIL;
            live(access.expires(), stage);
            // A numbered PR can still be inspected when the original base commit is no longer reachable.
            var result = request(transport, stage, "GET", selection.path() + "/pulls/" + number, new byte[0], access.token(), 200);
            live(access.expires(), stage);
            return result;
        }

        JsonObject mergeMetadata() throws ConnectionFailure, InterruptedException {
            Stage stage = Stage.PULL_REQUEST_MERGE;
            var variables = new JsonObject();
            variables.addProperty("owner", selection.owner());
            variables.addProperty("repository", selection.name());
            variables.addProperty("number", number);
            var query = new JsonObject();
            query.add("variables", variables);
            query.addProperty("query", """
                    query($owner: String!, $repository: String!, $number: Int!) {
                      repository(owner: $owner, name: $repository) {
                        databaseId
                        pullRequest(number: $number) {
                          number merged mergedAt isDraft baseRefName baseRefOid headRefName headRefOid
                          mergeCommit { oid }
                        }
                      }
                    }
                    """);
            live(access.expires(), stage);
            var result = request(transport, stage, "POST", "/graphql",
                    GitHubJson.JSON.toJson(query).getBytes(StandardCharsets.UTF_8), access.token(), 200);
            live(access.expires(), stage);
            return result;
        }
    }

    static final int DEPLOYMENT_PAGE_SIZE = 10;
    record DeploymentPage(JsonArray items, boolean possiblyTruncated, Instant observedAt) {}

    DeploymentReader openDeployments(GitHubPullRequestLookup.Intent intent, GitHubAppCredentials credentials,
                                     GitHubTransport transport) throws ConnectionFailure, InterruptedException {
        var permissions = new JsonObject();
        permissions.addProperty("metadata", "read");
        permissions.addProperty("deployments", "read");
        var selection = intent.selection();
        var access = authenticateScope(selection, credentials, transport, intent.appId(), permissions);
        return new DeploymentReader(selection, access, transport);
    }

    final class DeploymentReader {
        private final Selection selection;
        private final ScopedAccess access;
        private final GitHubTransport transport;

        private DeploymentReader(Selection selection, ScopedAccess access, GitHubTransport transport) {
            this.selection = selection;
            this.access = access;
            this.transport = transport;
        }

        DeploymentPage deployments(String sha, String environment) throws ConnectionFailure, InterruptedException {
            GitHubJson.require(sha.matches("[0-9a-f]{40}"));
            String encoded = URLEncoder.encode(environment, StandardCharsets.UTF_8);
            return page(Stage.DEPLOYMENTS, "/deployments?sha=" + sha + "&environment=" + encoded + "&");
        }

        DeploymentPage statuses(long id) throws ConnectionFailure, InterruptedException {
            GitHubJson.require(id > 0);
            return page(Stage.DEPLOYMENT_STATUSES, "/deployments/" + id + "/statuses?");
        }

        private DeploymentPage page(Stage stage, String suffix) throws ConnectionFailure, InterruptedException {
            live(access.expires(), stage);
            String path = selection.path() + suffix + "per_page=" + DEPLOYMENT_PAGE_SIZE + "&page=1";
            var response = requestResponse(transport, stage, "GET", path, new byte[0], access.token(), 200);
            live(access.expires(), stage);
            try {
                var items = GitHubJson.parseArray(response.body(), GitHubTransport.MAX_BYTES);
                GitHubJson.require(items.size() <= DEPLOYMENT_PAGE_SIZE);
                return new DeploymentPage(items, items.size() == DEPLOYMENT_PAGE_SIZE
                        || response.headers().firstValue("Link").isPresent(), clock.instant());
            } catch (RuntimeException e) {
                throw failed(Code.INVALID_METADATA, stage, 200, null, false);
            }
        }
    }

    private record ScopedAccess(long appId, String token, Instant expires) {
        @Override public String toString() { return "ScopedAccess[redacted]"; }
    }

    private static JsonObject sourcePermissions(PullAccess pullAccess) {
        var permissions = new JsonObject();
        permissions.addProperty("contents", "read");
        permissions.addProperty("metadata", "read");
        if (pullAccess != PullAccess.NONE)
            permissions.addProperty("pull_requests", pullAccess == PullAccess.READ ? "read" : "write");
        return permissions;
    }

    private ScopedAccess authenticateScope(Selection selection, GitHubAppCredentials credentials, GitHubTransport transport,
                                           Long expectedAppId, JsonObject permissions) throws ConnectionFailure, InterruptedException {
        java.util.Objects.requireNonNull(selection, "selection"); java.util.Objects.requireNonNull(credentials, "credentials");
        String jwt = credentials.jwt(clock.instant());
        var app = request(transport, Stage.APP, "GET", "/app", new byte[0], jwt, 200);
        long appId;
        try {
            appId = GitHubJson.number(app, "id");
            check(appId > 0 && credentials.matches(app) && (expectedAppId == null || appId == expectedAppId), Stage.APP);
        }
        catch (RuntimeException e) { throw failed(Code.INVALID_METADATA, Stage.APP, 0, null, false); }

        var installation = request(transport, Stage.INSTALLATION, "GET", "/app/installations/" + selection.installationId(), new byte[0], jwt, 200);
        try {
            var account = GitHubJson.object(installation.get("account"));
            check(GitHubJson.number(installation, "id") == selection.installationId()
                    && GitHubJson.number(installation, "app_id") == appId
                    && GitHubJson.number(account, "id") == selection.accountId()
                    && GitHubJson.text(account, "login").equalsIgnoreCase(selection.owner())
                    && GitHubJson.text(installation, "repository_selection").equals("selected")
                    && installation.has("suspended_at") && installation.get("suspended_at").isJsonNull(), Stage.INSTALLATION);
        } catch (RuntimeException e) { throw failed(Code.INVALID_METADATA, Stage.INSTALLATION, 0, null, false); }

        var tokenRequest = new JsonObject(); tokenRequest.add("repository_ids", GitHubJson.JSON.toJsonTree(List.of(selection.repositoryId())));
        tokenRequest.add("permissions", permissions);
        var tokenResponse = request(transport, Stage.TOKEN, "POST", "/app/installations/" + selection.installationId() + "/access_tokens",
                GitHubJson.JSON.toJson(tokenRequest).getBytes(StandardCharsets.UTF_8), jwt, 201);
        String token; Instant expires;
        try {
            var granted = GitHubJson.object(tokenResponse.get("permissions"));
            check(permissions.entrySet().containsAll(granted.entrySet())
                    && permissions.keySet().stream().allMatch(name -> name.equals("metadata") || granted.has(name)), Stage.TOKEN);
            token = GitHubJson.text(tokenResponse, "token");
            check(token.matches("[-A-Za-z0-9._~+/]{16,8192}={0,2}"), Stage.TOKEN);
            expires = Instant.parse(GitHubJson.text(tokenResponse, "expires_at"));
            check(expires.isAfter(clock.instant().plusSeconds(30)) && !expires.isAfter(clock.instant().plusSeconds(3900)), Stage.TOKEN);
        } catch (RuntimeException e) { throw failed(Code.INVALID_METADATA, Stage.TOKEN, 0, null, true); }

        live(expires, Stage.REPOSITORY_SCOPE);
        var scope = request(transport, Stage.REPOSITORY_SCOPE, "GET", "/installation/repositories?per_page=100&page=1", new byte[0], token, 200);
        try {
            check(GitHubJson.number(scope, "total_count") == 1 && scope.get("repositories").isJsonArray()
                    && scope.getAsJsonArray("repositories").size() == 1, Stage.REPOSITORY_SCOPE);
            var repository = GitHubJson.object(scope.getAsJsonArray("repositories").get(0));
            var owner = GitHubJson.object(repository.get("owner"));
            check(GitHubJson.number(repository, "id") == selection.repositoryId()
                    && GitHubJson.number(owner, "id") == selection.accountId()
                    && GitHubJson.text(owner, "login").equalsIgnoreCase(selection.owner())
                    && GitHubJson.text(repository, "name").equalsIgnoreCase(selection.name())
                    && GitHubJson.text(repository, "full_name").equalsIgnoreCase(selection.fullName()), Stage.REPOSITORY_SCOPE);
        } catch (RuntimeException e) { throw failed(Code.INVALID_METADATA, Stage.REPOSITORY_SCOPE, 0, null, false); }

        return new ScopedAccess(appId, token, expires);
    }

    static final int PULL_REQUEST_PAGE_SIZE = 10;
    record PullPage(JsonArray items, boolean possiblyTruncated, Instant observedAt) {}
    record BranchTip(String sha, Instant observedAt) {}

    /** Package-private host capability; credentials cannot be serialized into source inventory results. */
    final class VerifiedRepository {
        private final Snapshot snapshot;
        private final String token;
        private final GitHubTransport transport;
        private final PullAccess pullAccess;
        private VerifiedRepository(Snapshot snapshot, String token, GitHubTransport transport, PullAccess pullAccess) {
            this.snapshot = snapshot; this.token = token; this.transport = transport; this.pullAccess = pullAccess;
        }
        Snapshot snapshot() { return snapshot; }
        JsonObject tree(String sha) throws ConnectionFailure, InterruptedException { return read(sha, Stage.SOURCE_TREE, "trees"); }
        JsonObject blob(String sha) throws ConnectionFailure, InterruptedException { return read(sha, Stage.SOURCE_BLOB, "blobs"); }
        BranchTip branchTip(String branch) throws ConnectionFailure, InterruptedException {
            GitHubJson.require(GitHubPullRequestLookup.validBranch(branch));
            Stage stage = Stage.BRANCH_REF;
            live(snapshot.tokenExpiresAt(), stage);
            // The branch grammar allows slashes in names and excludes URI escapes and traversal.
            var reference = request(transport, stage, "GET", snapshot.selection().path() + "/git/ref/heads/" + branch,
                    new byte[0], token, 200);
            live(snapshot.tokenExpiresAt(), stage);
            try {
                var object = GitHubJson.object(reference.get("object"));
                String sha = GitHubJson.text(object, "sha");
                GitHubJson.require(GitHubJson.text(reference, "ref").equals("refs/heads/" + branch)
                        && GitHubJson.text(object, "type").equals("commit") && sha.matches("[0-9a-f]{40}"));
                return new BranchTip(sha, clock.instant());
            } catch (RuntimeException e) {
                throw failed(Code.INVALID_METADATA, stage, 200, null, false);
            }
        }
        PullPage pullRequests(GitHubPullRequestLookup.Intent intent) throws ConnectionFailure, InterruptedException {
            Stage stage = Stage.PULL_REQUESTS;
            if (pullAccess == PullAccess.NONE || !matchesIntent(intent))
                throw failed(Code.IDENTITY_OR_SCOPE_MISMATCH, stage, 0, null, false);
            var selection = snapshot.selection();
            String head = URLEncoder.encode(selection.owner() + ":" + intent.headRef(), StandardCharsets.UTF_8);
            String base = URLEncoder.encode(intent.baseRef(), StandardCharsets.UTF_8);
            String path = selection.path() + "/pulls?state=all&head=" + head + "&base=" + base
                    + "&per_page=" + PULL_REQUEST_PAGE_SIZE + "&page=1";
            live(snapshot.tokenExpiresAt(), stage);
            var response = requestResponse(transport, stage, "GET", path, new byte[0], token, 200);
            live(snapshot.tokenExpiresAt(), stage);
            try {
                var items = GitHubJson.parseArray(response.body(), GitHubTransport.MAX_BYTES);
                boolean possiblyTruncated = items.size() >= PULL_REQUEST_PAGE_SIZE || response.headers().firstValue("Link").isPresent();
                return new PullPage(items, possiblyTruncated, clock.instant());
            } catch (RuntimeException e) {
                throw failed(Code.INVALID_METADATA, stage, 200, null, false);
            }
        }
        JsonObject createDraft(GitHubPullRequestLookup.Intent intent, byte[] body) throws ConnectionFailure, InterruptedException {
            Stage stage = Stage.CREATE_PULL_REQUEST;
            if (pullAccess != PullAccess.WRITE || !matchesIntent(intent))
                throw failed(Code.IDENTITY_OR_SCOPE_MISMATCH, stage, 0, null, false);
            live(snapshot.tokenExpiresAt(), stage);
            return request(transport, stage, "POST", snapshot.selection().path() + "/pulls", body, token, 201);
        }

        private boolean matchesIntent(GitHubPullRequestLookup.Intent intent) {
            return snapshot.selection().equals(intent.selection()) && snapshot.appId() == intent.appId();
        }

        private JsonObject read(String sha, Stage stage, String kind) throws ConnectionFailure, InterruptedException {
            if (sha == null || !sha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid Git object identity");
            live(snapshot.tokenExpiresAt(), stage);
            var result = request(transport, stage, "GET", snapshot.selection().path() + "/git/" + kind + "/" + sha,
                    new byte[0], token, 200);
            live(snapshot.tokenExpiresAt(), stage); return result;
        }
        @Override public String toString() { return "VerifiedRepository[redacted]"; }
    }

    private void live(Instant expires, Stage stage) throws ConnectionFailure {
        if (!expires.isAfter(clock.instant().plusSeconds(5))) throw failed(Code.TOKEN_EXPIRED, stage, 0, null, false);
    }
    private static void check(boolean valid, Stage stage) throws ConnectionFailure {
        if (!valid) throw failed(Code.IDENTITY_OR_SCOPE_MISMATCH, stage, 0, null, stage == Stage.TOKEN);
    }
    private JsonObject request(GitHubTransport transport, Stage stage, String method, String path, byte[] body, String credential, int expected)
            throws ConnectionFailure, InterruptedException {
        var response = requestResponse(transport, stage, method, path, body, credential, expected);
        try {
            return GitHubJson.parse(response.body(), GitHubTransport.MAX_BYTES);
        } catch (RuntimeException e) {
            throw failed(Code.INVALID_METADATA, stage, expected, null, stage == Stage.TOKEN);
        }
    }

    private HttpResponse<byte[]> requestResponse(GitHubTransport transport, Stage stage, String method, String path,
                                                 byte[] body, String credential, int expected)
            throws ConnectionFailure, InterruptedException {
        HttpResponse<byte[]> response;
        try {
            response = stage == Stage.CREATE_PULL_REQUEST ? transport.postOnce(path, body, credential)
                    : transport.send(method, path, body, credential);
        }
        catch (IOException e) { throw failed(Code.TRANSPORT_INCOMPLETE, stage, 0, null, stage == Stage.TOKEN); }
        int status = response.statusCode();
        if (status != expected) {
            Code code = switch (status) {
                case 401 -> Code.AUTHENTICATION_FAILED;
                case 403, 429 -> Code.ACCESS_DENIED_OR_RATE_LIMITED;
                case 404 -> Code.NOT_FOUND_OR_NOT_INSTALLED;
                case 422 -> Code.SCOPE_REJECTED;
                default -> status >= 300 && status < 400 ? Code.REDIRECT_REFUSED
                        : status >= 500 ? Code.PROVIDER_UNAVAILABLE : Code.UNEXPECTED_STATUS;
            };
            throw failed(code, stage, status, retryTime(response, status), stage == Stage.TOKEN
                    && !Set.of(401, 403, 404, 422, 429).contains(status));
        }
        String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
        if (!type.equalsIgnoreCase("application/json") && !type.equalsIgnoreCase("application/vnd.github+json"))
            throw failed(Code.INVALID_METADATA, stage, status, null, stage == Stage.TOKEN);
        return response;
    }
    private Instant retryTime(HttpResponse<?> response, int status) {
        if (status != 403 && status != 429 && status < 500) return null;
        Instant now = clock.instant(), retry = now.plusSeconds(60);
        try {
            String after = response.headers().firstValue("Retry-After").orElse("");
            if (!after.isEmpty()) retry = after.matches("[0-9]{1,9}") ? now.plusSeconds(Long.parseLong(after))
                    : ZonedDateTime.parse(after, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException ignored) { /* Keep the conservative default; still inspect the independent reset header. */ }
        try {
            if (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")) {
                Instant reset = Instant.ofEpochSecond(Long.parseLong(response.headers().firstValue("X-RateLimit-Reset").orElseThrow()));
                if (reset.isAfter(retry)) retry = reset;
            }
        } catch (RuntimeException ignored) { /* Keep a valid Retry-After or the conservative default. */ }
        return retry.isBefore(now) ? now : retry;
    }
    private static ConnectionFailure failed(Code code, Stage stage, int status, Instant retry, boolean uncertain) {
        return new ConnectionFailure(new Failure(code, stage, status, retry, uncertain || stage.ordinal() > Stage.TOKEN.ordinal()));
    }
    public static Selection loadSelection(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid selection file");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = Channels.newInputStream(channel)) {
            return parseSelection(GitHubJson.parse(stream.readNBytes(8193), 8192));
        }
    }
    static Selection parseSelection(JsonObject o) {
        GitHubJson.fields(o, "formatVersion", "installationId", "accountId", "repositoryId", "owner", "name", "commitSha");
        GitHubJson.require(GitHubJson.number(o, "formatVersion") == 1);
        return new Selection(1, GitHubJson.number(o, "installationId"), GitHubJson.number(o, "accountId"),
                GitHubJson.number(o, "repositoryId"), GitHubJson.text(o, "owner"), GitHubJson.text(o, "name"), GitHubJson.text(o, "commitSha"));
    }
    public static void main(String[] args) {
        if (args.length != 1) { System.err.println("Usage: GitHubRepositoryConnection SELECTION_JSON"); System.exit(2); return; }
        try {
            var selection = loadSelection(Path.of(args[0]));
            String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER"), keyFile = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
            if (issuer == null || keyFile == null || keyFile.isBlank()) throw new IllegalArgumentException();
            var credentials = GitHubAppCredentials.load(issuer, Path.of(keyFile));
            System.out.println(GitHubJson.JSON.toJson(new GitHubRepositoryConnection().inspect(selection, credentials)));
        } catch (ConnectionFailure e) {
            System.err.println(GitHubJson.JSON.toJson(e.failure())); System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("Repository inspection could not start. Check the selection and host-side GitHub App configuration."); System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); System.err.println("Repository inspection interrupted; no automatic retry."); System.exit(1);
        }
    }
}
