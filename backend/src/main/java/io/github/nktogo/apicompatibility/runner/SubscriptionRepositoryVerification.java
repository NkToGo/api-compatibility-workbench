package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup;
import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding.CommitSources;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding.SourceBlocked;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Selects a source-only subscription repair from exact local Git objects. */
public final class SubscriptionRepositoryVerification {
    static final String BASELINE_API_VERSION = "2025-02-24.acacia";
    static final String TARGET_API_VERSION = "2025-07-30.basil";
    static final String CANDIDATE_ID = "repository-supplied-source";

    public record Revision(String commitSha, String treeSha, String blobSha, String contentSha256) {
        public Revision {
            CaseInputs.require(sha1(commitSha) && sha1(treeSha) && sha1(blobSha)
                    && contentSha256 != null && contentSha256.matches("[0-9a-f]{64}"), "Invalid source object identity");
        }

        private boolean matches(byte[] source) {
            return contentSha256.equals(CaseInputs.hash(source))
                    && blobSha.equals(RepairCommitBinding.gitObjectHash("blob", source));
        }
    }

    public record RepositoryInput(int formatVersion, String caseId, long accountId, long repositoryId, String repository,
                                  String sourcePath, String baselineApiVersion, String targetApiVersion,
                                  Revision baseline, Revision proposed) {
        public RepositoryInput {
            CaseInputs.require(formatVersion == 1 && SubscriptionPeriodCase.CASE_ID.equals(caseId)
                    && accountId > 0 && repositoryId > 0 && repository != null
                    && repository.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
                    && !repository.endsWith("/.") && !repository.endsWith("/..")
                    && RepairCommitBinding.validSourcePath(sourcePath) && baseline != null && proposed != null
                    && !baseline.commitSha().equals(proposed.commitSha()), "Invalid subscription repository input");
            requireVersions(baselineApiVersion, targetApiVersion);
        }

        boolean matchesSelection(Intent intent, String path) {
            var selection = intent.selection();
            return accountId == selection.accountId() && repositoryId == selection.repositoryId()
                    && repository.equalsIgnoreCase(selection.owner() + "/" + selection.name())
                    && sourcePath.equals(path) && baseline.commitSha().equals(selection.commitSha())
                    && proposed.commitSha().equals(intent.headSha());
        }

        boolean matchesObjects(CommitSources sources) {
            return baseline.treeSha().equals(sources.baseTreeSha()) && baseline.blobSha().equals(sources.baseBlobSha())
                    && proposed.treeSha().equals(sources.headTreeSha()) && proposed.blobSha().equals(sources.headBlobSha());
        }

        void verifyBytes(byte[] base, byte[] candidate) {
            CaseInputs.require(baseline.matches(base) && proposed.matches(candidate), "Captured repository source changed");
        }
    }

    record Prepared(RepositoryInput input, String baseline, String candidate) {
        @Override public String toString() { return "Prepared[" + input + "]"; }

        String register(RepairVerification verifier, Path caseRoot, String image) throws IOException {
            return verifier.registerRepositorySubscription(caseRoot, baseline, candidate, image, input);
        }
    }

    private SubscriptionRepositoryVerification() {}

    static Prepared prepare(Path repository, Intent intent, String sourcePath, String baselineVersion, String targetVersion)
            throws InterruptedException, SourceBlocked, IOException {
        requireVersions(baselineVersion, targetVersion);
        var source = RepairCommitBinding.readSources(repository, intent, sourcePath);
        var selected = intent.selection();
        var baseline = revision(selected.commitSha(), source.baseTreeSha(), source.baseBlobSha(), source.baseline());
        var proposed = revision(intent.headSha(), source.headTreeSha(), source.headBlobSha(), source.candidate());
        var input = new RepositoryInput(1, SubscriptionPeriodCase.CASE_ID, selected.accountId(), selected.repositoryId(),
                selected.owner() + "/" + selected.name(), sourcePath, baselineVersion, targetVersion, baseline, proposed);
        return new Prepared(input, decodeSource(source.baseline()), decodeSource(source.candidate()));
    }

    static RepositoryInput retained(JsonObject content, byte[] baseline, byte[] candidate) {
        var input = CaseInputs.JSON.fromJson(content, RepositoryInput.class);
        CaseInputs.require(input != null && content.equals(CaseInputs.JSON.toJsonTree(input)), "Invalid retained repository input");
        input.verifyBytes(baseline, candidate);
        return input;
    }

    private static Revision revision(String commit, String tree, String blob, byte[] source) {
        return new Revision(commit, tree, blob, CaseInputs.hash(source));
    }

    private static String decodeSource(byte[] bytes) throws IOException {
        CaseInputs.require(bytes.length <= 128000, "Committed source size exceeds limit");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        CaseInputs.require(!text.isBlank() && text.length() <= 32000 && !text.contains("\0"), "Unsupported committed source text");
        return text;
    }

    private static boolean sha1(String value) { return value != null && value.matches("[0-9a-f]{40}"); }

    private static void requireVersions(String baseline, String target) {
        CaseInputs.require(BASELINE_API_VERSION.equals(baseline) && TARGET_API_VERSION.equals(target),
                "Unsupported subscription version declarations");
    }

    public static void main(String[] args) {
        try {
            boolean execute = args.length == 9 && args[0].equals("verify");
            CaseInputs.require(execute || args.length == 6 && args[0].equals("preview"), "Invalid subscription repository command");
            var input = prepare(Path.of(args[1]), GitHubPullRequestLookup.loadIntent(Path.of(args[2])), args[3], args[4], args[5]);
            if (!execute) {
                System.out.println(CaseInputs.JSON.toJson(input.input()));
                return;
            }
            var verifier = new RepairVerification(Path.of(args[6]));
            String id = input.register(verifier, Path.of(args[7]), args[8]);
            System.out.println("Verification: " + id);
            var result = verifier.execute(id);
            System.out.println("Status: " + result.get("status").getAsString());
            if (result.has("evaluation")) System.out.println("Evaluation: " + result.getAsJsonObject("evaluation").get("conclusion").getAsString());
            if (!"COMPLETED".equals(result.get("status").getAsString())) System.exit(3);
        } catch (SourceBlocked e) {
            System.err.println("Subscription repository source is blocked: " + e.blocker);
            System.exit(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Subscription repository verification interrupted. Preserve the verification ID for recovery.");
            System.exit(3);
        } catch (Exception e) {
            System.err.println("Subscription repository verification could not complete. Check the selection, committed source, versions and worker setup.");
            System.exit(2);
        }
    }
}
