package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.nktogo.apicompatibility.runner.RepairCommitBindingTest.SOURCE;
import static io.github.nktogo.apicompatibility.runner.SubscriptionRepositoryVerification.BASELINE_API_VERSION;
import static io.github.nktogo.apicompatibility.runner.SubscriptionRepositoryVerification.TARGET_API_VERSION;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionRepositoryVerificationTest {
    @TempDir Path temporary;

    @Test void verifiesCommittedBytesAndKeepsTheSelectedRepositoryAndVersionsThroughReview() throws Exception {
        var revisions = RepairCommitBindingTest.history(temporary.resolve("repo"), "baseline", "candidate");
        var intent = RepairCommitBindingTest.intent(revisions.base(), revisions.head());
        var prepared = prepare(revisions, intent);
        Files.writeString(revisions.repository().resolve(SOURCE), "working tree edits");
        assertEquals(prepared.input(), prepare(revisions, intent).input());
        assertEquals(CaseInputs.hash("candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8)), prepared.input().proposed().contentSha256());
        assertEquals("2025-02-24.acacia", prepared.input().baselineApiVersion());
        assertEquals("2025-07-30.basil", prepared.input().targetApiVersion());

        var sandbox = new SubscriptionVerificationTest.Sandbox();
        var verifier = new RepairVerification(temporary, sandbox);
        String id = prepared.register(verifier, SubscriptionVerificationTest.caseRoot(), RepairVerificationTest.IMAGE);
        var result = verifier.execute(id);
        assertEquals("ALL_OBSERVED_REGRESSIONS_FIXED", result.getAsJsonObject("evaluation").get("conclusion").getAsString());
        assertEquals(24, sandbox.calls);
        var review = RepairCommitBinding.review(temporary, id, revisions.repository(), intent, SOURCE);
        assertEquals(RepairCommitBinding.State.MATCHED, review.binding().state());
        assertEquals(prepared.input(), review.repositoryInput());
        var definition = review.assessment().definition();
        assertEquals(BASELINE_API_VERSION, definition.transition().baseline().providerVersion());
        assertEquals(TARGET_API_VERSION, definition.repairedTarget().providerVersion());
        assertEquals(definition.transition().target().configuration(), definition.repairedTarget().configuration());
        assertNotEquals(CaseInputs.identity("bound-item-renewal-v1/subscription-period-v1"),
                definition.repairedTarget().configuration().sha256());

        var other = new Intent(1, intent.appId(), new Selection(1, 20, 30, 41, "example-owner", "billing", revisions.base()),
                intent.operationId(), intent.baseRef(), revisions.head());
        assertEquals(RepairCommitBinding.Blocker.REPOSITORY_INPUT_DIFFERS,
                RepairCommitBinding.check(temporary, id, revisions.repository(), other, SOURCE).blocker());
        var newOperation = new Intent(1, intent.appId(), intent.selection(),
                "b876052c-4c83-4d18-80a1-f9e2a35bcfa4", intent.baseRef(), revisions.head());
        assertEquals(RepairCommitBinding.State.MATCHED,
                RepairCommitBinding.check(temporary, id, revisions.repository(), newOperation, SOURCE).state());
    }

    @Test void rejectsUnsupportedVersionsAndUnrelatedTreeChanges() throws Exception {
        var revisions = RepairCommitBindingTest.history(temporary.resolve("repo"), "baseline", "candidate");
        var intent = RepairCommitBindingTest.intent(revisions.base(), revisions.head());
        assertThrows(IllegalArgumentException.class, () -> SubscriptionRepositoryVerification.prepare(revisions.repository(),
                intent, SOURCE, BASELINE_API_VERSION, "2025-03-31.basil"));
        Files.writeString(revisions.repository().resolve("pom.xml"), "<project>changed</project>");
        RepairCommitBindingTest.git(revisions.repository(), "add", "pom.xml");
        String head = RepairCommitBindingTest.commit(revisions.repository(), revisions.base());
        var changed = RepairCommitBindingTest.intent(revisions.base(), head);
        var blocked = assertThrows(RepairCommitBinding.SourceBlocked.class, () -> prepare(revisions, changed));
        assertEquals(RepairCommitBinding.Blocker.OTHER_TREE_CHANGES, blocked.blocker);
        assertFalse(Files.exists(temporary.resolve("verifications")));
    }

    @Test void stopsBeforeDispatchWhenTheCapturedBytesNoLongerMatchTheGitOrigin() throws Exception {
        var revisions = RepairCommitBindingTest.history(temporary.resolve("repo"), "baseline", "candidate");
        var prepared = prepare(revisions, RepairCommitBindingTest.intent(revisions.base(), revisions.head()));
        var sandbox = new SubscriptionVerificationTest.Sandbox();
        var verifier = new RepairVerification(temporary, sandbox);
        String id = prepared.register(verifier, SubscriptionVerificationTest.caseRoot(), RepairVerificationTest.IMAGE);
        Path run = temporary.resolve("verifications").resolve(id);
        Files.writeString(run.resolve("inputs/candidate.txt"), "changed");
        Path spec = run.resolve("spec.json");
        var registration = CaseInputs.object(Files.readAllBytes(spec));
        registration.add("inputs", CaseInputs.JSON.toJsonTree(CaseInputs.inventory(run.resolve("inputs"))));
        Files.writeString(spec, CaseInputs.JSON.toJson(registration));
        assertThrows(IllegalArgumentException.class, () -> verifier.execute(id));
        assertFalse(Files.exists(run.resolve("started.json")));
        assertEquals(0, sandbox.calls);
    }

    private static SubscriptionRepositoryVerification.Prepared prepare(RepairCommitBindingTest.Revisions revisions, Intent intent)
            throws Exception {
        return SubscriptionRepositoryVerification.prepare(revisions.repository(), intent, SOURCE, BASELINE_API_VERSION, TARGET_API_VERSION);
    }
}
