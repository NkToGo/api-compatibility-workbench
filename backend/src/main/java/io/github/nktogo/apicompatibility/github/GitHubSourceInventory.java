package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.inventory.MavenPomInventory;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Explicit-path, revision-bound POM inspection. Never checks out or executes assessed source. */
public final class GitHubSourceInventory {
    static final int MAX_SOURCE_REQUESTS = 24, MAX_TOTAL_BYTES = 262144;
    public record Plan(int formatVersion, List<String> pomPaths) {
        public Plan {
            pomPaths = List.copyOf(pomPaths);
            if (formatVersion != 1 || pomPaths.isEmpty() || pomPaths.size() > 8 || new HashSet<>(pomPaths).size() != pomPaths.size())
                throw new IllegalArgumentException("Invalid POM selection");
            for (String path : pomPaths) {
                if (path == null || path.length() > 240 || path.split("/", -1).length > 8 || !(path.equals("pom.xml") || path.endsWith("/pom.xml")))
                    throw new IllegalArgumentException("Unsupported POM path");
                for (String segment : path.split("/", -1)) if (!segment.matches("[A-Za-z0-9_.-]{1,100}") || segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("Unsafe POM path");
            }
        }
    }
    public enum State { COMPLETE_WITHIN_SELECTED_FILES, INCOMPLETE }
    public enum FileState { READ, MISSING, UNSUPPORTED_ENTRY, SIZE_LIMIT, POM_REJECTED, READ_FAILED, NOT_READ }
    public enum Stop { REQUEST_LIMIT, DEADLINE, INVALID_TREE_OR_BLOB, CONNECTION_FAILURE }
    public record FileResult(String path, FileState state, String gitBlobSha, String contentSha256,
                             Integer byteCount, MavenPomInventory.Report declarations) {}
    public record Problem(Stop reason, GitHubRepositoryConnection.Failure connectionFailure) {}
    public record Result(int formatVersion, GitHubRepositoryConnection.Snapshot repository, Plan plan,
                         Instant completedAt, State state, List<FileResult> files, int sourceRequests,
                         int sourceBytes, Problem problem, List<String> limitations) {
        public Result { files = List.copyOf(files); limitations = List.copyOf(limitations); }
    }
    /** Opt-in host capture. Source bytes are never included in diagnostic JSON. */
    public static final class Capture {
        private final Result inventory;
        private final transient Map<String, byte[]> poms;
        private Capture(Result inventory, Map<String, byte[]> poms) {
            this.inventory = inventory; this.poms = copy(poms);
        }
        public Result inventory() { return inventory; }
        public Map<String, byte[]> poms() { return copy(poms); }
        private static Map<String, byte[]> copy(Map<String, byte[]> source) {
            var result = new TreeMap<String, byte[]>(); source.forEach((path, bytes) -> result.put(path, bytes.clone()));
            return Collections.unmodifiableMap(result);
        }
        @Override public String toString() { return "Capture[source bytes omitted]"; }
    }
    private final Clock clock;
    public GitHubSourceInventory() { this(Clock.systemUTC()); }
    GitHubSourceInventory(Clock clock) { this.clock = clock; }
    public Result inspect(GitHubRepositoryConnection.Selection selection, GitHubAppCredentials credentials, Plan plan)
            throws GitHubRepositoryConnection.ConnectionFailure, InterruptedException {
        try (var transport = new GitHubTransport()) { return inspect(selection, credentials, plan, transport); }
    }
    Result inspect(GitHubRepositoryConnection.Selection selection, GitHubAppCredentials credentials, Plan plan, GitHubTransport transport)
            throws GitHubRepositoryConnection.ConnectionFailure, InterruptedException {
        return read(selection, credentials, plan, transport, null);
    }
    public Capture capture(GitHubRepositoryConnection.Selection selection, GitHubAppCredentials credentials, Plan plan)
            throws GitHubRepositoryConnection.ConnectionFailure, InterruptedException {
        try (var transport = new GitHubTransport()) { return capture(selection, credentials, plan, transport); }
    }
    @FunctionalInterface public interface CancellationCheck { void check() throws IOException, InterruptedException; }
    public Capture capture(GitHubRepositoryConnection.Selection selection, GitHubAppCredentials credentials, Plan plan, CancellationCheck control)
            throws GitHubRepositoryConnection.ConnectionFailure, InterruptedException {
        try (var transport = new GitHubTransport(GitHubTransport.ORIGIN, java.time.Duration.ofSeconds(30), control)) {
            return capture(selection, credentials, plan, transport);
        }
    }
    Capture capture(GitHubRepositoryConnection.Selection selection, GitHubAppCredentials credentials, Plan plan, GitHubTransport transport)
            throws GitHubRepositoryConnection.ConnectionFailure, InterruptedException {
        var poms = new TreeMap<String, byte[]>();
        return new Capture(read(selection, credentials, plan, transport, poms), poms);
    }
    private Result read(GitHubRepositoryConnection.Selection selection, GitHubAppCredentials credentials, Plan plan, GitHubTransport transport,
                        Map<String, byte[]> captured) throws GitHubRepositoryConnection.ConnectionFailure, InterruptedException {
        Objects.requireNonNull(plan, "plan"); Instant deadline = clock.instant().plusSeconds(180);
        var access = new GitHubRepositoryConnection(clock).openVerified(selection, credentials, transport);
        var reads = new GitHubPathReader(access, clock, deadline, MAX_SOURCE_REQUESTS, MAX_TOTAL_BYTES);
        var files = new ArrayList<FileResult>(); Problem problem = null;
        for (String path : plan.pomPaths()) {
            if (problem != null) { files.add(unread(path, FileState.NOT_READ)); continue; }
            try {
                var file = reads.file(path, (directory, names) -> {});
                if (file.state() != GitHubPathReader.State.READ) {
                    files.add(unread(path, FileState.valueOf(file.state().name())));
                    continue;
                }
                byte[] bytes = file.bytes();
                if (captured != null) captured.put(path, bytes);
                var declarations = MavenPomInventory.scan(bytes);
                files.add(new FileResult(path,
                        declarations.state() == MavenPomInventory.State.SCANNED ? FileState.READ : FileState.POM_REJECTED,
                        file.gitBlobSha(), GitSourceObjects.sha256(bytes), bytes.length, declarations));
            }
            catch (GitHubPathReader.Limit e) { files.add(unread(path, FileState.NOT_READ)); problem = new Problem(e.reason, null); }
            catch (GitHubRepositoryConnection.ConnectionFailure e) {
                files.add(unread(path, FileState.READ_FAILED)); problem = new Problem(Stop.CONNECTION_FAILURE, e.failure());
            } catch (RuntimeException e) {
                files.add(unread(path, FileState.READ_FAILED)); problem = new Problem(Stop.INVALID_TREE_OR_BLOB, null);
            }
        }
        State state = files.stream().allMatch(f -> f.state() == FileState.READ) ? State.COMPLETE_WITHIN_SELECTED_FILES : State.INCOMPLETE;
        return new Result(1, access.snapshot(), plan, clock.instant(), state, files, reads.requests(), reads.totalBytes(), problem,
                List.of("SELECTED_POMS_ONLY", captured == null ? "SOURCE_BYTES_NOT_RETAINED" : "SOURCE_BYTES_CAPTURED_IN_MEMORY", "MAVEN_EFFECTIVE_MODEL_NOT_RESOLVED",
                        "REQUEST_API_VERSION_NOT_ASSESSED", "WEBHOOK_API_VERSION_NOT_ASSESSED", "ACCOUNT_DEFAULT_VERSION_NOT_ASSESSED",
                        "NO_COMPATIBILITY_ASSESSMENT_OR_EXECUTION"));
    }
    private static FileResult unread(String path, FileState state) { return new FileResult(path, state, null, null, null, null); }
    public static Plan loadPlan(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid POM plan file");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = Channels.newInputStream(channel)) {
            var o = GitHubJson.parse(stream.readNBytes(8193), 8192); GitHubJson.fields(o, "formatVersion", "pomPaths");
            GitHubJson.require(GitHubJson.number(o, "formatVersion") == 1 && o.get("pomPaths").isJsonArray());
            var paths = new ArrayList<String>();
            for (var value : o.getAsJsonArray("pomPaths")) {
                GitHubJson.require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()); paths.add(value.getAsString());
            }
            return new Plan(1, paths);
        }
    }
    public static void main(String[] args) {
        if (args.length != 2) { System.err.println("Usage: GitHubSourceInventory SELECTION_JSON POM_PLAN_JSON"); System.exit(2); return; }
        try {
            var selection = GitHubRepositoryConnection.loadSelection(Path.of(args[0])); var plan = loadPlan(Path.of(args[1]));
            String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER"), keyFile = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
            if (issuer == null || keyFile == null || keyFile.isBlank()) throw new IllegalArgumentException();
            var result = new GitHubSourceInventory().inspect(selection, GitHubAppCredentials.load(issuer, Path.of(keyFile)), plan);
            System.out.println(GitHubJson.JSON.toJson(result)); if (result.state() == State.INCOMPLETE) System.exit(3);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) {
            System.err.println(GitHubJson.JSON.toJson(e.failure())); System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("Source inventory could not start. Check the selection, POM plan and host App configuration."); System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); System.err.println("Source inventory interrupted; no automatic retry."); System.exit(1);
        }
    }
}
