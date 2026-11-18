package io.github.nktogo.apicompatibility.github;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.ConnectionFailure;
import static io.github.nktogo.apicompatibility.github.GitHubSourceInventory.Stop;

/** Request budget and verified Git path traversal for host-selected paths. */
final class GitHubPathReader {
    static final int MAX_FILE_BYTES = 65536;
    enum State { READ, MISSING, UNSUPPORTED_ENTRY, SIZE_LIMIT }
    record File(State state, String gitBlobSha, byte[] bytes) {
        @Override public String toString() { return "File[" + state + ", source bytes omitted]"; }
    }
    static final class Limit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Stop reason;
        Limit(Stop reason) { this.reason = reason; }
    }

    private final GitHubRepositoryConnection.VerifiedRepository access;
    private final Clock clock;
    private final Instant deadline;
    private final int maxRequests;
    private final int maxBytes;
    private final Map<String, Map<String, GitSourceObjects.Entry>> trees = new HashMap<>();
    private int requests;
    private int totalBytes;

    GitHubPathReader(GitHubRepositoryConnection.VerifiedRepository access, Clock clock, Instant deadline,
                     int maxRequests, int maxBytes) {
        this.access = access;
        this.clock = clock;
        this.deadline = deadline;
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
    }

    int requests() { return requests; }
    int totalBytes() { return totalBytes; }

    private void checkDeadline() {
        if (!clock.instant().isBefore(deadline)) throw new Limit(Stop.DEADLINE);
    }

    private void request() {
        checkDeadline();
        if (requests >= maxRequests) throw new Limit(Stop.REQUEST_LIMIT);
        requests++;
    }

    private Map<String, GitSourceObjects.Entry> tree(String sha) throws ConnectionFailure, InterruptedException {
        checkDeadline();
        var cached = trees.get(sha);
        if (cached != null) return cached;
        request();
        var data = access.tree(sha);
        checkDeadline();
        var entries = GitSourceObjects.tree(data, sha);
        trees.put(sha, entries);
        return entries;
    }

    File file(String path, BiConsumer<String, Set<String>> directoryObserver) throws ConnectionFailure, InterruptedException {
        String sha = access.snapshot().treeSha();
        String directory = "";
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            var entries = tree(sha);
            directoryObserver.accept(directory, entries.keySet());
            var entry = entries.get(segments[i]);
            if (entry == null) return new File(State.MISSING, null, null);
            if (i == segments.length - 1) return blob(entry);
            if (!entry.mode().equals("040000")) return new File(State.UNSUPPORTED_ENTRY, null, null);
            sha = entry.sha();
            directory = directory.isEmpty() ? segments[i] : directory + "/" + segments[i];
        }
        throw new IllegalArgumentException("Missing source path");
    }

    private File blob(GitSourceObjects.Entry entry) throws ConnectionFailure, InterruptedException {
        if (!Set.of("100644", "100755").contains(entry.mode())) return new File(State.UNSUPPORTED_ENTRY, null, null);
        if (entry.size() == null || entry.size() > MAX_FILE_BYTES || entry.size() > maxBytes - totalBytes)
            return new File(State.SIZE_LIMIT, null, null);
        request();
        var data = access.blob(entry.sha());
        checkDeadline();
        byte[] bytes = GitSourceObjects.blob(data, entry);
        totalBytes += bytes.length;
        return new File(State.READ, entry.sha(), bytes);
    }
}
