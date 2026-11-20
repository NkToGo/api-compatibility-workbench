package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Reconciles exact GitHub pull request metadata for one locally selected same-repository intention. */
public final class GitHubPullRequestLookup {
    private static final String MARKER_PREFIX = "<!-- acw-repair-publication:";
    private static final String SHA = "[0-9a-f]{40}";
    private final GitHubRepositoryConnection connection;

    public record Intent(int formatVersion, long appId, GitHubRepositoryConnection.Selection selection,
                         String operationId, String baseRef, String headSha) {
        public Intent {
            if (formatVersion != 1 || appId <= 0 || selection == null || operationId == null
                    || !operationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || baseRef == null || baseRef.length() > 128 || baseRef.equals("HEAD")
                    || !baseRef.matches("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*(/[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*)*")
                    || baseRef.endsWith(".lock") || baseRef.contains(".lock/")
                    || baseRef.equals("workbench/repair-" + operationId)
                    || headSha == null || !headSha.matches(SHA) || headSha.equals(selection.commitSha())) {
                throw new IllegalArgumentException("Invalid pull request intention");
            }
        }

        public String headRef() { return "workbench/repair-" + operationId; }
    }

    public enum State { MATCHED, NOT_OBSERVED, CONFLICT, INCOMPLETE }
    public enum Reason { SEARCH_LIMIT, INVALID_RESPONSE, LOOKUP_FAILED, MULTIPLE_PULL_REQUESTS, DIFFERENT_PULL_REQUEST }
    public enum PullState { OPEN, CLOSED, MERGED }
    public enum Mismatch { REPOSITORY, BASE_REF, HEAD_REF, BASE_SHA, HEAD_SHA, MARKER }
    public record Outline(Intent intent, String headRef, String marker) {}
    public record ObservedPull(long number, PullState state, Boolean draft, List<Mismatch> mismatches) {
        public ObservedPull { mismatches = List.copyOf(mismatches); }
    }
    public record Result(State state, Reason reason, Outline outline, Instant observedAt, List<ObservedPull> observed,
                         GitHubRepositoryConnection.Failure failure) {
        public Result { observed = List.copyOf(observed); }
    }

    public GitHubPullRequestLookup() { this(new GitHubRepositoryConnection()); }
    GitHubPullRequestLookup(GitHubRepositoryConnection connection) { this.connection = connection; }

    public static Outline describe(Intent intent) {
        byte[] bytes = GitHubJson.JSON.toJson(intent).getBytes(StandardCharsets.UTF_8);
        return new Outline(intent, intent.headRef(), MARKER_PREFIX + GitSourceObjects.sha256(bytes) + " -->");
    }

    public Result lookup(Intent intent, GitHubAppCredentials credentials) throws InterruptedException {
        try (var transport = new GitHubTransport()) { return lookup(intent, credentials, transport); }
    }

    Result lookup(Intent intent, GitHubAppCredentials credentials, GitHubTransport transport) throws InterruptedException {
        var outline = describe(intent);
        try {
            var repository = connection.openVerified(intent.selection(), credentials, transport, intent.appId());
            return reconcile(outline, repository.pullRequests(intent));
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            return new Result(State.INCOMPLETE, Reason.LOOKUP_FAILED, outline, null, List.of(), e.failure());
        }
    }

    private static Result reconcile(Outline outline, GitHubRepositoryConnection.PullPage page) {
        if (page.possiblyTruncated())
            return new Result(State.INCOMPLETE, Reason.SEARCH_LIMIT, outline, page.observedAt(), List.of(), null);
        var observed = new ArrayList<ObservedPull>();
        var numbers = new HashSet<Long>();
        try {
            for (var item : page.items()) {
                var pull = inspect(outline, GitHubJson.object(item));
                GitHubJson.require(numbers.add(pull.number()));
                observed.add(pull);
            }
        } catch (RuntimeException e) {
            return new Result(State.INCOMPLETE, Reason.INVALID_RESPONSE, outline, page.observedAt(), List.of(), null);
        }
        if (observed.isEmpty()) return new Result(State.NOT_OBSERVED, null, outline, page.observedAt(), observed, null);
        if (observed.size() > 1)
            return new Result(State.CONFLICT, Reason.MULTIPLE_PULL_REQUESTS, outline, page.observedAt(), observed, null);
        if (!observed.getFirst().mismatches().isEmpty())
            return new Result(State.CONFLICT, Reason.DIFFERENT_PULL_REQUEST, outline, page.observedAt(), observed, null);
        return new Result(State.MATCHED, null, outline, page.observedAt(), observed, null);
    }

