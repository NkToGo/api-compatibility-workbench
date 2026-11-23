package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.compatibility.RepairEvaluation;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup;
import io.github.nktogo.apicompatibility.runner.RepairVerification.SubscriptionRepairEvidence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Binds retained synthetic subscription repair evidence to a source-only local commit. */
public final class RepairCommitBinding {
    private static final String SHA = "[0-9a-f]{40}";
    private static final Set<String> MODES = Set.of("40000", "100644", "100755", "120000", "160000");

    public enum State { MATCHED, BLOCKED }
    public enum Blocker { VERIFICATION_UNAVAILABLE, REPAIR_NOT_ESTABLISHED, GIT_OBJECTS_UNAVAILABLE,
        HEAD_NOT_DIRECT_CHILD, SOURCE_PATH_UNSUPPORTED, OTHER_TREE_CHANGES, BASE_SOURCE_DIFFERS, REPAIR_SOURCE_DIFFERS }
    public record Proof(int formatVersion, GitHubPullRequestLookup.Outline publication, String sourcePath, SubscriptionRepairEvidence verification,
                        String baseTreeSha, String headTreeSha, String baseBlobSha, String headBlobSha) {}
    public record Report(State state, Blocker blocker, SubscriptionRepairEvidence verification, String bindingIdentity, Proof proof) {}
    private record Commit(String tree, List<String> parents) {}
    private record Entry(String mode, String sha) {}

    private RepairCommitBinding() {}

    public static Report check(Path state, String verificationId, Path repository, GitHubPullRequestLookup.Intent intent,
                               String sourcePath) throws InterruptedException {
        CaseInputs.require(sourcePath != null && sourcePath.length() <= 240
                && sourcePath.matches("([A-Za-z0-9_-][A-Za-z0-9_.-]*/){0,7}PeriodConsumer\\.java"), "Unsupported repair source path");
        SubscriptionRepairEvidence evidence;
        try {
            evidence = RepairVerification.subscriptionRepairEvidence(state, verificationId);
        } catch (IOException | RuntimeException invalid) {
            return blocked(Blocker.VERIFICATION_UNAVAILABLE, null);
        }
        if (evidence.conclusion() != RepairEvaluation.Conclusion.ALL_OBSERVED_REGRESSIONS_FIXED)
            return blocked(Blocker.REPAIR_NOT_ESTABLISHED, evidence);
        try {
            var git = new GitObjects(repository.toAbsolutePath().normalize());
            return compare(git, intent, sourcePath, evidence);
        } catch (IOException | IllegalArgumentException invalid) {
            return blocked(Blocker.GIT_OBJECTS_UNAVAILABLE, evidence);
        }
    }

    private static Report compare(GitObjects git, GitHubPullRequestLookup.Intent intent, String sourcePath, SubscriptionRepairEvidence evidence)
            throws IOException, InterruptedException {
        var baseSha = intent.selection().commitSha();
        var base = git.commit(baseSha);
        var head = git.commit(intent.headSha());
        if (!head.parents().equals(List.of(baseSha))) return blocked(Blocker.HEAD_NOT_DIRECT_CHILD, evidence);
        String baseObject = base.tree(), headObject = head.tree();
        var segments = sourcePath.split("/");
        for (int i = 0; i < segments.length; i++) {
            String name = segments[i];
            String requiredMode = i == segments.length - 1 ? "100644" : "40000";
            var before = git.tree(baseObject);
            var after = git.tree(headObject);
            var oldEntry = before.get(name);
            var newEntry = after.get(name);
            if (oldEntry == null || newEntry == null || !oldEntry.mode().equals(requiredMode) || !newEntry.mode().equals(requiredMode))
                return blocked(Blocker.SOURCE_PATH_UNSUPPORTED, evidence);
            // Replace the allowed entry in the comparison only. Siblings, modes and their order must all match.
            after.put(name, oldEntry);
            if (!new ArrayList<>(before.entrySet()).equals(new ArrayList<>(after.entrySet())))
                return blocked(Blocker.OTHER_TREE_CHANGES, evidence);
            baseObject = oldEntry.sha();
            headObject = newEntry.sha();
        }
        if (!CaseInputs.hash(git.read("blob", baseObject)).equals(evidence.baseSourceSha256()))
            return blocked(Blocker.BASE_SOURCE_DIFFERS, evidence);
        if (!CaseInputs.hash(git.read("blob", headObject)).equals(evidence.repairSourceSha256()))
            return blocked(Blocker.REPAIR_SOURCE_DIFFERS, evidence);
        var proof = new Proof(1, GitHubPullRequestLookup.describe(intent), sourcePath, evidence,
                base.tree(), head.tree(), baseObject, headObject);
        return new Report(State.MATCHED, null, evidence, CaseInputs.identity(proof), proof);
    }

