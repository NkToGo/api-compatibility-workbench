package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.nktogo.apicompatibility.runner.RepairCommitBinding.Blocker.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairCommitBindingTest {
    static final String SOURCE = "src/main/PeriodConsumer.java";
    @TempDir Path temporary;

    record Revisions(Path repository, String base, String head) {}

    static Revisions history(Path repository, String original, String repair) throws Exception {
        Files.createDirectories(repository.resolve(SOURCE).getParent());
        git(repository, "init", "--object-format=sha1", "-q");
        git(repository, "config", "core.autocrlf", "false");
        Files.writeString(repository.resolve(SOURCE), original);
        Files.writeString(repository.resolve("pom.xml"), "<project/>\n");
        Files.writeString(repository.resolve("src/main/sibling.txt"), "unchanged\n");
        git(repository, "add", ".");
        String base = commit(repository, null);
        Files.writeString(repository.resolve(SOURCE), repair);
        git(repository, "add", SOURCE);
        return new Revisions(repository, base, commit(repository, base));
    }

    static Intent intent(String base, String head) {
        return new Intent(1, 10, new Selection(1, 20, 30, 40, "example-owner", "billing", base),
                "b876052c-4c83-4d18-80a1-f9e2a35bcfa3", "main", head);
    }

    private static String commit(Path repo, String parent) throws Exception {
        String tree = git(repo, "write-tree");
        return parent == null ? git(repo, "commit-tree", tree, "-m", "Original")
                : git(repo, "commit-tree", tree, "-p", parent, "-m", "Proposed repair");
    }

    private static String git(Path root, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgSign=false"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        var result = BoundedCommand.run(builder, Duration.ofSeconds(10), 65536, () -> false);
        assertEquals(BoundedCommand.Status.EXITED, result.status());
        assertEquals(0, result.exitCode(), new String(result.stderr(), StandardCharsets.UTF_8));
        return new String(result.stdout(), StandardCharsets.UTF_8).trim();
    }

    private String completed() throws Exception {
        var verifier = new RepairVerification(temporary, new SubscriptionVerificationTest.Sandbox());
        String id = SubscriptionVerificationTest.register(verifier);
        verifier.execute(id);
        return id;
    }

    @Test void bindsExactCommittedBytesAndIgnoresWorkingTreeEdits() throws Exception {
        String id = completed();
        var revisions = history(temporary.resolve("repo"), "baseline", "candidate");
        var intent = intent(revisions.base(), revisions.head());
        var matched = RepairCommitBinding.check(temporary, id, revisions.repository(), intent, SOURCE);
        assertEquals(RepairCommitBinding.State.MATCHED, matched.state());
        assertEquals(intent, matched.proof().publication().intent());
        assertTrue(matched.bindingIdentity().matches("[0-9a-f]{64}"));
        Files.writeString(revisions.repository().resolve(SOURCE), "working tree edits");
        assertEquals(matched, RepairCommitBinding.check(temporary, id, revisions.repository(), intent, SOURCE));
    }

    @Test void rejectsUnverifiedSourceAndAnyOtherTreeChange() throws Exception {
        String id = completed();
        var revisions = history(temporary.resolve("repo"), "baseline", "candidate\n");
        Path repo = revisions.repository();
        assertEquals(REPAIR_SOURCE_DIFFERS, RepairCommitBinding.check(temporary, id, repo, intent(revisions.base(), revisions.head()), SOURCE).blocker());
        Files.writeString(repo.resolve(SOURCE), "candidate");
        Files.writeString(repo.resolve("src/main/sibling.txt"), "changed");
        git(repo, "add", ".");
        String extra = commit(repo, revisions.base());
        assertEquals(OTHER_TREE_CHANGES, RepairCommitBinding.check(temporary, id, repo, intent(revisions.base(), extra), SOURCE).blocker());
        git(repo, "update-index", "--chmod=+x", SOURCE);
        String mode = commit(repo, revisions.base());
        assertEquals(SOURCE_PATH_UNSUPPORTED, RepairCommitBinding.check(temporary, id, repo, intent(revisions.base(), mode), SOURCE).blocker());
    }

    @Test void rejectsWrongBaseBytesAncestryAndMissingObjects() throws Exception {
        String id = completed();
        var revisions = history(temporary.resolve("repo"), "other baseline", "candidate");
        Path repo = revisions.repository();
        assertEquals(BASE_SOURCE_DIFFERS, RepairCommitBinding.check(temporary, id, repo, intent(revisions.base(), revisions.head()), SOURCE).blocker());
        String indirect = commit(repo, revisions.head());
        assertEquals(HEAD_NOT_DIRECT_CHILD, RepairCommitBinding.check(temporary, id, repo, intent(revisions.base(), indirect), SOURCE).blocker());
        assertEquals(GIT_OBJECTS_UNAVAILABLE, RepairCommitBinding.check(temporary, id, repo, intent(revisions.base(), "f".repeat(40)), SOURCE).blocker());
    }

    @Test void incompleteAndAlteredVerificationCannotBind() throws Exception {
        var verifier = new RepairVerification(temporary, new SubscriptionVerificationTest.Sandbox());
        String interrupted = SubscriptionVerificationTest.register(verifier);
        verifier.cancel(interrupted);
        String id = completed();
        var revisions = history(temporary.resolve("repo"), "baseline", "candidate");
        var intent = intent(revisions.base(), revisions.head());
        assertEquals(REPAIR_NOT_ESTABLISHED, RepairCommitBinding.check(temporary, interrupted, revisions.repository(), intent, SOURCE).blocker());
        var resultPath = temporary.resolve("verifications").resolve(id).resolve("result.json");
        var result = CaseInputs.object(Files.readAllBytes(resultPath));
        result.getAsJsonObject("evaluation").add("scenarios", new com.google.gson.JsonArray());
        CaseInputs.write(resultPath, result);
        var invalid = RepairCommitBinding.check(temporary, id, revisions.repository(), intent, SOURCE);
        assertEquals(VERIFICATION_UNAVAILABLE, invalid.blocker());
        assertNull(invalid.bindingIdentity());
    }
}