    private static ObservedPull inspect(Outline outline, JsonObject pull) {
        var intent = outline.intent();
        long number = GitHubJson.number(pull, "number");
        GitHubJson.require(number > 0);
        var base = GitHubJson.object(pull.get("base"));
        var head = GitHubJson.object(pull.get("head"));
        var mismatches = new ArrayList<Mismatch>();
        if (!matchesRepository(base, intent.selection()) || !matchesRepository(head, intent.selection())) mismatches.add(Mismatch.REPOSITORY);
        if (!GitHubJson.text(base, "ref").equals(intent.baseRef())) mismatches.add(Mismatch.BASE_REF);
        if (!GitHubJson.text(head, "ref").equals(outline.headRef())) mismatches.add(Mismatch.HEAD_REF);
        if (!revision(base).equals(intent.selection().commitSha())) mismatches.add(Mismatch.BASE_SHA);
        if (!revision(head).equals(intent.headSha())) mismatches.add(Mismatch.HEAD_SHA);
        GitHubJson.require(pull.has("body"));
        String body = pull.get("body").isJsonNull() ? "" : GitHubJson.text(pull, "body", 65536);
        if (!body.lines().filter(line -> line.startsWith(MARKER_PREFIX)).toList().equals(List.of(outline.marker())))
            mismatches.add(Mismatch.MARKER);
        Boolean draft = null;
        if (pull.has("draft")) {
            var value = pull.get("draft");
            GitHubJson.require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean());
            draft = value.getAsBoolean();
        }
        GitHubJson.require(pull.has("merged_at"));
        boolean merged = !pull.get("merged_at").isJsonNull();
        if (merged) Instant.parse(GitHubJson.text(pull, "merged_at"));
        var state = switch (GitHubJson.text(pull, "state")) {
            case "open" -> {
                GitHubJson.require(!merged);
                yield PullState.OPEN;
            }
            case "closed" -> merged ? PullState.MERGED : PullState.CLOSED;
            default -> throw new IllegalArgumentException("Unknown pull request state");
        };
        return new ObservedPull(number, state, draft, mismatches);
    }

    private static boolean matchesRepository(JsonObject side, GitHubRepositoryConnection.Selection selection) {
        GitHubJson.require(side.has("repo"));
        if (side.get("repo").isJsonNull()) return false;
        var repo = GitHubJson.object(side.get("repo"));
        var owner = GitHubJson.object(repo.get("owner"));
        return GitHubJson.number(repo, "id") == selection.repositoryId()
                && GitHubJson.number(owner, "id") == selection.accountId()
                && GitHubJson.text(owner, "login").equalsIgnoreCase(selection.owner())
                && GitHubJson.text(repo, "full_name").equalsIgnoreCase(selection.fullName());
    }

    private static String revision(JsonObject side) {
        String sha = GitHubJson.text(side, "sha");
        GitHubJson.require(sha.matches(SHA));
        return sha;
    }

    public static Intent loadIntent(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid intention file");
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var json = GitHubJson.parse(stream.readNBytes(8193), 8192);
            GitHubJson.fields(json, "formatVersion", "appId", "selection", "operationId", "baseRef", "headSha");
            GitHubJson.require(GitHubJson.number(json, "formatVersion") == 1);
            return new Intent(1, GitHubJson.number(json, "appId"),
                    GitHubRepositoryConnection.parseSelection(GitHubJson.object(json.get("selection"))),
                    GitHubJson.text(json, "operationId"), GitHubJson.text(json, "baseRef"), GitHubJson.text(json, "headSha"));
        }
    }

    public static void main(String[] args) {
        try {
            GitHubJson.require(args.length == 2);
            var intent = loadIntent(Path.of(args[1]));
            switch (args[0]) {
                case "describe" -> System.out.println(GitHubJson.JSON.toJson(describe(intent)));
                case "lookup" -> {
                    String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER");
                    String key = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
                    GitHubJson.require(issuer != null && key != null && !key.isBlank());
                    var result = new GitHubPullRequestLookup().lookup(intent, GitHubAppCredentials.load(issuer, Path.of(key)));
                    System.out.println(GitHubJson.JSON.toJson(result));
                    if (result.state() != State.MATCHED) System.exit(3);
                }
                default -> throw new IllegalArgumentException("Unknown pull request lookup command");
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Pull request lookup could not start. Check the intention and host-side App configuration.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Pull request lookup interrupted. No retry is inferred.");
            System.exit(3);
        }
    }
}