    private static Report blocked(Blocker blocker, SubscriptionRepairEvidence evidence) {
        return new Report(State.BLOCKED, blocker, evidence, null, null);
    }

    private record GitObjects(Path repository) {
        private Commit commit(String sha) throws IOException, InterruptedException {
            String raw = new String(read("commit", sha), StandardCharsets.ISO_8859_1);
            int headerEnd = raw.indexOf("\n\n");
            CaseInputs.require(headerEnd > 0, "Invalid Git commit");
            var headers = raw.substring(0, headerEnd).lines().toList();
            CaseInputs.require(headers.getFirst().matches("tree " + SHA), "Invalid Git commit tree");
            var parents = headers.stream().skip(1).takeWhile(header -> header.startsWith("parent "))
                    .map(header -> header.substring("parent ".length())).toList();
            CaseInputs.require(parents.stream().allMatch(parent -> parent.matches(SHA)), "Invalid Git commit parent");
            return new Commit(headers.getFirst().substring("tree ".length()), parents);
        }

        private Map<String, Entry> tree(String sha) throws IOException, InterruptedException {
            byte[] bytes = read("tree", sha);
            var entries = new LinkedHashMap<String, Entry>();
            for (int offset = 0; offset < bytes.length;) {
                int space = delimiter(bytes, offset, (byte) ' ');
                int end = delimiter(bytes, space + 1, (byte) 0);
                CaseInputs.require(end + 21 <= bytes.length, "Invalid Git tree entry");
                String mode = new String(bytes, offset, space - offset, StandardCharsets.US_ASCII);
                var name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, space + 1, end - space - 1)).toString();
                var object = HexFormat.of().formatHex(bytes, end + 1, end + 21);
                CaseInputs.require(MODES.contains(mode) && !name.isEmpty() && !name.equals(".") && !name.equals("..") && !name.contains("/")
                        && entries.putIfAbsent(name, new Entry(mode, object)) == null, "Invalid Git tree entry");
                offset = end + 21;
            }
            return entries;
        }

        private byte[] read(String type, String sha) throws IOException, InterruptedException {
            CaseInputs.require(sha.matches(SHA), "Invalid Git object identity");
            var command = new ProcessBuilder("git", "-C", repository.toString(), "--no-replace-objects", "--no-lazy-fetch",
                    "--no-optional-locks", "cat-file", type, sha);
            command.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
            command.environment().put("GIT_TERMINAL_PROMPT", "0");
            var result = BoundedCommand.run(command, Duration.ofSeconds(5), 262144, () -> false);
            if (result.status() != BoundedCommand.Status.EXITED || result.exitCode() != 0)
                throw new IOException("Git object is unavailable");
            try {
                var digest = MessageDigest.getInstance("SHA-1");
                digest.update((type + " " + result.stdout().length + "\0").getBytes(StandardCharsets.US_ASCII));
                CaseInputs.require(HexFormat.of().formatHex(digest.digest(result.stdout())).equals(sha), "Wrong Git object identity");
                return result.stdout();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private static int delimiter(byte[] bytes, int start, byte wanted) {
            for (int i = start; i < bytes.length; i++) if (bytes[i] == wanted) return i;
            throw new IllegalArgumentException("Invalid Git tree");
        }
    }

    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 5, "Expected state, verification ID, repository, intent and source path");
            var result = check(Path.of(args[0]), args[1], Path.of(args[2]),
                    GitHubPullRequestLookup.loadIntent(Path.of(args[3])), args[4]);
            System.out.println(CaseInputs.JSON.toJson(result));
            if (result.state() == State.BLOCKED) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Repair commit check could not start. Check the intent and selected paths.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Repair commit check interrupted.");
            System.exit(3);
        }
    }
}
